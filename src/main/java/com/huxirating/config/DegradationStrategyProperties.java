package com.huxirating.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 降级策略相关配置。
 * 统一收口为单一配置源，避免 YAML 与代码常量不一致。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "degradation.strategy")
public class DegradationStrategyProperties {

    /** 本地缓存最大容量 */
    private int cacheMaxSize = 100000;

    /** 本地缓存过期时间（分钟） */
    private int cacheExpireMinutes = 5;

    /** 秒杀接口降级模式 QPS */
    private int degradedQps = 2000;

    /** 秒杀接口正常模式 QPS */
    private int normalQps = 15000;

    /** 热点参数默认 QPS（按 voucherId） */
    private int hotspotDefaultQps = 1000;

    /** 热点参数特例券 ID */
    private long hotspotSpecialVoucherId = 1L;

    /** 热点参数特例券 QPS */
    private int hotspotSpecialQps = 2000;

    /** 商铺查询接口 QPS */
    private int shopQueryQps = 1000;

    public int getRecoveryQps(int trafficRatePercent) {
        return Math.max(1, normalQps * trafficRatePercent / 100);
    }

    public int getDegradedTrafficRate() {
        if (normalQps <= 0 || degradedQps <= 0) {
            return 0;
        }
        return Math.max(1, Math.min(100, degradedQps * 100 / normalQps));
    }
}
