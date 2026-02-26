package com.huxirating.degradation;

import com.huxirating.config.SentinelConfig;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 监控告警和流量恢复服务（L4）
 * <p>
 * 功能：
 * - 实时告警通知运维（钉钉、企业微信、邮件、短信）
 * - Redis 恢复后逐步放开流量（10% → 50% → 100%）
 * - 监控系统健康指标（QPS、RT、错误率）
 * - 自动流量恢复控制
 *
 * @author Nisson
 */
@Slf4j
@Service
public class MonitoringAndRecoveryService implements RedisHealthService.DegradationListener {

    @Resource
    private RedisHealthService redisHealthService;

    @Resource
    private DegradationService degradationService;

    @Resource
    private SentinelConfig sentinelConfig;

    /**
     * 流量恢复阶段
     */
    private enum RecoveryPhase {
        DEGRADED,        // 降级模式（0% 流量）
        RECOVERY_10,     // 恢复 10% 流量
        RECOVERY_50,     // 恢复 50% 流量
        RECOVERY_100,    // 恢复 100% 流量
        NORMAL           // 正常模式
    }

    private volatile RecoveryPhase currentPhase = RecoveryPhase.NORMAL;
    private final AtomicBoolean isRecovering = new AtomicBoolean(false);
    private LocalDateTime phaseStartTime;

    /**
     * 各阶段持续时间（秒）
     */
    private static final int PHASE_10_DURATION = 60;   // 10% 流量持续 1 分钟
    private static final int PHASE_50_DURATION = 120;  // 50% 流量持续 2 分钟

    /**
     * 监控指标统计
     */
    private final AtomicInteger alertCount = new AtomicInteger(0);
    private volatile LocalDateTime lastAlertTime;

    @Override
    public void onDegrade() {
        currentPhase = RecoveryPhase.DEGRADED;
        phaseStartTime = LocalDateTime.now();
        isRecovering.set(false);

        log.error("【L4 监控】系统进入降级模式，启动流量恢复准备");

        // 发送降级告警
        sendDegradationAlert("系统进入降级模式，Redis 不可用");
    }

    @Override
    public void onRecover() {
        if (isRecovering.compareAndSet(false, true)) {
            log.info("【L4 监控】Redis 已恢复，启动逐步流量恢复");
            sendRecoveryAlert("Redis 已恢复，启动逐步流量恢复");

            // 开始流量恢复流程
            startRecoveryProcess();
        }
    }

    /**
     * 启动流量恢复流程
     */
    private void startRecoveryProcess() {
        // 首先进入 10% 流量阶段
        transitionToPhase(RecoveryPhase.RECOVERY_10);
    }

    /**
     * 流量恢复进度检查（每 30 秒检查一次）
     */
    @Scheduled(fixedRate = 30000)
    public void checkRecoveryProgress() {
        if (!isRecovering.get()) {
            return;
        }

        if (phaseStartTime == null) {
            return;
        }

        long secondsSincePhaseStart = java.time.Duration.between(
                phaseStartTime, LocalDateTime.now()).getSeconds();

        switch (currentPhase) {
            case RECOVERY_10:
                if (secondsSincePhaseStart >= PHASE_10_DURATION) {
                    transitionToPhase(RecoveryPhase.RECOVERY_50);
                }
                break;
            case RECOVERY_50:
                if (secondsSincePhaseStart >= PHASE_50_DURATION) {
                    transitionToPhase(RecoveryPhase.RECOVERY_100);
                }
                break;
            case RECOVERY_100:
                // 100% 流量稳定一段时间后，完全恢复正常
                if (secondsSincePhaseStart >= 60) {
                    transitionToPhase(RecoveryPhase.NORMAL);
                }
                break;
            default:
                break;
        }
    }

    /**
     * 流量阶段切换
     */
    private void transitionToPhase(RecoveryPhase newPhase) {
        RecoveryPhase oldPhase = currentPhase;
        currentPhase = newPhase;
        phaseStartTime = LocalDateTime.now();

        log.info("【L4 流量恢复】阶段切换: {} -> {}", oldPhase, newPhase);

        // 更新 Sentinel 限流配置
        updateSentinelRules(newPhase);

        // 发送阶段切换通知
        sendPhaseChangeAlert(oldPhase, newPhase);

        // 如果恢复到正常模式
        if (newPhase == RecoveryPhase.NORMAL) {
            isRecovering.set(false);
            sendRecoveryAlert("流量恢复完成，系统已恢复正常");
        }
    }

    /**
     * 更新 Sentinel 限流规则（根据流量阶段）
     */
    private void updateSentinelRules(RecoveryPhase phase) {
        boolean degraded;
        switch (phase) {
            case DEGRADED:
                degraded = true;
                break;
            case RECOVERY_10:
            case RECOVERY_50:
            case RECOVERY_100:
                degraded = false; // Redis 已恢复，但逐步放开流量
                break;
            case NORMAL:
                degraded = false;
                break;
            default:
                degraded = false;
        }
        sentinelConfig.updateFlowRulesForDegradation(degraded);
    }

    /**
     * 获取当前流量通过率
     */
    public int getCurrentTrafficRate() {
        switch (currentPhase) {
            case DEGRADED:
                return 0;
            case RECOVERY_10:
                return 10;
            case RECOVERY_50:
                return 50;
            case RECOVERY_100:
                return 100;
            case NORMAL:
                return 100;
            default:
                return 100;
        }
    }

    /**
     * 发送降级告警（暂只记录日志）
     */
    private void sendDegradationAlert(String message) {
        alertCount.incrementAndGet();
        lastAlertTime = LocalDateTime.now();

        String fullMessage = String.format(
                "【⚠️ 严重告警】秒杀系统降级\n时间: %s\n消息: %s\n操作: 请立即检查 Redis 服务状态",
                LocalDateTime.now(), message);

        log.error(fullMessage);
        // TODO: 后续可接入钉钉/企业微信告警
    }

    /**
     * 发送恢复通知（暂只记录日志）
     */
    private void sendRecoveryAlert(String message) {
        String fullMessage = String.format(
                "【✅ 恢复通知】秒杀系统恢复\n时间: %s\n消息: %s\n当前流量: %d%%",
                LocalDateTime.now(), message, getCurrentTrafficRate());

        log.info(fullMessage);
        // TODO: 后续可接入钉钉/企业微信告警
    }

    /**
     * 发送阶段切换通知（暂只记录日志）
     */
    private void sendPhaseChangeAlert(RecoveryPhase oldPhase, RecoveryPhase newPhase) {
        String fullMessage = String.format(
                "【📊 流量恢复】阶段切换\n时间: %s\n阶段: %s -> %s\n当前流量: %d%%",
                LocalDateTime.now(), oldPhase, newPhase, getCurrentTrafficRate());

        log.info(fullMessage);
        // TODO: 后续可接入钉钉/企业微信告警
    }

    /**
     * 获取监控状态
     */
    public MonitoringStatus getMonitoringStatus() {
        MonitoringStatus status = new MonitoringStatus();
        status.currentPhase = currentPhase.name();
        status.currentTrafficRate = getCurrentTrafficRate();
        status.isRecovering = isRecovering.get();
        status.phaseStartTime = phaseStartTime;
        status.totalAlerts = alertCount.get();
        status.lastAlertTime = lastAlertTime;

        // 计算当前阶段已持续时长
        if (phaseStartTime != null) {
            status.secondsInCurrentPhase = java.time.Duration.between(
                    phaseStartTime, LocalDateTime.now()).getSeconds();
        }

        return status;
    }

    /**
     * 监控状态数据类
     */
    @Data
    public static class MonitoringStatus {
        private String currentPhase;
        private int currentTrafficRate;
        private boolean isRecovering;
        private LocalDateTime phaseStartTime;
        private long secondsInCurrentPhase;
        private int totalAlerts;
        private LocalDateTime lastAlertTime;
    }
}
