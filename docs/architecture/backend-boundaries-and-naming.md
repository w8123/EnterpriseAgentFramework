# 后端逻辑边界与命名重塑

本文记录 ReachAI 后端在物理服务拆分后的边界口径。当前主线已经从“先在旧 agent 后端内整理逻辑边界”推进到“五个后端部署单元协同运行”，因此本文不再把 `ai-agent-service` 描述为平台主后端，也不再把迁移中的路由描述为占位壳策略。

## 当前结论

- 第一阶段保持同一个 MySQL 库，不立即拆库。
- `reachai-control-service` 是管理端和外部系统看到的公共 API / BFF 入口，继续承接 `/api/**`、`/embed/**` 和 SDK 注册兼容入口。
- `reachai-runtime-service` 承接 Agent、Workflow、GraphSpec、Trace、RunOps、调试和运行时内部 API。
- `reachai-capability-service` 承接 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产 API。
- `reachai-knowledge-service` 是 Knowledge / Retrieval 部署单元，不再称为“技能服务”。
- `reachai-model-service` 是 Model Gateway 部署单元。
- 旧 `ai-agent-service` 不再作为 Maven、IDEA、本地启动或部署单元存在；如果后续出现兼容壳，也只能是明确标注的迁移辅助，不得承载新增业务实现。

## 部署单元

| 服务 | 默认端口 | 入口 | 当前职责 |
| --- | ---: | --- | --- |
| `reachai-model-service` | 18601 | `/model/**` | 模型实例、Chat、Embedding、Rerank、OpenAI 兼容代理 |
| `reachai-knowledge-service` | 18602 | `/ai/**` | 知识库、文件、chunk、RAG、业务索引、向量检索 |
| `reachai-control-service` | 18603 | `/api/**`, `/embed/**` | Platform Control、管理端 BFF、公共 API 兼容入口 |
| `reachai-runtime-service` | 18604 | Control 转发 + `/internal/runtime/**` | Agent Runtime、Workflow Studio 运行语义、Trace、RunOps、调试 |
| `reachai-capability-service` | 18605 | Control 转发 + `/internal/capability/**` | Capability Catalog、SDK 注册、能力快照、评审和资产目录 |

前端开发代理应保持：

- `/api/**` -> `reachai-control-service:18603`
- `/ai/**` -> `reachai-knowledge-service:18602`
- `/model/**` -> `reachai-model-service:18601`

前端不应直接依赖 Runtime 或 Capability 的内部端口；需要暴露给管理端的能力由 Control 保持公共契约。

## 逻辑域所有权

| 逻辑域 | 当前 owning service | 说明 |
| --- | --- | --- |
| Platform Control | `reachai-control-service` | 统一公共入口、项目管理、治理运维、跨域聚合和 BFF |
| Runtime Host | `reachai-runtime-service` | Agent、Workflow、GraphSpec、Trace、RunOps、调试、执行链路 |
| Capability Catalog | `reachai-capability-service` | SDK 注册、能力快照、字段级 diff、review/apply、能力资产目录 |
| Knowledge / Retrieval | `reachai-knowledge-service` | 知识库、检索、向量索引、RAG 和历史扫描器实现 |
| Model Gateway | `reachai-model-service` | 模型实例、对话、Embedding、Rerank 和 OpenAI 兼容代理 |

新增代码必须落在 owning service 内。跨服务调用应走显式 HTTP client、契约对象或内部 API，不允许为了快速编译直接跨服务写对方表或复用对方内部实现类。

## 旧 agent 主入口退场

旧结构中，`ai-agent-service` 曾经同时承担 Platform Control、Runtime Host、Capability Catalog 和部分治理能力。物理拆分后，这个“大而全后端”的定位已经退场：

- Capability 相关路由应在 `reachai-capability-service` 有本地实现，再由 Control 对外保持 `/api/**` 兼容。
- Runtime 相关路由应在 `reachai-runtime-service` 有本地实现，再由 Control 对外保持 `/api/**` 兼容。
- Platform Control 自有路由应在 `reachai-control-service` 本地实现。
- Knowledge 与 Model 分别由 `reachai-knowledge-service`、`reachai-model-service` 承接，不再通过旧 agent 主入口绕行。
- 对已迁移路由，不允许用“返回 disabled 响应”替代真实实现；要么由 owning service 实现，要么明确删除并更新前端/文档契约。

## Runtime 边界

Runtime Host 的公共契约与内部实现要继续分层：

- `api` 包暴露 facade、directory、contract、port 等可被 Controller 或其他服务依赖的稳定接口。
- `internal` 包承载 Default 实现、coordinator、executor、LangGraph4j adapter、legacy 过渡实现等内部细节。
- Controller 不应直接依赖复杂内部执行器，优先依赖 Runtime facade。
- 新链路失败时应暴露明确错误，不再静默降级到历史轻量 chat/RAG 流程。

`GraphSpec` 是 Workflow 的运行语义，`canvas_json` 只是画布布局。Workflow Studio、发布校验、Agent binding 和 Runtime 执行必须围绕 `ai_workflow.graph_spec_json` 保持一致。

## Capability 边界

Capability Catalog 负责从业务系统接收 SDK 注册和能力上报，并沉淀为可评审、可发布、可治理的能力资产：

- SDK 注册入口保持兼容，但实现归属 `reachai-capability-service`。
- 能力快照、字段级 diff、review/apply、ignore、扫描目录和资产目录应由 Capability 服务 owning。
- Control 可以保留公共入口和聚合视图，但不承载 Capability 业务实现。

## Knowledge 命名

历史目录或代码里出现的 `skill` 多数是兼容命名或存储命名，不应机械全局替换。但产品和文档口径应默认使用 `Capability / 能力`，并把原 `ai-skills-service` 的职责描述为 Knowledge / Retrieval。

当前主线使用 `reachai-knowledge-service` 作为部署单元名称。`skill_draft`、`skill_eval_snapshot`、`skill_interaction`、`skill_name`、`skill_kind` 等 SQL 名称属于兼容敏感存储名，除非有明确迁移任务，不要顺手重命名。

## 兼容合同

第一阶段兼容的重点是“公共契约稳定，内部归属清楚”：

- 公共 `/api/**`、`/embed/**` 和 SDK 注册入口保持稳定。
- 前端继续打 Control、Knowledge、Model 的稳定入口。
- 同库阶段仍要遵守表所有权和服务边界。
- 文档、部署清单和 IDEA 启动说明必须使用五服务口径。
- 不再引入 `LEGACY_AGENT_SERVICE_DISABLED`、默认 `LEGACY_AGENT_SERVICE_URL` 或旧 agent catch-all 代理作为当前行为。

## 验证命令

常用验证：

```powershell
node scripts/check-backend-boundary-naming.mjs
node scripts/check-internal-api-contracts.test.mjs
node scripts/check-internal-api-contracts.mjs
node scripts/check-physical-service-smoke.test.mjs
node scripts/check-physical-service-route-contracts.test.mjs
node scripts/check-physical-service-route-contracts.mjs
node scripts/check-backend-domain-dependencies.mjs
node scripts/check-backend-domain-dependencies.test.mjs
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
git diff --check
```

服务启动后的 live smoke：

```powershell
node scripts/check-physical-service-smoke.mjs
```
