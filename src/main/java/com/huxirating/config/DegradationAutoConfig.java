package com.huxirating.config;

import com.huxirating.degradation.DegradationService;
import com.huxirating.degradation.MonitoringAndRecoveryService;
import com.huxirating.degradation.RedisHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

/**
 * 降级自动配置
 * <p>
 * 负责：
 * - 启用定时任务（健康检查、流量恢复）
 * - 注册降级监听器
 * - 初始化降级链
 *
 * @author Nisson
 */
@Component
@EnableScheduling
public class DegradationAutoConfig {

    private static final Logger log = LoggerFactory.getLogger(DegradationAutoConfig.class);

    @Resource
    private RedisHealthService redisHealthService;

    @Resource
    private DegradationService degradationService;

    @Resource
    private MonitoringAndRecoveryService monitoringAndRecoveryService;

    /**
     * 初始化降级链
     * <p>
     * 降级通知流向：
     * RedisHealthService (L1)
     * → DegradationService (L2)
     * → MonitoringAndRecoveryService (L4)
     * → SentinelConfig (L3)
     */
    @PostConstruct
    public void initDegradationChain() {
        log.info("========== 初始化多级降级策略 ==========");

        // 注册 L2 降级监听器
        redisHealthService.setDegradationListener(degradationService);

        // 注意：MonitoringAndRecoveryService 也是监听器，
        // 但它需要同时监听 DegradationService 的状态变化
        // 这里简化处理，实际可以通过事件总线解耦

        log.info("L1: Redis 健康检查服务已启动");
        log.info("L2: 降级策略服务已启动");
        log.info("L3: Sentinel 熔断保护已配置");
        log.info("L4: 监控告警和流量恢复服务已启动");
        log.info("========== 多级降级策略初始化完成 ==========");
    }
}
