# Context Governance Kernel

## 当前定位

Context Governance Kernel 是 ReachAI 的企业 Agent 上下文治理底座。它负责上下文命名空间、上下文条目、证据、候选记忆、运行时用户映射、审计、检索、组包和生命周期治理。

当前文档按物理拆分后的五服务拓扑描述，不再使用旧大后端源码路径作为实现位置。

## 五服务职责

| 服务 | Context Governance 职责 |
| --- | --- |
| `reachai-control-service` | 暴露 `/api/context/**`、候选审核、运行时用户映射、嵌入式 session/page action 和管理端 BFF。 |
| `reachai-runtime-service` | 在 Agent / Workflow 执行时消费 context package，写入运行时元数据、Trace 和 Runtime 相关审计。 |
| `reachai-capability-service` | 提供 Capability Catalog、项目/实例/能力元数据和 registry credential 校验。 |
| `reachai-knowledge-service` | 在后续语义检索增强中承接 Knowledge / Retrieval、向量检索和 RAG 能力。 |
| `reachai-model-service` | 为 LLM 记忆抽取、Embedding、Rerank 和模型调用提供 Model Gateway。 |

第一阶段仍保持同一个 MySQL 库，不拆库；实现代码必须遵守 owning service 边界。

## 已落地能力

- 存储模型：`context_namespace`、`context_item`、`context_binding`、`context_evidence`、`context_audit_event`、`context_memory_candidate`、`context_runtime_user_mapping`。
- 访问边界：tenant、project、lane、visibility、RUNTIME_USER PRIVATE。
- 管理端能力：项目上下文管理、候选审核、运行时用户映射、运维摘要、审计过滤和深链。
- 运行时能力：Runtime CENTRAL 组包、候选记忆抽取、候选质量门、HYBRID fallback、metadata 解释摘要。
- 外部 AI Coding 能力：项目级 manifest、context candidate 提交、候选状态回查、审计深链模板。
- Header-first 外部工具鉴权：`X-ReachAI-AiCoding-Key`，不再生成 query key URL。

当前仍不包含：

- Milvus / FULLTEXT 级别的完整向量检索治理闭环。
- RUNTIME_USER 已采纳私有记忆条目管理 UI。
- 与旧 `ConversationMemoryService` 的完整替代和迁移策略。

## API 边界

| API | Owning service | 说明 |
| --- | --- | --- |
| `/api/context/namespaces` | `reachai-control-service` | Namespace CRUD。 |
| `/api/context/items` | `reachai-control-service` | Item 管理、binding、evidence。 |
| `/api/context/query` | `reachai-control-service` | Context 检索入口。 |
| `/api/context/package` | `reachai-control-service` | Runtime context 组包预览和管理端调试。 |
| `/api/context/audit` | `reachai-control-service` | 审计查询。 |
| `/api/context/ops/summary` | `reachai-control-service` | 运维摘要。 |
| `/api/context/lifecycle/run` | `reachai-control-service` | lifecycle dry run / 手动执行入口。 |
| `/api/context/memory/candidates` | `reachai-control-service` | PROJECT_DEV 和 RUNTIME_USER candidate 审核。 |
| `/api/context/runtime-user-mappings` | `reachai-control-service` | 平台用户到 runtime user 的映射维护。 |
| `/api/embed/**` | `reachai-control-service` | 嵌入式对话 session、message 和 page action 公开入口。 |
| `/api/ai-coding/projects/{projectId}/context-candidates` | `reachai-control-service` | 外部 AI Coding 工具提交 PROJECT_DEV candidates。 |

Runtime 执行时需要上下文注入，应通过 Runtime 与 Control/Context 边界的服务间契约完成，不让前端或外部工具直接访问 Runtime 内部端口。

## 当前代码位置

主要实现位置：

```text
reachai-control-service/src/main/java/com/enterprise/ai/control/context/
reachai-control-service/src/main/java/com/enterprise/ai/control/platform/
reachai-control-service/src/main/java/com/enterprise/ai/control/aiassist/
reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/
reachai-capability-service/src/main/java/com/enterprise/ai/capability/
ai-admin-front/src/views/context/ContextGovernance.vue
ai-admin-front/src/views/registry/RegistryProjectDetail.vue
ai-admin-front/src/api/context.ts
ai-admin-front/src/types/context.ts
ai-admin-front/scripts/check-context-candidate-ui.mjs
```

代表性测试：

- `reachai-control-service/src/test/java/com/enterprise/ai/control/context/*`
- `reachai-control-service/src/test/java/com/enterprise/ai/control/platform/*`
- `reachai-control-service/src/test/java/com/enterprise/ai/control/aiassist/*`
- `reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/*`
- `reachai-capability-service/src/test/java/com/enterprise/ai/capability/*`

## 验证命令

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
& "C:\Users\jsh\AppData\Local\Temp\apache-maven-3.9.9\bin\mvn.cmd" -pl reachai-control-service,reachai-runtime-service,reachai-capability-service,reachai-knowledge-service,reachai-model-service -am -DskipTests compile
cd ai-admin-front
npm run build
node scripts/check-context-candidate-ui.mjs
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

- 将 Context runtime 注入从 Control 管理边界继续收敛到清晰的 Runtime 服务间契约。
- 评估向量检索、FULLTEXT/ngram 和 Knowledge / Retrieval 的集成边界。
- 明确 `ConversationMemoryService` 与 Context Kernel 的替代关系。
- 为 RUNTIME_USER 已采纳私有记忆条目提供更严格 RBAC 后再开放管理 UI。
