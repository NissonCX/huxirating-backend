# 虎溪锐评 (HuxiRating)

<div align="center">

**一个企业级本地生活服务平台后端系统**

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.14-green)
![MySQL](https://img.shields.io/badge/MySQL-8.x-blue)
![Redis](https://img.shields.io/badge/Redis-7-red)
![License](https://img.shields.io/badge/License-MIT-yellow)

</div>

---

## 目录

- [项目简介](#项目简介)
- [核心功能](#核心功能)
- [技术架构](#技术架构)
- [快速开始](#快速开始)
- [API 文档](#api-文档)
- [架构亮点](#架构亮点)
- [性能指标](#性能指标)
- [部署说明](#部署说明)
- [项目结构](#项目结构)

---

## 项目简介

虎溪锐评是一个功能完整的本地生活服务平台后端系统，提供商户信息管理、用户社区互动、优惠券秒杀等核心功能。项目采用微服务架构设计理念，集成了高并发秒杀、异步消息处理、多级缓存、服务降级熔断等企业级技术方案。

### 主要特性

- :rocket: **高并发秒杀** - Redis 预扣库存 + RabbitMQ 异步入库
- :shield: **多级降级** - 五层降级策略保障服务可用性
- :zap: **多级缓存** - Caffeine + Redis 实现微秒级响应
- :fire: **限流熔断** - Sentinel 实现 QPS 限流和异常熔断
- :lock: **分布式锁** - Redisson 实现分布式锁互斥
- :bell: **消息可靠** - Outbox 模式 + 死信队列保证消息一致性

---

## 核心功能

### 用户管理

| 功能 | 描述 |
|------|------|
| 手机验证码登录 | 短信验证码快捷登录 |
| 分布式 Session | 基于 Redis Token 的会话管理 |
| 用户签到 | Redis Bitmap 实现签到统计 |
| 用户信息 | 用户基本信息与详细信息的分离存储 |

### 商户服务

| 功能 | 描述 |
|------|------|
| 商户查询 | 多级缓存优化，支持按类型、名称查询 |
| 附近商户 | Redis GEO 实现地理位置搜索 |
| 缓存策略 | 穿透/击穿/雪崩全方位防护 |

### 社区互动

| 功能 | 描述 |
|------|------|
| 探店笔记 | 博客发布与展示 |
| Feed 流推送 | Redis ZSet 实现滚动分页 |
| 点赞收藏 | Set 数据结构实现 |
| 用户关注 | 共同关注推荐 |

### 秒杀系统

| 功能 | 描述 |
|------|------|
| Lua 预扣库存 | 原子性校验库存 + 一人一单 |
| 异步下单 | RabbitMQ 解耦高并发请求 |
| 最终一致性 | Outbox + 定时补偿保证 |
| 幂等设计 | 防止重复消费 |

---

## 技术架构

### 技术栈

```
┌──────────────────────────────────────────────────────────────┐
│                        应用层                                 │
├──────────────────────────────────────────────────────────────┤
│  Spring Boot 2.7.14  │  Java 21  │  MyBatis-Plus 3.4.3      │
├──────────────────────────────────────────────────────────────┤
│                        中间件层                               │
├──────────────────────────────────────────────────────────────┤
│  Redis 7  │  RabbitMQ 3.x  │  Sentinel  │  Redisson 3.23.5   │
├──────────────────────────────────────────────────────────────┤
│                        数据层                                 │
├──────────────────────────────────────────────────────────────┤
│  MySQL 8.3  │  Caffeine (L1 Cache)  │  Redis (L2 Cache)     │
└──────────────────────────────────────────────────────────────┘
```

### 架构图

```
                    ┌─────────────────┐
                    │   客户端请求     │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │  Sentinel 限流  │
                    └────────┬────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
     ┌────────▼────────┐           ┌────────▼────────┐
     │  多级缓存查询    │           │  秒杀请求处理    │
     │  Caffeine→Redis │           │  Lua 预扣库存   │
     └────────┬────────┘           └────────┬────────┘
              │                             │
              │                     ┌───────▼────────┐
              │                     │  RabbitMQ MQ   │
              │                     │  异步入库       │
              │                     └───────┬────────┘
              │                             │
     ┌────────▼────────┐           ┌────────▼────────┐
     │   MySQL 数据库   │           │ Outbox 补偿机制 │
     └─────────────────┘           └─────────────────┘
```

---

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.6+
- MySQL 8.0+
- Redis 5.0+
- RabbitMQ 3.x
- Docker & Docker Compose (可选)

### 方式一：Docker 一键部署

```bash
# 克隆项目
git clone https://github.com/your-username/huxirating-backend.git
cd huxirating-backend

# 一键启动所有服务
docker-compose up -d

# 查看日志
docker-compose logs -f app
```

服务访问地址：
- 应用服务：http://localhost:8081
- RabbitMQ 管理台：http://localhost:15672 (guest/guest)

### 方式二：本地开发

```bash
# 1. 创建数据库并导入初始化脚本
mysql -u root -p < src/main/resources/db/huxirating.sql

# 2. 修改 application.yaml 配置
#    - 数据库连接信息
#    - Redis 连接信息
#    - RabbitMQ 连接信息

# 3. 编译项目
mvn clean install

# 4. 启动应用
mvn spring-boot:run
```

### 健康检查

```bash
# 检查应用状态
curl http://localhost:8081/admin/degradation/status

# 检查 Redis 健康状态
curl http://localhost:8081/admin/degradation/health
```

---

## API 文档

### 用户模块

| 接口 | 方法 | 描述 |
|------|------|------|
| `/user/code` | POST | 发送验证码 |
| `/user/login` | POST | 用户登录 |
| `/user/me` | GET | 获取当前用户信息 |
| `/user/sign` | POST | 用户签到 |
| `/user/sign/count` | GET | 签到统计 |

### 商户模块

| 接口 | 方法 | 描述 |
|------|------|------|
| `/shop/{id}` | GET | 查询商户详情 |
| `/shop/of/type` | GET | 按类型查询商户 |
| `/shop/of/name` | GET | 按名称查询商户 |

### 秒杀模块

| 接口 | 方法 | 描述 |
|------|------|------|
| `/voucher-order/seckill/{id}` | POST | 秒杀下单 |
| `/voucher-order/{orderId}` | GET | 查询订单状态 |

### 降级管理

| 接口 | 方法 | 描述 |
|------|------|------|
| `/admin/degradation/status` | GET | 获取降级状态 |
| `/admin/degradation/trigger` | POST | 手动触发降级 |
| `/admin/degradation/recover` | POST | 手动恢复服务 |

---

## 架构亮点

### 1. 秒杀系统设计

```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  用户请求    │ -> │  Lua 脚本   │ -> │  预扣库存    │
└─────────────┘    └─────────────┘    └─────────────┘
                                              │
                                              v
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  死信重试    │ <- │  消费失败    │ <- │  RabbitMQ   │
└─────────────┘    └─────────────┘    └─────────────┘
                                              │
                                              v
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│  订单完成    │ <- │  异步入库    │ <- │  消费成功    │
└─────────────┘    └─────────────┘    └─────────────┘
```

**核心特性**：
- Lua 脚本保证原子性操作
- MQ 异步处理削峰填谷
- 死信队列保证消息不丢失
- Outbox 模式实现最终一致性

### 2. 多级缓存架构

```
┌──────────────────────────────────────────────┐
│              缓存查询流程                     │
├──────────────────────────────────────────────┤
│  1. Caffeine (L1) - 微秒级响应               │
│     ├─ 命中 -> 返回                          │
│     └─ 未命中 -> Redis (L2)                 │
│  2. Redis (L2) - 毫秒级响应                  │
│     ├─ 命中 -> 回写 L1 -> 返回               │
│     └─ 未命中 -> Database                   │
│  3. Database - 持久化存储                    │
│     └─ 查询 -> 回写 L2/L1 -> 返回            │
└──────────────────────────────────────────────┘
```

**缓存策略**：
- 缓存穿透：空值缓存 + 布隆过滤
- 缓存击穿：互斥锁 + 逻辑过期
- 缓存雪崩：随机 TTL + 多级缓存

### 3. 多级降级策略

| 级别 | 策略 | 触发条件 |
|------|------|----------|
| L0 | Redis Sentinel | 主从故障自动转移 |
| L1 | 健康检查 | 5s PING，3 次失败触发 |
| L2 | DB 直写 + 本地缓存 | Redis 不可用时 |
| L3 | Sentinel 熔断 | 异常比例 > 50% |
| L4 | 监控恢复 | 流量渐进式恢复 |

---

## 性能指标

### QPS 承载能力

| 接口类型 | 正常模式 | 降级模式 |
|----------|----------|----------|
| 秒杀下单 | 200 QPS | 100 QPS |
| 商户查询 | 1000 QPS | 500 QPS |
| 博客列表 | 500 QPS | 200 QPS |

### 缓存命中率

- 商户详情：> 90%
- 用户信息：> 95%
- 热点数据：> 85%

### 响应时间

- L1 缓存命中：< 1ms
- L2 缓存命中：< 10ms
- 数据库查询：< 100ms

---

## 部署说明

### Docker 部署

```bash
# 构建镜像
docker build -t huxirating-backend:latest .

# 运行容器
docker run -d \
  --name huxirating-backend \
  -p 8081:8081 \
  -e SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/huxirating \
  -e SPRING_REDIS_HOST=redis \
  huxirating-backend:latest
```

### Kubernetes 部署

```bash
# 创建部署
kubectl apply -f k8s/deployment.yaml

# 创建服务
kubectl apply -f k8s/service.yaml

# 查看状态
kubectl get pods -l app=huxirating-backend
```

---

## 项目结构

```
huxirating-backend/
├── src/
│   ├── main/
│   │   ├── java/com/huxirating/
│   │   │   ├── config/              # 配置类
│   │   │   │   ├── RedissonConfig.java
│   │   │   │   ├── SentinelConfig.java
│   │   │   │   ├── RabbitMQConfig.java
│   │   │   │   └── CaffeineConfig.java
│   │   │   ├── controller/          # 控制器
│   │   │   ├── service/            # 服务层
│   │   │   │   ├── impl/           # 服务实现
│   │   │   ├── mapper/             # 数据访问层
│   │   │   ├── entity/             # 实体类
│   │   │   ├── dto/                # 数据传输对象
│   │   │   ├── utils/              # 工具类
│   │   │   ├── mq/                 # 消息队列
│   │   │   ├── degradation/        # 降级服务
│   │   │   └── HuxiRatingApplication.java
│   │   └── resources/
│   │       ├── application.yaml    # 配置文件
│   │       ├── db/                 # 数据库脚本
│   │       └── mapper/             # MyBatis 映射
│   └── test/
├── docker-compose.yml              # Docker 编排
├── Dockerfile                      # 镜像构建
├── pom.xml                         # Maven 配置
└── README.md                       # 项目文档
```

---

## 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 提交 Pull Request

---

## 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件

---

## 联系方式

- 作者：Nisson
- 项目链接：[https://github.com/your-username/huxirating-backend](https://github.com/your-username/huxirating-backend)

---

<div align="center">

**如果这个项目对你有帮助，请给一个 Star ⭐**

</div>
