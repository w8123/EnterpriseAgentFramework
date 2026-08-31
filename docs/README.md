# ReachAI 文档中心

这里是 ReachAI 的工程与产品知识库。根目录 [README](../README.md) 负责产品介绍和快速开始；本目录负责当前能力边界、服务契约、接入方法、运行手册和仍在推进的计划。

## 先按目的阅读

| 你要做什么 | 从这里开始 | 继续阅读 |
| --- | --- | --- |
| 快速理解产品和系统边界 | [平台定位与架构总览](./01-平台定位与架构总览.md) | [五服务边界与本地启动](./architecture/service-boundaries.md) |
| 接入一个 Java 业务系统 | [项目注册与能力资产](./02-项目注册与能力资产.md) | [SDK 接入与 Embed Chat 快速参考](./reference/SDK接入与EmbedChat快速参考.md)、[接入指南](./guides/) |
| 开发 Agent 或 Workflow | [Workflow Studio 与 Runtime](./03-Workflow-Studio与Runtime.md) | [Workflow 语义契约](./architecture/workflow-semantic-contract.md)、[Agent Supervisor Runtime](./architecture/agent-supervisor-runtime.md) |
| 排查运行、权限或开放协议 | [运行治理与开放协议](./04-运行治理与开放协议.md) | [Public Route Contracts](./architecture/public-route-contracts.md)、[Control API 认证边界矩阵](./architecture/platform-api-auth-matrix.md) |
| 使用知识、模型和企业数据 | [知识、模型与企业资产](./05-知识模型与企业资产.md) | [Docling 文档导入](./architecture/docling-document-ingestion.md)、[模型中心 V2](./architecture/model-center-v2.md) |
| 发布或做目标环境验收 | [生产运行手册](./operations/) | 对应专题的状态与验收矩阵 |
| 继续尚未完成的重构 | [计划与验收](./plans/) | 先核对计划顶部状态，不把计划当成当前事实 |
| 让 AI 编程工具接手 | [AI Memory](./ai-memory/) | 根目录 [AGENTS.md](../AGENTS.md) 优先级最高 |

## 当前部署拓扑

| 服务 | 默认端口 | 当前 owner 边界 | 模块说明 |
| --- | ---: | --- | --- |
| `reachai-model-service` | 18601 | 模型模板/实例、Chat、Embedding、Rerank | [README](../reachai-model-service/README.md) |
| `reachai-knowledge-service` | 18602，`/ai` | 知识库、文档、检索、RAG、业务索引、记忆投影 | [README](../reachai-knowledge-service/README.md) |
| `reachai-control-service` | 18603 | 公共 API/BFF、身份、Embed、项目工作台、治理与开放协议 | [README](../reachai-control-service/README.md) |
| `reachai-runtime-service` | 18604 | Agent、Workflow、GraphSpec、执行、Trace/RunOps、EvalOps | [README](../reachai-runtime-service/README.md) |
| `reachai-capability-service` | 18605 | SDK 注册、能力快照/评审、扫描目录、能力目录、运行时调用投影、API 市场 | [README](../reachai-capability-service/README.md) |

第一阶段五服务共用一个 MySQL 库，但表和代码仍按 owning service 隔离。管理端 `/api/**` 进入 Control，`/ai/**` 进入 Knowledge，`/model/**` 进入 Model；前端不直连 Runtime 或 Capability 内部端口。详细边界只在 [五服务边界与本地启动](./architecture/service-boundaries.md) 维护，不在各专题重复一套拓扑。

## 文档分区

| 目录 | 内容 | 是否代表当前事实 |
| --- | --- | --- |
| [architecture/](./architecture/) | 服务、路由、表、协议和运行语义契约 | 是；仍须以当前代码/SQL 校验 |
| [reference/](./reference/) | 身份、Embed、SDK、Context 等长篇专题参考 | 是；用于解释细节 |
| [guides/](./guides/) | 面向接入方的可执行步骤和样例 | 以文档注明的环境为准 |
| [operations/](./operations/) | 迁移、发布、演练和生产准入 | 命令默认未执行；以证据矩阵为准 |
| [plans/](./plans/) | 实施中或仍缺 E2E 的计划 | 否；计划不等于现状 |
| [ai-memory/](./ai-memory/) | 给 AI 编程工具的浓缩事实、决策和排障顺序 | 辅助入口；代码优先 |
| [系统截图/](./系统截图/) | README 和说明文档使用的产品截图 | 展示资产，不作为实现证据 |

## 当前事实源地图

| 事实 | 权威入口 |
| --- | --- |
| Maven 模块、Java/Spring 版本 | 根 `pom.xml` 与模块 `pom.xml` |
| 端口、服务 URL、功能开关 | 各服务 `src/main/resources/application.yml` |
| 前端路由和代理 | `ai-admin-front/src/router/index.ts`、`vite.config.ts` |
| 新库 Schema | `sql/initV2.sql` |
| 已有环境升级 | `sql/upgrade-*.sql` 与 [sql/README.md](../sql/README.md) |
| 公共路由生命周期 | [public-route-contracts.md](./architecture/public-route-contracts.md) |
| 服务间调用 | [internal-api-contracts.md](./architecture/internal-api-contracts.md) |
| 表所有权 | [service-table-ownership.md](./architecture/service-table-ownership.md) |
| Workflow 运行语义 | [workflow-semantic-contract.md](./architecture/workflow-semantic-contract.md) 与 Runtime 源码 |
| 实现/部署/生产完成度 | 专题顶部状态、验收矩阵、真实进程/数据库/浏览器证据 |

## 状态用语

- `CODE_VERIFIED` / `BUILD_VERIFIED`：只证明源码、测试或制品，不证明运行中的服务已加载。
- `E2E_PENDING` / `LIVE_PENDING` / `NOT RUN`：缺真实服务、数据库、外部依赖或浏览器证据，不得改写为“已完成”。
- `DEPLOYMENT_READY`：只对完成验收的具体环境成立，不自动代表生产。
- `PRODUCTION_*`：必须同时有目标环境配置、数据迁移、进程、监控、安全与业务 E2E 证据。

## 维护规则

1. 当前代码、SQL、接口、配置和测试高于文档；发现冲突时在同一变更中修正文档。
2. 高层 01–05 只讲稳定主线，易变字段、完整 API 和状态机下沉到专题契约或服务 README。
3. 一次性步骤、执行提示词和未完成验收放在 `plans/`；目标环境命令与演练放在 `operations/`。
4. 文档默认使用 `Capability / 能力`。`Skill` 只表示标准 Agent Skill 包、外部协议字段或明确的历史否定语境。
5. 新增、删除或移动文档时同步更新所在目录的 `README.md`，并运行文档检查。
6. 不在文档中保存真实 Token、密码、密钥、内部 URL、用户数据或个人机器专用路径。

验证入口：

```powershell
node scripts/check-documentation.mjs
git diff --check
```
