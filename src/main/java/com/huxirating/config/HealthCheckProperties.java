package com.huxirating.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 降级健康检查配置。
 * 绑定 degradation.health-check 前缀，替换 RedisHealthService 中的硬编码常量。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "degradation.health-check")
public class HealthCheckProperties {

    /** 是否启用健康检查 */
    private boolean enabled = true;

    /** 健康检查间隔（毫秒） */
    private int interval = 5000;

    /** 连续失败阈值，达到后判定 Redis 不可用 */
    private int failureThreshold = 3;

    /** 连续成功次数，达到后才判定 Redis 恢复 */
    private int recoverySuccessCount = 2;

    /** 恢复延迟（毫秒），距离上次故障至少多久才允许恢复 */
    private long recoveryDelay = 30000;
}
