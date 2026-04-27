package com.huxirating.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.service.ISeckillVoucherService;
import com.huxirating.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.huxirating.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 订单超时处理定时任务
 * 自动取消超时未支付的订单，恢复库存
 *
 * @author Nisson
 */
@Slf4j
@Component
public class OrderTimeoutTask {

    /** 订单超时时间（分钟） */
    private static final int ORDER_TIMEOUT_MINUTES = 30;

    /** 一人一单记录Key前缀 */
    private static final String SECKILL_ORDER_KEY_PREFIX = "seckill:order:";

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("rollback.lua"));
        ROLLBACK_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 自身代理，解决 @Transactional 自调用失效问题。
     * Spring AOP 不拦截 this.method() 调用，必须通过代理对象调用才能使事务生效。
     */
    @Resource
    @Lazy
    private OrderTimeoutTask self;

    /**
     * 每分钟执行一次，扫描超时未支付的订单
     */
    @Scheduled(fixedDelay = 60000)
    public void cancelUnpaidOrders() {
        // 计算超时时间点
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(ORDER_TIMEOUT_MINUTES);

        // 查询超时未支付的订单
        LambdaQueryWrapper<VoucherOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(VoucherOrder::getStatus, 1)  // 未支付
               .lt(VoucherOrder::getCreateTime, timeoutThreshold);  // 创建时间早于超时阈值

        List<VoucherOrder> timeoutOrders = voucherOrderService.list(wrapper);
        if (timeoutOrders.isEmpty()) {
            return;
        }

        log.info("发现 {} 个超时未支付订单，开始处理", timeoutOrders.size());

        for (VoucherOrder order : timeoutOrders) {
            try {
                self.cancelOrder(order);
            } catch (Exception e) {
                log.error("取消订单失败: orderId={}", order.getId(), e);
            }
        }
    }

    /**
     * 取消单个订单
     * 使用事务保证MySQL操作原子性，Redis回滚移到事务提交后执行，避免跨存储不一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(VoucherOrder order) {
        Long orderId = order.getId();
        Long voucherId = order.getVoucherId();
        Long userId = order.getUserId();

        // 1. 条件更新：仅当订单仍为未支付状态时才取消，避免并发覆盖已支付订单
        LocalDateTime now = LocalDateTime.now();
        boolean updated = voucherOrderService.lambdaUpdate()
                .set(VoucherOrder::getStatus, 4)
                .set(VoucherOrder::getUpdateTime, now)
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, 1)
                .update();
        if (!updated) {
            return;
        }

        // 2. 恢复 MySQL 库存（MySQL事务内）
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", voucherId)
                .update();

        // 3. Redis 回滚移到事务提交后执行，避免 MySQL 回滚导致 Redis 库存虚高
        executeAfterCommit(() -> {
            stringRedisTemplate.execute(
                    ROLLBACK_SCRIPT,
                    Arrays.asList(
                            "seckill:stock:" + voucherId,
                            "seckill:order:" + voucherId,
                            "seckill:token:" + voucherId + ":" + userId
                    ),
                    voucherId.toString(),
                    userId.toString()
            );
        });

        log.info("订单已超时取消: orderId={}, voucherId={}, userId={}", orderId, voucherId, userId);
    }

    /**
     * 在当前 MySQL 事务提交后执行 Redis 操作，避免事务回滚导致跨存储不一致。
     * 若不在事务上下文中，则立即执行。
     */
    private void executeAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        action.run();
                    } catch (Exception e) {
                        log.error("事务提交后 Redis 回滚失败，需人工介入: orderId", e);
                    }
                }
            });
        } else {
            action.run();
        }
    }
}
