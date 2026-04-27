# 虎溪锐评后端 (HuxiRating Backend) - 架构概览

> 本文档帮助开发者快速理解代码库架构，便于开发、重构、测试和调试。

## 1. 项目概述

虎溪锐评是一个本地生活服务平台后端系统，采用 **Spring Boot 单体架构**，核心特性包括：

- **高并发秒杀**：Redis Lua 预扣库存 + RabbitMQ 异步入库
- **多级降级**：五层降级策略保障服务可用性（Sentinel 高可用 → 健康检查 → DB 直写 → 熔断 → 流量恢复）
- **分布式锁**：Redisson 实现
- **消息可靠性**：Outbox 模式 + 死信队列

### 技术栈

| 组件 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 2.7.14 | 框架 |
| MyBatis-Plus | 3.4.3 | ORM |
| MySQL | 8.0+ (connector-j 8.3.0) | 数据库 |
| Redis | 7.0 | 缓存/分布式锁/库存 |
| RabbitMQ | 3.x | 消息队列 |
| Sentinel | 1.8.6 | 限流熔断 |
| Redisson | 3.23.5 | 分布式锁 |
| Hutool | 5.8.26 | 工具库 |

### 目录结构

```
src/main/java/com/huxirating/
├── config/          # 配置类（Redis、MQ、Sentinel、降级自动配置、异常处理）
├── controller/      # 控制器层（含 DegradationController 管理接口）
├── service/         # 业务逻辑层（接口 + impl）
├── mapper/          # MyBatis 数据访问层
├── entity/          # 数据库实体（11 个，含 MessageOutbox）
├── dto/             # 数据传输对象（Result、OrderMessage、PurchaseAttemptResponse 等）
├── utils/           # 工具类（RedisConstants、CacheClient、UserHolder、拦截器）
├── mq/              # RabbitMQ 消费者（OrderMessageConsumer、DeadLetterConsumer）
├── degradation/     # 降级服务模块（健康检查、降级策略、监控恢复、排队服务、SnowflakeIdWorker）
└── task/            # 定时任务（消息补偿 OrderCompensationTask、超时取消 OrderTimeoutTask）
```

---

## 2. 构建与命令

### 环境要求

- JDK 21+
- Maven 3.6+
- MySQL 8.0+
- Redis 7.0+（生产环境启用 Sentinel）
- RabbitMQ 3.x

### 开发命令

```bash
# 编译（跳过测试）
mvn -DskipTests compile

# 完整构建
mvn clean install

# 本地运行（端口 8081）
mvn spring-boot:run

# 运行所有测试
mvn test

# 运行单个测试方法
mvn -Dtest=HuxiRatingApplicationTests#testIdWorker test
```

### Docker 部署

```bash
# 基础环境（MySQL + Redis + RabbitMQ + App）
docker-compose up -d

# Sentinel 集群（1 主 2 从 + 3 Sentinel）
docker-compose -f docker-compose-sentinel.yml up -d
./deploy-sentinel.sh start|stop|restart|status|test
```

Dockerfile 采用多阶段构建（maven:3.9 + temurin-21），JVM 参数：`-Xms256m -Xmx512m`。

### 代码检查

项目未配置 Checkstyle/Spotless/PMD，使用 Maven compile 和 test 作为代码验证。

---

## 3. 代码风格与约定

### 统一 API 响应

所有 Controller/Service 返回 `Result` 对象，**不抛业务异常**：

```java
// 成功
return Result.ok(data);
return Result.ok(list, total);  // 分页

// 失败
return Result.fail("库存不足");
```

### Redis Key 管理

**所有 Redis key 和 TTL 必须通过 `RedisConstants` 定义**，禁止硬编码：

```java
// 正确
stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

// 错误（禁止）
stringRedisTemplate.opsForValue().get("cache:shop:" + id);
```

Key 命名规范（见 `RedisConstants.java`）：

| Key 模式 | 用途 | TTL |
|----------|------|-----|
| `login:code:{phone}` | 验证码 | 2min |
| `login:token:{uuid}` | 登录 Token | 30min |
| `cache:shop:{id}` | 商铺缓存 | 30min |
| `seckill:stock:{voucherId}` | 秒杀库存 | 永久 |
| `seckill:order:{voucherId}` | 一人一单 Set | 永久 |
| `seckill:token:{voucherId}:{userId}` | 秒杀 token→orderId 映射 | 30min |
| `order:status:{orderId}` | 异步订单状态 | 30min |

### 订单状态语义

| 存储 | 状态值 | 含义 |
|------|--------|------|
| Redis `order:status:{id}` | `"PENDING"` | 异步处理中 |
| DB `voucher_order.status` | `1`=未支付, `2`=已支付, `3`=已核销, `4`=已取消, `5`=退款中, `6`=已退款 |

**重要**：一人一单校验排除已取消订单（`status != 4`）。

### 秒杀原子性策略

1. **快速路径**：`seckill.lua` 原子性完成库存检查 + 一人一单 + 库存扣减 + token 映射
2. **最终一致性**：事务性 DB 写入 (`createVoucherOrderTx`)
3. **回滚**：`rollback.lua` 原子恢复库存 + 移除用户 + 清理 token；`deadletter-sync.lua` 原子化死信库存同步

### MQ 消费者可靠性模式

- **手动 ACK**：仅下游操作成功后确认
- **重试/死信**：主消费者 NACK + requeue；死信消费者是终态（不 requeue）

### 缓存策略（CacheClient）

| 策略 | 场景 |
|------|------|
| pass-through（缓存空值） | 防止缓存穿透 |
| mutex（互斥重建） | 强一致性场景 |
| logical-expire + 异步重建 | 热点 Key |

---

## 4. 测试

### 测试框架

- Spring Boot Test + JUnit 5

### 现有测试

仅 `HuxiRatingApplicationTests.java`，包含 4 个集成测试方法：
- `testIdWorker()` — Redis ID 生成器压测
- `testSaveShop()` — 逻辑过期缓存写入
- `loadShopData()` — GEO 数据批量写入
- `testHyperLogLog()` — UV 统计

**注意**：秒杀、MQ、降级等核心链路暂无自动化测试。

### 运行测试

```bash
mvn test
mvn -Dtest=HuxiRatingApplicationTests test
```

---

## 5. 安全

### 敏感信息管理

- **禁止**在代码中硬编码密码、密钥
- 生产环境使用环境变量或配置中心覆盖 `application.yaml` 中的默认值
- 当前默认密码（仅限开发环境）：MySQL `root/12345678`、Redis `huxirating123`、RabbitMQ `guest/guest`

### Redis 安全

- 设置强密码（配置：`spring.redis.password`）
- 启用 ACL 控制
- 限制外部网络访问

### RabbitMQ 安全

- 修改默认 guest 用户
- 创建专用用户并分配最小权限

### 认证流程

拦截器链顺序：

1. `RefreshTokenInterceptor` (order=0)：所有路径，读取 Redis Token，刷新 TTL，写入 `UserHolder`
2. `LoginInterceptor` (order=1)：受保护路径，校验 `UserHolder` 是否有用户

`UserHolder` 在 `afterCompletion` 中清理。

---

## 6. 配置

### 主配置文件

`src/main/resources/application.yaml`（服务端口 8081）

### 关键配置项

```yaml
# 数据库连接池（支撑降级模式 2000 QPS）
spring.datasource.hikari:
  maximum-pool-size: 100
  minimum-idle: 20
  leak-detection-threshold: 60000

# Redis Lettuce 连接池
spring.redis.lettuce.pool:
  max-active: 200
  max-idle: 64
  min-idle: 16

# Redis 哨兵模式
spring.redis.sentinel:
  enabled: true
  master: mymaster
  nodes: 127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381

# RabbitMQ 发布确认 + 消费者
spring.rabbitmq:
  publisher-confirm-type: correlated
  publisher-returns: true
  listener.simple:
    acknowledge-mode: manual
    prefetch: 10
    concurrency: 5
    max-concurrency: 10

# Sentinel
spring.cloud.sentinel:
  transport.port: 8719
  dashboard: localhost:8080

# 降级策略
degradation:
  health-check:
    interval: 5000
    failure-threshold: 3
  strategy:
    degraded-qps: 2000
    normal-qps: 15000
    hotspot-default-qps: 1000
    shop-query-qps: 1000
  monitoring:
    recovery:
      phase-10-duration: 60
      phase-50-duration: 120
    alerts:
      dingtalk/webhook: ""   # 生产环境配置
```

### 多环境配置

当前仅有单一 `application.yaml`，生产环境建议：

1. 启用 Redis Sentinel：`spring.redis.sentinel.enabled: true`
2. 配置告警 Webhook：`degradation.monitoring.alerts.dingtalk/wechat`
3. 使用环境变量覆盖敏感配置
4. 拆分 `application-dev.yaml` / `application-prod.yaml` profiles

---

## 7. 核心请求流程

### 正常秒杀流程

```
请求 → VoucherOrderServiceImpl.seckillVoucher
     → seckill.lua (Redis 原子扣库存 + 一人一单 + token 映射)
     → 写入 order:status:{orderId}=PENDING
     → 发布 OrderMessage 到 RabbitMQ
     → OrderMessageConsumer 消费（手动 ACK + Redisson 锁）
     → createVoucherOrderTx 事务写入
     → 清除 PENDING 状态
```

### MQ 故障处理

- 发送失败 → 写入 `tb_message_outbox` 表
- `OrderCompensationTask` 定时重试
- `RabbitMQConfirmConfig` 仅在 Broker 确认后标记 Outbox 状态
- 消费重试 → 重试队列 → 死信队列
- `DeadLetterConsumer` 执行 Redis 回滚（`deadletter-sync.lua`）并插入取消订单（status=4）

### 降级流程

```
Redis 故障 → RedisHealthService 检测（PING ×3 失败）
          → DegradationService 触发降级
          → DegradedVoucherOrderService 接管
          → DB 直写 + Snowflake ID + 同步订单创建 + 排队服务
          → Redis 恢复 → 流量渐进恢复（10% → 50% → 100%）
```

---

## 8. Lua 脚本索引

| 脚本 | 用途 |
|------|------|
| `seckill.lua` | 秒杀资格校验：库存检查 + 一人一单 + 库存扣减 + token→orderId 映射 |
| `rollback.lua` | 原子回滚：恢复库存 + 移除用户 + 清理 token |
| `deadletter-sync.lua` | 死信处理：原子化库存同步 + 移除用户（对比 Redis/MySQL 库存） |
| `unlock.lua` | 分布式锁释放：校验 value 一致性后 del |

---

## 9. 关键文件索引

| 文件 | 用途 |
|------|------|
| `utils/RedisConstants.java` | Redis Key/TTL 常量 |
| `dto/Result.java` | 统一响应封装 |
| `service/impl/VoucherOrderServiceImpl.java` | 秒杀主逻辑 |
| `mq/OrderMessageConsumer.java` | MQ 消费者 |
| `mq/DeadLetterConsumer.java` | 死信消费者 |
| `degradation/DegradationService.java` | 降级服务 |
| `degradation/RedisHealthService.java` | 健康检查 |
| `degradation/DegradedVoucherOrderService.java` | 降级模式秒杀 |
| `utils/CacheClient.java` | 缓存工具类 |
| `task/OrderCompensationTask.java` | Outbox 消息补偿 |
| `task/OrderTimeoutTask.java` | 订单超时取消 |
| `config/RabbitMQConfirmConfig.java` | MQ 发布确认回调 |
| `config/WebExceptionAdvice.java` | 全局异常处理 |

---

## 10. 管理接口

```bash
# 降级状态
GET  /admin/degradation/status
GET  /admin/degradation/health
POST /admin/degradation/trigger   # 手动触发降级（测试）
POST /admin/degradation/recover   # 手动触发恢复（测试）
POST /admin/degradation/cache/clear
```

---

## 11. 已知优化项（按优先级）

### P0 - 前端联调阻塞

1. **CORS + OPTIONS 放行**：`MvcConfig.java` 增加 CORS 配置，`LoginInterceptor` 放行 OPTIONS 请求
2. **错误协议标准化**：`WebExceptionAdvice` 完善 401/403/400/429/500 分类，统一业务错误码
3. **环境配置分层**：拆分 `application-dev/prod.yaml`，敏感信息走环境变量

### P0 - 秒杀链路正确性

1. **Outbox 完整化**：直发路径 correlationId 统一为 `outbox:{id}`；confirm ack=false/returns 也回写 outbox；补偿任务增加领取租约与退避
2. **Redis 回滚幂等**：引入 `rollback:order:{orderId}` SETNX 幂等键；outbox 失败回滚统一调用 Lua 原子脚本
3. **消费端可靠性**：转发 retry/DLQ 等待 confirm 后再 ACK；retryCount 安全解析；DLQ 失败转 parking-lot 队列
4. **MQ 可观测性**：producer confirm/return 埋点、consumer 消费耗时/异常分类埋点

### P1 - 前端体验与运维

1. **API 契约统一**：统一购买状态机（ACCEPTED/QUEUED/PROCESSING/ORDER_CREATED/FAILED/CANCELED）；长轮询减少前端轮询
2. **鉴权协议固化**：统一 `Authorization: Bearer <token>` header 规范
3. **指标与告警**：秒杀 Lua 返回码分布、MQ 堆积、Outbox pending 数、DB 扫描耗时
4. **DB 索引补齐**：`voucher_order` 补 `(status, create_time)` 索引，避免 `OrderTimeoutTask` 扫表

### P2 - 测试与工程化

1. **Test profile**：测试不依赖真实 Redis/MySQL，或提供可控 docker-compose
2. **Bean Validation**：字段级校验，替代散落手写校验
