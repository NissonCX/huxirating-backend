<div align="center">

# 虎溪锐评 (HuxiRating)

**企业级本地生活服务平台后端系统**

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.14-green.svg)](https://spring.io/projects/spring-boot)
[![MySQL](https://img.shields.io/badge/MySQL-8.0-blue)](https://www.mysql.com/)
[![Redis](https://img.shields.io/badge/Redis-7.0-red)](https://redis.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

## 简介

虎溪锐评是一个功能完整的本地生活服务平台后端系统，采用**微服务架构**设计理念，集成了高并发秒杀、异步消息处理、多级缓存、服务降级熔断等企业级技术方案。

> 适用于学习高并发、分布式系统设计的参考项目

---

## 特性

| 特性 | 描述 |
| :--- | :--- |
| :rocket: **高并发秒杀** | Redis 预扣库存 + RabbitMQ 异步入库 |
| :shield: **多级降级** | 五层降级策略保障服务可用性 |
| :zap: **多级缓存** | Caffeine(L1) + Redis(L2) 实现微秒级响应 |
| :fire: **限流熔断** | Sentinel 实现 QPS 限流和异常熔断 |
| :lock: **分布式锁** | Redisson 实现分布式锁互斥 |
| :bell: **消息可靠** | Outbox 模式 + 死信队列保证一致性 |

---

## 技术栈

```
┌─────────────────────────────────────────────────────────┐
│  Spring Boot 2.7.14   │   Java 21   │   MyBatis-Plus   │
├─────────────────────────────────────────────────────────┤
│  Redis 7.0  │  RabbitMQ  │  Sentinel  │  Redisson      │
├─────────────────────────────────────────────────────────┤
│  MySQL 8.0  │  Caffeine  │  Docker  │  Maven          │
└─────────────────────────────────────────────────────────┘
```

---

## 快速开始

### 环境要求

| 组件 | 版本 |
|------|------|
| JDK | 21+ |
| Maven | 3.6+ |
| MySQL | 8.0+ |
| Redis | 5.0+ |
| RabbitMQ | 3.x |

### Docker 部署（推荐）

```bash
# 克隆项目
git clone https://github.com/NissonCX/huxirating-backend.git
cd huxirating-backend

# 一键启动
docker-compose up -d

# 查看日志
docker-compose logs -f app
```

**访问地址**
- 应用：http://localhost:8081
- RabbitMQ：http://localhost:15672 (guest/guest)

### 本地开发

```bash
# 1. 导入数据库
mysql -u root -p < src/main/resources/db/huxirating.sql

# 2. 修改 application.yaml 配置

# 3. 编译运行
mvn clean install
mvn spring-boot:run
```

---

## 核心模块

### 秒杀系统

```
请求 → Lua预扣库存 → RabbitMQ → 异步入库 → 完成
              ↓           ↓
         原子性保证    死信重试
```

### 多级缓存

```
Caffeine (L1) → Redis (L2) → MySQL
   ↓ 1ms         ↓ 10ms       ↓ 100ms
```

### 降级策略

| 级别 | 触发条件 | 处理方式 |
|:----:|:---------|:---------|
| L0 | Sentinel 故障 | 主从自动转移 |
| L1 | Redis PING 失败 ×3 | 触发降级 |
| L2 | Redis 不可用 | DB直写 + 本地缓存 |
| L3 | 异常比例 > 50% | 熔断 |
| L4 | 服务恢复 | 流量渐进恢复 |

---

## API 接口

### 用户
| 接口 | 方法 | 说明 |
|------|:----:|------|
| `/user/code` | POST | 发送验证码 |
| `/user/login` | POST | 用户登录 |
| `/user/me` | GET | 获取当前用户 |
| `/user/sign` | POST | 用户签到 |

### 商户
| 接口 | 方法 | 说明 |
|------|:----:|------|
| `/shop/{id}` | GET | 商户详情 |
| `/shop/of/type` | GET | 按类型查询 |
| `/shop/of/name` | GET | 按名称搜索 |

### 秒杀
| 接口 | 方法 | 说明 |
|------|:----:|------|
| `/voucher-order/seckill/{id}` | POST | 秒杀下单 |
| `/voucher-order/{orderId}` | GET | 订单状态 |

---

## 性能指标

| 指标 | 数值 |
|------|:----:|
| 正常模式 QPS | 50,000（接近 Redis 单机极限的 50%） |
| 降级模式 QPS | 5,000（DB 直写模式） |
| 缓存命中率 | >90% |
| L1 响应时间 | <1ms |
| L2 响应时间 | <10ms |
| Redis 内存 | 2GB（主从节点） |
| 连接池大小 | 200 |

---

## 项目结构

```
src/main/java/com/huxirating/
├── config/          # 配置类（Redis、MQ、Sentinel等）
├── controller/      # 控制器层
├── service/         # 业务逻辑层
├── mapper/          # 数据访问层
├── entity/          # 实体类
├── dto/             # 数据传输对象
├── utils/           # 工具类
├── mq/              # 消息队列消费者
└── degradation/     # 降级服务模块
```

---

## 部署

```bash
# 构建镜像
docker build -t huxirating-backend:latest .

# 运行容器
docker run -d \
  --name huxirating-backend \
  -p 8081:8081 \
  huxirating-backend:latest
```

---

## 许可证

本项目采用 [MIT](LICENSE) 许可证

---

<div align="center">

**如果这个项目对你有帮助，请给一个 Star ⭐**

[主页](https://github.com/NissonCX/huxirating-backend)

</div>
