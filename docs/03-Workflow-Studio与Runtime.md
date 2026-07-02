# Workflow Studio 与 Runtime

## 定位

ReachAI 已将 **Agent 入口** 与 **Workflow 编排** 解耦：

- **Agent**（`ai_agent`）：智能体身份、入口策略、模型/权限/记忆范围，以及到 Workflow 的路由绑定。
- **Workflow**（`ai_workflow`）：可执行 `GraphSpec`、Studio 画布布局、运行时类型与发布版本。

**Workflow Studio** 是唯一的画布编辑器；历史 Agent 画布入口与 `agent_definition` 主模型已退役。Runtime 执行链路为：`AgentEntry` → `ai_agent_workflow_binding` 解析 → 已发布 Workflow 的 `GraphSpec` → `AgentRuntimeAdapter`。

这一层的产品亮点不是单纯“画流程图”，而是把可视化画布、交互式节点、会话式调试台、AI 生成/修改工作流、SDK 图注册、发布校验和 Runtime 执行都收敛到 Workflow 的 Graph 层；Agent 侧只负责“谁在用、从哪进、绑哪条流程”。

## 当前已落地

### Agent 入口（`ai_agent`）

`AgentEntryController` 提供 `/api/agents`：

- 列表、详情、新建、更新、删除 Agent 入口。
- 关键字段：`key_slug`、`agent_kind`（如 `PROJECT_ENTRY`、`PAGE_COPILOT`、`GLOBAL_EMBED`）、`system_prompt`、`model_instance_id`、`allowed_roles_json`、`entry_config_json`、`visibility`、`enabled`。
- Agent 不再内嵌 `graph_spec_json` / `canvas_json`；编排语义全部在 Workflow 上维护。

管理端入口：`AgentList.vue`、`AgentEdit.vue`、`AgentDebug.vue`、`AgentWorkflowBindings.vue`。遗留路由 `agent/:id/studio`、`agent/:id/versions` 仅保留兼容跳转，不应再作为编辑入口。

### Workflow 编排（`ai_workflow`）

`WorkflowDefinitionController` 提供 `/api/workflows`：

- 列表、详情、新建、更新、删除 Workflow。
- `GET/PUT /api/workflows/{id}/studio` 读写 Workflow Studio 草稿（`graph_spec_json` + `canvas_json`）。
- `/graph-node-types` 暴露 Studio 节点类型目录。
- `/runtime-validation` 做运行时配置校验。

`ai_workflow` 关键字段：

- `graph_spec_json`：平台 `GraphSpec`，是运行时语义的核心。
- `canvas_json`：Workflow Studio 画布布局，与运行语义分离。
- `workflow_type`、`runtime_type`（主线 `LANGGRAPH4J`）、`default_model_instance_id`、`default_resource_config_json`。
- `status`（`DRAFT` / `ACTIVE` 等）、`managed_by`（`MANUAL` / `SDK` / `AI_QUICK_ACCESS`）。

管理端入口：`WorkflowList.vue`、`WorkflowStudio.vue`、`WorkflowVersions.vue`。节点配置面板主路径已收拢到 `ai-admin-front/src/views/workflow/studio-panels/`；新增配置面板和 import 一律使用该目录，不再依赖历史兼容目录。

### Agent ↔ Workflow 绑定

`AgentWorkflowBindingController` 提供 `/api/agents/{agentId}/workflow-bindings`：

- 维护 `ai_agent_workflow_binding`：按 `DEFAULT`、`PAGE`、`ROUTE`、`ACTION`、`INTENT` 等类型把 Agent 入口路由到具体 Workflow。
- `resolve-preview` 可在发布前预览解析结果。

嵌入式与网关运行时由 `EmbedWorkflowRuntimeService` 解析：`AgentEntry` → binding → `ai_workflow` + 活跃版本 → `WorkflowRuntimeGraphAdapter` 编译为可执行图。

### 统一 Graph 层

统一 Graph 层的核心类型是 `GraphSpec`（后端当前以 `reachai-runtime-service` 主路径中的 `com.enterprise.ai.agent.graph.GraphSpec` 为准；前端 Workflow 图语义类型见 `ai-admin-front/src/types/workflow.ts`，与 `AgentGraphSpec` 名称并存）。

当前落地点：

- `ai_workflow.graph_spec_json` 保存运行时语义，`ai_workflow.canvas_json` 保存 Studio 画布布局。
- `WorkflowReleaseValidationService` 把 `GraphSpec` 当作发布契约，校验节点、边、入口、LLM 节点、Capability 引用、条件边、变量映射和可达性。
- `WorkflowVersionService` 发布时把 `GraphSpec` 快照写入 `ai_workflow_version`；RunOps/Trace 回放基于发布快照追溯执行路径。
- `AiRegistryService` 支持 SDK 注册图能力，把 SDK 上报的图标准化为 Workflow `GraphSpec`，并生成 Studio 可展示的 `canvas_json`。

### Workflow Studio 画布

`WorkflowStudio.vue` 承载画布编辑、调试、发布、Trace 回放、Capability 提取和评测入口。AI 与调试接口已迁移到 Workflow 命名空间：

- AI 生成：`POST /api/workflows/studio/generate-draft`（`LlmWorkflowDraftGenerator`）。
- AI 局部编辑：`POST /api/workflows/studio/edit-draft`（`WorkflowDraftEditService`）。
- 节点调试：`POST /api/workflows/studio/debug-node`（`WorkflowStudioDebugController`）。

AI 生成/修改仍走预览/应用模式，模型输出不直接覆盖当前画布。

### 交互式节点与 UI 请求

交互式节点是 Studio 与 Runtime 的重要能力：执行过程中可向用户请求补充、确认、选择或展示结构化结果。

- 节点类型定义在 `ai-admin-front/src/types/studio.ts`；配置面板在 `studio-panels/InteractionConfigPanel.vue`。
- 运行时统一 UI 协议是 `UiRequestPayload`（后端 `.../model/interactive/UiRequestPayload.java`，前端 `types/interaction.ts`）。
- 前端渲染由 `InteractionRenderer.vue` 承载。

`LangGraph4jRuntimeAdapter` 在执行 `INTERACTION` 节点时生成 `uiRequest`，支持 `COLLECT_INPUT`、`PRESENT_OUTPUT`、`USER_CHOICE`、`CONFIRM_ACTION`、`REVIEW_EDIT` 等形态，并可进入网关、嵌入式 Chat、RunOps/Trace 和 Studio 调试台。

### 会话式工作流调试台

调试台基于 `Executable Debug Session`，接口集中在 `/api/runtime/debug-sessions`：

- `POST` 创建并启动调试会话。
- `GET /{sessionId}` 恢复消息、节点轨迹、当前状态和 `uiRequest`。
- `POST /{sessionId}/submit` 从挂起点继续执行。
- `POST /{sessionId}/cancel` 取消会话。

后端通过 `executable_debug_session` 持久化会话状态；WAITING 从挂起节点继续，而非整图重跑。

### Runtime Adapter

统一 Runtime 接口当前由 `reachai-runtime-service` 承载：

- `AgentRuntimeAdapter` 是执行契约；`AgentRuntimeRequest` / `AgentRuntimeResult` 是统一请求/响应。
- `LangGraph4jRuntimeAdapter` 是当前 Workflow 主线执行器，读取 binding 解析后的 Workflow `GraphSpec`。
- `AgentScopeRuntimeAdapter` 仍服务自主智能体形态（`agent_kind` / `entry_config_json` 策略侧表达）。
- `WorkflowRuntimeGraphAdapter` 把 Agent + Workflow + 版本快照编译为 Runtime 图上下文。
- `CursorCodeAgentRuntimeAdapter`、`OpenAIAgentsRuntimeAdapter` 当前是不可用占位适配器。

Studio、发布和 RunOps 不绑定单一 Agent 框架；差异由 Agent 入口策略、Workflow `runtime_type` 和 `GraphSpec` 表达。

### 发布与版本

`WorkflowVersionController` 提供 `/api/workflows/{workflowId}/versions`：

- 版本列表、发布校验（`/validate`）、发布、回滚。

`ai_workflow_version` 保存发布快照；Agent 侧不再维护独立版本表。遗留 `/api/agents/{agentId}/versions` 若仍存在，只服务历史数据，新功能应走 Workflow 版本。

### Capability 挖掘与交互能力

`CapabilityMiningController` 同时暴露 `/api/skill-mining` 和 `/api/capability-mining`。Capability 草稿可从 Trace 或 Workflow 画布生成。

交互式能力由 `interaction_definition`、`interaction_session`、`interaction_event` 及 legacy `skill_interaction` 路径共同承载。

### 工作流凭证

`WorkflowCredentialController` 提供 `/api/agent/workflow-credentials`。`agent_workflow_credential` 表供 Studio 节点调用 HTTP、MCP 或外部系统时复用凭证引用。

## 仍待补齐

- Studio 节点类型已很多，仍需要更稳定的用户级操作手册和节点能力矩阵。
- `CursorCodeAgentRuntimeAdapter`、`OpenAIAgentsRuntimeAdapter` 目前仍是扩展边界，不应写成已可用生产 Runtime。
- Agent 列表/编辑页需更清晰呈现 binding 与 Workflow 关系；历史 Agent 画布兼容路由应逐步移除。
- 评测、Capability 挖掘与 Workflow 发布的边界需继续统一口径。
- 交互式 Capability 仍有部分 legacy `skill_interaction` 路径，文档、UI 和代码命名需要继续收敛。
- AI 修改工作流还需继续强化预览优先、局部 patch、差异确认和失败回滚。
- 后续新增节点和 Runtime 行为时，应继续把可执行语义写入 Workflow `GraphSpec`，不能只扩展 `canvas_json` 的前端表现。
