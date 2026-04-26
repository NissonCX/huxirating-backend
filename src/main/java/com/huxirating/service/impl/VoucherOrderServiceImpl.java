package com.huxirating.service.impl;

import cn.hutool.json.JSONUtil;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.huxirating.config.RabbitMQConfig;
import com.huxirating.degradation.DegradedVoucherOrderService;
import com.huxirating.degradation.RedisHealthService;
import com.huxirating.dto.OrderMessage;
import com.huxirating.dto.PurchaseAttemptResponse;
import com.huxirating.dto.Result;
import com.huxirating.entity.MessageOutbox;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.mapper.VoucherOrderMapper;
import com.huxirating.service.IMessageOutboxService;
import com.huxirating.service.ISeckillVoucherService;
import com.huxirating.service.IVoucherOrderService;
import com.huxirating.utils.RedisIdWorker;
import com.huxirating.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;
import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.huxirating.utils.RedisConstants.ORDER_STATUS_KEY;
import static com.huxirating.utils.RedisConstants.ORDER_STATUS_TTL;
import static com.huxirating.utils.RedisConstants.ORDER_META_KEY;
import static com.huxirating.utils.RedisConstants.ORDER_CANCEL_KEY;
import static com.huxirating.utils.RedisConstants.SECKILL_TOKEN_KEY;

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
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    @Lazy
    private DegradedVoucherOrderService degradedVoucherOrderService;
    @Resource
    private RedisHealthService redisHealthService;
    @Resource
    private com.huxirating.degradation.SeckillQueueService seckillQueueService;
    @Resource
    @Lazy
    private com.huxirating.degradation.DegradationService degradationService;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
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

        // 【关键修复】检查是否正在恢复中（同步数据期间）
        // 恢复期间 Redis Set 数据不一致，必须拒绝请求
        if (degradationService.isRecovering()) {
            log.warn("[恢复中] 系统正在恢复，进入排队: voucherId={}", voucherId);
            return seckillQueueService.enqueue(voucherId);
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
                    userId.toString(),
                    String.valueOf(orderId)
            );

            int r = result.intValue();
            if (r != 0) {
                if (r == 1) {
                    return Result.fail("库存不足");
                }
                // r == 2：userId 在 seckill:order set 里，但有两种截然不同的情况：
                //   A. DB 已有有效订单（status != 4）→ 真正的重复购买
                //   B. DB 没有有效订单          → 上一笔还在 MQ 处理中（飞行中状态）
                // 如果不区分，情况B会错误返回"不能重复下单"，
                // 用户查不到订单，会对着客服投诉"明明提示不能重复购买，但订单列表是空的"
                VoucherOrder existingOrder = this.query()
                        .eq("user_id", userId)
                        .eq("voucher_id", voucherId)
                        .notIn("status", 4, 6)
                        .one();
                if (existingOrder != null) {
                    return Result.fail("您已成功购买过该优惠券");
                }
                // 情况B：引导用户查询上一笔订单的处理进度，而不是误导性的"重复下单"
                return Result.fail("您有一笔订单正在处理中，请稍候查询订单状态");
            }

            // 【关键修复】预查 MySQL 库存，防止 Redis/MySQL 不一致时的死循环
            // 场景：Redis 库存=1，但 MySQL 库存已被其他用户抢光=0
            // 如果不预查，用户会反复抢购成功 → MQ → 消费失败 → 死信回滚 → 再抢...
            try {
                com.huxirating.entity.SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
                if (voucher == null) {
                    rollbackRedisPreDeduct(voucherId, userId);
                    return Result.fail("优惠券不存在");
                }
                // 检查有效期
                LocalDateTime now = LocalDateTime.now();
                if (now.isBefore(voucher.getBeginTime())) {
                    rollbackRedisPreDeduct(voucherId, userId);
                    return Result.fail("秒杀尚未开始");
                }
                if (now.isAfter(voucher.getEndTime())) {
                    rollbackRedisPreDeduct(voucherId, userId);
                    return Result.fail("秒杀已结束");
                }
                if (voucher.getStock() <= 0) {
                    // MySQL 库存已空，回滚 Redis 预扣
                    rollbackRedisPreDeduct(voucherId, userId);
                    log.warn("预查 MySQL 库存为空，已回滚 Redis: voucherId={}, userId={}", voucherId, userId);
                    return Result.fail("库存不足");
                }
            } catch (Exception e) {
                // 预查失败不影响主流程，记录日志继续（后续消费时会再次校验）
                log.warn("预查 MySQL 库存异常: voucherId={}", voucherId, e);
            }

            // 2. 标记订单状态为 PENDING（用户可通过查询接口感知处理进度）
            stringRedisTemplate.opsForValue().set(
                    ORDER_STATUS_KEY + orderId, "PENDING", ORDER_STATUS_TTL, TimeUnit.MINUTES);

            // 保存订单元信息，用于 pending cancel 等能力
            Map<String, Object> meta = new HashMap<>(4);
            meta.put("userId", userId);
            meta.put("voucherId", voucherId);
            stringRedisTemplate.opsForValue().set(
                    ORDER_META_KEY + orderId,
                    JSONUtil.toJsonStr(meta),
                    ORDER_STATUS_TTL,
                    TimeUnit.MINUTES
            );

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

    @Override
    @SentinelResource(value = "seckillVoucherPurchase", blockHandler = "seckillPurchaseBlockHandler",
            fallback = "seckillPurchaseFallback")
    public Result seckillVoucherPurchase(Long voucherId) {
        // 降级模式：直接返回已创建订单
        if (!redisHealthService.isRedisAvailable()) {
            Result degraded = degradedVoucherOrderService.handleSeckill(voucherId);
            return Result.ok(toPurchaseAttemptFromDegraded(degraded));
        }

        // 恢复中：进入排队
        if (degradationService.isRecovering()) {
            Result queued = seckillQueueService.enqueue(voucherId);
            return Result.ok(toPurchaseAttemptFromQueue(queued));
        }

        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");

        try {
            Long result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId)
            );
            int r = result.intValue();
            if (r != 0) {
                if (r == 1) {
                    return Result.ok(buildFailed("库存不足", "SOLD_OUT", false));
                }

                // r == 2：可能是重复购买，也可能是飞行中
                VoucherOrder existingOrder = this.query()
                        .eq("user_id", userId)
                        .eq("voucher_id", voucherId)
                        .notIn("status", 4, 6)
                        .one();
                if (existingOrder != null) {
                    return Result.ok(buildOrderCreated(existingOrder.getId().toString(), "您已成功购买过该优惠券"));
                }

                String inFlightOrderId = stringRedisTemplate.opsForValue()
                        .get(SECKILL_TOKEN_KEY + voucherId + ":" + userId);
                PurchaseAttemptResponse resp = new PurchaseAttemptResponse();
                resp.setState("PROCESSING");
                resp.setPurchaseToken(inFlightOrderId != null ? inFlightOrderId : String.valueOf(orderId));
                resp.setOrderId(inFlightOrderId);
                resp.setMessage("订单处理中，请稍后查询");
                PurchaseAttemptResponse.NextAction next = new PurchaseAttemptResponse.NextAction();
                next.setType("POLL");
                next.setUrl("/voucher-order/purchase/" + resp.getPurchaseToken());
                next.setRetryAfterMs(800L);
                resp.setNextAction(next);
                return Result.ok(resp);
            }

            // 预查 MySQL 库存/有效期；失败则回滚 Redis 预扣
            try {
                com.huxirating.entity.SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
                if (voucher == null) {
                    rollbackRedisPreDeduct(voucherId, userId);
                    return Result.ok(buildFailed("优惠券不存在", "NOT_FOUND", false));
                }
                LocalDateTime now = LocalDateTime.now();
                if (now.isBefore(voucher.getBeginTime())) {
                    rollbackRedisPreDeduct(voucherId, userId);
                    return Result.ok(buildFailed("秒杀尚未开始", "NOT_STARTED", false));
                }
                if (now.isAfter(voucher.getEndTime())) {
                    rollbackRedisPreDeduct(voucherId, userId);
                    return Result.ok(buildFailed("秒杀已结束", "ENDED", false));
                }
                if (voucher.getStock() <= 0) {
                    rollbackRedisPreDeduct(voucherId, userId);
                    return Result.ok(buildFailed("库存不足", "SOLD_OUT", false));
                }
            } catch (Exception e) {
                log.warn("预查 MySQL 库存异常: voucherId={}", voucherId, e);
            }

            // 标记 PENDING + 保存元信息
            stringRedisTemplate.opsForValue().set(
                    ORDER_STATUS_KEY + orderId, "PENDING", ORDER_STATUS_TTL, TimeUnit.MINUTES);
            Map<String, Object> meta = new HashMap<>(4);
            meta.put("userId", userId);
            meta.put("voucherId", voucherId);
            stringRedisTemplate.opsForValue().set(
                    ORDER_META_KEY + orderId,
                    JSONUtil.toJsonStr(meta),
                    ORDER_STATUS_TTL,
                    TimeUnit.MINUTES
            );

            // MQ 异步落库
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
                log.error("MQ 投递失败，写入 Outbox: orderId={}", orderId, e);
                saveToOutbox(orderId, messageBody);
            }

            PurchaseAttemptResponse resp = new PurchaseAttemptResponse();
            resp.setState("ACCEPTED");
            resp.setPurchaseToken(String.valueOf(orderId));
            resp.setOrderId(String.valueOf(orderId));
            resp.setMessage("已受理，正在创建订单");
            PurchaseAttemptResponse.NextAction next = new PurchaseAttemptResponse.NextAction();
            next.setType("POLL");
            next.setUrl("/voucher-order/purchase/" + orderId);
            next.setRetryAfterMs(500L);
            resp.setNextAction(next);
            return Result.ok(resp);

        } catch (Exception e) {
            log.error("秒杀受理异常: voucherId={}", voucherId, e);
            redisHealthService.checkHealth();
            return Result.ok(buildFailed("系统繁忙，请稍后重试", "SYSTEM_BUSY", true));
        }
    }

    public Result seckillPurchaseBlockHandler(Long voucherId, com.alibaba.csp.sentinel.slots.block.BlockException ex) {
        Result queued = seckillQueueService.enqueue(voucherId);
        return Result.ok(toPurchaseAttemptFromQueue(queued));
    }

    public Result seckillPurchaseFallback(Long voucherId, Throwable ex) {
        log.error("秒杀增强接口降级: voucherId={}", voucherId, ex);
        return Result.ok(buildFailed("系统繁忙，请稍后重试", "SYSTEM_BUSY", true));
    }

    @Override
    public Result queryPurchase(String token) {
        return Result.ok(queryPurchaseInternal(token));
    }

    @Override
    public Result waitPurchase(String token, Long timeoutMs) {
        long timeout = timeoutMs == null ? 25000L : Math.min(Math.max(timeoutMs, 1000L), 25000L);
        long start = System.currentTimeMillis();
        long sleepMs = 400L;
        while (System.currentTimeMillis() - start < timeout) {
            PurchaseAttemptResponse resp = queryPurchaseInternal(token);
            if (isTerminal(resp)) {
                return Result.ok(resp);
            }
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            sleepMs = Math.min((long) (sleepMs * 1.5), 2000L);
        }
        PurchaseAttemptResponse resp = queryPurchaseInternal(token);
        // 建议前端下一次轮询间隔
        if (resp.getNextAction() == null) {
            PurchaseAttemptResponse.NextAction next = new PurchaseAttemptResponse.NextAction();
            next.setType("POLL");
            next.setUrl("/voucher-order/purchase/" + token);
            next.setRetryAfterMs(1200L);
            resp.setNextAction(next);
        }
        return Result.ok(resp);
    }

    @Override
    @Transactional
    public Result cancelPurchase(String token) {
        if (token == null || token.isEmpty()) {
            return Result.fail("token 不能为空");
        }

        // 票据取消（排队）
        if (token.startsWith("T")) {
            Result r = seckillQueueService.cancel(token);
            if (!Boolean.TRUE.equals(r.getSuccess())) {
                return Result.ok(buildFailed(r.getErrorMsg(), "NOT_FOUND", false));
            }
            PurchaseAttemptResponse resp = new PurchaseAttemptResponse();
            resp.setState("CANCELED");
            resp.setPurchaseToken(token);
            resp.setQueueTicketId(token);
            resp.setMessage("已取消");
            return Result.ok(resp);
        }

        // orderId 取消：优先走 DB 取消；否则尝试 pending cancel
        Long userId = UserHolder.getUser().getId();
        Long orderId;
        try {
            orderId = Long.valueOf(token);
        } catch (Exception e) {
            return Result.fail("token 非法");
        }

        VoucherOrder order = getById(orderId);
        if (order != null) {
            Result r = cancelOrder(orderId);
            if (Boolean.TRUE.equals(r.getSuccess())) {
                PurchaseAttemptResponse resp = new PurchaseAttemptResponse();
                resp.setState("CANCELED");
                resp.setPurchaseToken(token);
                resp.setOrderId(token);
                resp.setMessage("已取消");
                return Result.ok(resp);
            }
            return Result.ok(buildFailed(r.getErrorMsg(), "CANCEL_FAILED", false));
        }

        // pending cancel
        String metaJson = stringRedisTemplate.opsForValue().get(ORDER_META_KEY + token);
        if (metaJson == null) {
            return Result.ok(buildFailed("订单不存在或已过期", "NOT_FOUND", false));
        }
        Map<?, ?> meta = JSONUtil.toBean(metaJson, Map.class);
        Object metaUserId = meta.get("userId");
        Object metaVoucherId = meta.get("voucherId");
        if (metaUserId == null || metaVoucherId == null) {
            return Result.ok(buildFailed("订单元信息缺失", "NOT_FOUND", false));
        }
        if (!String.valueOf(metaUserId).equals(String.valueOf(userId))) {
            return Result.fail("无权操作此订单");
        }

        // 先打取消标记，消费者落库前会检查
        stringRedisTemplate.opsForValue().set(
                ORDER_CANCEL_KEY + token,
                "1",
                ORDER_STATUS_TTL,
                TimeUnit.MINUTES
        );

        rollbackRedisPreDeduct(Long.valueOf(String.valueOf(metaVoucherId)), userId);
        stringRedisTemplate.delete(ORDER_STATUS_KEY + token);
        stringRedisTemplate.delete(ORDER_META_KEY + token);

        PurchaseAttemptResponse resp = new PurchaseAttemptResponse();
        resp.setState("CANCELED");
        resp.setPurchaseToken(token);
        resp.setOrderId(token);
        resp.setMessage("已取消");
        return Result.ok(resp);
    }

    private PurchaseAttemptResponse queryPurchaseInternal(String token) {
        PurchaseAttemptResponse resp = new PurchaseAttemptResponse();
        resp.setPurchaseToken(token);

        if (token == null || token.isEmpty()) {
            return buildFailed("token 不能为空", "NOT_FOUND", false);
        }

        // 排队票据
        if (token.startsWith("T")) {
            Result r = seckillQueueService.queryStatus(token);
            if (!Boolean.TRUE.equals(r.getSuccess())) {
                return buildFailed(r.getErrorMsg(), "NOT_FOUND", false);
            }
            Map<?, ?> data = (Map<?, ?>) r.getData();
            String status = String.valueOf(data.get("status"));
            resp.setQueueTicketId(token);
            if ("QUEUED".equals(status)) {
                resp.setState("QUEUED");
                resp.setMessage(String.valueOf(data.get("message")));
                PurchaseAttemptResponse.NextAction next = new PurchaseAttemptResponse.NextAction();
                next.setType("POLL");
                next.setUrl("/voucher-order/purchase/" + token);
                next.setRetryAfterMs(1000L);
                resp.setNextAction(next);
                return resp;
            }
            if ("PROCESSING".equals(status)) {
                resp.setState("PROCESSING");
                resp.setMessage(String.valueOf(data.get("message")));
                PurchaseAttemptResponse.NextAction next = new PurchaseAttemptResponse.NextAction();
                next.setType("POLL");
                next.setUrl("/voucher-order/purchase/" + token);
                next.setRetryAfterMs(1000L);
                resp.setNextAction(next);
                return resp;
            }
            if ("SUCCESS".equals(status)) {
                String orderId = String.valueOf(data.get("orderId"));
                return buildOrderCreated(orderId, "订单已创建");
            }
            if ("FAILED".equals(status)) {
                String reason = String.valueOf(data.get("reason"));
                return buildFailed("抢购失败：" + reason, "FAILED", false);
            }
            return buildFailed("未知状态", "FAILED", false);
        }

        // orderId
        Long orderId;
        try {
            orderId = Long.valueOf(token);
        } catch (Exception e) {
            return buildFailed("token 非法", "NOT_FOUND", false);
        }

        VoucherOrder order = getById(orderId);
        if (order != null) {
            if (order.getStatus() == 1) {
                return buildOrderCreated(token, "订单已创建，待支付");
            }
            if (order.getStatus() == 2) {
                PurchaseAttemptResponse r = buildOrderCreated(token, "订单已支付");
                if (r.getNextAction() != null) {
                    r.getNextAction().setType("NONE");
                    r.getNextAction().setUrl(null);
                }
                return r;
            }
            if (order.getStatus() == 3) {
                return buildOrderCreated(token, "订单已核销");
            }
            if (order.getStatus() == 4) {
                PurchaseAttemptResponse f = new PurchaseAttemptResponse();
                f.setState("CANCELED");
                f.setPurchaseToken(token);
                f.setOrderId(token);
                f.setMessage("订单已取消");
                return f;
            }
            if (order.getStatus() == 5) {
                return buildOrderCreated(token, "退款中");
            }
            if (order.getStatus() == 6) {
                return buildOrderCreated(token, "已退款");
            }
            return buildOrderCreated(token, "订单状态=" + order.getStatus());
        }

        String status = stringRedisTemplate.opsForValue().get(ORDER_STATUS_KEY + token);
        if ("PENDING".equals(status)) {
            PurchaseAttemptResponse p = new PurchaseAttemptResponse();
            p.setState("PROCESSING");
            p.setPurchaseToken(token);
            p.setOrderId(token);
            p.setMessage("订单处理中，请稍后查询");
            PurchaseAttemptResponse.NextAction next = new PurchaseAttemptResponse.NextAction();
            next.setType("POLL");
            next.setUrl("/voucher-order/purchase/" + token);
            next.setRetryAfterMs(800L);
            p.setNextAction(next);
            return p;
        }

        return buildFailed("订单不存在或已过期", "NOT_FOUND", false);
    }

    private boolean isTerminal(PurchaseAttemptResponse resp) {
        if (resp == null || resp.getState() == null) return true;
        return "ORDER_CREATED".equals(resp.getState())
                || "FAILED".equals(resp.getState())
                || "CANCELED".equals(resp.getState());
    }

    private PurchaseAttemptResponse buildOrderCreated(String orderId, String message) {
        PurchaseAttemptResponse resp = new PurchaseAttemptResponse();
        resp.setState("ORDER_CREATED");
        resp.setPurchaseToken(orderId);
        resp.setOrderId(orderId);
        resp.setMessage(message);
        PurchaseAttemptResponse.NextAction next = new PurchaseAttemptResponse.NextAction();
        next.setType("REDIRECT_TO_PAY");
        next.setUrl("/voucher-order/pay/" + orderId);
        next.setRetryAfterMs(null);
        resp.setNextAction(next);
        return resp;
    }

    private PurchaseAttemptResponse buildFailed(String message, String code, boolean retryable) {
        PurchaseAttemptResponse resp = new PurchaseAttemptResponse();
        resp.setState("FAILED");
        resp.setMessage(message);
        PurchaseAttemptResponse.ErrorInfo err = new PurchaseAttemptResponse.ErrorInfo();
        err.setCode(code);
        err.setRetryable(retryable);
        err.setDetail(message);
        resp.setError(err);
        return resp;
    }

    private PurchaseAttemptResponse toPurchaseAttemptFromQueue(Result queued) {
        if (!Boolean.TRUE.equals(queued.getSuccess())) {
            return buildFailed(queued.getErrorMsg(), "QUEUE_FAILED", true);
        }
        Map<?, ?> data = (Map<?, ?>) queued.getData();
        String ticketId = String.valueOf(data.get("ticketId"));
        PurchaseAttemptResponse resp = new PurchaseAttemptResponse();
        resp.setState("QUEUED");
        resp.setPurchaseToken(ticketId);
        resp.setQueueTicketId(ticketId);
        resp.setMessage(String.valueOf(data.get("message")));
        PurchaseAttemptResponse.NextAction next = new PurchaseAttemptResponse.NextAction();
        next.setType("POLL");
        next.setUrl("/voucher-order/purchase/" + ticketId);
        next.setRetryAfterMs(1000L);
        resp.setNextAction(next);
        return resp;
    }

    private PurchaseAttemptResponse toPurchaseAttemptFromDegraded(Result degraded) {
        if (!Boolean.TRUE.equals(degraded.getSuccess())) {
            return buildFailed(degraded.getErrorMsg(), "FAILED", false);
        }
        Object dataObj = degraded.getData();
        if (!(dataObj instanceof Map)) {
            return buildFailed("降级响应格式异常", "FAILED", false);
        }
        Map<?, ?> data = (Map<?, ?>) dataObj;
        Object orderId = data.get("orderId");
        if (orderId == null) {
            return buildFailed("订单创建失败", "FAILED", false);
        }
        Object msg = data.get("message");
        return buildOrderCreated(String.valueOf(orderId), msg == null ? "订单已创建" : String.valueOf(msg));
    }

    /**
     * Sentinel 限流降级 — 触发限流时的兜底
     * <p>
     * L3 熔断保护：当 DB 压力过大或异常比例超过 50% 时自动熔断
     * <p>
     * 【关键修复】被限流的请求进入排队系统，而不是直接拒绝
     * 用户获得 ticketId，可轮询查询处理结果
     */
    public Result seckillBlockHandler(Long voucherId, BlockException ex) {
        log.warn("【L3 熔断】秒杀接口被限流，进入排队: voucherId={}, rule={}", voucherId, ex.getRule());
        // 尝试进入排队系统
        return seckillQueueService.enqueue(voucherId);
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
        // 获取当前用户
        Long currentUserId = UserHolder.getUser().getId();
        
        // 1. 优先查库 —— 订单已落库（成功或失败）
        VoucherOrder order = getById(orderId);
        if (order != null) {
            // 越权校验：只能查询自己的订单
            if (!order.getUserId().equals(currentUserId)) {
                return Result.fail("订单不存在");
            }
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

    /**
     * 单事务：一人一单校验 + 扣库存 + 写订单，三步原子提交。
     * <p>
     * 崩溃安全分析：
     *   - 事务提交前宕机  → MySQL 回滚，requeue 后 count=0，重新执行，不超卖
     *   - 事务提交后宕机  → requeue 后 count>0（orderId 已存在），幂等跳过
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrderTx(OrderMessage orderMsg) {
        Long userId = orderMsg.getUserId();
        Long voucherId = orderMsg.getVoucherId();

        // 一人一单校验（排除已取消记录）
        int count = this.query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .notIn("status", 4, 6)
                .count();
        if (count > 0) {
            log.info("用户已有有效订单，跳过: userId={}, voucherId={}", userId, voucherId);
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

        // 写入订单
        VoucherOrder order = new VoucherOrder();
        order.setId(orderMsg.getOrderId());
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(1); // 1-未支付
        this.save(order);
    }

    private void saveToOutbox(Long orderId, String messageBody) {
        MessageOutbox outbox = new MessageOutbox();
        outbox.setOrderId(orderId);
        outbox.setMessageBody(messageBody);
        outbox.setStatus(0);
        outbox.setRetryCount(0);
        messageOutboxService.save(outbox);
    }

    /**
     * 回滚 Redis 预扣（库存+1，移除用户）- 原子操作
     * 用于预查 MySQL 库存为空时，撤销 Lua 脚本的预扣操作
     */
    private void rollbackRedisPreDeduct(Long voucherId, Long userId) {
        stringRedisTemplate.execute(
                ROLLBACK_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
    }

    @Override
    @Transactional
    public Result payOrder(Long orderId, Integer payType) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);

        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权操作此订单");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean updated = this.lambdaUpdate()
                .set(VoucherOrder::getStatus, 2)
                .set(VoucherOrder::getPayType, payType)
                .set(VoucherOrder::getPayTime, now)
                .set(VoucherOrder::getUpdateTime, now)
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getStatus, 1)
                .update();
        if (updated) {
            return Result.ok();
        }

        VoucherOrder latest = getById(orderId);
        if (latest != null && latest.getStatus() == 2) {
            // 幂等：重复支付
            return Result.ok();
        }
        return Result.fail("订单状态不允许支付");
    }

    @Override
    public Result queryUserOrders(Integer current, Integer status) {
        Long userId = UserHolder.getUser().getId();
        
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VoucherOrder> wrapper = 
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(VoucherOrder::getUserId, userId);
        if (status != null) {
            wrapper.eq(VoucherOrder::getStatus, status);
        }
        wrapper.orderByDesc(VoucherOrder::getCreateTime);
        
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<VoucherOrder> page = 
            this.page(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current, 10), wrapper);
        
        return Result.ok(page.getRecords(), page.getTotal());
    }

    @Override
    @Transactional
    public Result cancelOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权操作此订单");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean updated = this.lambdaUpdate()
                .set(VoucherOrder::getStatus, 4)
                .set(VoucherOrder::getUpdateTime, now)
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getStatus, 1)
                .update();
        if (!updated) {
            VoucherOrder latest = getById(orderId);
            if (latest != null && latest.getStatus() == 4) {
                // 幂等：重复取消
                return Result.ok();
            }
            return Result.fail("订单状态不允许取消");
        }

        // 仅当状态更新成功时，才执行副作用（避免库存重复恢复）
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        rollbackRedisPreDeduct(order.getVoucherId(), userId);

        return Result.ok();
    }

    @Override
    @Transactional
    public Result useVoucher(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权操作此订单");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean updated = this.lambdaUpdate()
                .set(VoucherOrder::getStatus, 3)
                .set(VoucherOrder::getUseTime, now)
                .set(VoucherOrder::getUpdateTime, now)
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getStatus, 2)
                .update();
        if (updated) {
            return Result.ok();
        }

        VoucherOrder latest = getById(orderId);
        if (latest != null && latest.getStatus() == 3) {
            // 幂等：重复核销
            return Result.ok();
        }
        return Result.fail("订单状态不允许核销");
    }

    @Override
    @Transactional
    public Result applyRefund(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            return Result.fail("无权操作此订单");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean updated = this.lambdaUpdate()
                .set(VoucherOrder::getStatus, 5)
                .set(VoucherOrder::getUpdateTime, now)
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getStatus, 2)
                .update();
        if (updated) {
            return Result.ok();
        }

        VoucherOrder latest = getById(orderId);
        if (latest != null && (latest.getStatus() == 5 || latest.getStatus() == 6)) {
            // 幂等：重复申请或已退款
            return Result.ok();
        }
        return Result.fail("订单状态不允许退款");
    }

    @Override
    @Transactional
    public Result confirmRefund(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean updated = this.lambdaUpdate()
                .set(VoucherOrder::getStatus, 6)
                .set(VoucherOrder::getRefundTime, now)
                .set(VoucherOrder::getUpdateTime, now)
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, 5)
                .update();
        if (!updated) {
            VoucherOrder latest = getById(orderId);
            if (latest != null && latest.getStatus() == 6) {
                // 幂等：重复确认
                return Result.ok();
            }
            return Result.fail("订单状态不允许确认退款");
        }

        // 退款成功：恢复库存 + 回滚 Redis 一人一单与库存
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        rollbackRedisPreDeduct(order.getVoucherId(), order.getUserId());

        return Result.ok();
    }
}
