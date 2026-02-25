package com.huxirating.mq;

import cn.hutool.json.JSONUtil;
import com.huxirating.config.RabbitMQConfig;
import com.huxirating.dto.OrderMessage;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.service.ISeckillVoucherService;
import com.huxirating.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Map;

import static com.huxirating.utils.RedisConstants.ORDER_STATUS_KEY;

/**
 * 秒杀订单消息消费者（手动 ACK 模式）
 * <p>
 * 设计要点：
 * 1. 幂等：消费前按 orderId 查重，防止重复消费
 * 2. 重试：失败时携带重试计数投入重试队列（TTL 延迟后回到主队列）
 * 3. 死信：超过最大重试次数后转入 DLQ，由 DeadLetterConsumer 回滚
 * 4. 可靠投递：手动 ACK，只有在重试/死信投递成功后才确认原消息；
 *    若投递也失败则 NACK+requeue，让消息回到主队列等待下次消费
 */
@Slf4j
@Component
public class OrderMessageConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.ORDER_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String body = new String(message.getBody());
        OrderMessage orderMsg = JSONUtil.toBean(body, OrderMessage.class);

        // 读取重试次数
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        int retryCount = headers.containsKey("x-retry-count")
                ? (int) headers.get("x-retry-count") : 0;

        log.info("收到订单消息: orderId={}, retryCount={}", orderMsg.getOrderId(), retryCount);

        try {
            // 幂等校验：订单已存在则直接跳过
            VoucherOrder existing = voucherOrderService.getById(orderMsg.getOrderId());
            if (existing != null) {
                log.info("订单已存在，跳过: orderId={}", orderMsg.getOrderId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            createVoucherOrder(orderMsg);
            // 订单落库成功，清除 PENDING 状态
            stringRedisTemplate.delete(ORDER_STATUS_KEY + orderMsg.getOrderId());
            log.info("订单处理成功: orderId={}", orderMsg.getOrderId());
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("订单处理失败: orderId={}, retry={}", orderMsg.getOrderId(), retryCount, e);
            handleFailure(channel, deliveryTag, orderMsg, retryCount);
        }
    }

    /**
     * 处理消费失败：尝试投递重试/死信队列，投递成功则 ACK 原消息，
     * 投递也失败则 NACK+requeue，由 Broker 重新投递。
     */
    private void handleFailure(Channel channel, long deliveryTag,
                               OrderMessage orderMsg, int retryCount) throws IOException {
        try {
            if (retryCount < RabbitMQConfig.MAX_RETRY_COUNT) {
                sendToRetryQueue(orderMsg, retryCount + 1);
                log.info("已投递重试队列: orderId={}, retry={}", orderMsg.getOrderId(), retryCount + 1);
            } else {
                log.error("超过最大重试次数，转入死信: orderId={}", orderMsg.getOrderId());
                sendToDeadLetterQueue(orderMsg);
            }
            // 重试/死信投递成功，确认原消息
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            // 投递重试/死信也失败了 → NACK+requeue，让消息回到主队列
            log.error("投递重试/死信队列失败，NACK requeue: orderId={}", orderMsg.getOrderId(), ex);
            channel.basicNack(deliveryTag, false, true);
        }
    }

    /**
     * 创建订单（写入 MySQL）
     * 使用 Redisson 分布式锁 + DB 查重 + 乐观锁扣库存
     */
    private void createVoucherOrder(OrderMessage orderMsg) {
        Long userId = orderMsg.getUserId();
        Long voucherId = orderMsg.getVoucherId();

        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            throw new RuntimeException("获取锁失败: userId=" + userId);
        }

        try {
            // DB 层再次校验一人一单
            int count = voucherOrderService.query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .count();
            if (count > 0) {
                log.info("用户已购买过此券: userId={}, voucherId={}", userId, voucherId);
                return;
            }

            // 乐观锁扣减 MySQL 库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if (!success) {
                throw new RuntimeException("库存扣减失败: voucherId=" + voucherId);
            }

            // 保存订单（设置初始状态为未支付）
            VoucherOrder order = new VoucherOrder();
            order.setId(orderMsg.getOrderId());
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            order.setStatus(1); // 1-未支付
            voucherOrderService.save(order);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 投递重试队列（不再 catch 异常，让调用方决定 ACK/NACK）
     */
    private void sendToRetryQueue(OrderMessage orderMsg, int retryCount) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.RETRY_EXCHANGE,
                RabbitMQConfig.RETRY_ROUTING_KEY,
                JSONUtil.toJsonStr(orderMsg),
                msg -> {
                    msg.getMessageProperties().setHeader("x-retry-count", retryCount);
                    return msg;
                }
        );
    }

    /**
     * 投递死信队列（不再 catch 异常，让调用方决定 ACK/NACK）
     */
    private void sendToDeadLetterQueue(OrderMessage orderMsg) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.DLX_EXCHANGE,
                RabbitMQConfig.DLQ_ROUTING_KEY,
                JSONUtil.toJsonStr(orderMsg)
        );
    }
}
