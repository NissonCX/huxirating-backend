package com.huxirating.degradation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.lang.management.ManagementFactory;

/**
 * 本地 Snowflake ID 生成器（降级时使用）
 * <p>
 * 当 Redis 不可用时，使用本地算法生成分布式唯一 ID
 * <p>
 * ID 结构 (64 位 long)：
 * - 1 位符号位（始终为 0）
 * - 41 位时间戳（毫秒级，可用 69 年）
 * - 10 位机器 ID（支持 1024 台机器）
 * - 12 位序列号（每毫秒每机器 4096 个 ID）
 * <p>
 * 基准时间：2025-01-01 00:00:00 UTC
 *
 * @author Nisson
 */
@Component
public class SnowflakeIdWorker {
    private static final Logger log = LoggerFactory.getLogger(SnowflakeIdWorker.class);

    // 基准时间：2025-01-01 00:00:00 UTC (毫秒)
    private static final long EPOCH = 1735689600000L;

    // 各部分位数
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    // 各部分最大值
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);  // 1023
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);    // 4095

    // 各部分位移
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = WORKER_ID_BITS + SEQUENCE_BITS;

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    /**
     * 构造函数
     * 默认使用机器 IP 的哈希值作为 workerId，确保分布式环境唯一性
     */
    public SnowflakeIdWorker() {
        this.workerId = generateWorkerId();
        log.info("SnowflakeIdWorker 初始化，workerId={}", workerId);
    }

    /**
     * 指定 workerId 的构造函数
     */
    public SnowflakeIdWorker(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    String.format("workerId 必须在 [0, %d] 范围内", MAX_WORKER_ID));
        }
        this.workerId = workerId;
        log.info("SnowflakeIdWorker 初始化，workerId={}", workerId);
    }

    /**
     * 生成下一个唯一 ID
     *
     * @return 唯一 ID
     */
    public synchronized long nextId() {
        long timestamp = getCurrentTimestamp();

        // 时钟回拨检测
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= 5) {
                // 小幅度回拨，等待
                try {
                    Thread.sleep(offset << 1);
                    timestamp = getCurrentTimestamp();
                    if (timestamp < lastTimestamp) {
                        throw new RuntimeException(
                                String.format("时钟回拨超过阈值，拒绝生成 ID。lastTimestamp=%d, currentTimestamp=%d",
                                        lastTimestamp, timestamp));
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException("时钟回拨等待被中断", e);
                }
            } else {
                throw new RuntimeException(
                        String.format("时钟回拨检测：lastTimestamp=%d, currentTimestamp=%d", lastTimestamp, timestamp));
            }
        }

        // 同一毫秒内，序列号自增
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 序列号溢出，等待下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 新的毫秒，序列号重置
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 组装 ID
        long id = ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;

        return id;
    }

    /**
     * 生成带前缀的 ID（用于兼容 RedisIdWorker 的用法）
     *
     * @param keyPrefix 前缀（此参数仅用于兼容，不影响生成的 ID）
     * @return 唯一 ID
     */
    public long nextId(String keyPrefix) {
        return nextId();
    }

    /**
     * 获取当前时间戳（毫秒）
     */
    private long getCurrentTimestamp() {
        return System.currentTimeMillis();
    }

    /**
     * 等待下一毫秒
     */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = getCurrentTimestamp();
        while (timestamp <= lastTimestamp) {
            timestamp = getCurrentTimestamp();
        }
        return timestamp;
    }

    /**
     * 生成 workerId（基于 IP 或其他唯一标识）
     * 默认使用环境变量或本地配置
     */
    private long generateWorkerId() {
        // 优先使用环境变量
        String workerIdStr = System.getenv("SNOWFLAKE_WORKER_ID");
        if (workerIdStr != null) {
            try {
                long id = Long.parseLong(workerIdStr);
                if (id >= 0 && id <= MAX_WORKER_ID) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        // 使用进程 ID + 网络接口哈希
        try {
            int processId = getProcessId();
            String networkInfo = System.getProperty("user.name") + System.getProperty("os.name");
            long hash = (processId + networkInfo.hashCode()) & MAX_WORKER_ID;
            return hash;
        } catch (Exception e) {
            // 兜底：使用随机数（不太推荐，但至少可用）
            return (long) (Math.random() * (MAX_WORKER_ID + 1));
        }
    }

    /**
     * 获取当前进程 ID
     */
    private int getProcessId() {
        try {
            String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
            return Integer.parseInt(runtimeName.split("@")[0]);
        } catch (Exception e) {
            return (int) (System.currentTimeMillis() % 1024);
        }
    }

    @PostConstruct
    public void init() {
        log.info("SnowflakeIdWorker 已初始化，workerId={}, EPOCH={}", workerId, EPOCH);
    }
}
