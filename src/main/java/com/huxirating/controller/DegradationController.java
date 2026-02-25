package com.huxirating.controller;

import com.huxirating.degradation.*;
import com.huxirating.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;

/**
 * 降级管理控制器
 * <p>
 * 提供：
 * - 降级状态查询
 * - 手动触发降级/恢复
 * - 健康检查状态
 * - 监控指标查询
 *
 * @author Nisson
 */
@Slf4j
@RestController
@RequestMapping("/admin/degradation")
public class DegradationController {

    @Resource
    private DegradationService degradationService;

    @Resource
    private DegradedVoucherOrderService degradedVoucherOrderService;

    @Resource
    private RedisHealthService redisHealthService;

    @Resource
    private MonitoringAndRecoveryService monitoringAndRecoveryService;

    /**
     * 获取降级状态
     */
    @GetMapping("/status")
    public Result getDegradationStatus() {
        Map<String, Object> status = degradedVoucherOrderService.getDegradationStatus();
        return Result.ok(status);
    }

    /**
     * 获取 Redis 健康状态
     */
    @GetMapping("/health")
    public Result getRedisHealth() {
        RedisHealthService.HealthStatus health = redisHealthService.getHealthStatus();
        return Result.ok(health);
    }

    /**
     * 获取监控状态
     */
    @GetMapping("/monitoring")
    public Result getMonitoringStatus() {
        MonitoringAndRecoveryService.MonitoringStatus status =
                monitoringAndRecoveryService.getMonitoringStatus();
        return Result.ok(status);
    }

    /**
     * 手动触发降级（测试用）
     */
    @PostMapping("/trigger")
    public Result triggerDegradation() {
        redisHealthService.setRedisAvailable(false);
        log.warn("【手动操作】触发降级模式");
        return Result.ok("降级模式已触发");
    }

    /**
     * 手动恢复（测试用）
     */
    @PostMapping("/recover")
    public Result triggerRecovery() {
        redisHealthService.setRedisAvailable(true);
        log.warn("【手动操作】触发恢复模式");
        return Result.ok("恢复模式已触发");
    }

    /**
     * 清空本地缓存
     */
    @PostMapping("/cache/clear")
    public Result clearCache() {
        degradationService.clearCache();
        return Result.ok("本地缓存已清空");
    }

    /**
     * 执行健康检查
     */
    @PostMapping("/health/check")
    public Result performHealthCheck() {
        redisHealthService.checkHealth();
        return Result.ok("健康检查已执行");
    }
}
