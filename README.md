# 点评平台后端项目

## 项目简介

一个类似大众点评的本地生活服务平台后端系统，提供商户信息浏览、用户互动、优惠券秒杀等功能。采用 Spring Boot + Redis + MySQL + RabbitMQ + Sentinel 技术栈，实现高性能、高并发的分布式应用。

## 核心功能

### 用户管理
- 手机短信验证码登录注册
- 基于 Redis Token 的分布式 Session
- 用户签到（Redis Bitmap）

### 商户服务
- Caffeine + Redis 多级缓存（L1 微秒级 → L2 毫秒级 → DB）
- 缓存穿透（空值缓存）、击穿（互斥锁/逻辑过期）、雪崩（随机 TTL）
- 基于 Redis GEO 的附近商户搜索

### 社区互动
- 探店笔记发布与点赞
- Feed 流推送（Redis ZSet 推模式 + 滚动分页）
- 用户关注与共同关注

### 秒杀系统
- Redis + Lua 原子校验（库存 + 一人一单）
- RabbitMQ 异步下单，实现最终一致性
- 重试队列 + 死信队列（DLQ）处理消费失败
- Outbox 模式 + 定时补偿保证消息可靠投递
- 幂等设计防止重复消费
- **Sentinel 限流降级**：QPS 限流 + 热点参数限流 + 熔断降级
- Redisson 分布式锁兜底

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Spring Boot 2.7.14 |
| 语言 | Java 21 |
| ORM | MyBatis-Plus 3.4.3 |
| 数据库 | MySQL 8.x |
| 缓存 | Caffeine（L1） + Redis Lettuce（L2） |
| 分布式锁 | Redisson 3.23.5 |
| 消息队列 | RabbitMQ（Publisher Confirm + DLQ + 重试） |
| 限流降级 | Sentinel（QPS 限流 / 热点参数 / 熔断） |
| 容器化 | Docker + docker-compose |
| 工具库 | Hutool 5.8.26, Lombok |
| 构建 | Maven |

## 架构亮点

- **秒杀链路**：Sentinel 限流 → Lua 预扣库存 → MQ 异步入库 → Outbox 补偿 → DLQ 兜底回滚
- **多级缓存**：Caffeine(L1, 5min) → Redis(L2, 30min) → DB，写时双删保证一致性
- **限流降级**：Sentinel QPS 限流(200) + 热点参数限流(单券50) + 异常比例熔断
- **缓存体系**：互斥锁 / 逻辑过期解决击穿，空值缓存解决穿透
- **分布式 ID**：基于 Redis INCR 的时间戳+序列号全局唯一 ID 生成器
- **Feed 流**：ZSet Score 滚动分页，避免传统分页数据重复/遗漏

## 快速启动

### 方式一：Docker 一键部署（推荐）

```bash
docker-compose up -d
```

自动启动 MySQL + Redis + RabbitMQ + 应用，初始化数据库。

- 应用：http://localhost:8081
- RabbitMQ 管理台：http://localhost:15672（guest/guest）

### 方式二：本地启动

```bash
# 1. 创建数据库并导入初始化脚本
mysql -u root -p < src/main/resources/db/hmdp.sql

# 2. 修改 application.yaml 中的数据库、Redis、RabbitMQ 连接配置

# 3. 编译启动
mvn clean install
mvn spring-boot:run
```

## 运行环境

- JDK 21
- MySQL 8.x
- Redis 5.0+
- RabbitMQ 3.x
- Docker & docker-compose（可选）
- Maven 3.6+