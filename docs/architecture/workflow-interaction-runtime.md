# Workflow INTERACTION Runtime

本文定义 Workflow `INTERACTION` 节点的生产级暂停/恢复语义。Studio Debug、Agent Workflow-as-Tool、Embed 公开提交与 RunOps 共用同一套契约。

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
                        -> executeFromNode(snapshot, nodeId, resume)
                        -> 同 root run/trace 继续
```

Control 只做公开入口、鉴权与代理；不得读写 Runtime 表。

## 6. Run / Trace 连续性

- 暂停：`runtime_run.status=WAITING_USER`，`ended_at` 为空；root span 不终结为 FAILED/SUCCESS
- `WAITING_APPROVAL` 仅用于 Supervisor policy confirmation / 真审批
- 恢复：同一 `trace_id` / root run；追加节点 span
- 成功 → `SUCCESS`；取消 → `CANCELLED`；超时 → `TIMEOUT`/`EXPIRED`；失败 → `FAILED`
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
- `current_node_id`、`resume_checkpoint_json`、`ui_request_json`
- `run_id` / `trace_id`
- 所有权：`app_id` / `tenant_id` / `session_id` / `user_id`
- `status` / `revision` / `idempotency_key` / `expires_at`
- Supervisor continuation（如需要）写入 `continuation_json`

恢复只使用暂停时 snapshot，不重新读最新草稿/发布版。

## 9. PRESENT_OUTPUT vs 提交型交互

- `PRESENT_OUTPUT`：展示结果卡片；默认不创建永远等待的 session；可发非阻塞 `ui.requested`（`behavior.blocking=false`）后继续
- `COLLECT_INPUT` / `USER_CHOICE` / `CONFIRM_ACTION`：必须暂停，创建 session，等待合法提交

API 查询模板的结果展示使用 `PRESENT_OUTPUT`。

## 10. Registry 开放门槛

开放前保持：

- `maturity=BETA`
- `runtimeExecutable=true`
- `publishable=false`
- `studioEnabled=false`
- `aiAuthoringEnabled=false`

全部门槛（Executor 多交互、Studio Debug E2E、刷新恢复、并发幂等、Agent/Embed 生产、同 Run/Trace、RunOps WAITING_USER、四类交互真实验证、release/build、无控制台异常）通过后，才改为 publishable/studio/aiAuthoring 全开。

外部 Codex/Cursor AI Coding 接入不在本契约范围内。
