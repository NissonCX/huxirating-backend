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
