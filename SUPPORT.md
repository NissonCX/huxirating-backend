# 获取帮助

## 文档

- 📖 [README](README.md) - 项目概述和快速开始
- 🏗️ [架构设计](#) - 架构设计文档
- 🔧 [API 文档](#) - 接口文档

## 常见问题

### 如何启动项目？

请参考 [README.md - 快速开始](README.md#快速开始) 部分。

### 项目依赖什么环境？

- JDK 21+
- Maven 3.6+
- MySQL 8.0+
- Redis 5.0+
- RabbitMQ 3.x

### 如何配置数据库？

修改 `src/main/resources/application.yaml` 中的数据库连接配置。

### 秒杀功能如何使用？

1. 创建秒杀券
2. 调用 `/voucher-order/seckill/{id}` 接口进行秒杀

## 获取帮助

如果您有其他问题：

1. 查看 [Issues](https://github.com/NissonCX/huxirating-backend/issues) 是否已有类似问题
2. 在 [Discussions](https://github.com/NissonCX/huxirating-backend/discussions) 中提问
3. 创建新的 Issue

## 商业支持

如需商业支持，请联系 [nisson@example.com](mailto:nisson@example.com)。

## 贡献

欢迎提交 Pull Request！请参阅 [贡献指南](CONTRIBUTING.md)。
