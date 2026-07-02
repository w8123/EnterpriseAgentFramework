# ReachAI 文档入口

本目录是 ReachAI 的内部知识库入口。根目录 `README.md` 面向快速了解和启动；本目录用于解释产品能力、实现边界、接口归属、SQL 基线和后续 AI 编程工具的上下文。

## 当前后端拓扑

当前后端主路径是五个部署单元：

| 服务 | 默认端口 | 定位 |
| --- | ---: | --- |
| `reachai-control-service` | 18603 | Public API / BFF / Platform Control |
| `reachai-runtime-service` | 18604 | Runtime Host |
| `reachai-capability-service` | 18605 | Capability Catalog |
| `reachai-knowledge-service` | 18602 | Knowledge / Retrieval |
| `reachai-model-service` | 18601 | Model Gateway |

第一阶段保持同一个 MySQL 库，不拆库。公共 `/api/**`、`/embed/**` 和 SDK 注册入口由 `reachai-control-service` 收口；前端不直接调用 Runtime 或 Capability 内部端口。旧 `ai-agent-service` module 已从仓库主路径删除，不再作为 Maven、IDEA、本地启动或部署单元存在。

## 推荐阅读顺序

| 文档 | 适合回答的问题 |
| --- | --- |
| [01-平台定位与架构总览.md](./01-平台定位与架构总览.md) | 系统定位、核心能力、管理端页面、统一 SQL 基线 |
| [02-项目注册与能力资产.md](./02-项目注册与能力资产.md) | 业务系统接入、SDK 注册、扫描接入、Tool/Capability 资产 |
| [03-Workflow-Studio与Runtime.md](./03-Workflow-Studio与Runtime.md) | Agent/Workflow 解耦、Workflow Studio、GraphSpec、binding、调试和发布 |
| [04-运行治理与开放协议.md](./04-运行治理与开放协议.md) | Trace、RunOps、ACL、Guard、MCP、A2A、Gateway |
| [05-知识模型与企业资产.md](./05-知识模型与企业资产.md) | 模型实例、知识库、业务索引、领域和市场资产 |

## 专题入口

| 目录 | 用途 |
| --- | --- |
| [architecture/](./architecture/) | 后端物理拆分、public route、internal API、服务表所有权和旧结构退场事实源 |
| [guides/](./guides/) | 面向业务系统接入方的操作型指南和样例 |
| [reference/](./reference/) | 身份授权、嵌入式对话、AI Coding、Context Governance 等长篇专题参考 |
| [ai-memory/](./ai-memory/) | 给 Codex、Cursor、Claude Code 等 AI 编程工具看的项目记忆入口 |

## 架构契约

| 文档 | 适合回答的问题 |
| --- | --- |
| [architecture/public-route-contracts.md](./architecture/public-route-contracts.md) | 前端和外部调用应使用的公共路由、冻结兼容 alias 和 retired route |
| [architecture/physical-split-route-ownership.md](./architecture/physical-split-route-ownership.md) | public route owning service 归属 |
| [architecture/internal-api-contracts.md](./architecture/internal-api-contracts.md) | 服务间 internal API 契约、owner/consumer 和前端禁用边界 |
| [architecture/service-table-ownership.md](./architecture/service-table-ownership.md) | 同库阶段的服务表所有权 |
| [architecture/backend-boundaries-and-naming.md](./architecture/backend-boundaries-and-naming.md) | 五服务边界、同库策略、命名规则和公共入口 |
| [architecture/physical-services-and-startup.md](./architecture/physical-services-and-startup.md) | 五服务启动、IDEA 配置、环境变量和验证入口 |
| [architecture/legacy-retirement.md](./architecture/legacy-retirement.md) | 旧 agent 主入口退场、兼容面生命周期和启动清单 |

## 文档维护规则

- 当前代码、SQL、接口和启动配置永远优先于文档。
- 新文档按产品能力和当前边界组织，不新增阶段型临时清单作为主知识库。
- 一次性执行计划、审计提示词、阶段任务拆解和已完成的联调讨论不再保留在 `docs/`；需要长期保留的结论应沉淀到当前事实文档、`docs/architecture/` 或 `docs/ai-memory/`。
- 默认使用 `Capability / 能力`；只有解释历史代码、SQL 名称或兼容路径时才使用 `Skill`。
- 实现说明必须指向真实代码模块、前端页面、接口路径或 SQL 表。
- 如果旧 `ai-agent-service`、旧三服务命名或旧代理策略再次出现在当前入口文档中，应视为边界回退并修正。
