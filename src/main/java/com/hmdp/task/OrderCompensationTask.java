package com.hmdp.task;

import com.hmdp.config.RabbitMQConfig;
import com.hmdp.entity.MessageOutbox;
import com.hmdp.service.IMessageOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 订单补偿定时任务
 * 定期扫描 outbox 表，将 MQ 投递失败的消息重新投递
 */
@Slf4j
@Component
public class OrderCompensationTask {

    @Resource
    private IMessageOutboxService messageOutboxService;
    @Resource
    private RabbitTemplate rabbitTemplate;

    private static final int MAX_OUTBOX_RETRY = 5;

    /** 每 30 秒扫描一次 outbox 表 */
    @Scheduled(fixedDelay = 30000)
    public void compensateOutbox() {
        List<MessageOutbox> pending = messageOutboxService.query()
                .eq("status", 0)
                .lt("retry_count", MAX_OUTBOX_RETRY)
                .last("LIMIT 100")
                .list();

        if (pending.isEmpty()) {
            return;
        }

        log.info("补偿任务: 发现 {} 条待投递消息", pending.size());

        for (MessageOutbox outbox : pending) {
            try {
                // correlationId 使用 "outbox:" 前缀，RabbitMQConfirmConfig 中 confirm 回调
                // 会据此将 outbox 标记为已发送，确保只有 Broker 真正收到后才更新状态
                CorrelationData correlationData = new CorrelationData("outbox:" + outbox.getId());
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.ORDER_EXCHANGE,
                        RabbitMQConfig.ORDER_ROUTING_KEY,
                        outbox.getMessageBody(),
                        correlationData
                );
                // 注意：这里不再直接设置 status=1，而是由 ConfirmCallback 异步标记
                log.info("补偿投递已发出: orderId={}, outboxId={}", outbox.getOrderId(), outbox.getId());
            } catch (Exception e) {
                outbox.setRetryCount(outbox.getRetryCount() + 1);
                if (outbox.getRetryCount() >= MAX_OUTBOX_RETRY) {
                    outbox.setStatus(2);
                }
                messageOutboxService.updateById(outbox);
                log.error("补偿投递失败: orderId={}, retry={}",
                        outbox.getOrderId(), outbox.getRetryCount(), e);
            }
        }
    }
}
