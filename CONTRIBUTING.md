# Contributing

感谢你关注睿池 ReachAI。这个项目重点关注 Java 企业系统与 AI Agent 的真实落地，欢迎提交问题、场景反馈和代码改进。

## 可以贡献什么

- 使用问题、部署问题、文档错误和复现步骤。
- Java 企业系统接入 Agent 的真实业务场景。
- 扫描、AI 语义理解、Agent Studio、Tool ACL、Trace、RAG、模型网关等模块的改进。
- README、设计文档、示例和教程。

## 提交 Issue

请尽量包含以下信息：

- 问题背景和期望行为。
- 实际行为、错误日志或截图。
- 运行环境：JDK、Maven、Node.js、Docker、数据库版本。
- 涉及模块：`ai-agent-service`、`ai-skills-service`、`ai-model-service`、`ai-admin-front` 等。

## 提交 Pull Request

1. Fork 仓库并创建功能分支。
2. 保持改动聚焦，避免把无关格式化和重构混在一起。
3. 如果修改后端代码，优先补充或更新相关单元测试。
4. 如果修改前端或文档，请确保 README 中的链接、截图路径和命令仍然可用。
5. 在 PR 描述中说明改动动机、主要实现和验证方式。

## 本地验证

后端：

```bash
mvn clean install
```

前端：

```bash
cd ai-admin-front
npm install
npm run build
```
