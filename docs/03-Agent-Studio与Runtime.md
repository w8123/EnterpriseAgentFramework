# Agent Studio 与 Runtime

## 定位

Agent Studio 是把能力资产组织成可发布 Agent 的核心工作台。Runtime 层负责把 Agent 定义转成可执行请求，并允许不同运行时以统一接口接入。当前主线是：Agent Definition -> 统一 GraphSpec -> AI 生成/修改工作流 -> 版本发布 -> Runtime Adapter -> Trace/RunOps。

这一层的产品亮点不是单纯“画流程图”，而是把可视化画布、AI 生成工作流、AI 修改工作流、SDK 图注册、发布校验和 Runtime 执行都收敛到统一 Graph 层。当前同时支持两类智能体形态：基于 AgentScope 的 `AUTONOMOUS` 自主智能体，以及基于 `GraphSpec`/LangGraph4j 的 `WORKFLOW` 工作流类智能体。

## 当前已落地

### Agent 定义与接口

`AgentManageController` 提供 `/api/agent/definitions`：

- 列表、详情、新建、更新、删除 Agent。
- `/runtimes` 暴露可用 Runtime。
- `/graph-node-types` 暴露 Studio 节点类型。
- `/runtime-validation` 做运行时配置校验。

`agent_definition` 当前关键字段：

- `agent_mode`：`AUTONOMOUS / WORKFLOW / CODE / EXTERNAL`。
- `tools_json`、`skills_json`：历史白名单字段。
- `tool_refs_json`、`skill_refs_json`：稳定引用字段。
- `runtime_type`、`runtime_placement`、`runtime_config_json`、`default_resource_config_json`：Runtime 选择和配置。
- `canvas_json`：Studio 画布布局。
- `graph_spec_json`：平台 GraphSpec，是运行时语义的核心。
- `allow_irreversible`：不可逆 Tool 调用闸口。
- `project_id`、`project_code`、`visibility`：项目隔离。

管理端入口是 `AgentList.vue`、`AgentEdit.vue`、`AgentStudio.vue`、`AgentVersions.vue` 和 `AgentDebug.vue`。

### 统一 Graph 层

统一 Graph 层的核心类型是 `AgentGraphSpec`，后端定义在 `ai-agent-service/src/main/java/com/enterprise/ai/agent/graph/AgentGraphSpec.java`，前端类型定义在 `ai-admin-front/src/types/agent.ts`。它不是画布快照的附属字段，而是 Agent 可执行语义的中间表示。

当前落地点：

- `agent_definition.graph_spec_json` 保存运行时语义，`agent_definition.canvas_json` 保存 Studio 画布布局，两者分离，避免把执行语义只写进前端画布。
- `AgentManageController` 的 `/api/agent/definitions/graph-node-types` 由 `AgentGraphNodeType` 输出节点能力目录，前端 Studio 以此渲染可用节点。
- `AgentReleaseValidationService` 把 `GraphSpec` 当作发布契约，校验节点、边、入口、LLM 节点、Capability 引用、条件边、变量映射和可达性。
- `AgentVersionService` 发布时把 `GraphSpec` 节点数、边数等写入版本元数据，RunOps/Trace 回放可以基于发布快照追溯执行路径。
- `AiRegistryService` 支持 SDK 注册图能力，把 SDK 上报的图标准化为 `WORKFLOW` 模式的 `GraphSpec`，并从 `GraphSpec` 生成 Studio 可展示的 `canvas_json`。

### Studio 画布

`AgentStudio.vue` 已经承载画布编辑、调试、发布、Trace 回放、Capability 提取和评测入口。节点配置拆到 `ai-admin-front/src/views/agent/studio-panels/`，包括：

- LLM、Tool、Capability、MCP 调用、HTTP 请求。
- 条件、循环、变量聚合、变量赋值、参数提取、意图分类。
- 知识检索、知识写入、文档抽取。
- 用户输入、人工审批、最终回答、代码节点。
- 凭证选择组件 `CredentialSelect.vue`。

`AgentStudioDebugController` 支持 `/api/agent/studio/debug-node` 和 `/api/agent/studio/debug-run`。`AgentStudioDraftController` 支持 `/api/agent/studio/generate-draft` 和 `/api/agent/studio/edit-draft`。

AI 生成工作流走 `/api/agent/studio/generate-draft`，由 `LlmWorkflowDraftGenerator` 调用模型生成严格 JSON 节点和边，再标准化为 `AgentGraphSpec` 与 `canvasSnapshot`。前端以预览/应用方式进入画布，避免模型输出直接覆盖当前流程。

AI 修改工作流走 `/api/agent/studio/edit-draft`，由 `WorkflowDraftEditService` 基于当前 `canvas`、用户修改意图和选中上下文生成新的 `AgentGraphSpec` 与画布快照。当前设计重点是围绕当前工作流做局部修改，尤其适合“把这个节点后面加审批”“把某段流程改成条件分支”“替换选中节点的工具调用”等 Studio 内编辑场景。

### Runtime Adapter

统一 Runtime 接口集中在 `ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime`：

- `AgentRuntimeAdapter` 是执行契约。
- `AgentRuntimeRequest` 和 `AgentRuntimeResult` 是统一请求/响应。
- `AgentRuntimeSelector` 根据 Agent 定义和策略选择 Runtime。
- `AgentRuntimeCapability` 描述 Runtime 是否可用、支持的模式和配置能力。
- `AgentRuntimePolicy` 处理运行时可用性和策略校验。
- `AgentScopeRuntimeAdapter` 支持 `AUTONOMOUS` 自主智能体，是默认 `runtime_type=AGENTSCOPE` 的执行适配器。
- `LangGraph4jRuntimeAdapter` 是当前 `WORKFLOW` 工作流类智能体的主要图执行器，读取 `GraphSpec`，支持条件边、变量映射、节点输出、MCP 调用、人工审批等节点。
- `CursorCodeAgentRuntimeAdapter` 和 `OpenAIAgentsRuntimeAdapter` 当前是不可用占位适配器，用于保留扩展边界。
- `RuntimeRegistryController` 和 `RuntimeRegistry.vue` 展示 Runtime 纳管状态，并提供嵌入式 Runtime dispatch 入口。

这意味着 Studio、发布和 RunOps 不需要绑定某一个 Agent 框架。自主智能体和工作流智能体都挂在 `AgentDefinition`、`AgentRuntimeAdapter`、`AgentRuntimeRequest`、`AgentRuntimeResult` 这组统一契约下，差异由 `agent_mode`、`runtime_type` 和 `GraphSpec` 表达。

### 发布、版本和评测

`AgentVersionController` 提供 `/api/agents/{agentId}/versions`：

- 版本列表、发布校验、发布、回滚。
- `/events` 查询发布治理审计事件。

`agent_version` 保存发布快照，`agent_release_event` 保存发布审计。`AgentVersionService` 会把 `GraphSpec` 节点数、边数等写入元数据，发布快照是 RunOps 追溯和回滚的基础。

评测能力由 `agent_eval_dataset`、`agent_eval_case`、`agent_eval_run`、`agent_eval_case_result` 承载，前端入口在 `AgentStudio.vue` 顶部“评测”按钮，接口封装在 `ai-admin-front/src/api/agentEval.ts`。

### Capability 挖掘与交互能力

`SkillMiningController` 同时暴露 `/api/skill-mining` 和 `/api/capability-mining`，推荐使用 Capability 语义。当前支持：

- 运行前检查。
- 从 Trace 或画布生成草稿。
- 草稿列表、状态更新和发布。
- Demo Trace 生成和清理。

交互式能力由 `skill_interaction`、`InteractiveFormSkill`、`InteractiveFormSkillExecutor`、`AgentInteractionController`、`SkillController` 的 pending interaction 管理接口承载。代码名仍有 `Skill`，但产品含义是需要挂起、补槽、确认、恢复的交互式 Capability。

### 工作流凭证

`WorkflowCredentialController` 提供 `/api/agent/workflow-credentials`，`WorkflowCredentialService` 负责按项目或全局范围解析凭证。`agent_workflow_credential` 表用于 Studio 节点在调用 HTTP、MCP 或外部系统时复用凭证引用，避免把密钥写进画布。

## 仍待补齐

- Studio 节点类型已经很多，仍需要更稳定的用户级操作手册和节点能力矩阵。
- `CursorCodeAgentRuntimeAdapter`、`OpenAIAgentsRuntimeAdapter` 目前仍是扩展边界，不应写成已可用生产 Runtime。
- `tools_json / skills_json` 与 `tool_refs_json / skill_refs_json` 并存，后续要继续向稳定引用收敛。
- 评测目前用于草稿和发布前判断，不应自动修改画布或绕过人工发布决策。
- 交互式 Capability 的存储仍使用 `skill_interaction` 等 legacy 名称，文档和 UI 需要继续统一口径。
- Studio UI 还需要更明确地区分“AgentScope 自主智能体”和“Workflow 工作流类智能体”，让用户创建、编辑、发布时能直观看到当前形态。
- AI 修改工作流还需要继续强化预览优先、局部 patch、差异确认和失败回滚，避免复杂流程被整图替换。
- 后续新增节点和 Runtime 时，应继续把可执行语义写入 `GraphSpec`，不能只扩展 `canvas_json` 的前端表现。
