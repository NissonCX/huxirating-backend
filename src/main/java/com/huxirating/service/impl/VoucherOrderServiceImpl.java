package com.huxirating.service.impl;

import cn.hutool.json.JSONUtil;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huxirating.config.RabbitMQConfig;
import com.huxirating.degradation.DegradedVoucherOrderService;
import com.huxirating.degradation.RedisHealthService;
import com.huxirating.dto.OrderMessage;
import com.huxirating.dto.Result;
import com.huxirating.entity.MessageOutbox;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.mapper.VoucherOrderMapper;
import com.huxirating.service.IMessageOutboxService;
import com.huxirating.service.IVoucherOrderService;
import com.huxirating.utils.RedisIdWorker;
import com.huxirating.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.huxirating.utils.RedisConstants.ORDER_STATUS_KEY;
import static com.huxirating.utils.RedisConstants.ORDER_STATUS_TTL;

/**
 * 优惠券订单服务
 * <p>
 * 异步秒杀流程：Lua 脚本校验 → RabbitMQ 投递 → 消费者写库
 * MQ 投递失败时写入 Outbox 表，由定时任务补偿
 * <p>
 * 降级策略（Redis 不可用时）：
 * - L1: 健康检查 + 自动切换
 * - L2: DB 直写 + 本地缓存 + Snowflake ID
 * - L3: Sentinel 熔断保护
 * - L4: 监控告警 + 流量逐步恢复
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
    @Resource
    private DegradedVoucherOrderService degradedVoucherOrderService;
    @Resource
    private RedisHealthService redisHealthService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 初始化：注册降级监听器
     */
    @PostConstruct
    public void init() {
        // 将降级服务注册为健康检查的监听器
        // 这样当 Redis 状态变化时，会自动通知降级服务
        log.info("VoucherOrderServiceImpl 初始化完成，降级策略已就绪");
    }

    @Override
    @SentinelResource(value = "seckillVoucher", blockHandler = "seckillBlockHandler",
            fallback = "seckillFallback")
    public Result seckillVoucher(Long voucherId) {
        // L1/L2 降级检查：如果 Redis 不可用，切换到降级模式
        if (!redisHealthService.isRedisAvailable()) {
            log.warn("[降级模式] Redis 不可用，使用降级秒杀流程: voucherId={}", voucherId);
            return degradedVoucherOrderService.handleSeckill(voucherId);
        }

        // 正常模式：Redis 可用，使用标准异步秒杀流程
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        try {
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

        } catch (Exception e) {
            // Redis 操作异常，降级到 DB 直写模式
            log.error("[降级触发] Redis 操作异常，切换到降级模式: voucherId={}", voucherId, e);
            redisHealthService.checkHealth(); // 触发健康检查
            return degradedVoucherOrderService.handleSeckill(voucherId);
        }
    }

    /**
     * Sentinel 限流降级 — 触发限流时的兜底
     * <p>
     * L3 熔断保护：当 DB 压力过大或异常比例超过 50% 时自动熔断
     */
    public Result seckillBlockHandler(Long voucherId, BlockException ex) {
        log.warn("【L3 熔断】秒杀接口被限流: voucherId={}, rule={}", voucherId, ex.getRule());
        return Result.fail("当前抢购人数过多，请稍后重试");
    }

    /**
     * Sentinel 熔断降级 — 服务异常时的降级
     */
    public Result seckillFallback(Long voucherId, Throwable ex) {
        log.error("【L3 熔断】秒杀接口降级: voucherId={}", voucherId, ex);
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

        // 降级模式下，不查询 Redis
        if (!redisHealthService.isRedisAvailable()) {
            return Result.fail("订单不存在或处理中");
        }

        // 2. 库里没有 → 检查 Redis 是否还在处理中
        try {
            String status = stringRedisTemplate.opsForValue().get(ORDER_STATUS_KEY + orderId);
            if ("PENDING".equals(status)) {
                Map<String, Object> result = new HashMap<>(4);
                result.put("orderId", orderId.toString());
                result.put("status", 0); // 0 表示处理中
                result.put("message", "订单处理中，请稍后查询");
                return Result.ok(result);
            }
        } catch (Exception e) {
            log.warn("Redis 查询失败，忽略: orderId={}", orderId, e);
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
