package com.huxirating.config;

import com.huxirating.dto.OrderCorrelationData;
import com.huxirating.dto.OrderMessage;
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
                // 直发路径 NACK 兜底：写入 Outbox 由定时任务补偿
                if (correlationData instanceof OrderCorrelationData) {
                    saveToOutboxOnNack((OrderCorrelationData) correlationData);
                }
            }
        });

        // ========== Publisher Returns（消息到达交换机但无法路由到队列） ==========
        rabbitTemplate.setReturnsCallback(returned -> {
            String body = new String(returned.getMessage().getBody());
            log.error("消息无法路由到队列: exchange={}, routingKey={}, replyCode={}, replyText={}, message={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyCode(),
                    returned.getReplyText(),
                    body);
            // 此时消息虽然到达了交换机(confirm=ack)，但没有任何队列接收
            // Redis 已预扣但无消费者处理，必须写入 Outbox 兜底防止库存被吞
            saveToOutboxOnReturn(body);
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

    /**
     * 直发路径 confirm NACK 时写入 Outbox 兜底。
     * 防止 Redis 已预扣但 MQ 消息未达交换机导致的"库存被吞"问题。
     */
    private void saveToOutboxOnNack(OrderCorrelationData orderData) {
        try {
            long existingCount = messageOutboxService.query()
                    .eq("order_id", orderData.getOrderId())
                    .eq("status", 0)
                    .count();
            if (existingCount > 0) {
                log.info("Outbox 记录已存在，跳过: orderId={}", orderData.getOrderId());
                return;
            }
            MessageOutbox outbox = new MessageOutbox();
            outbox.setOrderId(orderData.getOrderId());
            outbox.setMessageBody(orderData.getMessageBody());
            outbox.setStatus(0);
            outbox.setRetryCount(0);
            messageOutboxService.save(outbox);
            log.info("Confirm NACK，已写入 Outbox 兜底: orderId={}", orderData.getOrderId());
        } catch (Exception e) {
            log.error("写入 Outbox 失败，需人工介入: orderId={}", orderData.getOrderId(), e);
        }
    }

    /**
     * Return 回调时写入 Outbox 兜底。
     * 消息到达 Exchange 但无法路由到任何 Queue，此时 confirm 仍为 ack=true，
     * Outbox 不会被 confirm 回调触发，必须在此处补偿。
     */
    private void saveToOutboxOnReturn(String body) {
        try {
            OrderMessage orderMsg = cn.hutool.json.JSONUtil.toBean(body, OrderMessage.class);
            if (orderMsg.getOrderId() == null) {
                log.warn("Return 回调：无法解析 orderId，跳过 Outbox 写入: body={}", body);
                return;
            }
            long existingCount = messageOutboxService.query()
                    .eq("order_id", orderMsg.getOrderId())
                    .eq("status", 0)
                    .count();
            if (existingCount > 0) {
                log.info("Outbox 记录已存在，跳过: orderId={}", orderMsg.getOrderId());
                return;
            }
            MessageOutbox outbox = new MessageOutbox();
            outbox.setOrderId(orderMsg.getOrderId());
            outbox.setMessageBody(body);
            outbox.setStatus(0);
            outbox.setRetryCount(0);
            messageOutboxService.save(outbox);
            log.info("Return 回调，已写入 Outbox 兜底: orderId={}", orderMsg.getOrderId());
        } catch (Exception e) {
            log.error("Return 回调写入 Outbox 失败，需人工介入: body={}", body, e);
        }
    }
}
