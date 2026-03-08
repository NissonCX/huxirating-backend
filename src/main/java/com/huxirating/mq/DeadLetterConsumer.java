package com.huxirating.mq;

import cn.hutool.json.JSONUtil;
import com.huxirating.config.RabbitMQConfig;
import com.huxirating.dto.OrderMessage;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.huxirating.entity.VoucherOrder;
import com.huxirating.entity.SeckillVoucher;
import com.huxirating.service.IVoucherOrderService;
import com.huxirating.service.ISeckillVoucherService;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Collections;

import static com.huxirating.utils.RedisConstants.ORDER_STATUS_KEY;

/**
 * 死信队列消费者（手动 ACK 模式）
 * 处理多次重试仍失败的订单消息：回滚 Redis 库存，落库取消记录
 * <p>
 * 设计要点：
 * 1. 手动 ACK：处理成功才确认，处理失败不 requeue（防止死循环），仅记录日志人工介入
 * 2. 幂等：通过 orderId 查重，防止重复回滚
 */
@Slf4j
@Component
public class DeadLetterConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    private static final DefaultRedisScript<Long> DEADLETTER_SYNC_SCRIPT;

    static {
        DEADLETTER_SYNC_SCRIPT = new DefaultRedisScript<>();
        DEADLETTER_SYNC_SCRIPT.setLocation(new ClassPathResource("deadletter-sync.lua"));
        DEADLETTER_SYNC_SCRIPT.setResultType(Long.class);
    }

    @RabbitListener(queues = RabbitMQConfig.DLQ_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody());
        OrderMessage orderMsg = JSONUtil.toBean(body, OrderMessage.class);

        log.error("死信消息: orderId={}, userId={}, voucherId={}",
                orderMsg.getOrderId(), orderMsg.getUserId(), orderMsg.getVoucherId());

        try {
            // 幂等校验：如果订单已存在（可能是其他路径创建的），跳过回滚
            VoucherOrder existing = voucherOrderService.getById(orderMsg.getOrderId());
            if (existing != null) {
                log.info("死信处理: 订单已存在，跳过回滚: orderId={}, status={}",
                        orderMsg.getOrderId(), existing.getStatus());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 【关键修复】原子化同步 Redis 库存 + 移除用户
            // 解决 TOCTOU 问题：检查 MySQL 和更新 Redis 之间的时间窗口
            SeckillVoucher voucher = seckillVoucherService.getById(orderMsg.getVoucherId());
            int mysqlStock = voucher != null ? voucher.getStock() : 0;

            // Lua 脚本原子化操作：
            // 1. 如果 Redis 库存 > MySQL 库存，降为 MySQL 值（消除虚假库存）
            // 2. 否则，正常回滚 +1
            // 3. 移除用户
            Long result = stringRedisTemplate.execute(
                    DEADLETTER_SYNC_SCRIPT,
                    Collections.emptyList(),
                    orderMsg.getVoucherId().toString(),
                    orderMsg.getUserId().toString(),
                    String.valueOf(mysqlStock)
            );

            if (result != null && result == 0) {
                log.info("死信处理成功: voucherId={}, userId={}, mysqlStock={}, redis已同步",
                        orderMsg.getVoucherId(), orderMsg.getUserId(), mysqlStock);
            } else {
                log.warn("死信处理: Redis key 不存在，跳过同步: voucherId={}",
                        orderMsg.getVoucherId());
            }

            // 创建「已取消」订单记录，让用户查询时能看到明确的失败状态
            VoucherOrder failedOrder = new VoucherOrder();
            failedOrder.setId(orderMsg.getOrderId());
            failedOrder.setUserId(orderMsg.getUserId());
            failedOrder.setVoucherId(orderMsg.getVoucherId());
            failedOrder.setStatus(4); // 4-已取消
            voucherOrderService.save(failedOrder);

            // 清除 PENDING 状态
            stringRedisTemplate.delete(ORDER_STATUS_KEY + orderMsg.getOrderId());

            log.info("失败订单已落库: orderId={}, status=已取消", orderMsg.getOrderId());

            // 处理成功，确认消息
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("死信处理异常，需人工介入: orderId={}", orderMsg.getOrderId(), e);
            // 死信队列是最后一道防线，不再 requeue（否则死循环），
            // reject 且不 requeue，消息会被丢弃（已有日志记录，人工介入）
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
