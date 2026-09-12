# Workflow INTERACTION Runtime

本文定义 Workflow `INTERACTION` 节点的运行契约。Studio Debug、Agent Workflow-as-Tool、Embed 公开提交与 RunOps 共用这套语义；当前仅 `PRESENT_OUTPUT` 对外开放，阻塞交互仍需完成第 10 节的真实端到端验收。

## 1. 运行语义

`INTERACTION` 是 GraphSpec 可执行节点。Runtime 唯一事实源是 `RuntimeGraphSpecExecutor`，由节点 `config.interactionType` 决定是否暂停：

| Type | 是否暂停 | component（canonical） | 提交后行为 |
| --- | --- | --- | --- |
| `COLLECT_INPUT` | 是 | `form` / `text_question` | 校验字段后写入 `outputAlias` / binding，继续下游 |
| `USER_CHOICE` | 是 | `select` / `choice` / `multi_select` | 只接受声明选项；输出 `route`/`selected` |
| `CONFIRM_ACTION` | 是 | `confirm` | `confirm`/`reject`/`cancel` 稳定 route；reject/cancel 不执行受保护下游 |
| `PRESENT_OUTPUT` | 否 | `table`/`detail`/`summary_card`/`output_card` | 发出非阻塞 display `uiRequest`，立即继续 |
| `CUSTOM` | fail-closed | 受支持 `rendererKey` 才可发布 | 禁止任意 HTML/JS |

暂停时返回码：`RUNTIME_GRAPH_INTERACTION_WAITING`。执行状态语义：

- `COMPLETED`：`success=true`
- `SUSPENDED` / `WAITING_USER`：waiting 码
- `FAILED`：其它失败码
- `CANCELLED`：`RUNTIME_GRAPH_CANCELLED`

## 2. Pause / Resume 状态机

生产 session（`runtime_interaction_session`）与 Debug session 共用逻辑状态：

```
WAITING_USER -> RESUMING -> COMPLETED
WAITING_USER -> RESUMING -> WAITING_USER   # 下一交互
WAITING_USER -> CANCELLED
WAITING_USER -> EXPIRED
RESUMING -> FAILED
RESUMING -> WAITING_USER                   # 校验失败回滚等待（不消费幂等键）
```

并发控制：

- `revision` 乐观锁 / CAS：`UPDATE ... WHERE id=? AND status='WAITING_USER' AND revision=?`
- 同一 `idempotency_key` 重放返回同一结果，不重复执行下游
- 不同 payload 的重复提交返回冲突
- 校验失败不得把 session 卡在 `RESUMING`

## 3. uiRequest 协议

后端 `WorkflowInteractionUiRequest` 是唯一生成源。字段至少包括：

- `protocolVersion`（当前 `1.0`）
- `interactionId`（前缀 `wfi_`；Supervisor 审批仍为 `spv_`）
- `type`（InteractionType）
- `component`
- `workflowId` / `workflowVersionId`
- `nodeId`
- `runId` / `traceId`
- `title` / `message`
- `fields` / `options`
- `prefilled` / `data` / `summary` / `schema`
- `actions`
- `behavior`
- `ttlSeconds` / `expiresAt`
- `extension`

前端经 `normalizeUiRequest` 消费；Debug / Agent / Embed 不得各自拼装不同结构。

多选使用数组协议：`USER_CHOICE` 的 `component=multi_select` 接收 `values.value` 数组；`COLLECT_INPUT` 的字段 `type=multi_select` 接收同名字段数组，并要求发布配置声明非空 `options`。执行时逐项检查选项，标量或未声明选项保持等待；必填表单多选拒绝空数组。校验失败不消费幂等键，修正后可用同一个键继续提交。字段校验通过不改变第 10 节的阻塞交互开放门槛。

## 4. Debug 与生产边界

| | Studio Debug | 生产 Agent/Embed |
| --- | --- | --- |
| Session 表 | `runtime_executable_debug_session` | `runtime_interaction_session` |
| API | `/api/runtime/debug-sessions` create/stream/submit/restore | Embed：`/chat/sessions/{id}/interactions/{interactionId}/submit[/stream]` |
| GraphSpec | 草稿快照 | 暂停时已发布版本 GraphSpec snapshot |
| 恢复入口 | Debug submit（带 interactionId + idempotencyKey） | Control 代理 → Runtime Agent execute / InteractionResume |

两者共用 Executor 与 uiRequest 工厂；生产不得读取“最新 Workflow”覆盖 snapshot。

## 5. 调用链

```
Studio Debug:
  Frontend -> Control/Runtime debug-sessions
           -> RuntimeExecutableDebugSessionService
           -> RuntimeWorkflowDebugService
           -> RuntimeGraphSpecExecutor
           -> (WAITING) ui.requested + turn.waiting
  submit -> __interactionResume{interactionId,nodeId,values}
         -> executeFromNode(currentNodeId)

生产 Agent/Embed:
  Embed/Public chat -> RuntimeAgentExecutionService
                    -> AgentScopeSupervisorRuntimeAdapter
                    -> RuntimeGraphSpecExecutor (published snapshot)
                    -> WAITING: 创建 runtime_interaction_session + uiRequest
                    -> Supervisor 透传 uiRequest，不包装为 Tool failure
  submit(interactionId) -> RuntimeInteractionResumeService
                        -> CAS WAITING_USER->RESUMING
                        -> 校验 WorkflowCheckpointV1 / Legacy 路由
                        -> executeFromCheckpoint(snapshot, nodeId, resume, engineVersion)
                        -> 同 root run/trace 继续
```

Control 只做公开入口、鉴权与代理；不得读写 Runtime 表。

## 6. Run / Trace 连续性

- 暂停：交互会话为 `WAITING_USER`，`runtime_run.status=SUSPENDED`，暂停原因为 `USER_INPUT`；根 Run 和等待中的 Span 不提前终结
- Supervisor policy confirmation 与 Workflow 输入等待通过暂停原因区分，不把 `WAITING_APPROVAL` / `WAITING_USER` 混入根 Run 状态枚举
- 恢复：同一 `trace_id` / root run；追加节点 span
- 根 Run 成功 → `COMPLETED`；取消 → `CANCELLED`；超时 → `TIMED_OUT`；失败 → `FAILED`。交互会话自己的过期状态为 `EXPIRED`
- Replay：等待中的 interaction **禁止**自动伪造输入或跳过；默认拒绝 replay，提示提供替代输入后创建关联新 run

## 7. 幂等、并发、超时、取消、权限

- 目标节点消费：resume payload 必须匹配 `interactionId` + `nodeId`；消费后立即清除，禁止泄漏到下一 INTERACTION
- 过期：`expires_at` 后提交返回 `expired`
- 取消：session/run → `CANCELLED`，不执行下游
- 权限：校验 app/tenant/user/chat session 与 interactionId 归属；仅知道 interactionId 不能跨会话提交
- 敏感字段经现有 redaction；event 不写 secret/token 原文

## 8. GraphSpec snapshot 原则

生产 session 持久化：

- `source_type`、`workflow_id`、`workflow_version_id`
- `graph_spec_snapshot_json`
- `node_id`、`resume_checkpoint_json`、`ui_request_json`
- `checkpoint_schema_version`、`execution_engine_version`、`checkpoint_digest`、`checkpoint_size_bytes`
- `run_id` / `trace_id`
- 所有权：`app_id` / `tenant_id` / `session_id` / `user_id`
- `status` / `revision` / `idempotency_key` / `expires_at`
- Supervisor continuation（如需要）写入 `continuation_json`

Supervisor Workflow 续跑契约使用 `continuationSchemaVersion=3`，除计划、游标和固定发布身份外，还保存 `executionCallCounts` 中的 `workflow`、`a2a`、`managed` 累计调用次数。恢复入口要求精确非负整数，核对已完成与等待中的 Workflow 调用，并按固定配置的总调用预算校验。恢复值通过服务内部参数进入执行状态，公开请求体不能提供这份可信状态。旧格式缺少累计预算，明确拒绝，不推测或从零恢复。

RunOps 接受部分完成结果：未上报的规划、Workflow 次数和 Token 总量保留原值，明确上报的 0 仍有效。部分结果的安全元数据合并到原记录；调用／治理审计缺失时不倒扣此前已记录的计数。完整指标维持无需聚合查询的路径，部分指标仅执行一次聚合读取；终态仍受条件更新保护，迟到结果不能改写。

恢复只使用暂停时 snapshot，不重新读最新草稿/发布版。

本次提交的 `context` 不覆盖已保存的检查点。续跑业务输入只能通过目标交互节点声明并校验的 `values` 与 `action` 进入，避免在检查点摘要校验完成后替换暂停前的业务状态。

新生产暂停点必须写 `WorkflowCheckpointV1 / RUNTIME_KERNEL_V2` envelope，并校验 GraphSpec SHA-256、暂停节点、payload SHA-256、默认 256 KiB 配置上限与不可配置的 1 MiB 硬上限。`__workflowExecutionIdentity` 和 Eval typed context 不得序列化；可信身份由通过 ownership 校验的 Runtime 会话行重建。历史 Map 只走 schema `0 / LEGACY` 兼容路由，未知版本或任一摘要不匹配均写 `CHECKPOINT_REJECTED` 并失败关闭。

会话行、断点、`CREATED / REQUESTED` 事件在同一事务提交后才能向用户返回 `uiRequest`。连续交互时，旧等待完成和下一等待创建也必须原子提交。

## 9. PRESENT_OUTPUT vs 提交型交互

- `PRESENT_OUTPUT`：展示结果卡片；默认不创建永远等待的 session；可发非阻塞 `ui.requested`（`behavior.blocking=false`）后继续
- `COLLECT_INPUT` / `USER_CHOICE` / `CONFIRM_ACTION`：必须暂停，创建 session，等待合法提交

API 查询模板的结果展示使用 `PRESENT_OUTPUT`。

## 10. Registry 开放门槛

当前由 `RuntimeWorkflowNodeCapabilityRegistry` 按变体控制开放：`maturity=BETA`、`runtimeExecutable=true`，节点类型级 `publishable=false`，但 `enabledVariants=[PRESENT_OUTPUT]`。Studio 和 AI authoring 已启用该节点的展示变体；发布校验只放行显式 `PRESENT_OUTPUT`，阻塞变体仍被拒绝。

Executor 多交互、Studio Debug E2E、刷新恢复、并发幂等、Agent/Embed 实际暂停恢复、同 Run/Trace、RunOps 暂停原因及状态、各交互类型验证、发布及构建和浏览器检查通过后，才扩大 `enabledVariants`。单元测试通过或共享执行器已实现，都不能代替这些开放门槛。

外部 Codex/Cursor AI Coding 接入不在本契约范围内。
