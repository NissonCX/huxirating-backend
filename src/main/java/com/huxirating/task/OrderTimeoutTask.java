package com.huxirating.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.service.ISeckillVoucherService;
import com.huxirating.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
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

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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
                cancelOrder(order);
            } catch (Exception e) {
                log.error("取消订单失败: orderId={}", order.getId(), e);
            }
        }
    }

    /**
     * 取消单个订单
     */
    private void cancelOrder(VoucherOrder order) {
        Long orderId = order.getId();
        Long voucherId = order.getVoucherId();
        Long userId = order.getUserId();

        // 1. 更新订单状态为已取消
        order.setStatus(4);  // 已取消
        order.setUpdateTime(LocalDateTime.now());
        voucherOrderService.updateById(order);

        // 2. 恢复 MySQL 库存
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", voucherId)
                .update();

        // 3. 恢复 Redis 库存
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        stringRedisTemplate.opsForValue().increment(stockKey);

        // 4. 移除 Redis 一人一单记录
        String orderKey = SECKILL_ORDER_KEY_PREFIX + voucherId;
        stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());

        log.info("订单已超时取消: orderId={}, voucherId={}, userId={}", orderId, voucherId, userId);
    }
}
