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
            // 哨兵模式：高可用 + 读写分离
            org.redisson.config.SentinelServersConfig sentinelConfig = config.useSentinelServers()
                    .setMasterName(sentinelMaster)
                    .addSentinelAddress(convertToAddresses(sentinelNodes))
                    .setDatabase(0)
                    // 密码认证
                    .setPassword(password != null && !password.isEmpty() ? password : null)
                    // 连接池配置
                    .setMasterConnectionPoolSize(64)
                    .setSlaveConnectionPoolSize(64)
                    .setIdleConnectionTimeout(10000)
                    .setConnectTimeout(10000)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500);

            // 【大厂优化1】读写分离配置
            // ReadMode.SLAVE: 读操作从Slave读取，Slave不可用时降级到Master
            // ReadMode.MASTER_SLAVE: 读操作从Master和Slave一起读取（性能最高，但可能读到旧数据）
            // ReadMode.MASTER: 所有读操作都走Master（强一致，性能差）
            sentinelConfig.setReadMode(org.redisson.config.ReadMode.SLAVE);

            // 订阅操作（如发布订阅、Keyspace事件）必须走Master
            sentinelConfig.setSubscriptionMode(org.redisson.config.SubscriptionMode.MASTER);

            // 【大厂优化2】负载均衡策略：轮询Slave，避免单个Slave压力过大
            sentinelConfig.setLoadBalancer(new org.redisson.connection.balancer.RoundRobinLoadBalancer());

            // 【大厂优化3】订阅连接池大小（处理发布订阅、Keyspace事件）
            sentinelConfig.setSubscriptionConnectionPoolSize(50);
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
