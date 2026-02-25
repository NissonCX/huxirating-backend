package com.huxirating.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置（支持哨兵模式高可用）
 * <p>
 * 通过配置 redis.sentinel.enabled=true 启用哨兵模式
 * 哨兵模式优势：Master 挂了 30 秒内自动故障转移，大部分情况感知不到
 *
 * @author Nisson
 */
@Configuration
public class RedissonConfig {

    @Value("${redis.sentinel.enabled:false}")
    private boolean sentinelEnabled;

    @Value("${redis.sentinel.master:mymaster}")
    private String sentinelMaster;

    @Value("${redis.sentinel.nodes:127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381}")
    private String sentinelNodes;

    @Value("${redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${redis.port:6379}")
    private int redisPort;

    @Value("${redis.password:}")
    private String password;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        if (sentinelEnabled) {
            // 哨兵模式：高可用，自动故障转移
            config.useSentinelServers()
                    .setMasterName(sentinelMaster)
                    .addSentinelAddress(convertToAddresses(sentinelNodes))
                    .setDatabase(0)
                    // 密码认证
                    .setPassword(password != null && !password.isEmpty() ? password : null)
                    // 故障转移等待时间
                    .setMasterConnectionPoolSize(64)
                    .setSlaveConnectionPoolSize(64)
                    // 空闲检测
                    .setIdleConnectionTimeout(10000)
                    // 连接超时
                    .setConnectTimeout(10000)
                    // 超时重试次数
                    .setRetryAttempts(3)
                    .setRetryInterval(1500);
        } else {
            // 单机模式：开发环境使用
            config.useSingleServer()
                    .setAddress("redis://" + redisHost + ":" + redisPort)
                    .setDatabase(0)
                    // 密码认证
                    .setPassword(password != null && !password.isEmpty() ? password : null)
                    .setConnectionPoolSize(64)
                    .setConnectionMinimumIdleSize(10)
                    .setIdleConnectionTimeout(10000)
                    .setConnectTimeout(10000)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500);
        }

        return Redisson.create(config);
    }

    /**
     * 将逗号分隔的哨兵节点列表转换为 Redisson 地址格式
     */
    private String[] convertToAddresses(String nodes) {
        String[] nodeArray = nodes.split(",");
        String[] addresses = new String[nodeArray.length];
        for (int i = 0; i < nodeArray.length; i++) {
            addresses[i] = "redis://" + nodeArray[i].trim();
        }
        return addresses;
    }
}
