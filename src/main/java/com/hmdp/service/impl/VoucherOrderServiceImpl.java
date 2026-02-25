package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.OrderMessage;
import com.hmdp.dto.Result;
import com.hmdp.entity.MessageOutbox;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IMessageOutboxService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.ORDER_STATUS_KEY;
import static com.hmdp.utils.RedisConstants.ORDER_STATUS_TTL;

/**
 * 优惠券订单服务
 * <p>
 * 异步秒杀流程：Lua 脚本校验 → RabbitMQ 投递 → 消费者写库
 * MQ 投递失败时写入 Outbox 表，由定时任务补偿
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private IMessageOutboxService messageOutboxService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    @SentinelResource(value = "seckillVoucher", blockHandler = "seckillBlockHandler",
            fallback = "seckillFallback")
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        // 1. Lua 脚本原子校验：库存 + 一人一单 + 扣减
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 2. 标记订单状态为 PENDING（用户可通过查询接口感知处理进度）
        stringRedisTemplate.opsForValue().set(
                ORDER_STATUS_KEY + orderId, "PENDING", ORDER_STATUS_TTL, TimeUnit.MINUTES);

        // 3. 通过 RabbitMQ 异步处理订单入库
        OrderMessage orderMsg = new OrderMessage(orderId, userId, voucherId);
        String messageBody = JSONUtil.toJsonStr(orderMsg);

        try {
            CorrelationData correlationData = new CorrelationData("order:" + orderId);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ORDER_EXCHANGE,
                    RabbitMQConfig.ORDER_ROUTING_KEY,
                    messageBody,
                    correlationData
            );
            log.info("订单消息已投递 MQ: orderId={}", orderId);
        } catch (Exception e) {
            // MQ 投递失败，写入 Outbox 表由定时任务补偿
            log.error("MQ 投递失败，写入 Outbox: orderId={}", orderId, e);
            saveToOutbox(orderId, messageBody);
        }

        return Result.ok(orderId);
    }

    /** Sentinel 限流降级 — 触发限流时的兜底 */
    public Result seckillBlockHandler(Long voucherId, BlockException ex) {
        log.warn("秒杀接口被限流: voucherId={}, rule={}", voucherId, ex.getRule());
        return Result.fail("当前抢购人数过多，请稍后重试");
    }

    /** Sentinel 熔断降级 — 服务异常时的降级 */
    public Result seckillFallback(Long voucherId, Throwable ex) {
        log.error("秒杀接口降级: voucherId={}", voucherId, ex);
        return Result.fail("系统繁忙，请稍后重试");
    }

    @Override
    public Result queryOrderStatus(Long orderId) {
        // 1. 优先查库 —— 订单已落库（成功或失败）
        VoucherOrder order = getById(orderId);
        if (order != null) {
            Map<String, Object> result = new HashMap<>(4);
            result.put("orderId", order.getId().toString());
            result.put("status", order.getStatus());
            // status: 1未支付 2已支付 3已核销 4已取消
            if (order.getStatus() == 4) {
                result.put("message", "下单失败，库存扣减异常，请重试");
            }
            return Result.ok(result);
        }

        // 2. 库里没有 → 检查 Redis 是否还在处理中
        String status = stringRedisTemplate.opsForValue().get(ORDER_STATUS_KEY + orderId);
        if ("PENDING".equals(status)) {
            Map<String, Object> result = new HashMap<>(4);
            result.put("orderId", orderId.toString());
            result.put("status", 0); // 0 表示处理中
            result.put("message", "订单处理中，请稍后查询");
            return Result.ok(result);
        }

        // 3. 既不在库里也不在 Redis → 订单不存在
        return Result.fail("订单不存在");
    }

    private void saveToOutbox(Long orderId, String messageBody) {
        MessageOutbox outbox = new MessageOutbox();
        outbox.setOrderId(orderId);
        outbox.setMessageBody(messageBody);
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        messageOutboxService.save(outbox);
    }
}
