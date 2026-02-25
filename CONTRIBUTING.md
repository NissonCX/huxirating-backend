# 贡献指南

感谢你对虎溪锐评项目的关注！我们欢迎任何形式的贡献。

## 如何贡献

### 报告 Bug

如果你发现了 Bug，请：

1. 在 [Issues](https://github.com/your-username/huxirating-backend/issues) 中搜索是否已有相同问题
2. 如果没有，创建新的 Issue，包含：
   - 清晰的标题
   - 复现步骤
   - 期望行为
   - 实际行为
   - 环境信息（OS、Java 版本等）

### 提交代码

1. **Fork 项目**
   ```bash
   git clone https://github.com/your-username/huxirating-backend.git
   ```

2. **创建分支**
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **编写代码**
   - 遵循现有代码风格
   - 添加必要的注释
   - 确保代码能够通过编译

4. **提交更改**
   ```bash
   git add .
   git commit -m "feat: 添加某功能的描述"
   ```

5. **推送到 Fork**
   ```bash
   git push origin feature/your-feature-name
   ```

6. **创建 Pull Request**
   - 描述你的更改
   - 关联相关 Issue
   - 等待 Code Review

### 代码规范

- 使用 4 空格缩进
- 类名使用大驼峰命名法
- 方法名和变量名使用小驼峰命名法
- 常量使用全大写下划线分隔
- 添加适当的 JavaDoc 注释

### Commit 消息规范

使用语义化提交消息：

- `feat:` 新功能
- `fix:` 修复 Bug
- `docs:` 文档更新
- `style:` 代码格式调整
- `refactor:` 代码重构
- `test:` 测试相关
- `chore:` 构建/工具链相关

示例：
```
feat: 添加商户缓存预热功能
fix: 修复秒杀库存扣减并发问题
docs: 更新部署文档
```

## 开发指南

### 环境搭建

请参考 [README.md](README.md#快速开始) 中的环境搭建部分。

### 运行测试

```bash
mvn test
```

### 代码检查

```bash
mvn checkstyle:check
```

## 问题咨询

如有任何问题，欢迎在 Discussions 中提问。

---

再次感谢你的贡献！
