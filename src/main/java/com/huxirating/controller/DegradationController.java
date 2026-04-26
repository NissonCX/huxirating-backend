package com.huxirating.controller;

import com.huxirating.degradation.*;
import com.huxirating.dto.Result;
import com.huxirating.dto.UserDTO;
import com.huxirating.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Map;
import java.util.Set;

/**
 * 降级管理控制器
 * <p>
 * 提供：
 * - 降级状态查询
 * - 手动触发降级/恢复
 * - 健康检查状态
 * - 监控指标查询
 * <p>
 * 注意：管理接口需要管理员权限，通过 ADMIN_USER_IDS 白名单控制
 *
 * @author Nisson
 */
@Slf4j
@RestController
@RequestMapping("/admin/degradation")
public class DegradationController {

    /**
     * 管理员用户ID白名单
     * 生产环境应从配置文件或数据库读取
     */
    private static final Set<Long> ADMIN_USER_IDS = Set.of(1L);

    @Resource
    private DegradedVoucherOrderService degradedVoucherOrderService;

    @Resource
    private RedisHealthService redisHealthService;

    @Resource
    private MonitoringAndRecoveryService monitoringAndRecoveryService;

    /**
     * 检查当前用户是否为管理员
     */
    private boolean isAdmin() {
        UserDTO user = UserHolder.getUser();
        return user != null && ADMIN_USER_IDS.contains(user.getId());
    }

    /**
     * 获取降级状态（所有人可访问）
     */
    @GetMapping("/status")
    public Result getDegradationStatus() {
        Map<String, Object> status = degradedVoucherOrderService.getDegradationStatus();
        MonitoringAndRecoveryService.MonitoringStatus monitoringStatus =
                monitoringAndRecoveryService.getMonitoringStatus();
        status.put("currentQpsLimit", monitoringStatus.getCurrentSeckillQpsLimit());
        status.put("currentPhase", monitoringStatus.getCurrentPhase());
        status.put("currentTrafficRate", monitoringStatus.getCurrentTrafficRate());
        return Result.ok(status);
    }

    /**
     * 获取 Redis 健康状态（所有人可访问）
     */
    @GetMapping("/health")
    public Result getRedisHealth() {
        RedisHealthService.HealthStatus health = redisHealthService.getHealthStatus();
        return Result.ok(health);
    }

    /**
     * 获取监控状态（所有人可访问）
     */
    @GetMapping("/monitoring")
    public Result getMonitoringStatus() {
        MonitoringAndRecoveryService.MonitoringStatus status =
                monitoringAndRecoveryService.getMonitoringStatus();
        return Result.ok(status);
    }

    /**
     * 手动触发降级（仅管理员）
     */
    @PostMapping("/trigger")
    public Result triggerDegradation() {
        if (!isAdmin()) {
            return Result.fail("无权限访问");
        }
        redisHealthService.setRedisAvailable(false);
        log.warn("【手动操作】触发降级模式，操作人：{}", UserHolder.getUser().getId());
        return Result.ok("降级模式已触发");
    }

    /**
     * 手动恢复（仅管理员）
     */
    @PostMapping("/recover")
    public Result triggerRecovery() {
        if (!isAdmin()) {
            return Result.fail("无权限访问");
        }
        redisHealthService.setRedisAvailable(true);
        log.warn("【手动操作】触发恢复模式，操作人：{}", UserHolder.getUser().getId());
        return Result.ok("恢复模式已触发");
    }

    /**
     * 执行健康检查（仅管理员）
     */
    @PostMapping("/health/check")
    public Result performHealthCheck() {
        if (!isAdmin()) {
            return Result.fail("无权限访问");
        }
        redisHealthService.checkHealth();
        return Result.ok("健康检查已执行");
    }
}
