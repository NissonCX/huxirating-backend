package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Redis 全局唯一 ID 生成器
 * ID结构：时间戳(31位) + 序列号(32位) = 63位 long
 *
 * @author Nisson
 */
@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1735689600L; // 2025-01-01 00:00:00 UTC
    private static final long COUNT_BITS = 32;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成全局唯一递增 ID
     * Redis Key: icr:{keyPrefix}:{yyyyMMdd}，按天自增
     */
    public long nextId(String keyPrefix) {
        long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        return timestamp << COUNT_BITS | count;
    }
}