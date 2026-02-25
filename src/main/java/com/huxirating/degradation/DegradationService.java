package com.huxirating.degradation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.huxirating.entity.SeckillVoucher;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.service.ISeckillVoucherService;
import com.huxirating.service.IVoucherOrderService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 降级策略服务（L2 降级：DB 直写 + 本地缓存）
 * <p>
 * 当 Redis 不可用时，启用以下降级策略：
 * - 库存查询：改查 MySQL（带本地缓存）
 * - 一人一单：改查 MySQL（带本地缓存）
 * - ID 生成：改用本地 Snowflake 算法
 * - 乐观锁扣库存 + 同步创建订单
 * <p>
 * 性能调整：
 * - Sentinel 限流从 1000 QPS 降到 100 QPS
 * - 本地缓存减少数据库压力
 *
 * @author Nisson
 */
@Slf4j
@Service
public class DegradationService implements RedisHealthService.DegradationListener {

    @Resource
    private SnowflakeIdWorker snowflakeIdWorker;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 是否处于降级模式
     */
    private volatile boolean degraded = false;

    /**
     * 本地缓存配置
     */
    private static final int CACHE_MAX_SIZE = 10000;
    private static final Duration CACHE_EXPIRE = Duration.ofMinutes(5);

    /**
     * 库存本地缓存（voucherId -> stock）
     */
    private final Cache<Long, Integer> stockCache = Caffeine.newBuilder()
            .maximumSize(CACHE_MAX_SIZE)
            .expireAfterWrite(CACHE_EXPIRE)
            .build();

    /**
     * 用户购买记录本地缓存（userId:voucherId -> 已购买）
     */
    private final Cache<String, Boolean> purchaseCache = Caffeine.newBuilder()
            .maximumSize(CACHE_MAX_SIZE)
            .expireAfterWrite(CACHE_EXPIRE)
            .build();

    /**
     * 库存扣减记录（用于补偿 Redis）
     * key: voucherId, value: 扣减数量
     */
    private final ConcurrentHashMap<Long, Integer> stockDecrementLog = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("降级策略服务已初始化");
    }

    /**
     * 触发降级
     */
    @Override
    public void onDegrade() {
        degraded = true;
        log.warn("【L2 降级启动】已切换到 DB 直写 + 本地缓存模式");
        log.warn("降级策略：库存查询 -> MySQL，一人一单 -> MySQL，ID 生成 -> Snowflake");
        log.warn("性能调整：Sentinel 限流 QPS 降低到 100");
    }

    /**
     * 恢复正常
     */
    @Override
    public void onRecover() {
        degraded = false;
        log.info("【L2 降级恢复】Redis 已恢复，正在切换回正常模式...");

        // TODO: 启动数据补偿任务，将本地缓存的数据同步回 Redis
        // 这可以通过后台任务逐步完成，避免阻塞主流程
    }

    /**
     * 判断是否处于降级模式
     */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * 降级模式：获取库存（带本地缓存）
     */
    public Integer getStockWithCache(Long voucherId) {
        return stockCache.get(voucherId, key -> {
            SeckillVoucher sv = seckillVoucherService.getById(key);
            return sv != null ? sv.getStock() : 0;
        });
    }

    /**
     * 降级模式：检查用户是否已购买（带本地缓存）
     */
    public boolean hasUserPurchased(Long userId, Long voucherId) {
        String cacheKey = userId + ":" + voucherId;
        return purchaseCache.get(cacheKey, key -> {
            long count = voucherOrderService.query()
                    .eq("user_id", userId)
                    .eq("voucher_id", voucherId)
                    .count();
            return count > 0;
        });
    }

    /**
     * 降级模式：扣减库存（带本地缓存更新）
     */
    public boolean deductStock(Long voucherId) {
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if (success) {
            // 更新本地缓存
            stockCache.invalidate(voucherId);

            // 记录扣减，用于后续补偿 Redis
            stockDecrementLog.merge(voucherId, 1, (a, b) -> a + b);
        }

        return success;
    }

    /**
     * 降级模式：创建订单
     */
    public VoucherOrder createOrder(Long orderId, Long userId, Long voucherId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(1); // 未支付

        boolean saved = voucherOrderService.save(order);
        if (saved) {
            // 更新购买记录缓存
            String cacheKey = userId + ":" + voucherId;
            purchaseCache.put(cacheKey, true);
        }

        return order;
    }

    /**
     * 降级模式：使用 Snowflake 生成订单 ID
     */
    public Long generateOrderId() {
        return snowflakeIdWorker.nextId();
    }

    /**
     * 获取当前限流 QPS（降级时降低）
     */
    public int getCurrentQpsLimit() {
        return degraded ? 100 : 1000;
    }

    /**
     * 获取降级状态信息
     */
    public DegradationStatus getStatus() {
        DegradationStatus status = new DegradationStatus();
        status.degraded = degraded;
        status.stockCacheSize = stockCache.estimatedSize();
        status.purchaseCacheSize = purchaseCache.estimatedSize();
        status.stockDecrementLogSize = stockDecrementLog.size();
        status.currentQpsLimit = getCurrentQpsLimit();
        return status;
    }

    /**
     * 清空本地缓存（用于测试或强制刷新）
     */
    public void clearCache() {
        stockCache.invalidateAll();
        purchaseCache.invalidateAll();
        log.info("本地缓存已清空");
    }

    /**
     * 获取库存扣减记录（用于补偿 Redis）
     */
    public ConcurrentHashMap<Long, Integer> getStockDecrementLog() {
        return stockDecrementLog;
    }

    /**
     * 降级状态数据类
     */
    @Data
    public static class DegradationStatus {
        private boolean degraded;
        private long stockCacheSize;
        private long purchaseCacheSize;
        private int stockDecrementLogSize;
        private int currentQpsLimit;
    }
}
