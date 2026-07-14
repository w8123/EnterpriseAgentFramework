# Agent Supervisor Runtime

本文是 ReachAI Agent 执行主路径、版本模型、Workflow-as-Tool 和 Page Bridge 跨路由协议的架构事实源。产品层说明见 [`../03-Workflow-Studio与Runtime.md`](../03-Workflow-Studio与Runtime.md)。

## Runtime 主路径

```text
runtime_agent
  -> ACTIVE runtime_agent_config_version
  -> enabled runtime_agent_workflow_tool rows (each pins workflow_version_id)
  -> AgentScope Java 2.0.0 GA ReAct Supervisor
  -> pinned runtime_workflow_version GraphSpec snapshots
  -> answer + runtime_run + Trace/Tool/Guard child events
```

`RuntimeAgentExecutionService` 以 Agent 当前 ACTIVE 配置版本及其 Workflow-as-Tool 目录作为唯一可执行工具集。

## 聚合与版本边界

| 对象 | 责任 |
| --- | --- |
| `runtime_agent` | 稳定身份、接入形态、项目归属、可见性和启用状态 |
| `runtime_agent_config_version` | Supervisor runtime、模型、提示词、执行限制、超时和策略的版本快照 |
| `runtime_agent_workflow_tool` | 某个配置版本可选择的 Workflow 工具白名单、契约覆盖和固定 `workflow_version_id` |
| `runtime_workflow` / `runtime_workflow_version` | Workflow 定义和已发布 `GraphSpec` 快照 |

发布新 Agent 配置版本时，旧 ACTIVE 版本退为历史状态；Runtime 只解析当前 ACTIVE Agent 配置版本。发布动作必须为每个启用的 Workflow-as-Tool 解析并写入当时选定的 `runtime_workflow_version.id`。执行时直接读取这个固定版本，不能再次解析 Workflow 当前 ACTIVE 版本。工具行随 Agent 配置版本复制和发布，确保一次 Agent 执行的工具集合与每个 Workflow 的 `GraphSpec` 都是不可变快照；Workflow 发布新版本后，必须发布新的 Agent 配置版本才能使用。

Agent 身份 API 与 Supervisor 配置 API 是两条独立写路径：`POST/PUT /api/agents` 只维护 `runtime_agent` 的稳定身份字段，不接收提示词、模型或扩展配置；这些运行设置只能通过配置草稿 API 写入。项目接入自动 provisioning 也必须先创建/复用 Agent，再单独创建并发布配置版本，禁止一次请求同时写两个聚合边界。

配置版本生命周期为 `DRAFT -> ACTIVE -> ARCHIVED`。ACTIVE 和 ARCHIVED 都是不可变快照；`POST /api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft` 会把历史快照及其完整工具目录复制为新的单一 DRAFT，再由用户编辑和发布。

## 产品与 API 语义

- 产品对象统一称为 **Agent / 智能体**；项目中不存在第二套“入口实体”模型。
- Agent 是通用 Supervisor 聚合根，不再按接入形态分类；页面副驾驶只是由项目接入流程按稳定 keySlug 自动创建或复用的 Agent。
- Agent 执行请求只使用 `agentId`（允许值为 Agent id 或 keySlug），不再接受 `agentDefinitionId` 别名。
- Agent 与 Workflow 不存在静态 binding、强制路由或 binding 故障降级。唯一关系是已发布配置版本中的 Workflow-as-Tool 白名单。
- Workflow 仍是独立的可发布业务能力；Agent 是理解、规划、工具选择和回答聚合的运行主体。

## 管理端交互

- Agent 列表显示接入形态、Supervisor 配置状态/版本和已发布 Workflow 工具数，并提供编辑、调试、评测和 RunOps 操作。
- Agent Supervisor 工作台分为“Agent 身份与接入”和“Supervisor 运行配置”；工具目录支持名称、风险、权限键、Schema 覆盖、启停和排序。
- 配置版本抽屉可查看 DRAFT/ACTIVE/ARCHIVED 历史并把不可变快照复制为草稿。
- Agent 调试只有 Supervisor Agent 执行，不再提供 Lightweight Chat 或独立“流式对话”模式。调试请求统一走 `/api/runtime/agents/execute/stream`，实时呈现 `supervisor.step`，并在完成时保留完整结果、Trace、UI 请求和会话 ID；RunOps 单独呈现 PLAN、REPLAN、WORKFLOW_TOOL 和配置版本指标。
- Agent Eval 通过 `RuntimeAgentExecutionService` 执行已发布配置，断言 zero/single/multi Workflow、有限重规划、页面动作、策略决策、UI 请求、Trace 和最终回答，不再评测一份脱离发布状态的 GraphSpec 副本。
- “创建页面助手”完成后同时提供 Workflow Studio、Agent 工作台和 RunOps 三个后续入口。

## RunOps 运行事实边界

`runtime_run` 是每次用户或外部入口执行的根事实。Agent 即使直接回答、没有调用任何 Workflow 或 Tool，也必须先创建一行 Run；独立 Workflow 执行同样创建 Run。RunOps 列表、KPI、趋势、故障诊断、版本对比和回放都从该表开始，不能再通过 Tool 日志或 Span 反推“是否发生过一次运行”。

`runtime_trace_span`、`runtime_tool_call_log` 和 `runtime_guard_decision_log` 都是通过 `trace_id` 归属 Run 的子事件：

- `runtime_trace_span` 表达 Supervisor、PLAN、REPLAN、Workflow-as-Tool、Workflow Graph 节点和 LLM 等父子执行链路。
- `runtime_tool_call_log` 表达底层 Tool / Capability 的实际调用审计。
- `runtime_guard_decision_log` 表达 ALLOW、DENY、REQUIRE_CONFIRMATION 等策略决策。

Run 类型只有 `AGENT`、`WORKFLOW`。状态为 `RUNNING`、`SUCCESS`、`FAILED`、`WAITING_APPROVAL`、`CANCELLED`、`TIMEOUT`；其中 `WAITING_APPROVAL` 表示本次执行已暂停并等待人工确认。入口类型为 `DEBUG`、`EMBED`、`GATEWAY`、`API`、`EVAL`、`REPLAY`、`WORKFLOW_STUDIO`、`A2A`。Tool 不是根运行类型。

创建 Run 时就固定 Agent 配置版本、Workflow 发布版本、身份与入口上下文，并把审计快照写入 `snapshot_json`。回放创建新的 `entry_type=REPLAY` Run，通过 `replay_of_trace_id` 指向来源，严格使用来源 Run 的历史 Agent 配置和所有固定 Workflow 版本；历史配置或固定版本不可解析时应明确失败，不能静默切换到当前 ACTIVE 配置。

## 规划与策略边界

AgentScope Supervisor 必须在首次 Workflow 调用前记录 PLAN。Workflow 失败后必须先记录 REPLAN 才能继续调用。默认上限为 6 个计划步骤、4 次 Workflow 调用和 2 次重规划；默认总超时 120 秒、Workflow 超时 60 秒、Page Bridge 超时 20 秒。

只读工具允许并行，写工具串行。所有工具调用先进入 `SupervisorToolPolicyService`，决策顺序为：ACTIVE 配置工具白名单与启停状态 -> project -> tenant -> Agent allowed roles -> permissionKey 角色映射 -> risk level。

- `READ`：校验通过后自动执行。
- `PAGE_ACTION`：还必须从本轮用户原始问题中识别到明确的打开、跳转或页面操作意图；外部请求不能用布尔标记伪造该意图。
- `WRITE`：创建一次性确认交互，确认凭证精确绑定 interaction、permissionKey、toolName 和 args，提交后不可重放。
- `IRREVERSIBLE`：默认拒绝；只有配置 `policy.irreversibleMode=CONFIRM` 时才允许进入强确认。

`DEV_ALLOW_ALL` 只在研发期跳过 tenant allowlist 与 permission-role 映射，不跳过工具白名单、项目、Agent allowed roles、页面显式意图、写确认和不可逆默认拒绝。所有 ALLOW / DENY / REQUIRE_CONFIRMATION 决策都写入 Guard 与 Trace。

## 页面动作边界

事实查询优先 API/数据 Workflow。只有用户明确要求打开、跳转、在页面查询或操作时，Supervisor 才能选择页面动作 Workflow。

跨路由页面动作必须在同一 embed session 内完成 `NAVIGATE -> TARGET_READY -> PAGE_ACTION`。Control 拥有 `control_embed_session`、`control_page_registry` 和 `control_page_action_event`；Runtime 只能通过 `/internal/control/page-bridge/execute` 协作，不能直接读写这些表。

## 演进约束

- 新 Agent runtime 可以实现 `SupervisorRuntimeAdapter`，但必须复用 ACTIVE Agent 配置版本与 Workflow-as-Tool 目录。
- 新策略必须复用统一 policy/trace 决策点。
- 新页面协议阶段必须保持 session、project、Agent 和 page instance 的一致性校验。
- 新 Workflow 节点语义必须进入 `GraphSpec` 和发布快照。
