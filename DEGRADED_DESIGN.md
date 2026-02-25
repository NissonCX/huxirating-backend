# Redis 故障多级降级策略文档

## 概述

本秒杀系统实现了完整的多级降级策略，当 Redis 不可用时，系统能够自动降级并保持核心功能可用。

## 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                         秒杀系统架构                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────┐      ┌──────────────┐      ┌──────────────┐  │
│  │ Redis Sentinel│─────▶│ Redis Master │◀─────│ Redis Slave  │  │
│  │   (L0 高可用) │      │   (主节点)    │      │   (从节点)    │  │
│  └──────────────┘      └──────────────┘      └──────────────┘  │
│         │                                                      │
│         │ 故障转移 30s 内                                      │
│         ▼                                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │             L1: 健康检查 + 自动切换                        │  │
│  │  • 每 5 秒 PING 检查 Redis 健康状态                        │  │
│  │  • 连续失败 3 次判定为不可用                               │  │
│  │  • 自动触发降级                                           │  │
│  └──────────────────────────────────────────────────────────┘  │
│         │                                                      │
│         │ Redis 不可用                                        │
│         ▼                                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │             L2: DB 直写 + 本地缓存                         │  │
│  │  • 库存查询: 改查 MySQL (带 Caffeine 本地缓存)            │  │
│  │  • 一人一单: 改查 MySQL (带 Caffeine 本地缓存)            │  │
│  │  • ID 生成: 改用本地 Snowflake 算法                       │  │
│  │  • 乐观锁扣库存 + 同步创建订单                             │  │
│  │  • QPS 限制: 200 → 100                                    │  │
│  └──────────────────────────────────────────────────────────┘  │
│         │                                                      │
│         │ DB 压力过大 / 异常比例 > 50%                         │
│         ▼                                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │             L3: 熔断保护                                   │  │
│  │  • Sentinel 自动熔断                                      │  │
│  │  • 返回友好提示: "当前抢购人数过多，请稍后重试"            │  │
│  └──────────────────────────────────────────────────────────┘  │
│         │                                                      │
│         │ 实时监控                                             │
│         ▼                                                      │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │             L4: 监控告警 + 流量恢复                        │  │
│  │  • 实时告警通知 (钉钉/企业微信/邮件)                       │  │
│  │  • Redis 恢复后逐步放开流量                               │  │
│  │    10% (1分钟) → 50% (2分钟) → 100% (1分钟) → 正常       │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                   │
└─────────────────────────────────────────────────────────────────┘
```

## 核心组件

### 1. Redis Sentinel 哨兵模式 (L0 高可用)

**配置文件**: `src/main/resources/sentinel.conf`

```bash
# 哨兵监控的主节点
sentinel monitor mymaster 127.0.0.1 6379 2

# 主节点响应超时时间（毫秒）
sentinel down-after-milliseconds mymaster 30000

# 故障转移超时时间（毫秒）
sentinel failover-timeout mymaster 180000
```

**优势**:
- Master 挂了 30 秒内自动故障转移
- 大部分情况业务感知不到
- 自动主从切换，无需人工干预

**启用方式**:
修改 `application.yaml`:
```yaml
spring:
  redis:
    sentinel:
      enabled: true
      master: mymaster
      nodes: 127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381
```

### 2. Redis 健康检查服务 (L1)

**类**: `com.huxirating.degradation.RedisHealthService`

**功能**:
- 每 5 秒执行 PING 检查
- 连续失败 3 次触发降级
- Redis 恢复后延迟 30 秒再切换回正常模式

**关键配置**:
```yaml
degradation:
  health-check:
    enabled: true
    interval: 5000              # 健康检查间隔（毫秒）
    failure-threshold: 3        # 连续失败阈值
    recovery-delay: 30000       # 恢复延迟（毫秒）
```

**监控 API**:
```bash
GET /admin/degradation/health  # 查看健康状态
POST /admin/degradation/health/check  # 手动触发检查
```

### 3. 降级策略服务 (L2)

**类**: `com.huxirating.degradation.DegradationService`

**降级策略**:

| 功能 | 正常模式 | 降级模式 |
|------|---------|---------|
| 库存查询 | Redis | MySQL + Caffeine 本地缓存 |
| 一人一单 | Redis Set | MySQL + Caffeine 本地缓存 |
| ID 生成 | RedisIdWorker | SnowflakeIdWorker |
| 订单创建 | 异步 MQ | 同步 DB 直写 |
| QPS 限制 | 200 | 100 |

**本地缓存配置**:
```yaml
degradation:
  strategy:
    cache-max-size: 10000       # 本地缓存最大容量
    cache-expire-minutes: 5     # 本地缓存过期时间（分钟）
```

### 4. Sentinel 熔断保护 (L3)

**类**: `com.huxirating.config.SentinelConfig`

**熔断规则**:

1. **异常比例熔断**: 异常比例 > 50% 时降级 10 秒
2. **异常数熔断**: 异常数超过 50 时降级 10 秒

**限流规则**:
- 正常模式: QPS 200
- 降级模式: QPS 100 (自动调整)

### 5. 监控告警和流量恢复 (L4)

**类**: `com.huxirating.degradation.MonitoringAndRecoveryService`

**流量恢复阶段**:

```
降级 → 10% (1分钟) → 50% (2分钟) → 100% (1分钟) → 正常
```

**告警配置**:
```yaml
degradation:
  monitoring:
    alerts:
      dingtalk:
        enabled: false
        webhook: https://oapi.dingtalk.com/robot/send?access_token=YOUR_TOKEN
      wechat:
        enabled: false
        webhook: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=YOUR_KEY
```

**监控 API**:
```bash
GET /admin/degradation/monitoring  # 查看监控状态
```

## 使用指南

### 正常使用

系统完全自动，无需人工干预：

1. Redis 正常 → 使用标准异步秒杀流程
2. Redis 故障 → 自动切换到降级模式
3. Redis 恢复 → 自动逐步恢复流量

### 手动干预

通过管理接口进行手动控制：

```bash
# 触发降级（测试用）
POST /admin/degradation/trigger

# 触发恢复（测试用）
POST /admin/degradation/recover

# 查看降级状态
GET /admin/degradation/status

# 清空本地缓存
POST /admin/degradation/cache/clear
```

### 生产环境部署

1. **启用 Redis Sentinel**:
   ```bash
   # 启动 Redis 主节点
   redis-server --port 6379

   # 启动 Redis 从节点
   redis-server --port 6380 --slaveof 127.0.0.1 6379

   # 启动 3 个哨兵节点
   redis-server /path/to/sentinel.conf --port 26379
   redis-server /path/to/sentinel.conf --port 26380
   redis-server /path/to/sentinel.conf --port 26381
   ```

2. **修改配置**:
   ```yaml
   spring:
     redis:
       sentinel:
         enabled: true
   ```

3. **配置告警**:
   ```yaml
   degradation:
     monitoring:
       alerts:
         dingtalk:
           enabled: true
           webhook: YOUR_DINGTALK_WEBHOOK
   ```

## 测试方案

### 1. 模拟 Redis 故障

```bash
# 停止 Redis
redis-cli shutdown

# 或者在代码中手动触发
POST /admin/degradation/trigger
```

**预期结果**:
- 系统自动切换到降级模式
- 秒杀功能继续可用（性能降低）
- 日志显示降级信息

### 2. 模拟 Redis 恢复

```bash
# 启动 Redis
redis-server

# 或者在代码中手动触发
POST /admin/degradation/recover
```

**预期结果**:
- 系统检测到 Redis 恢复
- 流量逐步放开（10% → 50% → 100%）
- 最终恢复正常模式

### 3. 压力测试

```bash
# 正常模式
ab -n 10000 -c 200 http://localhost:8081/api/voucher/seckill/1

# 降级模式
# 先停止 Redis，然后执行相同测试
```

**对比指标**:
- QPS: 200 → 100
- RT: 10ms → 50ms (DB 直写)
- 成功率: 保持 100%

## 性能影响

| 指标 | 正常模式 | 降级模式 | 影响 |
|------|---------|---------|------|
| QPS | 200 | 100 | -50% |
| RT | 10ms | 50ms | +400% |
| 库存准确性 | 100% | 100% | 无影响 |
| 一人一单 | 100% | 100% | 无影响 |
| ID 唯一性 | 100% | 100% | 无影响 |

## 关键文件

```
src/main/java/com/huxirating/
├── config/
│   ├── RedissonConfig.java              # Redisson 哨兵配置
│   ├── SentinelConfig.java              # Sentinel 限流熔断配置
│   └── DegradationAutoConfig.java       # 降级自动配置
├── degradation/
│   ├── RedisHealthService.java          # L1: 健康检查
│   ├── DegradationService.java          # L2: 降级策略
│   ├── SnowflakeIdWorker.java           # 本地 ID 生成器
│   ├── DegradedVoucherOrderService.java # 降级订单服务
│   └── MonitoringAndRecoveryService.java # L4: 监控恢复
├── controller/
│   └── DegradationController.java       # 降级管理接口
└── service/impl/
    └── VoucherOrderServiceImpl.java     # 集成降级逻辑

src/main/resources/
├── sentinel.conf                        # 哨兵配置
└── application.yaml                     # 应用配置
```

## 常见问题

### Q1: 为什么需要本地缓存？

A: 降级模式下直接查 DB 压力过大，使用 Caffeine 本地缓存可以：
- 减少 DB 查询次数
- 提升响应速度
- 保护 DB 不被压垮

### Q2: Snowflake ID 会不会冲突？

A: 不会。Snowflake 算法保证：
- 41 位时间戳：保证时间唯一性
- 10 位机器 ID：支持 1024 台机器
- 12 位序列号：每毫秒 4096 个 ID

### Q3: 为什么流量要逐步恢复？

A: 直接放开 100% 流量可能导致：
- Redis 瞬间压力过大
- 缓存未预热，大量请求穿透到 DB
- 可能引发连锁故障

逐步恢复让系统有时间：
- 预热缓存
- 监控指标
- 及时发现问题

### Q4: 降级模式下的数据一致性如何保证？

A: 通过多重保障：
1. 数据库唯一索引（防止重复下单）
2. 乐观锁扣库存（防止超卖）
3. DB 层一人一单校验
4. 本地缓存 + 数据库双重校验

### Q5: 如何配置告警？

A: 修改 `application.yaml`:
```yaml
degradation:
  monitoring:
    alerts:
      dingtalk:
        enabled: true
        webhook: YOUR_WEBHOOK_URL
```

支持钉钉、企业微信、邮件等多种通道。

## 总结

本降级策略实现了：

✅ **高可用**: Redis Sentinel 30 秒内自动故障转移
✅ **自动降级**: Redis 故障时自动切换，无需人工干预
✅ **功能保障**: 核心秒杀功能始终可用
✅ **数据一致性**: 不会超卖、不会重复下单
✅ **平滑恢复**: 流量逐步放开，避免冲击
✅ **实时监控**: 完善的监控和告警机制

这套方案让秒杀系统在 Redis 完全不可用的情况下，依然能够提供基础的秒杀服务，最大限度保障业务连续性。
