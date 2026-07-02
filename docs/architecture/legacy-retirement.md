# 旧结构退场清单

本文记录物理服务拆分后，旧 agent 主入口、旧代理、旧命名和旧部署结构的退场状态。当前目标不是保留一个“禁用响应”的壳，而是让每条仍然有效的公共路由都落到 owning service 的真实实现上；不再需要的路由应删除并同步前端与文档。

## 当前拓扑

| 部署单元 | 默认端口 | 当前定位 |
| --- | ---: | --- |
| `reachai-model-service` | 18601 | Model Gateway |
| `reachai-knowledge-service` | 18602 | Knowledge / Retrieval |
| `reachai-control-service` | 18603 | Public API / BFF / Platform Control |
| `reachai-runtime-service` | 18604 | Runtime Host |
| `reachai-capability-service` | 18605 | Capability Catalog |

第一阶段保持同一个 MySQL 库，不立即拆库。公共入口由 Control 保持 `/api/**`、`/embed/**` 和 SDK 注册兼容；前端不直接依赖 Runtime、Capability 的内部端口。

## 前端入口

| 前端路径 | 后端目标 | 说明 |
| --- | --- | --- |
| `/api/**` | `reachai-control-service:18603` | 管理端公共 API、BFF、跨域聚合 |
| `/ai/**` | `reachai-knowledge-service:18602` | 知识库、检索、RAG |
| `/model/**` | `reachai-model-service:18601` | 模型管理、chat、embedding、rerank |

前端不应出现 `ai-agent-service`、`VITE_AI_AGENT_SERVICE_URL` 或“路由不再代理到旧 agent”的提示文案。若页面需要 Runtime 或 Capability 数据，应经 Control 暴露稳定 `/api/**` 契约。

## 兼容面生命周期

| 兼容面 | 状态 | 要求 |
| --- | --- | --- |
| Control -> Runtime public API | 保留 | Control 作为公共 BFF，调用 Runtime owning implementation |
| Control -> Capability public API | 保留 | Control 作为公共 BFF，调用 Capability owning implementation |
| Control platform retired route | 已清理 | 不再保留 generic fallback 或旧 agent catch-all |
| Capability retired route | 已清理 | 迁移为 Capability 本地实现或删除 |
| Runtime retired route | 已清理 | Runtime 服务内不存在需要旧 agent 兜底的公开路由 |
| Legacy agent service | 退场 | 不作为 Maven/IDEA/本地启动/部署单元存在 |

## Control 清单

Control 是当前公共入口。它可以聚合 Runtime、Capability、Knowledge、Model 的结果，但不应把旧 agent 作为缺省后端。

已确认方向：

- `/api/**`、`/embed/**` 和 SDK 注册兼容入口由 Control 对外保持稳定。
- Runtime 相关公共路由经 Control 调用 `reachai-runtime-service`。
- Capability 相关公共路由经 Control 调用 `reachai-capability-service`。
- Platform Control 自有能力在 Control 本地实现。
- 未定义路由不再进入旧 agent 通用代理。

需要持续防回流：

- 不引入 `LEGACY_AGENT_SERVICE_URL` 默认值。
- 不引入 `LEGACY_AGENT_SERVICE_DISABLED` 作为当前路由策略。
- 不恢复 `/api/**` generic fallback。
- 不让前端直接打旧 agent 端口。

## Runtime 清单

Runtime Host 承接 Agent、Workflow、GraphSpec、Trace、RunOps 和调试链路。

当前要求：

- Runtime 服务内的公开/内部路由应有 Runtime owning implementation。
- Control 可以作为公共 BFF，但 Runtime 业务实现不回落到旧 agent。
- 新链路失败应返回明确错误，避免静默降级到历史轻量 chat/RAG 流程。
- `GraphSpec` 仍是 Workflow 运行语义，发布校验和执行链路围绕 `ai_workflow.graph_spec_json`。

## Capability 清单

Capability Catalog 承接 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产目录。

当前要求：

- SDK 注册公开入口保持兼容，但实现归属 Capability。
- Control 只保持公共 API 和聚合视图，不承载 Capability 业务实现。
- Capability 已迁移路由必须是本地实现，不再通过旧 agent 代理。
- 能力资产目录、字段级 diff 和 review/apply 不应写入 Runtime 或 Knowledge 的业务边界。

## Knowledge 与 Model 清单

`reachai-knowledge-service` 是 Knowledge / Retrieval 部署单元，不再称为“技能服务”。它承接知识库、文件、chunk、RAG、业务索引、向量检索和历史扫描器实现。

`reachai-model-service` 是 Model Gateway 部署单元，承接模型实例中心、Chat、Embedding、Rerank 和 OpenAI 兼容代理。

需要持续防回流：

- 文档和 README 不再用“技能服务”描述 Knowledge 部署单元。
- 部署清单不恢复 `ai-skills-service` 或旧 model Dockerfile 命名。
- 前端 `/ai/**` 和 `/model/**` 代理保持指向当前服务。

## 部署清单

当前本地启动顺序建议：

1. `reachai-model-service`，端口 18601。
2. `reachai-knowledge-service`，端口 18602，context path `/ai`。
3. `reachai-capability-service`，端口 18605。
4. `reachai-runtime-service`，端口 18604。
5. `reachai-control-service`，端口 18603。

常用环境变量：

- `AI_MYSQL_HOST`
- `AI_MYSQL_PORT`
- `AI_MYSQL_DATABASE`
- `AI_MYSQL_USERNAME`
- `AI_MYSQL_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `MILVUS_HOST`
- `MILVUS_PORT`
- `MODEL_SERVICE_URL`
- `KNOWLEDGE_SERVICE_URL`
- `CAPABILITY_SERVICE_URL`
- `RUNTIME_SERVICE_URL`

## 观测与验证

如果响应头或日志里再次出现 `X-ReachAI-Legacy-Proxy`、`LEGACY_AGENT_SERVICE_DISABLED`、默认旧 agent URL，说明边界发生回流，需要立即阻断。

推荐验证：

```powershell
node scripts/check-backend-boundary-naming.mjs
node scripts/check-physical-service-smoke.test.mjs
node scripts/check-physical-service-route-contracts.test.mjs
node scripts/check-physical-service-route-contracts.mjs
node scripts/check-backend-domain-dependencies.mjs
node scripts/check-backend-domain-dependencies.test.mjs
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
git diff --check
```

五个服务启动后，用下面的 live smoke 验证 Control、Runtime、Capability、Knowledge、Model 的健康入口和 Control 到内部服务的聚合健康：

```powershell
node scripts/check-physical-service-smoke.mjs --wait-ms 120000 --interval-ms 3000
```
