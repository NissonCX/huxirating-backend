package com.huxirating.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.huxirating.entity.Shop;
import com.huxirating.entity.ShopType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置
 * <p>
 * 多级缓存架构：Caffeine（L1，微秒级） → Redis（L2，毫秒级） → DB
 * 适用于读多写少的热点数据，减少 Redis 网络开销
 *
 * @author Nisson
 */
@Configuration
public class CaffeineConfig {

    /**
     * 商铺详情本地缓存
     * 最大 1000 条，写入后 5 分钟过期，访问后 2 分钟刷新
     */
    @Bean
    public Cache<Long, Shop> shopCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .expireAfterAccess(2, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }

    /**
     * 商铺类型列表本地缓存（数据极少变动）
     * 最大 10 条，写入后 30 分钟过期
     */
    @Bean
    public Cache<String, List<ShopType>> shopTypeCache() {
        return Caffeine.newBuilder()
                .maximumSize(10)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
}
