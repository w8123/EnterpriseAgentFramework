# RunOps Trace 到 Workflow 候选闭环

## 目标与边界

该能力把用户在 RunOps 中主动选择的一次成功 Agent 运行，转换为一个可审阅、可调试、可重放的 Workflow 候选。转换结果始终是 `DRAFT`，不会自动发布、不会自动绑定 Agent，也不会改变线上路由。

首批能力只覆盖有明确只读治理证据的单 Workflow 运行。认证、授权、凭据和身份治理不在本闭环内，候选不得新增或修改这些行为。

## 端到端流程

1. 用户在 RunOps 运行详情选择“生成 Workflow 候选”。
2. Control 从 Runtime 实时读取完整 RunOps 详情，执行 fail-closed 资格判定。
3. Control 创建或复用 `TRACE_WORKFLOW_CANDIDATE` AI Coding 任务，并签发标准交接包。
4. AI Coding 客户端读取任务上下文和精确源 Workflow 版本，按 `reachai.trace-workflow-candidate-report/v1` 回传 Artifact。
5. Artifact 应用时，Control 重新读取源 Trace、精确源版本和当前执行路径，不信任创建任务时的脱敏快照作为安全依据。
6. Control 检查节点白名单、节点数量、Tool/Capability 身份和调用数量血缘，然后调用 Runtime 预校验。
7. Runtime 在自身事务和表所有权内创建或替换任务专属 DRAFT，并记录任务、源 Trace、源 Workflow 和源版本元数据。
8. Control 回读 Runtime 已保存的 Workflow，记录 `updatedAt` 修订号和 GraphSpec SHA-256 指纹，并再次执行当前发布校验。
9. 用户通过 Workflow AI Coding 使用代表性输入运行候选；平台只接受候选修订完成后启动、且与当前候选 GraphSpec 指纹一致的成功 Workflow Trace。
10. 平台验证全部通过后，用户才可完成人工验收。发布和 Agent 绑定仍是后续独立显式操作。

## 资格门槛

源 Run 必须同时满足：

- `status=COMPLETED`、`runType=AGENT`、`runtimeType=AGENTSCOPE`；
- 恰好一次 Plan、零次 Replan、恰好一次 Workflow-as-Tool 调用；
- 没有审批、Guard deny、暂停或失败的工具调用；
- 恰好一个成功 `WORKFLOW_TOOL` Span，并带精确 Workflow 版本身份；
- 恰好一个 `SUPERVISOR_TOOL_POLICY / WORKFLOW_TOOL` 决策，且明确记录 `ALLOW`、`readOnly=true`；
- `riskLevel` 不能是 `PAGE_ACTION`、`WRITE` 或 `IRREVERSIBLE`；
- 实际 Workflow 节点执行路径完整且全部成功；
- 源 Workflow 的精确版本快照仍可由 Runtime 读取。

不满足任一条件时，Control 返回具体 blocker，不创建任务。

## Artifact 应用安全边界

首批候选仅允许以下节点类型：

- `USER_INPUT`
- `LLM`
- `TOOL`
- `CAPABILITY`
- `IF_ELSE`
- `VARIABLE_ASSIGN`
- `TEMPLATE`
- `ANSWER`
- `INTENT_CLASSIFIER`
- `VARIABLE_AGGREGATOR`
- `PARAMETER_EXTRACT`

每种候选节点的数量不得超过源 Trace 实际成功路径中的数量。候选 Tool/Capability 引用必须来自实际成功路径，不能删除全部已观测工具、引入新工具，或增加同一工具的调用节点数量。未知类型、别名类型及 `PAGE_ACTION`、`HTTP_REQUEST`、`MCP_CALL`、`KNOWLEDGE_WRITE`、`KNOWLEDGE_RETRIEVAL`、`DOCUMENT_EXTRACT`、`CODE`、`LOOP`、审批和交互型扩权节点均 fail-closed。

这些限制只证明候选没有扩展首批允许的执行表面，不替代 Runtime 发布校验、代表性重放和人工审阅。

## API 与所有权

Control 对控制台提供：

- `GET /api/runops/traces/{traceId}/workflow-candidate/eligibility`
- `POST /api/runops/traces/{traceId}/workflow-candidate/tasks`

Control 调用 Runtime 的内部写入口：

- `POST /internal/runtime/runops/workflow-candidates/drafts`

Control 只拥有资格投影、AI Coding 任务和验收编排；Runtime 拥有 Trace、Workflow、GraphSpec、版本、调试 Run 和候选草稿持久化。Control 不直接写 Runtime 表。

## 幂等与验收

- 同一 Trace、同一执行器的未终态任务由 Control 复用。
- 候选 `keySlug` 带任务作用域；同一任务重试由 Runtime 替换同一个 DRAFT，不产生多个任务草稿。
- Runtime 拒绝复用非 DRAFT、其他项目、其他任务或其他源版本拥有的同名 Workflow。
- `DRAFT_CREATED` 实时要求 Workflow 仍为 DRAFT，且当前 `updatedAt` 修订号与 GraphSpec 指纹都和 Artifact 应用结果一致；即使 GraphSpec 未变，默认模型等执行配置发生修改也会阻断验收。
- `RELEASE_VALIDATION` 每次从 Runtime 实时执行，不复用旧校验结果。
- `CANDIDATE_REPLAY` 只接受在候选修订完成后启动、且与当前 GraphSpec 指纹对应的 `COMPLETED` Workflow Run；旧草稿或旧修订的成功 Run 不能通过新草稿验收。
- 人工验收通过不等于发布；候选仍需显式发布并按正常治理流程决定是否绑定 Agent。

## 验证入口

- Control：`TraceWorkflowCandidateEligibilityTest`、`TraceWorkflowCandidateApplicationServiceTest`、`TraceWorkflowCandidateTaskProviderTest`、`TraceWorkflowCandidateControllerTest`
- Runtime：`RuntimeTraceWorkflowCandidateDraftServiceTest`、`RuntimeTraceWorkflowCandidateInternalControllerTest`
- 前端：`ai-admin-front` 的 `npm run build`
