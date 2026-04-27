package com.huxirating.degradation;

import com.huxirating.config.HealthCheckProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Redis 健康检查服务（L1 降级：健康检查 + 自动切换）
 * <p>
 * 功能：
 * - 可配置间隔 PING 检查 Redis 健康状态
 * - 连续失败达到阈值则判定为不可用
 * - 检测到不可用，自动切换到降级方案
 * - Redis 恢复后，延迟判定才切换回正常模式
 * <p>
 * 所有阈值/间隔参数通过 HealthCheckProperties 从 YAML 注入，不再硬编码。
 *
 * @author Nisson
 */
@Slf4j
@Service
public class RedisHealthService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private HealthCheckProperties healthCheckProperties;

    @Resource
    private TaskScheduler taskScheduler;

    /**
     * 健康状态
     */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    private volatile boolean redisAvailable = true;
    private volatile long lastFailureTime = 0;

    /**
     * 统计信息
     */
    private final AtomicLong totalChecks = new AtomicLong(0);
    private final AtomicLong successChecks = new AtomicLong(0);
    private final AtomicLong failureChecks = new AtomicLong(0);

    /**
     * 降级状态监听器
     */
    private final List<DegradationListener> degradationListeners = new CopyOnWriteArrayList<>();

    public void setDegradationListener(DegradationListener listener) {
        // add-only：不清空已有监听器，避免覆盖其他组件注册的监听器
        addDegradationListener(listener);
    }

    public void addDegradationListener(DegradationListener listener) {
        if (listener != null && !degradationListeners.contains(listener)) {
            degradationListeners.add(listener);
        }
    }

    /**
     * 初始化：执行首次检查 + 注册定时检查任务
     */
    @PostConstruct
    public void init() {
        log.info("Redis 健康检查服务启动，检查间隔: {}ms, 失败阈值: {} 次, 恢复延迟: {}ms",
                healthCheckProperties.getInterval(),
                healthCheckProperties.getFailureThreshold(),
                healthCheckProperties.getRecoveryDelay());

        // 首次检查
        checkHealth();

        // 动态调度：使用 TaskScheduler 支持从配置读取间隔
        if (healthCheckProperties.isEnabled()) {
            long intervalMs = healthCheckProperties.getInterval();
            taskScheduler.scheduleAtFixedRate(this::checkHealth,
                    Instant.now().plusMillis(intervalMs),
                    Duration.ofMillis(intervalMs));
        }
    }

    /**
     * 执行健康检查
     */
    public void checkHealth() {
        totalChecks.incrementAndGet();
        boolean healthy = pingRedis();

        if (healthy) {
            handleSuccess();
        } else {
            handleFailure();
        }
    }

    /**
     * PING Redis 检查连通性
     */
    private boolean pingRedis() {
        try {
            String result = stringRedisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            return "PONG".equalsIgnoreCase(result);
        } catch (Exception e) {
            log.debug("Redis PING 失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 处理检查成功
     */
    private void handleSuccess() {
        successChecks.incrementAndGet();
        int successes = consecutiveSuccesses.incrementAndGet();
        consecutiveFailures.set(0);

        // 如果当前处于降级状态，检查是否可以恢复
        if (!redisAvailable) {
            if (successes >= healthCheckProperties.getRecoverySuccessCount()) {
                // 检查距离上次故障是否足够久
                long timeSinceFailure = System.currentTimeMillis() - lastFailureTime;
                if (timeSinceFailure >= healthCheckProperties.getRecoveryDelay()) {
                    recoverToNormalMode();
                } else {
                    log.info("Redis 已恢复但等待稳定期，还需等待 {}ms",
                            healthCheckProperties.getRecoveryDelay() - timeSinceFailure);
                }
            }
        }
    }

    /**
     * 处理检查失败
     */
    private void handleFailure() {
        failureChecks.incrementAndGet();
        int failures = consecutiveFailures.incrementAndGet();
        consecutiveSuccesses.set(0);

        log.warn("Redis 健康检查失败，连续失败次数: {}/{}", failures, healthCheckProperties.getFailureThreshold());

        if (redisAvailable && failures >= healthCheckProperties.getFailureThreshold()) {
            switchToDegradationMode();
        }
    }

    /**
     * 切换到降级模式
     */
    private void switchToDegradationMode() {
        redisAvailable = false;
        lastFailureTime = System.currentTimeMillis();
        log.error("【降级触发】Redis 不可用，系统已切换到降级模式！");

        for (DegradationListener listener : degradationListeners) {
            try {
                listener.onDegrade();
            } catch (Exception e) {
                log.error("降级监听器执行失败", e);
            }
        }

        // 这里可以触发告警通知
        sendAlert("Redis 不可用，系统已降级");
    }

    /**
     * 恢复到正常模式
     */
    private void recoverToNormalMode() {
        redisAvailable = true;
        consecutiveSuccesses.set(0);
        log.info("【恢复触发】Redis 已恢复正常，系统切换回正常模式！");

        for (DegradationListener listener : degradationListeners) {
            try {
                listener.onRecover();
            } catch (Exception e) {
                log.error("恢复监听器执行失败", e);
            }
        }

        // 发送恢复通知
        sendAlert("Redis 已恢复，系统正常");
    }

    /**
     * 手动触发健康检查
     */
    public HealthStatus getHealthStatus() {
        return new HealthStatus(
                redisAvailable,
                consecutiveFailures.get(),
                consecutiveSuccesses.get(),
                totalChecks.get(),
                successChecks.get(),
                failureChecks.get()
        );
    }

    /**
     * 判断 Redis 当前是否可用
     */
    public boolean isRedisAvailable() {
        return redisAvailable;
    }

    /**
     * 手动设置 Redis 可用状态（用于测试或运维干预）
     */
    public void setRedisAvailable(boolean available) {
        if (this.redisAvailable != available) {
            if (available) {
                recoverToNormalMode();
            } else {
                switchToDegradationMode();
            }
        }
    }

    /**
     * 发送告警通知
     */
    private void sendAlert(String message) {
        // TODO: 集成钉钉、企业微信、邮件等告警通道
        log.warn("【告警】{}", message);
    }

    /**
     * 降级状态监听器接口
     */
    public interface DegradationListener {
        /**
         * 触发降级时调用
         */
        void onDegrade();

        /**
         * 恢复正常时调用
         */
        void onRecover();
    }

    /**
     * 健康状态数据类
     */
    @Data
    public static class HealthStatus {
        private final boolean redisAvailable;
        private final int consecutiveFailures;
        private final int consecutiveSuccesses;
        private final long totalChecks;
        private final long successChecks;
        private final long failureChecks;

        public double getSuccessRate() {
            return totalChecks == 0 ? 0 : (double) successChecks / totalChecks;
        }
    }
}
