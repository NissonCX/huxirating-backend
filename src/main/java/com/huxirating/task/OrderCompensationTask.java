package com.huxirating.task;

import cn.hutool.json.JSONUtil;
import com.huxirating.config.RabbitMQConfig;
import com.huxirating.dto.OrderMessage;
import com.huxirating.entity.MessageOutbox;
import com.huxirating.service.IMessageOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

import static com.huxirating.utils.RedisConstants.ORDER_STATUS_KEY;
import static com.huxirating.utils.RedisConstants.SECKILL_STOCK_KEY;

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
    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
                    // MQ 投递彻底失败，必须回滚 Redis，否则：
                    //   1. seckill:order:{voucherId} 中的 userId 永远留在 set，该用户将永远无法再次抢购
                    //   2. seckill:stock 计数与 DB 实际库存永久不一致
                    rollbackRedis(outbox.getMessageBody());
                }
                messageOutboxService.updateById(outbox);
                log.error("补偿投递失败: orderId={}, retry={}",
                        outbox.getOrderId(), outbox.getRetryCount(), e);
            }
        }
    }

    /**
     * 回滚 Redis：恢复库存 + 从已购 set 中移除 userId
     * <p>
     * 调用时机：Outbox 重试次数耗尽，MQ 投递彻底放弃。
     * 此时订单从未落库，必须撤销 Lua 脚本已做的两个副作用：
     *   - decr seckill:stock:{voucherId}
     *   - sadd seckill:order:{voucherId} userId
     */
    private void rollbackRedis(String messageBody) {
        try {
            OrderMessage msg = JSONUtil.toBean(messageBody, OrderMessage.class);
            String stockKey = SECKILL_STOCK_KEY + msg.getVoucherId();
            String orderKey = "seckill:order:" + msg.getVoucherId();

            stringRedisTemplate.opsForValue().increment(stockKey);
            stringRedisTemplate.opsForSet().remove(orderKey, msg.getUserId().toString());
            // ORDER_STATUS_KEY 有 TTL 会自动过期，这里主动清理让用户即时感知
            stringRedisTemplate.delete(ORDER_STATUS_KEY + msg.getOrderId());

            log.warn("Outbox 彻底失败，Redis 已回滚: orderId={}, userId={}, voucherId={}",
                    msg.getOrderId(), msg.getUserId(), msg.getVoucherId());
        } catch (Exception ex) {
            // Redis 回滚失败只记录告警，不影响 Outbox 状态更新
            // 这属于"双重失败"场景（MQ + Redis 同时不可用），需人工介入
            log.error("Outbox Redis 回滚失败，需人工介入: body={}", messageBody, ex);
        }
    }
}
