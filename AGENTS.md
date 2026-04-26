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
| Redis | 7.0 | 缓存/分布式锁/库存 |
| RabbitMQ | 3.x | 消息队列 |
| Sentinel | 1.8.6 | 限流熔断 |
| Redisson | 3.23.5 | 分布式锁 |

### 目录结构

```
src/main/java/com/huxirating/
├── config/          # 配置类（Redis、MQ、Sentinel、降级自动配置）
├── controller/      # 控制器层
├── service/         # 业务逻辑层（接口 + impl）
├── mapper/          # MyBatis 数据访问层
├── entity/          # 数据库实体
├── dto/             # 数据传输对象（Result、OrderMessage 等）
├── utils/           # 工具类（RedisConstants、CacheClient、UserHolder）
├── mq/              # RabbitMQ 消费者
├── degradation/     # 降级服务模块（健康检查、降级策略、监控恢复）
└── task/            # 定时任务（消息补偿）
```

---

## 2. 构建与命令

### 环境要求

- JDK 21+
- Maven 3.6+
- MySQL 8.0+
- Redis 7.0+（启用 Sentinel 端口）
- RabbitMQ 3.x

### 开发命令

```bash
# 编译（跳过测试）
mvn -DskipTests compile

# 完整构建
mvn clean install

# 本地运行
mvn spring-boot:run

# 运行所有测试
mvn test

# 运行单个测试类
mvn -Dtest=HuxiRatingApplicationTests test

# 运行单个测试方法
mvn -Dtest=HuxiRatingApplicationTests#testIdWorker test
```

### Docker 部署

```bash
# 启动所有服务（MySQL、Redis、RabbitMQ、App）
docker-compose up -d

# 仅启动 Sentinel 集群
docker-compose -f docker-compose-sentinel.yml up -d
./deploy-sentinel.sh
```

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
| `seckill:stock:{voucherId}` | 秒杀库存 | - |
| `seckill:order:{voucherId}` | 一人一单 Set | - |
| `order:status:{orderId}` | 异步订单状态 | 30min |

### 订单状态语义

| 存储 | 状态值 | 含义 |
|------|--------|------|
| Redis `order:status:{id}` | `"PENDING"` | 异步处理中 |
| DB `voucher_order.status` | `1`=未支付, `2`=已支付, `3`=已核销, `4`=已取消 |

**重要**：一人一单校验排除已取消订单（`status != 4`）。

### 秒杀原子性策略

1. **快速路径**：Lua 脚本 (`seckill.lua`) 原子性完成库存检查 + 一人一单 + 库存扣减
2. **最终一致性**：事务性 DB 写入 (`createVoucherOrderTx`)

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

### 运行测试

```bash
# 全部测试
mvn test

# 单个测试类
mvn -Dtest=HuxiRatingApplicationTests test
```

### 测试约定

- 测试类位于 `src/test/java/com/huxirating/`
- 使用 `@SpringBootTest` 进行集成测试

---

## 5. 安全

### 敏感信息管理

- **禁止**在代码中硬编码密码、密钥
- 生产环境使用环境变量或配置中心
- 修改默认密码（MySQL、Redis、RabbitMQ）

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

`src/main/resources/application.yaml`

### 关键配置项

```yaml
# 数据库连接池（支撑降级模式 2000 QPS）
spring.datasource.hikari:
  maximum-pool-size: 100
  minimum-idle: 20

# Redis 哨兵模式
spring.redis.sentinel:
  enabled: true
  master: mymaster
  nodes: 127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381

# RabbitMQ 发布确认
spring.rabbitmq:
  publisher-confirm-type: correlated
  publisher-returns: true
  listener.simple.acknowledge-mode: manual

# 降级策略
degradation:
  health-check:
    interval: 5000
    failure-threshold: 3
  strategy:
    degraded-qps: 2000    # 降级模式 QPS
    normal-qps: 15000     # 正常模式 QPS
  monitoring:
    recovery:
      phase-10-duration: 60   # 10% 流量持续秒数
      phase-50-duration: 120  # 50% 流量持续秒数
```

### 多环境配置

生产环境建议：

1. 启用 Redis Sentinel：`spring.redis.sentinel.enabled: true`
2. 配置告警 Webhook：`degradation.monitoring.alerts.dingtalk/wechat`
3. 使用环境变量覆盖敏感配置

---

## 7. 核心请求流程

### 正常秒杀流程

```
请求 → VoucherOrderServiceImpl.seckillVoucher
     → seckill.lua (Redis 原子扣库存 + 一人一单)
     → 写入 order:status:{orderId}=PENDING
     → 发布 OrderMessage 到 RabbitMQ
     → OrderMessageConsumer 消费（手动 ACK + Redisson 锁）
     → createVoucherOrderTx 事务写入
     → 清除 PENDING 状态
```

### MQ 故障处理

- 发送失败 → 写入 `message_outbox` 表
- `OrderCompensationTask` 定时重试
- `RabbitMQConfirmConfig` 仅在 Broker 确认后标记 Outbox 状态
- 消费重试 → 重试队列 → 死信队列
- `DeadLetterConsumer` 执行 Redis 回滚并插入取消订单（status=4）

### 降级流程

```
Redis 故障 → RedisHealthService 检测（PING ×3 失败）
          → DegradationService 触发降级
          → DegradedVoucherOrderService 接管
          → DB 直写 + Snowflake ID + 同步订单创建
          → Redis 恢复 → 流量渐进恢复（10% → 50% → 100%）
```

---

## 8. 关键文件索引

| 文件 | 用途 |
|------|------|
| `src/main/java/com/huxirating/utils/RedisConstants.java` | Redis Key/TTL 常量 |
| `src/main/java/com/huxirating/dto/Result.java` | 统一响应封装 |
| `src/main/resources/seckill.lua` | 秒杀 Lua 脚本 |
| `src/main/java/com/huxirating/service/impl/VoucherOrderServiceImpl.java` | 秒杀主逻辑 |
| `src/main/java/com/huxirating/mq/OrderMessageConsumer.java` | MQ 消费者 |
| `src/main/java/com/huxirating/mq/DeadLetterConsumer.java` | 死信消费者 |
| `src/main/java/com/huxirating/degradation/DegradationService.java` | 降级服务 |
| `src/main/java/com/huxirating/degradation/RedisHealthService.java` | 健康检查 |
| `src/main/java/com/huxirating/utils/CacheClient.java` | 缓存工具类 |

---

## 9. 管理接口

```bash
# 降级状态
GET  /admin/degradation/status
GET  /admin/degradation/health
POST /admin/degradation/trigger   # 手动触发降级（测试）
POST /admin/degradation/recover   # 手动触发恢复（测试）
POST /admin/degradation/cache/clear
```

---

## 10. 后端持续优化方案（面向“可构建前端”的工程化版本）

目标：在现有秒杀架构（Redis Lua 预扣 + MQ 异步落库 + Outbox + 死信回滚 + 多级降级）基础上，把项目推进到“前端可稳定对接、可测试、可观测、可运维、可扩展”的状态。

---

### 0. 现状快照（已具备能力）

- 秒杀链路：`seckill.lua` 原子预扣；RabbitMQ 异步落库；死信回滚；Outbox 补偿；超时取消。
- 降级体系：Redis 健康检查（L1）→ DB 直写（L2）→ Sentinel（L3）→ 放量恢复（L4）。
- 业务闭环：支付/取消/核销/退款（基础版本）；订单状态机（1~6）。

---

### 1. P0（前端联调/生产就绪阻塞项）

#### 1.1 CORS + OPTIONS 放行（否则前端直接请求失败）
- 说明：CORS 是浏览器的“跨域访问限制”（例如前端 `http://localhost:5173` 调用后端 `http://localhost:8081` 会触发）。
- 目标：支持浏览器跨域访问（个人学习项目默认放开本地端口即可），且 OPTIONS 预检不会被 401/拦截。
- 预计改动：
  - `src/main/java/com/huxirating/config/MvcConfig.java`（增加 CORS / 放行 OPTIONS）
  - `src/main/java/com/huxirating/utils/LoginInterceptor.java`（确保 OPTIONS 直接放行）

#### 1.2 错误协议标准化（前端必须可稳定处理）
- 目标：通过“统一异常处理类”把所有异常/业务错误统一成同一套返回结构（Result），并提供稳定业务错误码（toast/重试/埋点）。
- 策略：保持 `Result` 为统一返回体；在异常处理器里补齐：
  - 401 未登录 / 403 无权限 / 400 参数错误 / 429 限流 / 500 系统错误
  - errorCode（若不改 `Result` 字段，则统一放在 `data.error.code`）
- 预计改动：
  - `src/main/java/com/huxirating/config/WebExceptionAdvice.java`（完善异常分类处理）
  - `src/main/java/com/huxirating/dto/Result.java`（可选：新增 errorCode 字段；或保持不改，在 data.error 内标准化）

#### 1.3 环境与配置分层（dev/test/prod profiles）
- 目标：避免硬编码地址/账号密码；分环境启动；支持前端本地联调。
- 预计改动：
  - `src/main/resources/application.yaml`（拆分 profile：dev/test/prod，敏感信息走 env）

---

### 2. P0（秒杀链路正确性：避免“吞库存/永久卡一人一单”）

#### 2.1 MQ 投递可靠性闭环（Outbox 完整化）
- 现状核对（代码事实）：
  - 业务直发使用 correlationId=`order:{orderId}`，confirm 回写 outbox 只识别 `outbox:{id}`，导致“直发路径” ack=false/returns 只有日志无补偿（`src/main/java/com/huxirating/service/impl/VoucherOrderServiceImpl.java`、`src/main/java/com/huxirating/config/RabbitMQConfirmConfig.java`）。
  - outbox 当前只在 `convertAndSend` 同步抛异常时插入（`src/main/java/com/huxirating/service/impl/VoucherOrderServiceImpl.java`）。
  - confirm ack=false / returns 目前仅 log，不入库（`src/main/java/com/huxirating/config/RabbitMQConfirmConfig.java`）。
- 风险：Redis 已预扣但 MQ 消息未达交换机/不可路由 → outbox 不会补偿 → “用户无订单 + 不能再买 + 库存被吞”。
- 目标：任何投递失败（抛异常 / ack=false / returned）都进入“可恢复状态”，要么 outbox 重发成功，要么进入终态回滚。
- 预计改动（推荐做法：发送前写 outbox 为唯一事实来源）：
  - `src/main/java/com/huxirating/service/impl/VoucherOrderServiceImpl.java`
    - 发送前先插入 `tb_message_outbox(status=0)`，并用 `outbox:{id}` 作为 correlationId 发 MQ。
    - 直发路径不再用 `order:{orderId}` 作为 correlationId，统一为 outboxId。
  - `src/main/java/com/huxirating/config/RabbitMQConfirmConfig.java`
    - confirm ack=true：回写 outbox `status=1`（已发送）。
    - confirm ack=false：回写 outbox `status=0` 并记录失败原因（需要 outbox 增字段或日志表）。
    - returns：回写 outbox 为“路由失败终态”（建议 `status=2` 或单独 status），并输出告警。
  - `src/main/java/com/huxirating/task/OrderCompensationTask.java`
    - 增加 outbox 领取/租约（claim）机制，避免多实例重复发送（例如 status=PROCESSING 或基于 UPDATE claim）。
    - 增加退避重试（nextRetryAt）与速率限制，避免故障风暴。
  - `src/main/resources/db/huxirating.sql`
    - 可选：outbox 增加字段（`next_retry_at`,`last_error`,`processing_owner`,`processing_until`）与索引。

#### 2.2 Redis 回滚幂等化（避免重复回滚导致库存虚高）
- 现状核对（代码事实）：
  - DLQ 回滚使用 `deadletter-sync.lua`（`src/main/resources/deadletter-sync.lua`），但 outbox 最终失败回滚仍是多条 Redis 命令（`src/main/java/com/huxirating/task/OrderCompensationTask.java`），存在“部分失败/进程崩溃后重复回滚”窗口。
- 目标：回滚必须具备强幂等（同一 orderId 最多回滚一次），且原子执行。
- 预计改动：
  - `src/main/resources/rollback.lua` / `src/main/resources/deadletter-sync.lua`
    - 引入 `rollback:order:{orderId}` 的 `SETNX` 幂等键（或把幂等锚点落库后再回滚）。
  - `src/main/java/com/huxirating/task/OrderCompensationTask.java`
    - outbox 最终失败统一改为调用 Lua 原子回滚（而不是 `incr + srem + del` 分步）。
  - `src/main/java/com/huxirating/mq/DeadLetterConsumer.java`
    - 回滚前/后增加幂等锚点校验，避免“回滚成功但取消订单未落库”导致重复 +1。

#### 2.3 消费端重试/死信的可靠性与安全边界（避免“ACK 早于落地”导致消息黑洞）
- 现状核对（代码事实）：
  - 主消费者失败后会把消息转发到重试队列/死信队列，然后 ACK 原消息（`src/main/java/com/huxirating/mq/OrderMessageConsumer.java`）。
  - 转发动作未等待 broker confirm；若转发实际丢失，原消息已 ACK，形成“消息黑洞”。
  - retryCount header 读取存在类型强转风险（`src/main/java/com/huxirating/mq/OrderMessageConsumer.java`）。
  - DLQ 消费失败时 `NACK requeue=false` 直接丢弃，无停车场队列（`src/main/java/com/huxirating/mq/DeadLetterConsumer.java`）。
- 目标：
  - 转发到 retry/DLQ 必须可证明已落地（或用更标准的重试机制），避免 ACK 过早。
  - DLQ 失败必须可保留与可人工处理。
- 预计改动：
  - `src/main/java/com/huxirating/mq/OrderMessageConsumer.java`
    - retryCount 读取改为 Number 安全解析；读取纳入 try/catch。
    - 转发 retry/DLQ 增加 correlationId 并等待 confirm 成功后再 ACK 原消息；若 confirm 失败则 NACK requeue=true。
    - 为失败消息补充错误信息 header（`x-error-type/x-error-message/x-first-fail-ts`）。
  - `src/main/java/com/huxirating/config/RabbitMQConfirmConfig.java`
    - 覆盖“消费者转发消息”的 confirm/returns 处理（可复用同一模板）。
  - `src/main/java/com/huxirating/config/RabbitMQConfig.java`
    - 可选：新增 parking-lot 队列（`seckill.order.error.queue`）用于 DLQ 消费失败时转存。

#### 2.4 可观测性（MQ 层指标/告警，先打点再优化）
- 目标：让 MQ 故障可见、可定位、可告警（否则问题只靠日志排查）。
- 建议指标：
  - producer：confirm ack/nack、returns、outbox pending 数、重试次数分布、最终失败数
  - consumer：消费耗时、重试进入率、DLQ 进入率、parking-lot 数量
- 预计改动：
  - `src/main/java/com/huxirating/config/RabbitMQConfirmConfig.java`（埋点 confirm/return）
  - `src/main/java/com/huxirating/mq/OrderMessageConsumer.java`（埋点消费耗时与异常分类）
  - `src/main/java/com/huxirating/mq/DeadLetterConsumer.java`（埋点回滚成功/失败）

---

### 3. P1（前端体验与接口契约）

#### 3.1 API 契约统一（响应结构、分页、状态机）
- 目标：前端只需遵循一套契约即可覆盖正常/排队/降级。
- 建议：
  - 统一状态机（ACCEPTED/QUEUED/PROCESSING/ORDER_CREATED/FAILED/CANCELED）
  - 统一购买进度查询与长轮询（减少前端短轮询压力）
- 预计改动：
  - `src/main/java/com/huxirating/dto/PurchaseAttemptResponse.java`
  - `src/main/java/com/huxirating/controller/VoucherOrderController.java`
  - `src/main/java/com/huxirating/service/impl/VoucherOrderServiceImpl.java`

#### 3.2 鉴权协议固化（前端 SDK 友好）
- 目标：明确 header 规范为 `Authorization: Bearer <token>`，前端 SDK 可复用通用中间件；401/403 语义清晰。
- 预计改动：
  - `src/main/java/com/huxirating/utils/RefreshTokenInterceptor.java`
  - `src/main/java/com/huxirating/utils/LoginInterceptor.java`

---

### 4. P1（运维与可观测性）

#### 4.1 指标与告警（建议先打点再优化）
- 关键指标：
  - 秒杀：Lua 返回码分布、PENDING 时长分布、成功/失败比
  - MQ：主队列/重试/死信堆积、消费耗时
  - Outbox：pending 数、retry 分布、失败数
  - DB：超时取消扫描耗时/命中数

#### 4.2 DB 索引补齐
- 目标：避免 `OrderTimeoutTask` 扫表；建议补 `(status, create_time)`。
- 预计改动：`src/main/resources/db/indexes.sql`

---

### 5. P2（测试与工程化）

#### 5.1 引入 test profile（可在 CI/本地稳定跑）
- 目标：测试不依赖真实 Redis/MySQL，或提供可控的 docker-compose（可选）。

#### 5.2 参数校验体系（Bean Validation）
- 目标：对前端错误提示友好（字段级错误）；避免散落手写校验。

---

### 6. 执行顺序（建议）

1) CORS/OPTIONS 放行 + 错误协议标准化（立刻解除前端联调阻塞）
2) Outbox 完整化（confirm/returns 也可靠）+ Redis 回滚幂等
3) API 契约统一（购买进度、长轮询、分页、错误码）
4) profiles 分环境 + 运维指标 + 索引
5) 测试与校验体系

---

### 7. 验证（端到端）

- 前端联调：浏览器跨域请求（含 OPTIONS）全链路通过，401/403/429/500 可稳定识别。
- 秒杀：消息丢失场景不吞库存（confirm ack=false/returns/网络抖动），用户可追踪/可取消。
- MQ：重试/死信/Outbox 三条路径最终一致：要么订单创建，要么回滚且可再次购买。
- 任务：`OrderTimeoutTask` 在大数据量下不扫表（索引生效）。
