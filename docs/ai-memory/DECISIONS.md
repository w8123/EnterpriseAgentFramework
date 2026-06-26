# ReachAI Durable Decisions

## Product Story

ReachAI 的稳定定位是“面向 Java 企业系统的 AI 能力中台”。不要退回成“工作流编辑器”或“扫描历史项目生成 Tool”的窄叙事。

## Registration First

新系统和核心系统优先通过 `reachai-spring-boot2-starter` 与 `reachai-capability-sdk` 主动注册。平台扫描 Controller/OpenAPI 是存量和低改造场景的补充路径。

## Capability Naming

产品、文档和 UI 默认使用 `Capability / 能力`。

`Skill` 在当前代码中仍大量存在，但它更接近历史命名或内部能力包概念，不等同于外部 Agent 生态里的标准 Skill 包。命名迁移必须看真实合同和兼容边界，不能盲目替换。

## Agent And Workflow Split

Agent 入口与 Workflow 编排已拆分：

- **Agent** 唯一主表是 `ai_agent`（代码侧 `AgentEntry` / `AgentEntryEntity`）。`agent_definition` 与 `/api/agent/definitions` 已退役，不要在新功能中继续写入或依赖。
- **Workflow** 主表是 `ai_workflow`；`GraphSpec`、`canvas_json`、版本发布均在 Workflow 上维护。
- **Workflow Studio** 是唯一画布编辑器；Agent Studio 路由与页面仅保留兼容跳转。
- Runtime 执行合同：`AgentEntry` + `ai_agent_workflow_binding` + 已发布 Workflow `GraphSpec` → `AgentRuntimeAdapter`。
- 对外 API 主线：`/api/agents`、`/api/workflows`；binding 走 `/api/agents/{agentId}/workflow-bindings`。

## GraphSpec Is Runtime Semantics

`GraphSpec` 是可执行语义，归属 `ai_workflow`；`canvas_json` 是布局。任何新增节点、边、条件、变量映射、AI 生成/编辑或 Runtime 行为，都必须维护 Workflow 上的 `GraphSpec`。

## Preview Before Apply

AI 生成和 AI 修改工作流必须走预览/应用模式。模型输出不能直接覆盖当前 Workflow Studio 画布。

## Local Edit By Selection

Workflow Studio 的 AI 局部编辑默认围绕当前选中节点、边或多选范围工作。用户说“修改这里”“在这个节点后面加审批”时，选中上下文是输入合同的一部分。

## Runtime Adapter Boundary

`AgentRuntimeAdapter` 是统一执行契约。不同 Runtime 可以有自己的能力模型和配置面板，但 Workflow Studio、发布、RunOps 不应绑定到单一 Agent 框架。

## SQL Baseline

`sql/init.sql` 是唯一基线。未来 schema 变化同时维护 `init.sql` 和 `sql/upgrade-*.sql`。

## No Old Data Compatibility By Default

项目快速迭代时，不默认为旧数据做复杂兼容迁移。需要清理旧字段、重建表或丢弃旧数据时，在 SQL 注释和变更说明中明确即可。

## ReachAI Branding And SDK Technical Identity

ReachAI 是产品品牌，也是新 JDK8 接入 SDK 的技术身份。新业务系统接入使用 `reachai.*` 配置、`X-ReachAI-*` header、`Reach*` 类名和 `reachai-*` Maven artifact。历史 `eaf.*` 配置、`X-EAF-*` header、`Eaf*` 类名属于旧兼容边界；旧 `ai-spring-boot-starter` 已退役。

## Documentation Shape

`docs/` 是当前知识库，按能力组织。旧阶段文档和临时讨论稿不应重新扩散。新增文档要回答“当前真实系统是什么、代码在哪里、边界是什么”。

## Backend Logical Boundary Reshape

后端架构表达从旧的 `model / skills / agent` 三服务名，收敛为五个长期逻辑域：

- Model Gateway
- Knowledge / Retrieval
- Capability Catalog
- Runtime Host
- Platform Control

当前三个 Spring Boot 部署单元暂时不变：`ai-model-service` 主要承载 Model Gateway，`ai-skills-service` 主要承载 Knowledge / Retrieval，`ai-agent-service` 主要承载 Capability Catalog、Runtime Host 和 Platform Control。新增代码、文档和 UI 文案应优先归入逻辑域；是否物理拆出 `reachai-capability-service`、`reachai-runtime-service` 或 `reachai-control-service` 留到边界稳定后再评估。

`ai-skills-service` 的 artifactId 和目录名短期保留，但产品和文档口径改为 Knowledge / Retrieval deployment unit，不再称为“技能服务”。

## Frontend Theme Direction

前端主题应继续基于共享 CSS 变量和 Element Plus 覆盖层演进。不要在新页面中引入散落的硬编码颜色。

## Runtime User Identity Priority

嵌入式 Runtime User Memory 与 Workflow Runtime 主 userId 统一采用：

`globalUserId → externalUserId → userId`

- `RuntimeContextPackageService`、`RuntimeMemoryCandidateService`、`ContextMemoryCandidateService.approve` 均按此顺序解析。
- `WorkflowRuntimeService.userId(principal)` 自 Phase-3 起改为 globalUserId 优先（此前为 externalUserId 优先）；影响 workflow 执行传给 Runtime 的 userId、trace 与 `ToolExecutionContext.userId`。
- `externalUserId` 作为业务系统侧 fallback；`userId` 为平台侧最后 fallback。

## Embed Runtime vs Platform Identity

- **Embed runtime subject**：`RuntimeUserIdentityResolver` 从 embed session/claims 解析 `globalUserId → externalUserId`，作为 `context_memory_candidate.user_id` 与 embed review API 的隔离键。
- **Platform self-service**：`ContextMemoryCandidateController` 默认绑定 `PlatformPrincipal.userId`（平台库主键字符串），不能把平台用户 ID 默认等同于 embed runtime subject。
- **Platform delegated review（Phase-2.14）**：平台后台代审 runtime 用户候选必须同时满足 `context:runtime-user:review`（或 `platform:admin` / `*`）和 ACTIVE `context_runtime_user_mapping`；映射键是 `runtime_user_id`（`globalUserId → externalUserId → userId` 归一值），可 tenant-wide 或项目级；维护 `/api/context/runtime-user-mappings` 需 `context:runtime-user:mapping:manage` 或 admin。
- **Platform delegated review UI（Phase-2.15）**：管理端只在 Runtime 映射 tab 中开放选中 ACTIVE 映射后的 candidate 缓冲区代审；请求必须带 `memoryLane=RUNTIME_USER` 和映射的 `runtimeUserId`，reviewer 仍由当前 `PlatformPrincipal.userId` 注入；不展示已采纳 PRIVATE item 列表。
- **Candidate audit trace UI（Phase-2.16）**：PROJECT_DEV 候选表的“查审计”只复用候选 `submissionId/traceId` 过滤 Audit tab，不新增审计语义；`tab=audit` 深链应直接进入审计页，方便外部 AI Coding / code-scan 批次追踪。
- **Manifest audit trace template（Phase-2.17）**：AI Coding Gateway manifest 的 `endpoints.contextCandidateAuditUrlTemplate` 与项目详情复制面板暴露 `traceId={submissionId}` 的 Audit tab URL 模板；这是治理 UI 深链，不是新的候选审核 API，不回显原始 `aiCodingKey`。
- **Manifest candidate status template（Phase-2.18）**：AI Coding Gateway manifest 的 `endpoints.contextCandidateStatusUrlTemplate` 与项目详情复制面板暴露 `traceId={submissionId}&status=PENDING` 的候选列表 URL 模板；这是既有 `GET /context-candidates` collection 的回查 discovery，不新增候选详情或审核 API。
- **Page Assistant header no-echo（Phase-2.19）**：`pageAssistantManifest` 通过 `X-ReachAI-AiCoding-Key` / `AiCodingKeyContext` 校验时，不把 raw key 回显到 endpoint URL、`aiCodingAccess.accessKey` 或 helper 命令；`reachai-page-assistant.ps1` 用 `-AiCodingKey` / `REACHAI_AI_CODING_KEY` 发送 header。
- **SDK onboarding header no-echo（Phase-2.20）**：`onboardingManifest` 通过 `X-ReachAI-AiCoding-Key` / `AiCodingKeyContext` 校验时，不把 raw key 回显到 `agentProvisioning.provisionAgentUrl` 或 `aiCodingAccess.accessKey`；外部工具用同一个 header 调用无 query key 的 provisioning URL。
- **Workflow AI Coding prompt header-first（Phase-2.21）**：Page Assistant Wizard 复制给外部工具的 Workflow AI Coding prompt 不再把 `aiCodingKey` 拼进 Workflow/report URL；认证统一要求 `X-ReachAI-AiCoding-Key` header。workflow-ai-coding skill 与文档示例同样 header-first。
- **SDK quick-access prompt header-first（Phase-2.22）**：`SdkAccessWizard` 复制给外部工具的 onboarding prompt 不再把 `aiCodingKey` 拼进 manifest、agent provisioning、access session、step report 或 session check URL；认证统一要求 `X-ReachAI-AiCoding-Key` header。reachai-onboarding skill 与文档示例同样 header-first。
- **External AI Coding header-only（Phase-2.23）**：`PlatformAuthInterceptor` 只接受 `X-ReachAI-AiCoding-Key`，不再从 `aiCodingKey` query 提取外部工具 key；`AiAssistController`、AI Coding Gateway manifest、Page Assistant prompt 与随包 docs 不再生成或宣传 query-key URL。
- **Phase-4 决策**：runtime 私有 memory 的用户确认入口走 `/api/embed/sessions/{sessionId}/memory/candidates`；平台后台审核 embed runtime 候选必须走企业身份映射 / RBAC，不能默认等同。
- **PENDING candidate 不进 Runtime prompt**；approve 后的 `context_item` 可在下一轮 CENTRAL Runtime 注入命中。

## Embed Session Token Binding

- `EmbedSessionService.requireActiveSession` 是 embed chat 与 embed memory candidate API 的**统一** session 门禁。
- Phase-4.1 起强校验：`tenantId`、`appId`、`projectCode`、`agentId`、`externalUserId`、`globalUserId`、`pageInstanceId`、`pageKey` 必须与 bearer token claims 一致。
- `route`/`origin` 不纳入会话期强校验：SPA 路由可能变化，且 session 创建时已做必要校验；重复强校验可能误伤正常 embed 对话。

## Context Admin List API vs Search API

- **管理列表**（Phase-5）：`GET /api/context/items` 用于管理端工作台；支持 `namespaceId`/`itemType`/`status`/`keyword` 过滤；可列出 REVOKED/STALE 等非 ACTIVE item；经 `ContextAccessPolicyService.isItemAccessibleInScope` 过滤。
- **召回检索**：`POST /api/context/query` 仍为 ACTIVE-only 召回排序，供 Runtime 组包与语义检索；二者语义分离，不可混用。
- **管理端页面**：`ai-admin-front` `/context/governance` 的条目、PROJECT_DEV 候选、组包、lifecycle 仅操作 `memoryLane=PROJECT_DEV`；Runtime 映射 tab 维护代审授权，并在选中 ACTIVE 映射后审核对应 RUNTIME_USER candidate 缓冲区；不管理已采纳 RUNTIME_USER PRIVATE item；组包预览不渲染 `userMemory`。

## Project Scope Dual-Identifier (Phase-5.1)

- `matchesProject(query, namespace)` 同时考虑 `projectCode` 与 `projectId`：可比较字段冲突即拒绝；无共同可比较标识（如 query 仅 code、namespace 仅 id）不默认匹配。
- `GET /api/context/items` 的 `projectId` 传入 `listActiveNamespaceEntities` 与 access policy，不再只是 controller 透传。
- `listActiveNamespaceEntities` / `GET /api/context/namespaces` 候选 SQL 在存在 project 标识时 OR 匹配 code、id、或 project 字段均为空的 namespace；最终边界仍由 `isItemAccessibleInScope` 收紧。
- `PROJECT/PRIVATE` visibility 受双标识约束；`GLOBAL/TENANT` 与 `RUNTIME_USER + PRIVATE` 不变。

## Create Path Project Boundary (Phase-5.2)

- `validateNamespaceBoundaryForCreate` 与 `matchesProject` 共用 `matchesProjectIdentity` helper，读/写路径 projectCode/projectId 双标识语义一致。
- request 与 namespace 无共同可比较项目标识时不允许 create item 写入。
- `tenantId` 仍是硬边界；`RUNTIME_USER + PRIVATE` binding/owner 规则不变。
- 未改 SQL/schema、namespaceKey 生成逻辑。

## ContextProjectIdentity (Phase-6)

- `ContextProjectIdentity`（`public final`，无 Spring）：read/create/candidate 共用 `matches` / `requireMatch` / `namespaceKeyToken`。
- 语义与 Phase-5.2 `matchesProjectIdentity` 一致：双方都有 code/id 须相等；仅 code vs 仅 id 不匹配；单边有标识另一边无 → 拒绝；双方皆空 → 允许。

## namespaceKey projectId-only (Phase-6)

- projectCode 优先保持旧 key；无 code 有 id 时用 `pid-{projectId}` token。
- 旧 projectId-only namespace **不自动改写**、不做双 key lookup；历史环境可能出现新旧 key 并存，需后续显式迁移。

## Candidate Approve Project Validation (Phase-6)

- `validateNamespaceForCandidate` 改用 `ContextProjectIdentity.requireMatch`，与 create 路径对齐。

## Context Lifecycle Manual Ops (Phase-6)

- `POST /api/context/lifecycle/run`：candidate expire + PROJECT_DEV item stale；`dryRun` 只统计；`markStaleByLifecycle` 系统路径（`actorType=SYSTEM`）；默认跳过 RUNTIME_USER；`includeRuntimeUserItems=true` 本批仅 warning 不实际 stale。
- Phase-6 当时未做：SQL/schema、定时任务、RBAC、向量、LLM 抽取、Runtime 注入、前端大页面；定时任务后续由 Phase-2.11 `ContextLifecycleScheduler` 补齐。

## Context Retrieval Scoring (Phase-7)

- 多因素确定性 `rankScore`：keyword(title/summary/content)、itemType、trust、confidence、verified/updated recency、sourceType、binding。
- `hitReason` 主原因 + `scoreBreakdown` 分项字符串；无 keyword 时按质量因子排序。

## Context Ops Summary (Phase-7)

- `GET /api/context/ops/summary`：默认 PROJECT_DEV；`runtimeUserExcludedCount` 仅计数不暴露明细；Phase-7 首版为内存聚合，后续 Phase-2.12 已改为 SQL count 聚合。

## Context Audit List Filters (Phase-7)

- `ContextAuditListRequest`：`eventType`/`actorType`/`actorId`/`decision`/`traceId`/`namespaceId`；limit 上限 500；`dateFrom/dateTo` 后续由 Phase-2.10 补齐。

## Context Ops Summary RUNTIME_USER Gate (Phase-7.1)

- 默认 PROJECT_DEV 运维摘要；`memoryLane=RUNTIME_USER` 且 `includeRuntimeUser=false` → 400。
- `includeRuntimeUser=true` 时仅返回 lane 级聚合计数 + aggregate-only warning；不暴露 item 明细。

## Context Audit projectId Filter (Phase-7.1)

- `GET /api/context/audit` 与 `auditEventCountRecent` 支持 `projectId`；与 `projectCode` 同时存在时双条件 AND。

## Context Audit Date Range (Phase-2.10)

- `GET /api/context/audit` / `ContextAuditListRequest` 支持 `dateFrom/dateTo` ISO datetime；`ContextGovernance.vue` 审计 tab 用日期范围追踪 AI Coding / code-scan 候选提交与审核链路。

## Context Lifecycle Scheduler (Phase-2.11)

- `ContextLifecycleScheduler` 复用 `ContextLifecycleService.run`，默认 `eaf.context.lifecycle.enabled=false`；开启后按 `eaf.context.lifecycle.fixed-delay-ms` 定时执行 candidate expire 与 PROJECT_DEV item stale。
- Scheduler 可配置 `tenant-id`、`project-code`、`project-id`、`dry-run`、`candidate-expire-limit`、`item-stale-limit`；管理端 lifecycle 仍只暴露 dryRun，不开放 RUNTIME_USER 已采纳私有记忆条目管理或真实 stale 操作。

## Context Ops Summary SQL Aggregation (Phase-2.12)

- `ContextOpsSummaryService` 对 `context_item` 与 `context_memory_candidate` 统计改用 Mapper `selectCount` 条件聚合，不再加载明细到内存后遍历。
- namespace 范围仍由 `ContextNamespaceService.listActiveNamespaceEntities` 与 `ContextProjectIdentity` 决定；candidate 统计仍按 tenant + 可选 `projectCode` / `projectId` 过滤。
- `memoryLane=RUNTIME_USER` 的 ops summary 仍须 `includeRuntimeUser=true`，且只返回聚合计数 + aggregate-only warning，不暴露私有 item 明细。

## HYBRID Runtime Context Injection Timing (Phase-2.13)

- `runtimePlacement=HYBRID` 在 embed 入口仍先返回 `hybrid-placement-deferred`，不提前 compose，避免 embedded runtime 成功时留下未实际使用的 Context 注入/audit。
- `AgentRouter` 只在 embedded runtime 失败并准备回落 central runtime 前调用 `RuntimeContextPackageService.injectForCentralFallback`。
- fallback 注入使用同一 `RuntimeContextIdentity` 的拷贝，并将实际注入 placement 设置为 `CENTRAL`；central adapter 继续通过 `AgentRuntimeRequest.effectiveUserMessage()` 消费 prompt section。

## Context Governance Kernel Phase-1 Closure

- **主体能力**：Context Governance Kernel 是 ReachAI 平台能力，不依赖 AgentScope / LangGraph4j 作为主语；Runtime 通过 `runtimeContext` 字段注入。
- **Lane 分离**：`PROJECT_DEV`（项目治理记忆）与 `RUNTIME_USER`（运行时用户记忆）必须逻辑隔离，检索/组包强制 `memory_lane` 相等。
- **Runtime 只读**：嵌入式 CENTRAL 只读注入；不自动写长期记忆；`EMBEDDED` 跳过；`HYBRID` 初始 deferred，只有 embedded 失败回落 central 前注入。
- **Candidate 确认写回**：对话后仅生成 PENDING candidate；**approve 后**才写 `context_item`；PENDING 不参与 Runtime prompt。
- **API 身份分离**：`ContextMemoryCandidateController`（PlatformPrincipal）与 `EmbedMemoryCandidateController`（session+globalUserId）不可混用；平台代审 embed 用户私有记忆必须通过 Phase-2.14 身份映射与 RBAC。
- **管理端边界**：`ContextGovernance.vue` 的上下文条目/PROJECT_DEV 候选/组包/lifecycle 仅 `PROJECT_DEV`；Runtime 映射 tab 只做代审授权管理和映射内 RUNTIME_USER candidate 采纳/忽略，已采纳 PRIVATE item 管理 UI 暂不开放。
- **证据链边界**：候选行“查审计”只按 `traceId` 串联 `context_audit_event`，不把 PENDING candidate 纳入 Runtime prompt，也不绕过 approve 状态机。
- **Ops aggregate-only**：`memoryLane=RUNTIME_USER` 的 ops summary 须 `includeRuntimeUser=true`，且只返回聚合计数 + aggregate-only warning。
- **ProjectIdentity**：`projectCode` / `projectId` 统一经 `ContextProjectIdentity`；namespaceKey projectId-only 用 `pid-{id}`。
- **Phase-2 范围**：LLM 抽取、向量检索、RBAC、ConversationMemory 关系梳理等——均不在 Phase-1；定时 lifecycle、ops summary SQL 聚合、HYBRID fallback 注入、后端 RBAC 身份桥、映射内 RUNTIME_USER candidate 代审 UI、候选审计追踪 UI、manifest 审计深链模板、候选状态回查模板、Page Assistant header no-echo、SDK onboarding header no-echo、Workflow AI Coding prompt header-first 与 SDK quick-access prompt header-first 已由后续 Phase-2.11/2.12/2.13/2.14/2.15/2.16/2.17/2.18/2.19/2.20/2.21/2.22 补齐。
