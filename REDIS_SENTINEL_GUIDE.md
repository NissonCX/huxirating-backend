# Redis 哨兵模式部署指南

## 架构说明

```
                    ┌──────────────────────────────────────┐
                    │         应用层 (Spring Boot)         │
                    │   StringRedisTemplate + Redisson    │
                    └─────────────────┬────────────────────┘
                                      │
                    ┌─────────────────┼────────────────────┐
                    │                 │                    │
            ┌───────▼───────┐ ┌──────▼───────┐ ┌──────────▼─────────┐
            │ Sentinel-1    │ │  Sentinel-2  │ │    Sentinel-3      │
            │  (26379)      │ │   (26380)    │ │     (26381)        │
            └───────┬───────┘ └──────┬───────┘ └──────────┬─────────┘
                    │                 │                    │
                    └─────────────────┼────────────────────┘
                                      │
            ┌─────────────────────────┼─────────────────────────┐
            │                         │                         │
      ┌─────▼─────┐            ┌─────▼─────┐            ┌─────▼─────┐
      │  Master   │◄───────────│  Slave-1  │            │  Slave-2  │
      │  (6379)   │            │   (6380)  │            │   (6381)  │
      │  读写     │            │   只读    │            │   只读    │
      └───────────┘            └───────────┘            └───────────┘
```

## 快速开始

### 1. 一键部署

```bash
# 启动哨兵模式
./deploy-sentinel.sh start

# 查看状态
./deploy-sentinel.sh status

# 测试故障转移
./deploy-sentinel.sh test

# 停止服务
./deploy-sentinel.sh stop
```

### 2. 启用应用哨兵模式

修改 `application.yaml`：

```yaml
spring:
  redis:
    password: huxirating123
    sentinel:
      enabled: true  # 改为 true
```

重启应用即可。

## 配置说明

### Redis 配置

| 参数 | 值 | 说明 |
|:-----|:---|:-----|
| 密码 | `huxirating123` | 所有节点统一密码 |
| 最大内存 | 256MB | 内存淘汰策略 allkeys-lru |
| 持久化 | AOF + RDB | AOF 每秒同步，RDB 自动快照 |

### 哨兵配置

| 参数 | 值 | 说明 |
|:-----|:---|:-----|
| down-after-milliseconds | 5000 | 5秒无响应判定下线 |
| parallel-syncs | 1 | 每次同步 1 个从节点 |
| failover-timeout | 30000 | 故障转移超时 30 秒 |
| quorum | 2 | 2个哨兵同意即可切换 |

## 端口映射

| 服务 | 容器内端口 | 主机端口 |
|:-----|:----------|:---------|
| Redis Master | 6379 | 6379 |
| Redis Slave 1 | 6379 | 6380 |
| Redis Slave 2 | 6379 | 6381 |
| Sentinel 1 | 26379 | 26379 |
| Sentinel 2 | 26379 | 26380 |
| Sentinel 3 | 26379 | 26381 |

## 常用命令

### 查看 Redis 状态

```bash
# 主节点
docker exec huxirating-redis-master redis-cli -a huxirating123 INFO replication

# 从节点 1
docker exec huxirating-redis-slave-1 redis-cli -a huxirating123 INFO replication

# 查看主从复制状态
docker exec huxirating-redis-master redis-cli -a huxirating123 INFO replication
```

### 查看哨兵状态

```bash
# 查看监控的主节点
docker exec huxirating-redis-sentinel-1 redis-cli -p 26379 SENTINEL masters

# 查看监控的从节点
docker exec huxirating-redis-sentinel-1 redis-cli -p 26379 SENTINEL slaves mymaster

# 查看当前主节点地址
docker exec huxirating-redis-sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster

# 查看哨兵状态
docker exec huxirating-redis-sentinel-1 redis-cli -p 26379 SENTINEL sentinel mymaster
```

### 手动触发故障转移

```bash
docker exec huxirating-redis-sentinel-1 redis-cli -p 26379 SENTINEL failover mymaster
```

### 测试数据同步

```bash
# 在主节点写入数据
docker exec huxirating-redis-master redis-cli -a huxirating123 SET test_key "hello"

# 在从节点读取数据
docker exec huxirating-redis-slave-1 redis-cli -a huxirating123 GET test_key
docker exec huxirating-redis-slave-2 redis-cli -a huxirating123 GET test_key
```

## 故障转移流程

1. 主节点故障（网络断开/进程崩溃）
2. 哨兵检测到主节点下线（5秒）
3. 多个哨兵协商确认（quorum = 2）
4. 选举一个从节点升级为新主节点
5. 其他从节点同步新主节点
6. 应用自动连接到新主节点（通过哨兵发现）

整个过程约 10-30 秒。

## 日志查看

```bash
# 查看主节点日志
docker logs -f huxirating-redis-master

# 查看从节点日志
docker logs -f huxirating-redis-slave-1

# 查看哨兵日志
docker logs -f huxirating-redis-sentinel-1

# 查看应用日志
docker logs -f huxirating-backend
```

## 数据持久化

数据存储在 Docker 卷中，重启容器不会丢失数据：

```bash
# 查看卷
docker volume ls | grep redis

# 备份数据
docker run --rm -v redis-master-data:/data -v $(pwd):/backup alpine tar czf /backup/redis-backup.tar.gz -C /data .
```

## 生产环境建议

1. **增加内存限制**：根据业务量调整 `maxmemory`
2. **定期备份**：设置定时任务备份 RDB 文件
3. **监控告警**：接入 Prometheus + Grafana 监控
4. **密码强度**：使用更强的密码
5. **网络隔离**：Redis 部署在内网，仅应用层可访问
6. **多机房部署**：将节点分布在不同可用区

## 故障排查

### 连接失败

```bash
# 检查容器状态
docker ps -a | grep redis

# 检查网络
docker network inspect huxirating-backend_redis-sentinel

# 测试连接
docker exec huxirating-redis-master redis-cli -a huxirating123 PING
```

### 主从同步异常

```bash
# 查看复制延迟
docker exec huxirating-redis-slave-1 redis-cli -a huxirating123 INFO replication | grep lag
```

### 哨兵无法选举

```bash
# 检查哨兵日志
docker logs huxirating-redis-sentinel-1 | grep -i failover

# 手动重置哨兵状态
docker exec huxirating-redis-sentinel-1 redis-cli -p 26379 SENTINEL RESET mymaster
```

## 目录结构

```
huxirating-backend/
├── docker-compose-sentinel.yml    # 哨兵模式编排文件
├── redis-sentinel/                # 哨兵配置目录
│   ├── sentinel1.conf
│   ├── sentinel2.conf
│   └── sentinel3.conf
└── deploy-sentinel.sh             # 一键部署脚本
```

## 参考链接

- [Redis Sentinel 官方文档](https://redis.io/docs/manual/sentinel/)
- [Redisson 哨兵配置](https://github.com/redisson/redisson/wiki/2.-Configuration#22-sentinel)
