# Agent 与 Workflow 解耦重构设计

## 背景

我们已经确认，“项目全局 AI 按钮根据当前页面自动切换能力”不能继续建立在“一个 `AgentDefinition` 同时等于入口 Agent、Workflow、GraphSpec、Studio 画布和发布单元”的模型上。

当前平台里，`LangGraph4j` 从 `AgentDefinition.graphSpec` 读取执行语义。于是一个 `WORKFLOW Agent` 实际就是一个工作流。这个模型能支撑早期 Studio 和 SDK 图注册，但不适合“一个项目入口 Agent，在不同页面、不同意图、不同动作下路由到多个 Workflow”的产品目标。

本次重构不以历史数据兼容为约束。数据库可以按正确模型重建，升级脚本可以明确清理或废弃旧数据。目标是修正领域模型，而不是继续给旧聚合补字段。

## 结论

接受“LangGraph 从 Agent 身上剥离”作为核心方向。

推荐采用“新命名 + 新边界 + 分层复用 + 复制成熟实现后切语义边界”：

- 新写独立的 Agent 入口、Workflow、Binding 领域模型和 API。
- 复用现有 `GraphSpec`、LangGraph4j 执行器核心、Studio 画布转换、发布校验规则、页面动作目录和 Embed 身份边界。
- 不在旧 `AgentDefinitionService` 上继续追加“entry agent / page route / workflow binding”语义。
- 旧 `agent_definition` 相关代码在第一阶段作为兼容读取或旧页面支撑，最终删除或降级为迁移期 facade。

旧 Agent Studio 已经沉淀了成熟的画布体验、节点配置、AI 编辑、调试回放和样式细节。它不是要丢掉的资产。可以直接复制旧前后端实现作为迁移起点，也可以抽取共享组件；关键约束是复制后必须立刻切掉旧 `AgentDefinition` 聚合语义：

- 路由和主键从 `agentId` 切到 `workflowId`。
- 加载/保存从 `/api/agent/definitions` 切到 `/api/workflows`。
- AI 生成/编辑从 `/api/agent/studio/*` 切到 `/api/workflows/studio/*`。
- 调试请求不再要求前端传 `AgentDefinition`，而是传 Workflow 上下文。
- 发布/回滚从 `agent_version` 切到 `ai_workflow_version`。
- Agent 身份、可见性、角色、嵌入策略不进入 Workflow Studio。

也就是说，推荐复制和复用“成熟实现”，不复制“错误领域边界”。

## Graph 分层原则

Graph 这层不能丢。它是 ReachAI 连接后端 SDK、前端页面、AI Coding 工具、Studio 编排和 Runtime 执行的中间语义层。本次重构不是删除 Graph，而是把 Graph 从旧 `AgentDefinition` 聚合里拆出来，分成两类清晰对象。

### 项目资产 Graph

项目资产 Graph 描述项目事实，回答“项目里有什么能力、页面、接口、字段和动作，以及它们如何关联”。

来源包括：

- 后端 SDK 注册的 Capability、参数、DTO、字段和 SDK 图。
- 前端页面注册的 `pageKey`、route、页面字段和 page actions。
- API 资产、Tool 关联、能力组合、业务索引和知识资源。
- Cursor / Codex / Claude Code 等 AI Coding 工具在页面助手接入过程中提交的文件证据、页面动作 manifest 和验证结果。

项目资产 Graph 服务于发现、生成、引用分析和推荐编排。它不是某个入口 Agent 的私有字段。

### Workflow GraphSpec

Workflow GraphSpec 描述可执行流程，回答“AI 要怎么一步步完成任务”。

它包含：

- LLM 节点。
- Tool / Capability 节点。
- PageAction 节点。
- 条件、循环、变量、模板、答案节点。
- 人机交互、审批、调试与运行态输出。

Workflow GraphSpec 归属 `ai_workflow.graph_spec_json`，发布快照归属 `ai_workflow_version.graph_spec_snapshot_json`。`canvas_json` 只保存 Studio 布局，不是运行语义。

### 二者关系

```text
后端 SDK / 前端页面 / AI Coding
        ↓
项目资产 Graph
        ↓ 引用 / 生成 / 辅助编排
Workflow GraphSpec
        ↓
Runtime 执行
```

因此最终边界是：

```text
Agent 不拥有 Graph
Workflow 拥有可执行 GraphSpec
项目资产 Graph 作为 Workflow 编排素材
```

## 当前逻辑审计

### 数据库

`sql/init.sql` 当前把 Agent 和 Workflow 混在 `agent_definition`：

- `agent_definition.agent_mode` 表达产品形态：`AUTONOMOUS / WORKFLOW / CODE / EXTERNAL`。
- `agent_definition.runtime_type` 表达运行时：`AGENTSCOPE / LANGGRAPH4J / ...`。
- `agent_definition.graph_spec_json` 保存 GraphSpec 执行语义。
- `agent_definition.canvas_json` 保存 Studio 画布布局。
- `agent_definition.tools_json / skills_json / model_instance_id / system_prompt / allowed_roles_json` 保存 Agent 治理和运行配置。
- `agent_version.snapshot_json` 冻结的是整个 `AgentDefinition + canvas_json`。

这导致版本发布、运行时选择、画布编辑和入口身份全部围绕 `agent_id`。

已有正向参照是 `capability_composition.graph_spec_json`。它说明 GraphSpec 可以独立于 Agent 存在，平台已有“可执行图不是 Agent 主表字段”的先例。

Embed 和页面动作已经初步形成独立目录：

- `eaf_page_registry`：页面身份、`page_key`、路由、origin、当前页面实例。
- `eaf_page_action_registry`：页面动作、入参/出参 schema、确认策略、`allowed_agent_ids_json`。
- `eaf_embed_session`：会话、`agent_id`、`page_key`、`page_instance_id`、origin、route。
- `eaf_page_action_event`：页面动作请求和回传审计。

这些表应保留并调整语义：页面动作不再“授权给某个 Workflow Agent”，而是授权给项目入口 Agent，再由绑定关系决定具体 Workflow。

### 后端领域类

当前核心聚合：

- `AgentDefinition`
- `AgentDefinitionEntity`
- `AgentDefinitionService`
- `AgentManageController`
- `AgentVersionService`
- `AgentReleaseValidationService`

问题点：

- `AgentDefinition` 同时有身份字段、prompt、model、role、runtime、GraphSpec、canvas、pipeline、knowledge 等字段。
- `AgentDefinitionService.create` 会自动 `bootstrapLangGraphIfEmpty`。
- `AgentDefinitionService.update` 在 `runtimeType` 更新时也可能重置 `graphSpec`，说明运行时选择和流程定义已经耦合。
- `AgentManageController` 的 CRUD、runtime validation、graph node types 都挂在 `/api/agent/definitions`。
- `AgentVersionService.publish` 发布的是整个 AgentDefinition 快照。

### Runtime

当前主链路：

```text
AgentRouter
  -> AgentRuntimeRequest.agentDefinition
  -> AgentRuntimeSelector
  -> AgentRuntimeAdapter
  -> LangGraph4jRuntimeAdapter
  -> definition.getGraphSpec()
```

`AgentRuntimeRequest` 只携带 `AgentDefinition`，没有 Workflow 上下文。因此 Runtime 没有办法表达“同一个 Agent 入口，这次因为 pageKey/action/intent 选择了另一个 Workflow”。

`AgentScopeRuntimeAdapter` 当前适合保留为自主 Agent 运行时。`LangGraph4jRuntimeAdapter` 应从“Agent Runtime Adapter”演进为“Workflow Runtime Adapter”，输入从 `AgentDefinition` 变为 `WorkflowRuntimeRequest` 或包含 `WorkflowDefinition` 的请求。

### SDK 图注册

`AiRegistryService.syncAgentGraphs` 当前把 SDK 上报的 `GraphSpec` 标准化后创建或更新 `AgentDefinition`：

```text
SDK Agent Graph
  -> GraphSpec
  -> AgentDefinition(agentMode=WORKFLOW, runtimeType=LANGGRAPH4J)
```

重构后应改为：

```text
SDK Workflow Graph
  -> WorkflowDefinition(runtimeType=LANGGRAPH4J)
  -> optional AgentWorkflowBinding
```

SDK 图注册不应该再默认创建一个可嵌入 Agent。

### Page Assistant 与 Embed

当前页面助手和 Embed 已经有正确的一部分边界：

- 页面助手负责注册页面和页面动作。
- Embed token 由业务后端证明项目身份后签发。
- 业务前端只拿短期 embed token。
- 页面动作通过当前 session 和 `pageInstanceId` 投递给当前页面实例。

仍然混乱的点：

- 页面动作引用扫描在 `PlatformEmbedOpsController` 中查询 `AgentDefinitionEntity.graphSpecJson`。
- `EmbedChatController.executeMessage` 通过 session 的 `agentId` 解析 `AgentDefinition`，然后直接执行该 Agent。
- `PageActionRegistry.allowed_agent_ids_json` 目前表达“哪些 Agent 可调用动作”，但在新模型下需要表达“哪些入口 Agent 可使用动作”，而具体 Workflow 由 binding 决定。

### 前端

当前前端类型和页面也围绕 Agent：

- `ai-admin-front/src/types/agent.ts`：`AgentDefinition` 和 `AgentForm` 包含 `graphSpec`、`canvasJson`、`runtimeType`。
- `ai-admin-front/src/api/agent.ts`：`createAgent/updateAgent/listAgentVersions/publishAgentVersion/validateAgentRelease` 都以 agentId 为核心。
- `AgentEdit.vue` 同时编辑 Agent 身份和 workflow graph。
- `AgentStudio.vue` 以 `agentId` 加载和保存 `graphSpec/canvasJson`。
- `AgentVersions.vue` 发布和回滚 Agent 版本。
- `PageAssistantWizard.vue` 和 `SdkAccessWizard.vue` 目前靠提示词区分页面助手与项目 SDK 接入，但平台模型仍未提供 Agent -> Workflow 的挂载关系。

## 目标模型

### Agent

Agent 是项目级 AI 入口和治理对象，不再直接等于某个 GraphSpec。

建议新表名：

```text
ai_agent
```

核心字段：

- `id`
- `project_id`
- `project_code`
- `key_slug`
- `name`
- `description`
- `agent_kind`: `PROJECT_ENTRY / AUTONOMOUS / EXTERNAL`
- `visibility`
- `system_prompt`
- `model_instance_id`
- `allowed_roles_json`
- `entry_config_json`
- `enabled`
- `created_at`
- `updated_at`

说明：

- 项目全局 AI 按钮绑定 `PROJECT_ENTRY` Agent。
- AgentScope 自主智能体可以暂时作为 `AUTONOMOUS` Agent 保留。
- Agent 不再有 `graph_spec_json` 和 `canvas_json`。
- Agent 不再有 `runtime_type=LANGGRAPH4J`，因为 LangGraph 属于 Workflow。

### Workflow

Workflow 是可执行流程、Studio 画布和 GraphSpec 的归属对象。

建议新表名：

```text
ai_workflow
```

核心字段：

- `id`
- `project_id`
- `project_code`
- `key_slug`
- `name`
- `description`
- `workflow_type`: `CHAT / PAGE_ASSISTANT / SDK_GRAPH / CAPABILITY_FLOW / SYSTEM`
- `runtime_type`: `LANGGRAPH4J / EXTERNAL / FUTURE`
- `graph_spec_json`
- `canvas_json`
- `input_schema_json`
- `output_schema_json`
- `default_model_instance_id`
- `default_resource_config_json`
- `status`: `DRAFT / ACTIVE / DISABLED`
- `managed_by`: `MANUAL / SDK / PAGE_ASSISTANT / SYSTEM`
- `extra_json`
- `created_at`
- `updated_at`

说明：

- Studio 编辑的是 Workflow。
- LangGraph4j 执行的是 Workflow。
- SDK 图注册创建的是 Workflow。
- 页面助手生成或挂载的是页面能力 Workflow。

### Workflow Version

Workflow 版本是发布单元。

建议新表名：

```text
ai_workflow_version
```

核心字段：

- `id`
- `workflow_id`
- `version`
- `snapshot_json`
- `graph_spec_snapshot_json`
- `canvas_snapshot_json`
- `rollout_percent`
- `status`: `DRAFT / ACTIVE / RETIRED`
- `published_by`
- `published_at`
- `note`

说明：

- `agent_version` 不再发布 GraphSpec。
- Workflow 发布、回滚、RunOps 回放围绕 `workflow_id`。
- Agent 只需要自己的配置版本时，再单独引入 `ai_agent_version`，首期可以不做。

### Agent Workflow Binding

Binding 表达“入口 Agent 在什么上下文下选择哪个 Workflow”。

建议新表名：

```text
ai_agent_workflow_binding
```

核心字段：

- `id`
- `agent_id`
- `workflow_id`
- `project_code`
- `binding_type`: `DEFAULT / PAGE / ACTION / INTENT`
- `page_key`
- `route_pattern`
- `action_key`
- `intent_type`
- `priority`
- `enabled`
- `guard_config_json`
- `metadata_json`
- `created_at`
- `updated_at`

解析规则：

1. 精确 `agent_id + page_key + action_key` 优先。
2. 其次 `agent_id + page_key + intent_type`。
3. 其次 `agent_id + page_key`。
4. 其次 `agent_id + route_pattern`。
5. 最后 `agent_id + DEFAULT`。
6. 同级按 `priority` 降序。

### Page Capability

页面目录和动作目录继续保留，但语义调整：

- `eaf_page_registry` 仍是页面身份目录。
- `eaf_page_action_registry` 仍是页面动作目录。
- `allowed_agent_ids_json` 表达允许哪些入口 Agent 使用该页面动作。
- 页面动作被哪个 Workflow 消费，不再靠扫描 Agent GraphSpec，而是通过 `ai_agent_workflow_binding` 和 Workflow GraphSpec 引用关系共同判断。

后续可以新增只读视图或服务对象：

```text
PageCapabilityView
  page
  actions
  entryAgent
  bindings
  activeWorkflows
  diagnostics
```

## 新写与复用边界

### 必须新写

- 新表：`ai_agent`、`ai_workflow`、`ai_workflow_version`、`ai_agent_workflow_binding`。
- 新领域类：`AgentEntry`、`WorkflowDefinition`、`WorkflowVersion`、`AgentWorkflowBinding`。
- 新服务：
  - `AgentEntryService`
  - `WorkflowDefinitionService`
  - `WorkflowVersionService`
  - `AgentWorkflowBindingService`
  - `AgentWorkflowResolver`
  - `WorkflowRuntimeService`
- 新 API：
  - `/api/agents`
  - `/api/workflows`
  - `/api/workflows/{id}/versions`
  - `/api/agents/{agentId}/workflow-bindings`
  - `/api/embed/agents/{agentId}/resolve-workflow` 内部或调试接口
- 新前端类型：
  - `AgentEntry`
  - `WorkflowDefinition`
  - `WorkflowBinding`
- 新前端 API 文件：
  - `src/api/agents.ts`
  - `src/api/workflows.ts`
  - `src/api/agentWorkflowBindings.ts`

### 应该复用

- `GraphSpec` 后端类型。
- `AgentGraphSpec` 前端类型可以重命名或别名为 `WorkflowGraphSpec`，首期可复用原结构。
- `LangGraph4jRuntimeAdapter` 的节点执行逻辑。
- `AgentGraphNodeType.catalog()` 节点目录，后续可重命名为 `WorkflowGraphNodeType`。
- `LlmWorkflowDraftGenerator`。
- `WorkflowDraftEditService`。
- `graphSpecToCanvas / canvasToGraphSpec` 转换逻辑。
- `AgentReleaseValidationService` 的 GraphSpec 校验主体，抽成 `WorkflowReleaseValidationService`。
- `ExecutableDebugSessionService` 的调试会话思想，输入改为 workflow。
- `PageActionCatalogService`。
- Embed token、session、audit 的身份边界。
- `CompositionRuntimeExecutor` 的“GraphSpec 独立执行”经验。

### 应该废弃或降级

- `agent_definition.graph_spec_json`
- `agent_definition.canvas_json`
- `agent_definition.runtime_type=LANGGRAPH4J`
- `agent_version.snapshot_json` 作为 Workflow 发布快照的做法
- `/api/agent/definitions` 作为 Workflow Studio 的主 API
- `AgentStudio.vue` 直接按 `agentId` 编辑 GraphSpec
- `PlatformEmbedOpsController` 扫描 `AgentDefinitionEntity.graphSpecJson` 查页面动作引用
- SDK 图注册创建 `WORKFLOW Agent`

## Runtime 新链路

目标执行链：

```text
EmbedChatController / Gateway / A2A
  -> AgentEntryService.resolve(agentId or keySlug)
  -> AgentWorkflowResolver.resolve(agent, pageKey, route, actionKey, intentType, message)
  -> WorkflowVersionService.resolveActive(workflowId)
  -> WorkflowRuntimeRequest(agentContext, workflowSnapshot, sessionContext, message)
  -> WorkflowRuntimeSelector
  -> LangGraph4jWorkflowRuntimeAdapter
  -> AgentResult / ChatResponse / UiRequest
```

`AgentRuntimeRequest` 不应继续作为 Workflow 执行的唯一请求。建议新增：

```java
WorkflowRuntimeRequest {
    String traceId;
    String sessionId;
    String userId;
    List<String> roles;
    String message;
    AgentEntry agent;
    WorkflowDefinition workflow;
    WorkflowVersion activeVersion;
    Map<String, Object> pageContext;
    Map<String, Object> metadata;
}
```

`LangGraph4jRuntimeAdapter` 可以先通过适配层复用：

```text
WorkflowRuntimeRequest
  -> temporary AgentDefinition-like execution context
  -> existing LangGraph4jRuntimeAdapter
```

但这是迁移期技巧，不应成为最终领域模型。最终适配器应直接读取 `workflow.graphSpec`。

## API 迁移设计

### Agent API

新 API：

```http
GET    /api/agents?projectId=&projectCode=&kind=
POST   /api/agents
GET    /api/agents/{id}
PUT    /api/agents/{id}
DELETE /api/agents/{id}
```

职责：

- 管理入口 Agent、人设、权限、默认模型、嵌入策略。
- 不返回 `graphSpec`。
- 不提供 Studio graph node types。

### Workflow API

新 API：

```http
GET    /api/workflows?projectId=&projectCode=&type=&managedBy=
POST   /api/workflows
GET    /api/workflows/{id}
PUT    /api/workflows/{id}
DELETE /api/workflows/{id}
POST   /api/workflows/runtime-validation
GET    /api/workflows/graph-node-types
POST   /api/workflows/studio/generate-draft
POST   /api/workflows/studio/edit-draft
POST   /api/workflows/studio/debug-run
POST   /api/workflows/studio/debug-node
```

职责：

- 管理 GraphSpec、canvas、Studio 调试、AI 生成/编辑。
- 不管理入口 Agent 的嵌入策略。

### Workflow Version API

新 API：

```http
GET  /api/workflows/{workflowId}/versions
POST /api/workflows/{workflowId}/versions
POST /api/workflows/{workflowId}/versions/validate
POST /api/workflows/{workflowId}/versions/{versionId}/rollback
```

职责：

- 发布、回滚、校验 Workflow。
- `agentId` 从版本 API 中消失。

### Binding API

新 API：

```http
GET    /api/agents/{agentId}/workflow-bindings
POST   /api/agents/{agentId}/workflow-bindings
PUT    /api/agents/{agentId}/workflow-bindings/{bindingId}
DELETE /api/agents/{agentId}/workflow-bindings/{bindingId}
POST   /api/agents/{agentId}/workflow-bindings/resolve-preview
```

职责：

- 让管理端解释“当前页面/动作/意图会命中哪个 Workflow”。
- 支撑项目详情页闭环状态。

### 旧 API 策略

因为不保留旧数据，旧 API 不需要长期兼容。

建议短期策略：

1. 保留 `/api/agent/definitions` 到新页面切完。
2. 标记旧 Agent Studio 为 deprecated。
3. 新页面和新向导只调用新 API。
4. 完成前端切换后删除旧 CRUD、旧版本发布、旧 SDK 图注册到 Agent 的路径。

## 前端页面改造

### 新页面或重命名页面

- `AgentList.vue`：入口 Agent 列表。
- `AgentEdit.vue`：只编辑入口 Agent 信息，不展示 GraphSpec。
- `WorkflowList.vue`：Workflow 列表。
- `WorkflowStudio.vue`：从当前 `AgentStudio.vue` 拆出，以 `workflowId` 为路由参数。
- `WorkflowVersions.vue`：从 `AgentVersions.vue` 拆出。
- `AgentWorkflowBindings.vue`：入口 Agent 的页面/动作/意图挂载管理。

### 路由建议

```text
/agents
/agents/:agentId/edit
/agents/:agentId/bindings
/workflows
/workflows/:workflowId/studio
/workflows/:workflowId/versions
```

项目详情页增加闭环入口：

- AI 快速接入：创建或选择项目入口 Agent。
- 创建页面助手：注册页面动作，默认绑定到项目入口 Agent。
- 页面能力挂载：查看 pageKey/actionKey 命中的 Workflow。

### 类型迁移

`ai-admin-front/src/types/agent.ts` 拆分：

- `types/agent.ts`：AgentEntry、AgentKind、AgentForm。
- `types/workflow.ts`：WorkflowDefinition、WorkflowGraphSpec、WorkflowForm、WorkflowVersion。
- `types/agentWorkflowBinding.ts`：Binding 类型和 resolve preview 类型。

`AgentGraphSpec` 首期可作为 `WorkflowGraphSpec` 的 alias，避免一次性大改所有节点配置面板。

## SDK 接入向导改造

`SdkAccessWizard.vue` 的“使用 AI 快速接入”逻辑需要同步调整：

- manifest 返回 `embed.entryAgentId` / `embed.entryAgentKeySlug`。
- 文案强调“接入项目全局 AI 入口”，不是“接入某个 Workflow Agent”。
- 业务前端在主布局挂载一个全局 Chat 按钮。
- token broker 每次带 `agentId + pageKey + route + origin + pageInstanceId`。
- 未接入页面助手的页面可以普通对话，但没有页面动作。
- 已接入页面助手的页面通过 binding 自动获得 Workflow 和页面动作。

AI 提示词应明确：

```text
不要为每个业务页面创建不同 AI 按钮。
不要让业务前端选择不同 workflowId。
业务前端只知道 entryAgentKeySlug 和当前页面上下文。
ReachAI 平台根据 pageKey、route、intent、actionKey 解析实际 Workflow。
```

## 页面助手改造

`PageAssistantWizard.vue` 和 `pageAssistantOnboardingPrompt.ts` 的定位应调整为“创建页面能力并挂载到入口 Agent”：

- 页面助手注册 `eaf_page_registry` 和 `eaf_page_action_registry`。
- 页面助手可以创建一个 `PAGE_ASSISTANT` Workflow，或选择已有 Workflow。
- 完成后创建或更新 `ai_agent_workflow_binding`。
- 默认 binding 指向项目入口 Agent。
- 仍然禁止页面助手去修改 SDK、网关、token broker。

页面助手成功标准：

```text
page registered
actions registered
workflow created or selected
binding enabled
current embed session can resolve workflow
page action can dispatch only to current pageInstanceId
```

## SQL 重构策略

因为不保留旧数据，并且下一步会直接使用新数据库，推荐新建一份独立初始化脚本：

```text
sql/init2.sql
```

`init2.sql` 是 Agent/Workflow 解耦后的目标 schema 基线。后续实现和验证优先面向新库 + `init2.sql`，而不是在旧 `init.sql` 上继续叠加大规模兼容改造。

具体策略：

1. 新建 `sql/init2.sql`，包含完整的新库初始化结构，而不是只包含差异。
2. `ai_agent`、`ai_workflow`、`ai_workflow_version`、`ai_agent_workflow_binding` 直接作为新模型主表。
3. 旧 `agent_definition.graph_spec_json`、`agent_definition.canvas_json`、`agent_version` 不进入新模型主路径。
4. 新库里保留仍然有价值的现有能力域表，例如项目注册、能力资产、页面目录、页面动作、Embed 身份和审计表；但它们可以按新模型语义重新整理字段。
5. `sql/init.sql` 暂时作为旧系统基线和参考，不作为本次重构的主要落点。
6. 本轮不写复杂历史迁移，也不尝试把旧 `agent_definition` 数据搬到新表。

`upgrade` 脚本不再作为首选交付物。只有在需要让某个已有开发库过渡到新模型时，才补一份明确破坏性的脚本：

```text
sql/upgrade-20260614-agent-workflow-decoupling.sql
```

该 upgrade 脚本只能用于开发/测试库，语义要写清楚：

- 可以 drop 或重建新模型冲突旧表。
- 不迁移旧 `agent_definition.graph_spec_json`。
- 可以清空旧 Agent/Workflow 相关种子数据。
- 保留或重建项目、能力、页面目录、Embed 事件等仍然需要的表。

注意：

- `sql/README.md` 要新增 `init2.sql` 的使用说明，明确“新数据库请执行 `init2.sql`”。
- 旧 `init.sql` 和 `init2.sql` 并存期间，文档必须说明哪个脚本对应旧模型，哪个脚本对应 Agent/Workflow 解耦后的新模型。
- 后续代码配置需要能指向新库，避免旧库表结构和新代码互相误伤。

## 分阶段落地

### 阶段 1：新模型落库和后端骨架

- 新建 `sql/init2.sql`，作为新数据库的完整初始化脚本。
- 在 `init2.sql` 中新增 `ai_agent`、`ai_workflow`、`ai_workflow_version`、`ai_agent_workflow_binding`。
- 在 `sql/README.md` 中说明 `init2.sql` 的使用方式，以及它和旧 `init.sql` 的边界。
- 新增实体、Mapper、Service。
- 新增 Agent/Workflow/Binding 基础 CRUD。
- 不接入旧 Runtime。

### 阶段 2：Workflow Studio 从 Agent Studio 拆出

- 复制 `AgentStudio.vue` 为 `WorkflowStudio.vue`，但立刻改类型和 API，不保留 `agentId` 语义。
- `AgentEdit.vue` 删除 GraphSpec 编辑职责。
- `AgentVersions.vue` 拆为 `WorkflowVersions.vue`。
- AI 生成/编辑接口迁移到 `/api/workflows/studio/*`。

### 阶段 3：LangGraph 执行器改为 Workflow Runtime

- 新增 `WorkflowRuntimeRequest`。
- 新增 `WorkflowRuntimeService`。
- 将 `LangGraph4jRuntimeAdapter` 改造为读取 Workflow GraphSpec。
- 发布校验从 `AgentReleaseValidationService` 抽为 `WorkflowReleaseValidationService`。

### 阶段 4：Embed Chat 接入 Resolver

- `EmbedChatController` 不再 `resolveAgent -> executeByDefinition`。
- 改为 `resolveAgent -> resolveWorkflow -> executeWorkflow`。
- session metadata 写入 `resolvedWorkflowId`、`bindingId`、`pageKey`。
- 页面动作校验同时检查入口 Agent 授权和 Workflow 节点引用。

### 阶段 5：SDK 图注册和页面助手改造

- SDK 图注册写入 `ai_workflow`。
- 页面助手注册页面动作后创建/更新 binding。
- `SdkAccessWizard.vue` 改为引导全局入口 Agent。
- `PageAssistantWizard.vue` 改为引导页面能力挂载。

### 阶段 6：清理旧模型

- 删除或废弃 `AgentDefinition.graphSpec`。
- 删除旧 `/api/agent/definitions` 中 workflow 相关职责。
- 删除旧 `AgentStudio.vue` 或改为跳转到 `WorkflowStudio.vue`。
- 删除扫描 `AgentDefinition.graphSpecJson` 的页面动作引用逻辑。

## 风险与决策

### 是否整块复制旧 AgentStudio

可以复制作为起点，但必须在第一轮提交就完成命名和 API 边界切换：

- 路由参数必须从 `agentId` 改成 `workflowId`。
- 保存接口必须从 `updateAgent` 改成 `updateWorkflow`。
- 发布接口必须从 `publishAgentVersion` 改成 `publishWorkflowVersion`。
- 页面文案必须从“智能体发布”改成“Workflow 发布”。

否则复制会扩大混乱。

### AgentScope 怎么办

首期保留 AgentScope 在 Agent 层：

- `agent_kind=AUTONOMOUS`
- 使用 `AgentScopeRuntimeAdapter`
- 不进入 Workflow Studio

LangGraph 则只属于 Workflow。这样最符合当前产品语义。

### 是否迁移旧数据

不迁移。

下一阶段默认使用新数据库和 `sql/init2.sql`。开发库和测试库如果确实需要从旧库过渡，可以另行使用破坏性 upgrade SQL 明确清理。真实需要保留历史数据时再单独写一次迁移方案，但本次不把兼容性拖进主设计。

## 验证建议

设计文档阶段：

```powershell
git diff --check -- docs/superpowers/specs/2026-06-14-agent-workflow-decoupling-refactor-design.md
rg -n "REVIEW_MARKER_SHOULD_NOT_EXIST" docs/superpowers/specs/2026-06-14-agent-workflow-decoupling-refactor-design.md
```

实施阶段至少覆盖：

```powershell
rg -n "ai_agent|ai_workflow|ai_agent_workflow_binding" sql/init2.sql
mvn -pl ai-agent-service "-Dtest=*Workflow*,*Agent*,*Embed*,*AiAssist*" test
cd ai-admin-front
npm run build
node scripts/check-page-assistant-prompt.mjs
git diff --check
```

## 成功标准

- 一个项目入口 Agent 可以绑定多个 Workflow。
- 一个 Workflow 可以被不同 Agent 复用，但受 Binding 和权限控制。
- LangGraph4j 不再从 Agent 读取 GraphSpec。
- Studio 编辑的是 Workflow，不是 Agent。
- SDK 图注册生成 Workflow，不生成 Workflow Agent。
- 页面助手注册页面能力，并挂载到项目入口 Agent。
- 全局 AI 按钮只需要 entryAgent 和当前页面上下文，不需要知道 workflowId。
- Embed Chat 能根据 `agentId + pageKey + route + intent/action` 解析出实际 Workflow。
- 页面动作仍然只投递给当前 `pageInstanceId`。
