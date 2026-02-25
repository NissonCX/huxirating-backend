package com.huxirating.config;

import com.huxirating.entity.MessageOutbox;
import com.huxirating.service.IMessageOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;

/**
 * RabbitMQ 生产者可靠性配置
 * <p>
 * Publisher Confirms：消息到达交换机后 Broker 回调 confirm(ack=true)；
 *   若无法到达交换机则 confirm(ack=false)。
 * Publisher Returns：消息到达交换机但无法路由到任何队列时回调。
 * <p>
 * 配合 Outbox 补偿：
 * - CorrelationData.id 中携带 outboxId（如有）
 * - confirm(ack=true) 时将 outbox 标记为已发送
 * - confirm(ack=false) / return 时记录日志告警，outbox 保持待发送状态等待补偿
 */
@Slf4j
@Configuration
public class RabbitMQConfirmConfig {

    @Resource
    private IMessageOutboxService messageOutboxService;

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);

        // 开启 mandatory，确保消息无法路由时触发 ReturnsCallback（而非静默丢弃）
        rabbitTemplate.setMandatory(true);

        // ========== Publisher Confirms ==========
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String correlationId = correlationData != null ? correlationData.getId() : "unknown";

            if (ack) {
                log.debug("消息已到达交换机: correlationId={}", correlationId);
                // 如果 correlationId 是 outbox 记录的 ID，更新状态为已发送
                markOutboxSentIfNeeded(correlationId);
            } else {
                log.error("消息未到达交换机: correlationId={}, cause={}", correlationId, cause);
                // outbox 记录仍为待发送(status=0)，定时任务会补偿重发
            }
        });

        // ========== Publisher Returns（消息到达交换机但无法路由到队列） ==========
        rabbitTemplate.setReturnsCallback(returned -> {
            log.error("消息无法路由到队列: exchange={}, routingKey={}, replyCode={}, replyText={}, message={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    new String(returned.getMessage().getBody()));
            // 此时消息虽然到达了交换机(confirm=ack)，但没有任何队列接收
            // 这通常是队列配置错误，需要告警排查
        });

        return rabbitTemplate;
    }

    /**
     * 尝试将 outbox 记录标记为已发送。
     * correlationId 格式约定：以 "outbox:" 前缀标识来自 outbox 补偿的消息。
     */
    private void markOutboxSentIfNeeded(String correlationId) {
        if (correlationId == null || !correlationId.startsWith("outbox:")) {
            return;
        }
        try {
            Long outboxId = Long.parseLong(correlationId.substring("outbox:".length()));
            MessageOutbox outbox = messageOutboxService.getById(outboxId);
            if (outbox != null && outbox.getStatus() == 0) {
                outbox.setStatus(1); // 已发送
                messageOutboxService.updateById(outbox);
                log.info("Outbox 已确认发送: outboxId={}, orderId={}", outboxId, outbox.getOrderId());
            }
        } catch (NumberFormatException e) {
            log.warn("无法解析 outbox correlationId: {}", correlationId);
        }
    }
}
