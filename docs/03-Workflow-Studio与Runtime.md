# Workflow Studio 与 Runtime

## 定位

ReachAI 将 Agent、Workflow 和 Runtime 分成三个清晰层次：

- **Agent**（`runtime_agent`）：稳定的智能体聚合根，承载身份、入口、项目范围和启用状态。
- **Agent 配置版本**（`runtime_agent_config_version`）：承载 Supervisor runtime、模型、提示词、规划/调用上限、超时和策略配置；只有已发布版本可执行。
- **Workflow**（`runtime_workflow`）：可复用的确定性业务能力，运行语义是 `GraphSpec`，画布布局是 `canvas_json`。
- **Workflow-as-Tool**（`runtime_agent_workflow_tool`）：按 Agent 配置版本维护可被 Supervisor 选择的已发布 Workflow 白名单及工具契约。

主执行链路是：

`Agent` → 已发布 Agent 配置版本 → AgentScope Supervisor → 规划/选工具 → 一个或多个已发布 Workflow `GraphSpec` → 汇总回答。

## Agent Supervisor

当前 Supervisor 运行时位于 `reachai-runtime-service`，实现为 `AgentScopeSupervisorRuntimeAdapter`，依赖 AgentScope Java `2.0.0` 正式版的 `ReActAgent`。`RuntimeAgentExecutionService` 解析 Agent、当前 ACTIVE 配置版本和该版本的 Workflow 工具目录。

Supervisor 的职责边界：

1. 理解用户真实意图并记录短计划。
2. 从版本化白名单选择零个、一个或多个 Workflow。
3. 将 Workflow 结果组合成最终回答。
4. Workflow 失败后先记录修订计划，再有限重规划。
5. 对事实查询优先选择 API/数据 Workflow；只有用户明确要求“打开、跳转、在页面上查询或操作”时，才允许选择页面动作 Workflow。

默认执行限制：

| 配置 | 默认值 |
| --- | ---: |
| 最大计划步骤 | 6 |
| 最大 Workflow 调用次数 | 4 |
| 最大重规划次数 | 2 |
| 总执行超时 | 300 秒 |
| 单 Workflow 超时 | 180 秒 |
| Page Bridge 超时 | 30 秒 |

只读 Workflow 可按 `parallel_read_only` 允许并行工具调用；写操作使用串行锁执行。每次调用都经过 `SupervisorToolPolicyService` 并写 Guard/Trace：READ 自动执行，PAGE_ACTION 要求用户原始问题中存在明确页面意图，WRITE 进入一次性确认，不可逆操作默认拒绝。`DEV_ALLOW_ALL` 仅跳过研发期 tenant allowlist 与 permission-role 映射，不绕过风险边界。

## Agent 配置与 Workflow-as-Tool

管理 API：

- `GET /api/agents/{agentId}/config-versions`：查看配置版本。
- `PUT /api/agents/{agentId}/config-versions/draft`：保存下一版草稿及其 Workflow 工具白名单。
- `POST /api/agents/{agentId}/config-versions/{configVersionId}/publish`：发布并激活配置版本。
- `POST /api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft`：把不可变历史快照复制为新草稿。
- `GET /api/workflows/search`：为 Agent 工具选择器提供服务端分页搜索；关键词覆盖 Workflow 名称、keySlug、描述、项目编码和类型。

`POST/PUT /api/agents` 只维护 Agent 身份、接入形态、项目范围、角色和启用状态；提示词、模型、Supervisor 限制与工具目录不得通过身份 API 写入。执行请求统一使用 `agentId`，不保留 `agentDefinitionId` 别名。

管理端 `AgentEdit.vue` 维护 Supervisor 配置和 Workflow 工具，并通过配置版本 API 发布新版本。Agent 主表保持稳定，提示词、模型、限制和工具集合通过配置版本演进，避免把每次配置调整写回身份主表。

每个 Workflow 工具可以覆盖名称、描述、输入/输出 schema，并声明 `risk_level`、`permission_key`、`read_only`、启用状态和优先级。Runtime 只装载 ACTIVE Workflow 的 ACTIVE 版本快照；草稿或没有可执行版本的 Workflow 不会被暴露给 Supervisor。

## Workflow Studio 与 GraphSpec

Workflow Studio 是唯一的画布编辑器：

- `runtime_workflow.graph_spec_json` 保存运行语义。
- `runtime_workflow.canvas_json` 只保存画布布局。
- `WorkflowReleaseValidationService` 校验节点、边、入口、变量映射、Capability 引用和可达性。
- 发布后的 `runtime_workflow_version.graph_spec_snapshot_json` 是 Supervisor 调用时的执行事实源。
- AI 生成使用 `POST /api/workflows/studio/generate-draft`。
- AI 编排使用 `POST /api/workflows/studio/edit-draft`：AgentScope Authoring Adapter 通过受约束工具修改内存候选，确定性内核负责 mutation / validation；仅 `status=SUCCEEDED` 可应用到草稿。

新增节点或 Runtime 行为必须把可执行语义写入 Workflow `GraphSpec`，不能只扩展前端画布。Workflow 仍可独立调试、发布、回滚和回放；被 Agent 使用时，它是 Supervisor 的受控工具，而不是静态入口路由。

## Page Bridge 跨路由协议

页面动作由 `reachai-control-service` 拥有会话和命令状态，Runtime 通过 internal API 调用，不跨服务直写 Control 表。跨路由动作遵循同一会话内的完整协议：

1. 校验 ACTIVE embed session、项目和 Agent 身份一致。
2. 如果目标页面不同，向当前 Page Bridge 投递 `NAVIGATE` 命令。
3. 等待导航完成，并取得目标页面返回的 `pageInstanceId`；未返回时，等待目标 Page Bridge 的新注册实例。
4. 将同一 session 更新到目标 `pageKey/pageInstanceId/route`。
5. 再向目标页面实例投递真实 Page Action，并等待成功、失败或超时。
6. `NAVIGATE`、`TARGET_READY`、`PAGE_ACTION` 分阶段进入 Page Action Event 与 Trace。

导航和动作共享一个截止时间，不会在目标页面未就绪时提前执行。业务页面必须在路由切换后重新注册 Page Bridge；导航结果若已明确提供新实例，Control 不会再无条件等待注册表查询。

## Trace 与会话

Supervisor 使用调用方 `sessionId` 保持多轮会话；未提供时生成运行时会话标识。Trace 至少记录：

- Agent 与激活配置版本；
- PLAN / REPLAN；
- Workflow、版本、参数、耗时、结果与失败码；
- Guard/Policy 决策；
- Page Bridge 分阶段事件；
- 最终答案和模型元数据。

RunOps 和 Trace 应以这些记录解释“为什么选择这个 Workflow、调用了哪些 Workflow、为何重规划”。

## 兼容面

- `/api/runtime/agents/execute`、`/api/runtime/agents/execute/detailed` 及既有兼容 alias 进入 Supervisor 主路径。
- Agent 历史画布入口不再恢复；GraphSpec、画布和 Workflow 版本始终归属 Workflow。
- `LangGraph4jRuntimeAdapter` / `RuntimeGraphSpecExecutor` 负责单个 Workflow 执行；AgentScope 负责上层理解、规划、选择和有限重规划。
