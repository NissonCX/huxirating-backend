package com.huxirating.degradation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.huxirating.config.ApplicationContextProvider;
import com.huxirating.entity.SeckillVoucher;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.service.ISeckillVoucherService;
import com.huxirating.service.IVoucherOrderService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

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
 * - 正常模式：50000 QPS（接近 Redis 单机极限的 50%）
 * - 降级模式：5000 QPS（DB 直写模式降低压力）
 * - 本地缓存容量：100000（提升 10 倍）
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
    private static final int CACHE_MAX_SIZE = 100000;  // 提升到 10 万
    private static final int NORMAL_QPS = 50000;       // 正常模式 QPS
    private static final int DEGRADED_QPS = 5000;      // 降级模式 QPS
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
        log.warn("性能调整：Sentinel 限流 QPS 从 {} 降低到 {}", NORMAL_QPS, DEGRADED_QPS);
    }

    /**
     * 恢复正常
     * <p>
     * 【Cache-Aside 模式】Redis 恢复后的处理策略：
     * 1. 删除 Redis 中降级期间可能变脏的缓存（库存、一人一单记录）
     * 2. 清空本地 Caffeine 缓存
     * 3. 下次查询时，自动从 MySQL 重建缓存
     * <p>
     * 这种方案的优势：
     * - 简单：不需要同步数据，只需删除缓存
     * - 可靠：MySQL 是唯一数据源，不会出现数据不一致
     * - 高效：只删除降级期间有修改的缓存，而不是全部删除
     */
    @Override
    public void onRecover() {
        degraded = false;
        log.info("【L2 降级恢复】Redis 已恢复，开始清理缓存...");

        // 异步清理，避免阻塞主流程
        new Thread(() -> {
            try {
                // 获取 Spring 上下文
                StringRedisTemplate redisTemplate = ApplicationContextProvider.getBean(StringRedisTemplate.class);
                if (redisTemplate == null) {
                    log.error("【缓存清理】无法获取 RedisTemplate");
                    return;
                }

                // 1. 清理本地 Caffeine 缓存
                long stockCacheSize = stockCache.estimatedSize();
                long purchaseCacheSize = purchaseCache.estimatedSize();
                stockCache.invalidateAll();
                purchaseCache.invalidateAll();
                log.info("【缓存清理】本地 Caffeine 已清空: stock={}, purchase={}",
                        stockCacheSize, purchaseCacheSize);

                // 2. 清理 Redis 中降级期间修改过的缓存
                if (!stockDecrementLog.isEmpty()) {
                    int clearedCount = 0;
                    for (Long voucherId : stockDecrementLog.keySet()) {
                        try {
                            // 删除库存缓存
                            String stockKey = "seckill:stock:" + voucherId;
                            redisTemplate.delete(stockKey);

                            // 删除一人一单记录缓存（可选，因为降级期间可能已修改）
                            String orderKey = "seckill:order:" + voucherId;
                            redisTemplate.delete(orderKey);

                            clearedCount++;
                            log.debug("【缓存清理】已删除 voucherId={} 的 Redis 缓存", voucherId);
                        } catch (Exception e) {
                            log.warn("【缓存清理】删除失败: voucherId={}", voucherId, e);
                        }
                    }

                    log.info("【缓存清理】Redis 缓存已清理: {} 个优惠券", clearedCount);

                    // 3. 清空补偿日志
                    stockDecrementLog.clear();
                }

                log.info("【L2 降级恢复】缓存清理完成，下次查询时将从 MySQL 重建缓存");
            } catch (Exception e) {
                log.error("【缓存清理】执行失败", e);
            }
        }, "Redis-Cache-Cleanup").start();
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
        return degraded ? DEGRADED_QPS : NORMAL_QPS;
    }

    /**
     * 获取正常模式 QPS
     */
    public int getNormalQps() {
        return NORMAL_QPS;
    }

    /**
     * 获取降级模式 QPS
     */
    public int getDegradedQps() {
        return DEGRADED_QPS;
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
