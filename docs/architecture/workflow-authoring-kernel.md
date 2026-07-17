# Workflow Authoring 统一内核

## 目标

Workflow Studio 网页内 AI 与 Cursor、Codex、Claude Code、外部 CLI 等 AI Coding 客户端共享同一套 Workflow 修改能力。共享的是 GraphSpec 语义修改、候选校验、canvas 投影和并发保存规则；鉴权、自然语言理解和交互形态不强行合并。

## 分层

```text
Workflow Studio（Bearer / 登录态）
  自然语言
    -> WorkflowAuthoringAgentAdapter
    -> AgentScope ReActAgent
    -> 受约束 Authoring Tools（内存候选）
                                      \
                                       GraphSpec Mutation Kernel
                                      /   -> release validation
AI Coding / CLI（X-ReachAI-AiCoding-Key） -> canvas projection
  直接提交结构化 operations               -> revision check / save
```

### 入口适配层

- Workflow Studio 只展示底部“AI 编排”单入口，不再要求用户先选择“生成”或“修改”。已有 GraphSpec、画布选择和自然语言共同决定 operations。
- Workflow Studio 设计期自然语言编排由 **AgentScope Authoring Adapter** 承载：模型负责理解意图、决定节点/边变更、调用受约束工具，并根据结构化工具错误做有限重试。
- AI Coding / CLI 直接读取 context 并提交 operations，不要求再调用平台模型。
- 两个入口保留不同鉴权。外部 `aiCodingKey` 不能替代平台登录态，网页 Bearer 也不能绕过外部 AI Coding guard。
- Runtime Supervisor（已发布 Agent / Workflow-as-Tool 执行）与 Workflow Authoring Agent（设计期候选编排）是两个不同适配器，不得互相复用为同一运行时角色。

### GraphSpec 修改内核

`RuntimeWorkflowGraphMutationService` 是共享语义内核：

- 输入只有当前 GraphSpec 和原子 operations；
- 不调用模型、不鉴权、不执行 Workflow、不直接持久化；
- 在 GraphSpec 深拷贝上原子应用整批操作；
- deep merge 嵌套 patch，禁止修改 node / edge id；
- 检查 edge、entry、finish 引用；
- 删除节点时同步清理关联边和 entry / finish；
- 输出候选 GraphSpec、changedNodes、changedEdges 和摘要。

支持的操作为 `ADD_NODE`、`UPDATE_NODE`、`DELETE_NODE`、`ADD_EDGE`、`UPDATE_EDGE`、`DELETE_EDGE`、`SET_ENTRY`、`SET_FINISH`。

### AgentScope Authoring Tools

`AgentScopeWorkflowAuthoringAgentAdapter` 实现 `WorkflowAuthoringAgentAdapter`，每次请求创建独立内存 session：

| 工具 | 职责 |
| --- | --- |
| `inspect_workflow_context` | 只读返回 workflow、候选 GraphSpec、选择、模型与可用资源摘要 |
| `apply_candidate_operations` | 在候选深拷贝上原子应用 operations；失败返回结构化错误（含 code / operationIndex / retryable） |
| `validate_candidate` | 调用发布级候选校验，返回 valid / errors / warnings |
| `finalize_preview` | 仅当已有候选且最近一次 validation.valid=true 时成功；不保存、不发布、不执行 |

约束：

- `maxIters` 默认 8，候选 mutation 最多 3 次，低 temperature，禁止并行修改同一候选；
- 模型实例使用请求中的 `modelInstanceId`；
- AgentScope 不得直接写 Workflow 数据库，也不得自行判定“结果正确”；
- GraphSpec 是语义真相，canvas 只从最终合法候选投影；
- 返回的 `operations` 是 original → 最终候选的净变更，不是最后一轮修补，也不包含失败 mutation。

分支约定：

- `IF_ELSE`：仅用于可写成确定性 runtime expression / `conditionGroups` 的条件；出边使用 `route:<groupId>`，并提供 `route:else`；
- `INTENT_CLASSIFIER`：用于自然语言语义、意图或主题分类；推荐 `strategy=LLM|HYBRID`、`inputExpression=input`、`defaultRoute=else`，类边使用 `route:<classId>`，拒绝/兜底边使用 `route:else`。

### Proposal 校验与兼容修复

网页 AI 的候选必须通过发布链路同一套 `RuntimeWorkflowReleaseValidationService.validateProposed()`。

- **Workflow Studio `/edit-draft`**：由 AgentScope 读取 mutation / validation 结构化错误后自主修正；确定性内核决定候选能否 finalize。
- **兼容入口 `/generate-draft`**：仍可使用 `RuntimeWorkflowDraftRepairService` 对同一选定模型做最多两轮直接修复轮次。它不是独立“修复模型”，也不再作为 Studio 主链路控制器。

循环只操作内存候选，不保存、不发布、不运行带副作用节点。达到上限仍失败时，返回 `status=FAILED` 和未解决错误，由用户继续修改、重新生成或放弃。

### Canvas 和持久化

- GraphSpec 始终是运行语义真相；`canvas_json` 只从候选 GraphSpec 投影和布局。
- `dryRun=true` 只返回 Proposal。
- `dryRun=false` 仍需 `validation.valid=true` 才能保存；无效 Proposal 返回 `saved=false`，不得落库。
- 保存前使用 `baseRevision` 做乐观并发检查；冲突后必须重新读取 context 并合并。

## AgentScope 与外部编码工具

AgentScope 承载网页内的意图理解、工具选择和有限步骤编排，但只能通过候选工具调用本页内核，不能直接写 Workflow 表。

OpenCode、Codex、Cursor 等属于外部客户端或代码上下文提供者，不应成为 Runtime 的必选依赖。它们通过 Workflow AI Coding API / MCP 复用同一内核即可。

## API 返回契约（edit-draft）

`POST /api/workflows/studio/edit-draft` 返回至少包含：

- `status`: `SUCCEEDED` | `FAILED`
- `provider`: `AGENTSCOPE_AUTHORING`
- `summary` / `operations` / `graphSpec` / `canvasSnapshot`
- `warnings` / `validationErrors`
- `attempts` / `failureCode`

只有 `SUCCEEDED` 且 `validationErrors` 为空的结果可以应用到草稿。失败时不得表现为伪成功预览。

## 兼容入口

- `/api/workflows/studio/generate-draft`：保留给 Page Assistant 等既有调用方的创建兼容入口；GraphSpec-first，执行发布级候选校验和有界直接修复轮次。Workflow Studio 主界面不再单独暴露此入口。
- `/api/workflows/studio/edit-draft`：网页编排主入口；自然语言经 AgentScope Authoring Adapter 与受约束工具进入统一修改内核。
- `/api/workflows/{workflowId}/ai-coding/patch`：外部结构化 patch 入口；直接调用统一修改内核。

兼容路由可以保留，但不得再复制 canvas-first 修改规则。
