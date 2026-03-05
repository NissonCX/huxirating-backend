package com.huxirating.degradation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.huxirating.config.ApplicationContextProvider;
import com.huxirating.entity.SeckillVoucher;
import com.huxirating.entity.VoucherOrder;
import com.huxirating.service.ISeckillVoucherService;
import com.huxirating.service.IVoucherOrderService;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@Service
public class DegradationService implements RedisHealthService.DegradationListener {
    private static final Logger log = LoggerFactory.getLogger(DegradationService.class);

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
     * 是否正在恢复中（同步数据期间）
     * 【关键】同步期间阻塞请求，避免 race condition
     */
    private volatile boolean recovering = false;

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
     * 库存扣减记录（用于补偿 Redis 库存）
     * key: voucherId, value: 扣减数量
     */
    private final ConcurrentHashMap<Long, Integer> stockDecrementLog = new ConcurrentHashMap<>();

    /**
     * 降级期间购买记录（用于补偿 Redis 一人一单 Set）
     * key: voucherId, value: 购买该券的用户 ID 集合
     */
    private final ConcurrentHashMap<Long, Set<Long>> purchaseRecordLog = new ConcurrentHashMap<>();

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
     * 【第三轮修复】解决 race condition：
     * - 同步期间设置 recovering=true，请求进入"恢复中"状态
     * - 同步期间新请求走排队或返回"系统恢复中"
     * - 使用 Redis 事务确保同步的原子性
     * <p>
     * 时序分析：
     * T1: 用户 A 在降级期间下单 → purchaseRecordLog 记录
     * T2: Redis 恢复，设置 recovering=true
     * T3: 使用 MULTI/EXEC 事务原子同步所有数据
     * T4: 同步完成，设置 recovering=false, degraded=false
     * T5: 用户 A 再来买 → Lua 查 Set 发现已购买 → 正确拒绝
     */
    @Override
    @SuppressWarnings("unchecked")
    public void onRecover() {
        // 【关键】先设置恢复中状态，再开始同步
        recovering = true;
        log.info("【L2 降级恢复】Redis 已恢复，开始同步数据...");

        try {
            StringRedisTemplate redisTemplate = ApplicationContextProvider.getBean(StringRedisTemplate.class);
            if (redisTemplate == null) {
                log.error("【恢复同步】无法获取 RedisTemplate");
                recovering = false;
                degraded = true;  // 同步失败，保持降级
                return;
            }

            // 1. 清空排队队列（同步期间不接受新排队）
            try {
                SeckillQueueService queueService = ApplicationContextProvider.getBean(SeckillQueueService.class);
                if (queueService != null) {
                    queueService.clearAllQueues();
                    log.info("【恢复同步】排队队列已清空");
                }
            } catch (Exception e) {
                log.warn("【恢复同步】清空排队队列失败", e);
            }

            // 2. 【关键】使用 Redis 事务原子同步一人一单数据
            // 所有 SADD 操作在一个 MULTI/EXEC 事务中执行，避免中间状态
            if (!purchaseRecordLog.isEmpty()) {
                final int[] syncedCount = {0};

                // 使用 SessionCallback + RedisOperations 执行事务
                redisTemplate.execute(new SessionCallback<Void>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public Void execute(org.springframework.data.redis.core.RedisOperations operations) {
                        operations.multi();  // 开始事务

                        try {
                            for (Map.Entry<Long, Set<Long>> entry : purchaseRecordLog.entrySet()) {
                                Long voucherId = entry.getKey();
                                Set<Long> userIds = entry.getValue();

                                if (userIds != null && !userIds.isEmpty()) {
                                    String orderKey = "seckill:order:" + voucherId;
                                    String[] userIdStrs = userIds.stream()
                                            .map(String::valueOf)
                                            .toArray(String[]::new);

                                    operations.opsForSet().add(orderKey, userIdStrs);
                                    syncedCount[0] += userIds.size();
                                }
                            }

                            operations.exec();  // 提交事务
                            log.info("【恢复同步】一人一单数据已原子同步，共 {} 条购买记录", syncedCount[0]);
                        } catch (Exception e) {
                            operations.discard();  // 回滚事务
                            log.error("【恢复同步】事务执行失败，已回滚", e);
                            throw new RuntimeException("Redis 事务执行失败", e);
                        }

                        return null;
                    }
                });

                purchaseRecordLog.clear();
            }

            // 3. 同步库存到 Redis（从 DB 读取最新值）
            if (!stockDecrementLog.isEmpty()) {
                for (Long voucherId : stockDecrementLog.keySet()) {
                    try {
                        SeckillVoucher sv = seckillVoucherService.getById(voucherId);
                        if (sv != null) {
                            String stockKey = "seckill:stock:" + voucherId;
                            redisTemplate.opsForValue().set(stockKey, String.valueOf(sv.getStock()));
                            log.info("【恢复同步】voucherId={} 库存已更新为 {}", voucherId, sv.getStock());
                        }
                    } catch (Exception e) {
                        log.warn("【恢复同步】库存同步失败: voucherId={}", voucherId, e);
                    }
                }

                log.info("【恢复同步】库存数据同步完成，共 {} 个优惠券", stockDecrementLog.size());
                stockDecrementLog.clear();
            }

            // 4. 清理本地 Caffeine 缓存
            long stockCacheSize = stockCache.estimatedSize();
            long purchaseCacheSize = purchaseCache.estimatedSize();
            stockCache.invalidateAll();
            purchaseCache.invalidateAll();
            log.info("【恢复同步】本地缓存已清空: stock={}, purchase={}", stockCacheSize, purchaseCacheSize);

            // 5. 【关键】同步完成后才切换状态
            degraded = false;
            recovering = false;

            log.info("【L2 降级恢复】数据同步完成，一人一单约束已恢复");

        } catch (Exception e) {
            log.error("【恢复同步】执行失败，保持降级状态", e);
            recovering = false;
            // 同步失败，保持降级状态，等待下次恢复
            degraded = true;
        }
    }

    /**
     * 判断是否处于降级模式
     */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * 判断是否正在恢复中（同步数据期间）
     */
    public boolean isRecovering() {
        return recovering;
    }

    /**
     * 判断是否可以处理请求（非降级且非恢复中）
     */
    public boolean canHandleRequest() {
        return !degraded && !recovering;
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
                    .ne("status", 4)  // 排除已取消订单，与正常路径保持一致
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

            // 【关键】记录降级期间的购买记录，用于恢复时同步到 Redis Set
            purchaseRecordLog.computeIfAbsent(voucherId, k -> ConcurrentHashMap.newKeySet()).add(userId);
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
        status.purchaseRecordLogSize = purchaseRecordLog.size();
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
        private int purchaseRecordLogSize;  // 降级期间购买记录数
        private int currentQpsLimit;
    }
}
