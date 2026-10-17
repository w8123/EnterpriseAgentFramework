# Context Governance Kernel

> 状态：SOURCE_VERIFIED / SEMANTIC_PROJECTION_DB_E2E_PENDING（2026-08-24）

## 2026-10-17 收口：项目上下文治理已下线

管理端「上下文治理」页面（`/context/governance`）及其 PROJECT_DEV 项目上下文接口已删除，原因是 Runtime 从未消费这些数据：Agent / Workflow 执行时不读取项目上下文条目，`/api/context/package` 只是管理端预览，AI Coding 的 `context-candidates` 提交接口也只回显请求、不落库。同批删除的还有嵌入页面管理里的「页面记忆工作台」（写 PAGE_CONTEXT 条目，同样无人消费）。

保留且仍在主路径上的部分：个人记忆（RUNTIME_USER）自助治理、私有候选确认、Agent 运行时召回与注入、跨域记忆擦除，以及 Runtime 用户映射（管理端入口移到「身份与权限 / 运行时用户映射」，路由 `/settings/runtime-user-mappings`）。`control_context_*` 表继续保留，因为个人记忆复用 namespace、item、candidate、audit 等表。

## 当前定位

Context Governance Kernel 是 ReachAI 的企业 Agent 上下文治理底座。它负责上下文命名空间、上下文条目、证据、候选记忆、运行时用户映射、审计、检索、组包和生命周期治理。

当前文档按物理拆分后的五服务拓扑描述，不再使用旧大后端源码路径作为实现位置。

## 五服务职责

| 服务 | Context Governance 职责 |
| --- | --- |
| `reachai-control-service` | 暴露 `/api/context/**`、个人记忆自助治理、候选审核、运行时用户映射、嵌入式 session/page action 和管理端 BFF。 |
| `reachai-runtime-service` | 在 Agent / Workflow 执行时消费 context package 和 HMAC-attested personal memory，承载 AgentScope 单次会话状态、完整事件账本、Trace 和 Runtime 相关审计。 |
| `reachai-capability-service` | 提供 Capability Catalog、项目/实例/能力元数据和 registry credential 校验。 |
| `reachai-knowledge-service` | 承接 Knowledge / Retrieval、向量检索和 RAG，并保存可重建、owner-hashed 的个人记忆搜索投影。 |
| `reachai-model-service` | 为 LLM 记忆抽取、Embedding、Rerank 和模型调用提供 Model Gateway。 |

第一阶段仍保持同一个 MySQL 库，不拆库；实现代码必须遵守 owning service 边界。

## 已落地能力

- 存储模型：`control_context_namespace`、`control_context_item`、`control_context_binding`、`control_context_evidence`、`control_context_audit_event`、`control_context_memory_candidate`、`control_context_runtime_user_mapping`、`control_context_memory_outbox`，以及 Knowledge 可重建投影和 Runtime 会话账本。
- 访问边界：tenant、project、lane、visibility、RUNTIME_USER PRIVATE。
- 管理端能力：个人记忆自助增改删导出、私有候选确认、运行时用户映射。
- 运行时能力：AgentScope 多轮会话状态、完整事件账本、HMAC 个人记忆注入、候选记忆抽取、候选质量门、HYBRID fallback、metadata 解释摘要。
- Header-first 外部工具鉴权：`X-ReachAI-AiCoding-Key`，不再生成 query key URL。

当前 Knowledge 个人记忆投影支持 `LEXICAL`、`HYBRID`、`VECTOR` 三种模式，Embedding 统一经过 Model Gateway；`HYBRID` 在单条向量尚未就绪时保留词面降级。新增语义 schema 和真实 Provider/目标库 E2E 仍为待验收，不能仅凭源码把语义模式写成已部署。当前仍不包含图谱级个人记忆，也不自动迁移旧实验性会话数据；主路径以 Runtime AgentScope state + ledger 为准。

## API 边界

| API | Owning service | 说明 |
| --- | --- | --- |
| `/api/context/personal-memories/**` | `reachai-control-service` | 当前认证用户的长期记忆 CRUD、查询、审计和导出。 |
| `/api/context/personal-memory-candidates/**` | `reachai-control-service` | 当前认证用户的私有候选确认或忽略。 |
| `/api/context/memory-erasure-requests/**` | `reachai-control-service` | 独立权限保护的九域 Agent 记忆擦除请求、重试、状态和人工证据；不是普通上下文 CRUD。 |
| `/api/context/runtime-user-mappings` | `reachai-control-service` | 平台用户到 runtime user 的映射维护。 |
| `/api/embed/**` | `reachai-control-service` | 嵌入式对话 session、message 和 page action 公开入口。 |

Runtime 执行时需要上下文注入，应通过 Runtime 与 Control/Context 边界的服务间契约完成，不让前端或外部工具直接访问 Runtime 内部端口。

## 当前代码位置

主要实现位置：

```text
reachai-control-service/src/main/java/com/enterprise/ai/control/context/
reachai-control-service/src/main/java/com/enterprise/ai/control/platform/
reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/
reachai-capability-service/src/main/java/com/enterprise/ai/capability/
ai-admin-front/src/views/settings/RuntimeUserMapping.vue
ai-admin-front/src/views/settings/PersonalMemory.vue
ai-admin-front/src/api/context.ts
ai-admin-front/src/types/context.ts
```

代表性测试：

- `reachai-control-service/src/test/java/com/enterprise/ai/control/context/*`
- `reachai-control-service/src/test/java/com/enterprise/ai/control/platform/*`
- `reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/*`
- `reachai-capability-service/src/test/java/com/enterprise/ai/capability/*`

## 验证命令

```powershell
mvn -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
Set-Location ai-admin-front
npm run build
```

从仓库根目录运行静态边界检查：

```bash
node scripts/check-backend-boundary-naming.mjs
node scripts/check-backend-domain-dependencies.mjs
node scripts/check-physical-service-route-contracts.mjs
```

五服务本地启动后再运行：

```bash
node scripts/check-physical-service-smoke.mjs
```

## 后续工作

- 在不改变 Control canonical revalidation 的前提下，完成 Knowledge semantic projection 的目标库、真实 Provider、切模重建与降级 E2E。
- 增加 outbox backlog、DEAD、recall 降级、candidate 年龄和 session lease 冲突的指标与告警面板。
- 按 [Personal Agent Memory](../architecture/personal-agent-memory.md) 的当前迁移与证据矩阵完成 MySQL、Redis、多副本、SSE、语义投影和浏览器 E2E；不要在本页复制易过期的脚本数量或执行状态。
