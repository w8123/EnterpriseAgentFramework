# Contributing

感谢关注 ReachAI。这个项目重点关注 Java 企业系统中 AI 能力中台的真实落地，欢迎提交问题、场景反馈和代码改进。

## 可以贡献什么

- 使用问题、部署问题、文档错误和复现步骤。
- Java 企业系统接入 ReachAI 的真实业务场景。
- SDK 注册、Capability Catalog、Workflow Studio、Runtime Host、RunOps、Trace、RAG、Model Gateway、MCP/A2A 等模块的改进。
- README、设计文档、示例和教程。

## 提交 Issue

请尽量包含：

- 问题背景和期望行为。
- 实际行为、错误日志或截图。
- 运行环境：JDK、Maven、Node.js、Docker、数据库版本。
- 涉及模块：`reachai-control-service`、`reachai-runtime-service`、`reachai-capability-service`、`reachai-knowledge-service`、`reachai-model-service`、`ai-admin-front` 等。

## 提交 Pull Request

1. Fork 仓库并创建功能分支。
2. 保持改动聚焦，避免把无关格式化和重构混在一起。
3. 后端改动优先补充或更新相关测试，并至少跑目标模块编译。
4. 前端改动优先在 `ai-admin-front` 下跑 `npm run build`，类型风险高时先跑 `npx vue-tsc --noEmit`。
5. 文档或规则改动至少跑 `git diff --check`。
6. PR 描述中说明改动动机、主要实现和验证方式。

## 当前后端拓扑

当前后端主路径是五个部署单元：

- `reachai-control-service`: public API / BFF / Platform Control。
- `reachai-runtime-service`: Runtime Host。
- `reachai-capability-service`: Capability Catalog。
- `reachai-knowledge-service`: Knowledge / Retrieval。
- `reachai-model-service`: Model Gateway。

第一阶段保持同一个 MySQL 库，不拆库。旧 `ai-agent-service` module 已从仓库主路径删除，不再作为 Maven、IDEA、本地启动或部署单元存在。

## 本地验证

后端五服务编译：

```powershell
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
```

边界与路由 guard：

```powershell
node scripts/check-backend-boundary-naming.mjs
node scripts/check-physical-service-route-contracts.test.mjs
node scripts/check-physical-service-route-contracts.mjs
node scripts/check-backend-domain-dependencies.test.mjs
node scripts/check-backend-domain-dependencies.mjs
node scripts/check-physical-service-smoke.test.mjs
```

五个服务都启动后：

```powershell
node scripts/check-physical-service-smoke.mjs
```

前端：

```powershell
cd ai-admin-front
npm install
npm run build
```
