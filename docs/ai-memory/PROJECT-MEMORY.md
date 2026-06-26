# ReachAI Project Memory

## Product Positioning

睿池 ReachAI 是面向 Java 企业系统的 AI 能力中台。它不是单纯的工作流画布，也不是只扫描历史项目生成 Tool 的工具，而是把企业系统中的接口、领域方法、知识、模型、流程、权限和运行审计沉淀为 Agent 可理解、可编排、可治理、可开放的能力资产。

当前外部叙事可以稳定表述为：

- 面向 Java 企业系统的 AI 能力中台。
- Enterprise AI Capability Platform。
- AI Agent Control Plane / Enterprise Agent Runtime Platform 也可作为架构侧表达。

## Main Flow

1. 业务系统引入 `reachai-spring-boot2-starter` 和 `reachai-capability-sdk`。
2. 业务方法或 Controller 使用 `@ReachCapability` 声明能力，参数或请求 DTO 字段使用 `@ReachParam` 补充语义，返回 DTO 字段使用 `@ReachOutput` 声明可引用输出。
3. Starter 在启动时同步项目、实例、能力快照和 SDK 图。
4. 平台形成字段级 diff、评审 apply/ignore，并沉淀正式能力资产。
5. Workflow Studio 基于能力资产编排 Workflow `GraphSpec`（`ai_workflow`）。
6. Agent 入口（`ai_agent`）通过 binding 绑定 Workflow；Workflow 发布形成版本快照，Runtime 通过 `AgentEntry` + binding + `AgentRuntimeAdapter` 执行。
7. RunOps、Trace、ACL、Guard、Gateway、MCP、A2A 和嵌入式对话负责生产治理与开放。

## Modules

- `ai-admin-front/`: Vue 3 + Element Plus 管理端，包含项目注册、能力目录、Agent 入口、Workflow Studio、RunOps、模型、知识库、MCP/A2A、嵌入审计等页面。
- `ai-agent-service/`: 当前平台核心部署单元，内部承载 Capability Catalog、Runtime Host、Platform Control；包括 Agent 入口（`ai_agent`）、Workflow 编排（`ai_workflow`）、Workflow Studio、Runtime、发布、Trace、Tool ACL、注册中心、能力资产、嵌入式身份、Gateway、MCP、A2A 等核心服务。
- `ai-skills-service/`: 当前 Knowledge / Retrieval 部署单元，承载知识库、文件、chunk、文档 pipeline、RAG、业务索引、向量检索和历史扫描器实现；不要再把它描述成“技能服务”。
- `ai-model-service/`: 当前 Model Gateway 部署单元，承载模型实例中心、模型配置、Chat、Embedding、Rerank 和 OpenAI 兼容代理。
- `ai-common/`: 通用模型、响应、工具类。
- `reachai-capability-sdk/`: JDK8 兼容的业务系统能力声明 SDK 契约。
- `reachai-spring-boot2-starter/`: Spring Boot 2 接入、扫描、注册、心跳、能力同步和 SDK 图同步。
- `ai-runtime-contract/`: 中台内部 Tool / Skill 运行时契约。
- `sql/`: 根 SQL 基线与升级脚本。
- `docs/`: 当前权威知识库。

## Current Documentation Shape

`docs/README.md` 是当前知识库入口。主文档按产品能力组织：

- `01-平台定位与架构总览.md`
- `02-项目注册与能力资产.md`
- `03-Agent-Studio与Runtime.md`
- `04-运行治理与开放协议.md`
- `05-知识模型与企业资产.md`
- `06-项目背景技术与功能说明.md`
- `07-平台身份与授权模型.md`
- `08-平台对话框对外嵌入支持.md`
- `16-后端逻辑边界与命名重塑.md`

不要新增阶段型验收清单作为主知识库。新文档应按产品能力组织，并指向真实代码、接口、SQL 表或前端页面。

## Backend Logical Boundaries

当前架构表达按五个长期逻辑域组织，而不是按当前三服务名组织：

- Model Gateway：当前承载于 `ai-model-service`。
- Knowledge / Retrieval：当前承载于 `ai-skills-service`。
- Capability Catalog：当前承载于 `ai-agent-service` 内部边界，覆盖注册中心、能力快照、扫描目录、语义文档、Tool/Capability 资产。
- Runtime Host：当前承载于 `ai-agent-service` 内部边界，覆盖 Agent 入口、Workflow、GraphSpec 执行、调试、人工交互和 Runtime Adapter。
- Platform Control：当前承载于 `ai-agent-service` 内部边界，覆盖身份、RBAC、ACL、Guard、Gateway、MCP、A2A、市场、RunOps/Trace 管理面。

本轮重塑不改变端口、路由、SQL 表名、Maven artifactId 或前端页面结构。

## SQL Baseline

`sql/init.sql` 是当前唯一 SQL 基线入口。它覆盖注册中心、能力资产、Agent、GraphSpec、Trace、RunOps、Tool ACL、Guard、模型、知识库、业务索引、MCP、A2A、Gateway、市场资产和嵌入式对话等表。

历史 service-level SQL 已从活跃路径移除。未来 SQL 变化必须同时维护根基线和 `sql/upgrade-*.sql`。

## Agent, Workflow Studio And Runtime

Agent 与 Workflow 已解耦：

- **Agent**（`ai_agent` / `AgentEntry`）：身份、入口策略、权限与 binding；API `/api/agents`。`agent_definition` 主模型已退役。
- **Workflow**（`ai_workflow`）：`GraphSpec`、`canvas_json`、版本与发布；API `/api/workflows`。
- **Workflow Studio**：唯一画布编辑器（`WorkflowStudio.vue`）；原 Agent Studio 已退役，遗留路由仅兼容跳转。
- **Binding**（`ai_agent_workflow_binding`）：`/api/agents/{agentId}/workflow-bindings`，Runtime 由 `EmbedWorkflowRuntimeService` 等解析 Agent → Workflow → 活跃版本。

Workflow Studio 统一承载：可视化画布、交互式节点、会话式调试台、AI 生成/局部修改、SDK 图展示、发布校验、Runtime 执行与 Trace/RunOps 复盘。

Workflow 主线 Runtime 是 LangGraph4j + `GraphSpec`。AgentScope 等仍可通过 Agent 入口策略服务自主智能体形态。`CursorCodeAgentRuntimeAdapter` 和 `OpenAIAgentsRuntimeAdapter` 当前是扩展边界，不应写成已可用生产 Runtime。

## GraphSpec

`GraphSpec` 是平台可执行语义的核心中间表示，归属 Workflow 而非 Agent。

- 后端类型：`ai-agent-service/src/main/java/com/enterprise/ai/agent/graph/GraphSpec.java`。
- 前端 Workflow 图语义类型：`ai-admin-front/src/types/workflow.ts`（`WorkflowStudioState`、`WorkflowDefinition` 等；`AgentGraphSpec` 名称仍存在于类型层，新改动以 workflow 模块为准）。
- DB 字段：`ai_workflow.graph_spec_json` 保存运行语义；`ai_workflow.canvas_json` 保存 Workflow Studio 画布布局。
- 发布校验：`WorkflowReleaseValidationService`。
- Runtime 执行：`LangGraph4jRuntimeAdapter`（经 `WorkflowRuntimeGraphAdapter` 与 binding 解析）。

新增节点、边、变量映射、条件路由或 Runtime 行为时，要优先维护 Workflow `GraphSpec` 语义，不能只扩展画布 JSON。

## AI Draft And Local Edit

AI 生成工作流：

- API: `/api/workflows/studio/generate-draft`
- Controller: `WorkflowStudioDraftController`
- Generator: `LlmWorkflowDraftGenerator`
- 结果应标准化为 `GraphSpec` 和 `canvasSnapshot`，前端走预览/应用，不直接覆盖画布。

AI 局部编辑工作流：

- API: `/api/workflows/studio/edit-draft`
- Service: `WorkflowDraftEditService`
- 输入应包含当前 canvas、用户意图和选中上下文。
- 默认围绕当前选中节点/边/多选内容做局部修改，而不是重生成整张图。

## Registration And Capability Assets

推荐新系统和核心系统使用 `reachai-spring-boot2-starter` 与 `reachai-capability-sdk` 主动注册。Controller/OpenAPI 扫描保留给存量系统和低改造场景。

重要概念：

- `@ReachCapability`: 声明业务方法或接口可作为 Agent 能力。
- `@ReachParam`: 声明参数语义。
- `ReachAiRegistryProperties`: 使用 `reachai.registry`、`reachai.project`、`reachai.capability` 配置。
- `ReachCapabilityBeanScanner`: 从 ReachAI 注解和请求体结构生成能力描述。
- `tool_definition`: 统一承载 Tool 与粗粒度 Capability。

## Identity And Embed Chat

平台身份与嵌入式对话已经进入主线能力：

- 平台用户支持本地账号、Header SSO、OIDC、SAML 等入口。
- 业务用户目录以 `tenantId + appId + externalUserId` 为稳定键。
- 嵌入式对话使用短期 `embedToken`，前端 SDK 不保存 `appSecret`。
- 业务后端负责用应用凭证和当前登录用户向平台申请 token。
- Page Action 绑定 `sessionId + pageInstanceId`，只调用当前页面已注册动作。
- 自定义 renderer 只能使用注册过的 `rendererKey` 和结构化数据，不能注入任意 HTML。

相关 SQL 包括 `eaf_embed_session`、`eaf_page_action_event`、`eaf_embed_chat_event`、`eaf_embed_renderer` 和 `eaf_embed_token_revocation`。

## Frontend State

管理端已经有暗色/亮色主题基础，后续主题改动应复用 CSS 变量和现有主题文件。不要在页面里散落硬编码暗色、浅色或 Element Plus 覆盖。

项目范围选择器、注册中心、扫描项目、Workflow Studio 侧边栏折叠等行为和 `MainLayout.vue`、`ProjectSelector.vue`、路由名称、项目 store 相关。改导航和布局前先查这些共享位置。

## Context Governance Kernel v1（Phase-1 已完成）

**ReachAI Context Governance Kernel v1** 第一阶段已收口（2026-06-23）。企业 Agent 上下文治理底座，与 `ConversationMemoryService` **独立**、不绑定单一 Runtime 框架。

- 权威文档：`docs/Context-Governance-Kernel.md`
- AI 精简基线：`docs/ai-memory/CONTEXT-GOVERNANCE-BASELINE.md`
- 代码：`ai-agent-service/.../context/`（含 `runtime/`、`memory/`）
- 管理页：`ai-admin-front/src/views/context/ContextGovernance.vue`（`/context/governance`，仅 `PROJECT_DEV`）
- SQL：6 张 context 表（见 `sql/init.sql`）

**当前可用能力：** 结构化存储与 CRUD；lane 隔离；access policy；检索+HYBRID token/CJK fallback+组包+`scoreBreakdown`；Runtime CENTRAL query-aware 只读注入；`runtimeContextHits` 命中解释 metadata；Candidate 确认写回与相关 ACTIVE 记忆审核 quality signal；embed 自确认 API；PROJECT_DEV 治理页；AI Coding Gateway discovery + 单条/批量 Context Candidate 提交 + `contextCandidateStatusUrlTemplate` 回查模板 + `contextCandidateAuditUrlTemplate` 审计深链模板；SDK onboarding manifest no-echo，SDK quick-access prompt / reachai-onboarding skill、Page Assistant 与 Workflow AI Coding 外部工具体验 header-only，不在 manifest/prompt URL 中回显或拼接 raw key；候选按 `submissionId/traceId` 跳转审计；lifecycle dryRun/run；ops summary；audit+evidence。

**禁止误解的边界：**

- 不是 ConversationMemory 的替代或迁移完成品。
- `PROJECT_DEV` 不进 Runtime prompt；`RUNTIME_USER` 管理端仅开放映射内 candidate 代审，不开放已采纳 PRIVATE item 管理。
- PENDING candidate 不进 prompt；Phase-2.1/2.2/2.3/2.4/2.5/2.6 已有 LLM 自动抽取 MVP、候选质量门、显式 HYBRID token/CJK fallback、Runtime 当前消息 query-aware 组包、管理端预览模式切换、response 命中解释 metadata 和 candidate 审核 quality signal；只生成 `RUNTIME_USER` PENDING candidate，并抑制有效 PENDING 精确重复与已确认 ACTIVE 精确重复；相关 ACTIVE 记忆只写 compact metadata 提示，不自动合并；仍无向量/FULLTEXT。
- Platform 不能无映射替 embed 用户审核私有记忆；代审必须同时满足 RBAC 与 ACTIVE `context_runtime_user_mapping`。
- ops summary 默认 PROJECT_DEV；RUNTIME_USER 须 `includeRuntimeUser=true` 且 aggregate-only。
- `ContextProjectIdentity` 统一 projectCode/projectId；仅 code vs 仅 id 不匹配。
- HYBRID 注入 deferred；跨用户代审只允许走 RBAC + ACTIVE runtime 用户映射。

历史分阶段边界（Phase-2～7.1）详见 `CONTEXT-GOVERNANCE-BASELINE.md` 与 `docs/Context-Governance-Kernel.md` 实施阶段索引。
