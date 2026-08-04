# ReachAI 统一对话组件实施计划与 Cursor 执行提示词

> 状态：P2 **Code Ready / E2E Pending**（2026-07-17；本轮修复安全阶段事件 + Token Streaming 链路与反缓冲，尚未完成真实浏览器 Network 验收）
> 更新时间：2026-07-17
> 适用仓库：`D:\work\EnterpriseAgentFramework`

## 流式能力真实状态（P2 收口后）

| 层级 | 状态 | 证据 |
|------|------|------|
| A. 执行事件流 | **已完成** | Embed 实时代理 Runtime SSE；Workflow Debug `/debug-sessions/stream`；Control 无缓冲 allowlist 代理 |
| B. Agent Token Streaming | **Code Ready / E2E Pending** | PUBLIC_FINAL 即时 `message.delta`；sync fallback 标记 `streamMode=sync_fallback` / `tokenStreaming=false`；反缓冲头 + Vite SSE 配置 |
| C. Embed Token Streaming | **Code Ready / E2E Pending** | `createEmbedTransport.tokenStreaming: true`；不暴露 supervisor.step |
| D. Workflow 实时节点 | **Core Ready / E2E Pending** | 安全最终输出序列：`node.output.delta` + 后端 `message.delta`；前端不再推导公共文本 |
| E. Workflow Token Streaming | **false / 未完成** | `createWorkflowWorkingCopyTransport.tokenStreaming: false` |
| F. 请求级取消（含 Workflow-as-Tool） | **Cancellation Code Ready / E2E Pending** | `RuntimeAgentExecutionCancellation` 扇出到并行 `RuntimeGraphSpecExecutionCancellation`；取消码 `SUPERVISOR_CANCELLED` / `RUNTIME_GRAPH_CANCELLED`；不伪装 TIMEOUT |
| G. 静默期断开检测 | **Cancellation Code Ready / E2E Pending** | Runtime SSE comment heartbeat（默认 8s，可配置）；Control `SseStreamRelay` 原样转发并 flush；写失败关闭 upstream |
| H. 安全 Supervisor 阶段 | **Code Ready / E2E Pending** | `supervisor.step` additive：`stepId/state/source/title`；forced/explicit final 一致；implicit direct 不再伪装规划 |
| P2 总状态 | **Code Ready / E2E Pending** | 仅当三端真实 Network 验收全部通过后才允许写「P2 Completed」 |

### P2 安全边界

- INTERNAL：规划 / Workflow-as-Tool 的 content 与 reasoning **不公开**
- PUBLIC_FINAL：禁止 tool_call；reasoning 仅计长度；不输出工具参数 / Prompt
- Workflow-as-Tool 内部 node delta **不进入** Agent/Embed 公共 `message.delta`
- 公共 `message.delta` 所有权：仅 Runtime 后端显式发出；前端不得根据 `publicUserOutput` 二次推导
- 半截断流：保留已公开文本，标记 cancelled，禁止 sync 重试与 forced PUBLIC_FINAL 第二次模型请求
- 请求级取消：Controller → `RuntimeAgentExecutionCancellation` → Model stream + **全部**活动 Workflow-as-Tool
- Cooperative cancellation（**节点边界协作式取消**）：节点前后与同步 Feign 调用前后检查 cancellation；停止后续节点与可取消资源；**不承诺**回滚当前节点已发生的外部副作用
- OpenFeign 同步调用（`modelServiceClient.chat` / `capabilityClient.executeTool` / `controlClient.executePageBridge`）**无法硬中断**进行中的 HTTP；不得声称这些调用会在 8s 内停止
- Heartbeat 仅 transport comment，不得进入 Conversation / Embed `onEvent` / Trace / Reducer
- Heartbeat 调度使用有界 `ScheduledThreadPoolExecutor`（remove-on-cancel）；单连接 `emitter.send` 阻塞不得拖住其他连接

### 取消 SLA

- Heartbeat 默认 `reachai.runtime.agent-stream.heartbeat-interval-ms=8000`（Debug 流同理）
- 8s SLA 只描述「发现连接断开并设置取消信号」的时限（Control 向 downstream 写 heartbeat 失败 → 关闭 Runtime InputStream → Runtime emitter onError/onCompletion → cancellation），**不**承诺节点内同步 HTTP 在该时限内停止

### 已知后续风险（本轮不扩大范围）

- AgentScope `Hook` / `PreReasoningEvent` 已标记 deprecated（编译警告：已过时且待删除）；后续升级需迁移 Hook API，本轮不迁移

## 1. 背景

ReachAI 当前存在三类面向用户的对话入口：

1. Agent 调试台：用于调试已发布 Agent / AgentScope Supervisor。
2. Workflow Studio 调试对话框：用于运行当前 Workflow 草稿、查看节点轨迹并恢复 `WAITING` 会话。
3. 业务前端 Embed Chat：由业务系统通过 `@reachai/embed-chat` 嵌入页面，并通过 Embed Token 和 Page Bridge 与 ReachAI 交互。

三类入口业务语义不同，但消息列表、输入框、流式状态、交互卡片、错误处理、重试、会话状态等基础能力高度重叠。当前实现已经发生明显分叉：

- Agent 调试台在 `ai-admin-front/src/views/agent/AgentDebug.vue` 内维护完整对话 UI、流式状态和交互恢复逻辑。
- Workflow Studio 在 `ai-admin-front/src/views/workflow/WorkflowStudio.vue` 与 `composables/useWorkflowStudioDebugRun.ts` 中维护另一套消息、输入表单、交互恢复和节点轨迹逻辑。
- Embed SDK 在 `ai-admin-front/src/sdk/eafChat.ts` 内用原生 DOM 再实现一套消息、输入、SSE、结构化 UI 和 Page Action 逻辑。
- 交互卡片至少存在三套渲染实现：`DynamicInteraction.vue`、`InteractionRenderer.vue`、`eafChat.ts#renderStructuredUi`。
- 班组 Angular 前端当前还存在一份与 SDK 等价的最小协议实现，说明只提供示例代码会继续造成复制和漂移。

本计划的目标不是把三个页面做成完全相同的页面，而是建立一套稳定的对话内核和共享对话 UI，让三个产品入口只保留执行对象、鉴权方式和调试洞察上的差异。

## 2. 当前事实基线

实施前必须重新读取并以真实代码为准，至少核对以下文件：

### 2.1 Agent 调试台

- `ai-admin-front/src/views/agent/AgentDebug.vue`
- `ai-admin-front/src/api/agent.ts`
- `ai-admin-front/src/types/chat.ts`
- `ai-admin-front/src/types/agent.ts`
- `reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/api/RuntimePublicController.java`
- `reachai-control-service/src/main/java/com/enterprise/ai/control/runtime/ControlRuntimePublicController.java`
- `reachai-control-service/src/main/java/com/enterprise/ai/control/runtime/RuntimeAgentStreamProxy.java`

当前路由：

- `POST /api/runtime/agents/execute/stream`
- `DELETE /api/runtime/agents/sessions/{sessionId}`
- `POST /api/runtime/interactions/human-approvals/{interactionId}/submit`

当前 SSE 事件至少包括：

- `execution.started`
- `supervisor.step`
- `message.delta`
- `ui.requested`
- `execution.completed`
- `execution.error`

注意：P2 后 Supervisor 在 `PUBLIC_FINAL` 阶段通过 `contentDeltaSink` 实时发送多段 `message.delta`；`RuntimePublicController` 仅在 `contentStreamed=false` 时才补发一次最终 answer，避免重复。

### 2.2 Workflow Studio 调试

- `ai-admin-front/src/views/workflow/WorkflowStudio.vue`
- `ai-admin-front/src/views/workflow/composables/useWorkflowStudioDebugRun.ts`
- `ai-admin-front/src/api/workflow.ts`
- `ai-admin-front/src/types/workflow.ts`
- `reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeDebugSessionCompatibilityController.java`
- `reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/debug/RuntimeExecutableDebugSessionService.java`
- `reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowDebugService.java`

当前路由：

- `POST /api/runtime/debug-sessions`
- `GET /api/runtime/debug-sessions/{sessionId}`
- `POST /api/runtime/debug-sessions/{sessionId}/submit`
- `POST /api/runtime/debug-sessions/{sessionId}/cancel`

当前特点：

- 使用 REST 返回完整 `WorkflowDebugSessionView`，不是 SSE。
- SessionView 中包含 `messages`、`steps`、`uiRequest`、`status`、`traceId`。
- `WAITING` 状态通过 `/submit` 恢复。
- 初始输入可能是普通问题，也可能来自 `USER_INPUT` 节点的多个结构化字段。
- 节点轨迹、画布高亮、变量快照、Trace 回放属于 Workflow Studio 专属能力。

### 2.3 Embed Chat

- `ai-admin-front/src/sdk/index.ts`
- `ai-admin-front/src/sdk/eafChat.ts`
- `ai-admin-front/src/sdk/eafPageBridge.ts`
- `ai-admin-front/src/sdk/style.css`
- `ai-admin-front/src/sdk/eafChat.test-d.ts`
- `ai-admin-front/vite.sdk.config.ts`
- `ai-admin-front/scripts/write-embed-chat-package.mjs`
- `reachai-control-service/src/main/java/com/enterprise/ai/control/platform/PlatformEmbedPublicController.java`
- `reachai-control-service/src/test/java/com/enterprise/ai/control/platform/PlatformEmbedPublicControllerTest.java`
- `docs/reference/嵌入式对话与页面动作.md`

当前包名和入口：

- `@reachai/embed-chat`
- `createEafChat(options)`
- `createEafPageBridge(options)`
- ESM、CJS、UMD、CSS 构建产物

当前公开路由：

- `POST /api/embed/chat/sessions`
- `POST /api/embed/chat/sessions/{sessionId}/messages`
- `POST /api/embed/chat/sessions/{sessionId}/messages/stream`
- `POST /api/embed/chat/sessions/{sessionId}/interactions/{interactionId}/submit`
- Page Action pending/result 相关路由

注意：Embed `/messages/stream` 实时代理 Runtime SSE。P2 后 Agent/Embed 公共 `message.delta` 来自 Runtime `PUBLIC_FINAL` 阶段的真实模型增量（`tokenStreaming: true`）。Supervisor / Workflow 内部节点与 reasoning 仍不向 Embed 暴露。

Workflow Debug 交互取消语义：

- REST：`POST .../submit` 且 `action=cancel` → `SessionView.status=CANCELLED`（清空 `currentNodeId` / `uiRequest`，不调用 `debugRun`）
- SSE：`emitSessionEvents` 对 `CANCELLED` 发送 `event: turn.cancelled`（**不是** `turn.failed`）；`ERROR`/`FAILED` 才发 `turn.failed`
- 前端 `adaptWorkflowDebugStreamEvent('turn.cancelled')` 与 REST `adaptWorkflowSessionView` 最终均映射为 Conversation `turn.cancelled`（`turnStatus=cancelled`）
- 普通 interaction resume **禁止**在无 sessionId 时回退 create；仅自由文本与 `WORKFLOW_INITIAL_INPUT_ID` 可 create
- Embed 公开事件顺序：`message.delta` → `ui.requested` → `page.action.requested` → `message.completed`（completion 可解析 `pageActionQueue`，但 `page.action.requested` 必须先于 completed 对外出现；Bridge 执行可晚于 completed）
- Control Embed SSE relay 放行 `page.action.requested`（仍过滤 `supervisor.step` / `reasoning.delta` / debug）
- 独立 SSE / completion queue / pending poll **三条路径**共用同一个 Page Bridge dispatcher（`dispatchPageActionExactlyOnce`）；独立 SSE 不依赖 completion metadata 才执行

### 2.4 交互卡片

- `ai-admin-front/src/types/interaction.ts`
- `ai-admin-front/src/components/interaction/DynamicInteraction.vue`
- `ai-admin-front/src/components/interaction/InteractionRenderer.vue`
- `ai-admin-front/src/components/interaction/*.vue`

当前调用方：

- Agent 调试台使用 `DynamicInteraction.vue`。
- Workflow Studio 和 Capability Kernel 使用 `InteractionRenderer.vue`。
- Embed SDK 在 `eafChat.ts` 内自行创建 DOM。

实施时必须先定义唯一的公共协议和唯一的渲染注册表，再逐步迁移调用方。不得简单把其中一套复制到其他入口。

## 3. 目标与非目标

### 3.1 目标

1. Agent 调试台、Workflow Studio 调试对话框和 Embed Chat 使用同一套消息模型、对话状态机和交互卡片协议。
2. 三端复用同一份消息列表、输入框、加载/流式状态、错误与重试、卡片渲染代码。
3. 三端通过不同 `ConversationTransport` 接入现有后端，不要求第一阶段统一成同一个后端路由。
4. 保持现有 `createEafChat()`、Page Bridge、公开路由和主要配置向后兼容。
5. 对外组件支持 Vue、React、Angular、传统前端和 UMD 场景。
6. 交互卡片支持内置组件、受控自定义渲染器、提交状态、失败重试和过期状态。
7. Embed Token、Page Bridge、Origin、`pageInstanceId` 等安全边界保持不变。
8. 明确区分执行事件流和 Token 级文字流，并对两者分别验收。

### 3.2 非目标

1. 不把 Agent 调试台、Workflow Studio 和 Embed 做成完全相同的页面。
2. 不把 Supervisor 步骤、Workflow 节点轨迹或 Trace 面板暴露到普通业务 Embed。
3. 不改变 `GraphSpec` 运行语义，不把 `canvasJson` 当运行语义。
4. 不为了本任务修改数据库 schema；如果实施中发现确需数据库变化，必须先停下说明原因并按仓库 SQL 规则处理。
5. 不允许服务端下发任意 HTML、JavaScript 或 Vue 组件源码。
6. 不允许业务浏览器保存 `appSecret`、AI Coding Key 或平台管理 Token。
7. 不在第一步删除全部旧实现；必须分阶段迁移并保留兼容层，直到调用方和测试完成迁移。

## 4. 总体架构

```text
                         Conversation Core
        types / reducer / controller / event parser / renderer registry
                                  |
                       Shared Conversation UI
        message list / composer / blocks / interaction / error / loading
                                  |
             +--------------------+--------------------+
             |                    |                    |
      AgentDebugShell      WorkflowDebugShell      EmbedChatShell
             |                    |                    |
    AgentDebugTransport  WorkflowWorkingCopyTransport   EmbedTransport
             |                    |                    |
       Runtime Agent SSE    Debug Session REST/SSE   Embed API/SSE
```

共享的是对话结构和交互能力；不共享的是三个入口的产品外壳和调试洞察。

## 5. 前端公共契约

### 5.1 消息与 Block 模型

不要继续以 `answer: string + uiRequest?: UiRequest` 作为唯一展示模型。它只能表达一段文本加一张卡片，无法可靠表达多卡片、文本与卡片穿插、卡片状态更新和部分失败。

建议新增：

```ts
export type ConversationRole = 'user' | 'assistant' | 'system' | 'runtime'

export type ConversationMessageStatus =
  | 'pending'
  | 'streaming'
  | 'completed'
  | 'failed'
  | 'cancelled'

export interface ConversationMessage {
  id: string
  role: ConversationRole
  status: ConversationMessageStatus
  blocks: ConversationBlock[]
  createdAt: string
  updatedAt?: string
  metadata?: Record<string, unknown>
}

export type ConversationBlock =
  | ConversationTextBlock
  | ConversationInteractionBlock
  | ConversationPageActionBlock
  | ConversationNoticeBlock
  | ConversationErrorBlock

export interface ConversationTextBlock {
  id: string
  type: 'text'
  text: string
  status?: 'streaming' | 'completed'
}

export interface ConversationInteractionBlock {
  id: string
  type: 'interaction'
  request: UiRequestV1
  state: InteractionRenderState
}
```

兼容要求：

- 现有 `ChatResponse.answer` 转换成一个 `text` block。
- 现有 `ChatResponse.uiRequest` 转换成一个 `interaction` block。
- Workflow `ExecutableDebugMessage.content/uiRequest` 转换成相同 block。
- 不要求第一阶段修改所有后端响应 DTO。

### 5.2 交互协议 `UiRequestV1`

在 `ai-admin-front/src/types/interaction.ts` 的现有结构基础上收敛，不要另造语义冲突的字段：

```ts
export interface UiRequestV1 {
  schemaVersion: '1.0'
  interactionId: string
  traceId?: string
  type?: string
  component: string
  title?: string
  message?: string
  ttlSeconds?: number
  expiresAt?: string
  fields?: UiFieldPayload[]
  prefilled?: Record<string, unknown>
  missing?: string[]
  summary?: Record<string, unknown>
  data?: unknown
  schema?: Record<string, unknown>
  actions?: UiActionPayload[]
  datasources?: Record<string, unknown>
  behavior?: Record<string, unknown>
  extension?: {
    rendererKey?: string
    pageActionRequest?: unknown
    [key: string]: unknown
  }
}
```

兼容要求：

- 后端尚未返回 `schemaVersion` 时，前端归一化器按 `1.0` 处理。
- `component` 大小写统一在归一化层处理，不让每个渲染器各自判断。
- `interactionId` 对需要提交的卡片为必填；只读卡片可生成稳定的本地 ID。
- 服务端不得下发任意 HTML。

内置组件第一批至少支持：

- `form`
- `text_question`
- `select`
- `choice`
- `multi_select`
- `confirm`
- `table`
- `detail`
- `card`
- `report`
- `summary_card`
- `list_card`
- `output_card`
- `page_action`
- `custom` 的安全 fallback

交互状态：

```ts
export type InteractionRenderState =
  | 'waiting'
  | 'submitting'
  | 'resolved'
  | 'failed'
  | 'expired'
  | 'cancelled'
```

### 5.3 公共事件协议

公共对话事件与调试扩展事件分开：

```ts
export interface ConversationEventEnvelope<T = unknown> {
  protocolVersion: '1.0'
  type: ConversationEventType | string
  sessionId?: string
  turnId?: string
  sequence?: number
  timestamp: string
  traceId?: string
  data: T
}
```

公共事件：

- `session.created`
- `session.restored`
- `turn.started`
- `message.started`
- `message.delta`
- `ui.requested`
- `page.action.requested`
- `turn.waiting`
- `turn.completed`
- `turn.cancelled`
- `turn.failed`

调试扩展事件：

- `debug.supervisor.step`
- `debug.workflow.node.started`
- `debug.workflow.node.completed`
- `debug.workflow.node.waiting`
- `debug.workflow.node.failed`
- `debug.trace.available`

第一阶段由 Transport 将现有事件或 REST 快照归一化为公共事件，不立即破坏现有后端事件名。

### 5.4 Transport 契约

```ts
export interface ConversationTransportCapabilities {
  eventStreaming: boolean
  tokenStreaming: boolean
  abort: boolean
  restore: boolean
  structuredInitialInput: boolean
  pageActions: boolean
}

export interface ConversationTransport {
  readonly kind: 'agent-debug' | 'workflow-draft' | 'embed'
  readonly capabilities: ConversationTransportCapabilities

  startTurn(
    input: ConversationTurnInput,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope>

  resumeInteraction?(
    interactionId: string,
    action: string,
    values: Record<string, unknown>,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope>

  restoreSession?(): Promise<ConversationSnapshot | null>
  cancelTurn?(): Promise<void>
  clearSession?(): Promise<void>
  dispose(): void
}
```

约束：

- UI 组件不得直接 import `api/agent.ts`、`api/workflow.ts` 或调用 Embed API。
- Token 刷新、路由差异、REST 快照转换、SSE 解析都属于 Transport。
- `ConversationController` 只依赖 Transport 契约和公共事件。

## 6. 共享组件设计

建议新建以下结构，实际命名可在不改变边界的前提下微调：

```text
ai-admin-front/src/conversation/
  core/
    conversationTypes.ts
    conversationEvents.ts
    conversationReducer.ts
    createConversationController.ts
    parseSseStream.ts
    normalizeUiRequest.ts
  transports/
    createAgentDebugTransport.ts
    createWorkflowWorkingCopyTransport.ts
    createEmbedTransport.ts
  renderers/
    interactionRegistry.ts
    builtinRenderers.ts
  components/
    ConversationView.vue
    ConversationMessageList.vue
    ConversationMessageItem.vue
    ConversationComposer.vue
    ConversationBlockRenderer.vue
    UnifiedInteractionRenderer.vue
    cards/
  web-component/
    ReachAiChatElement.ce.vue
    defineReachAiChatElement.ts
```

### 6.1 `ConversationController`

职责：

- 保存 session、turn、message、block 状态。
- 消费 Transport 的 `AsyncIterable` 事件。
- 合并 `message.delta`，不得每个 Token 新建消息。
- 维护发送、等待交互、失败、取消状态。
- 防止同一个 `interactionId` 重复提交。
- Abort 后将当前消息置为 `cancelled`，不得误显示成普通错误。
- 处理事件乱序、重复 completion 和连接中断。
- 向 Shell 暴露调试扩展事件，但不把调试数据塞进普通消息 block。

### 6.2 `ConversationView`

职责：

- 消息列表、空状态、输入框、发送/停止、错误重试。
- 渲染公共 blocks。
- 控制自动滚动：用户停留在底部时自动跟随；用户向上滚动后显示“回到底部”，不要强行抢滚动。
- 支持 `compact`、`comfortable` 两种 density。
- 支持 `inline`、`panel` 两种 chrome；悬浮球和 Drawer 属于 Embed Shell。
- 暴露必要 slots/hooks：`header`、`empty`、`message-meta`、`footer`。
- 不直接包含 Supervisor、Workflow 节点或 Trace 业务逻辑。

### 6.3 样式与封装

- 共享组件不得依赖 Element Plus，避免外部包体积膨胀和样式冲突。
- ReachAI 管理端可以在 Shell 层继续使用 Element Plus。
- Web Component 默认使用 Shadow DOM；主题通过 CSS Variables 进入。
- Vue 内部使用同一份组件源码，不另写一套 Embed DOM。
- 保留现有 `--reachai-chat-primary`，并扩展但不随意改名。

建议主题变量：

```css
--reachai-chat-primary
--reachai-chat-primary-contrast
--reachai-chat-surface
--reachai-chat-surface-muted
--reachai-chat-text
--reachai-chat-text-muted
--reachai-chat-border
--reachai-chat-danger
--reachai-chat-success
--reachai-chat-radius
--reachai-chat-shadow
--reachai-chat-width
--reachai-chat-height
--reachai-chat-font-family
```

### 6.4 安全与可访问性

- 默认按纯文本渲染模型输出。
- 如果增加 Markdown，必须使用明确 allowlist 和经过验证的 sanitizer；不得直接 `v-html` 渲染模型内容。
- 自定义 renderer 只能由宿主前端本地注册，服务端只能发送 `rendererKey + data`。
- 所有按钮有可读 label；加载、错误、状态变化使用合适的 `aria-live`。
- 输入框支持 `Ctrl + Enter`，同时保留可点击发送按钮。
- 卡片提交后禁用重复操作；失败时恢复按钮并提供重试。
- 支持键盘焦点、移动端和 `prefers-reduced-motion`。

## 7. 三类 Shell 的迁移边界

### 7.1 Agent 调试台

共享：

- 消息列表
- 输入框
- 流式文字状态
- 发送、停止、重试
- 交互卡片
- 消息自动滚动

保留在 `AgentDebug.vue`：

- Agent 标题和发布状态
- Supervisor 推理步骤
- 执行洞察面板
- Trace、工具调用、耗时元信息
- 人工审批列表
- 调试状态标签

迁移方式：

- `createAgentDebugTransport` 封装现有 `/execute/stream`。
- `executeAgentStream()` 暂时保留兼容导出，但内部可委托给公共 SSE parser，避免两个 parser。
- `supervisor.step` 转换为 `debug.supervisor.step`，由 Agent Shell 消费。
- 使用 `message-meta` slot 渲染 Trace、工具和耗时。

### 7.2 Workflow Studio 调试对话框

共享：

- 对话消息
- 普通问题输入框
- 结构化初始输入卡片
- `WAITING` 交互卡片
- 提交、取消、错误和恢复状态

保留在 Workflow Shell：

- 当前 Working Copy / 已发布版本语义
- 节点轨迹、节点详情、变量快照
- 画布节点高亮和回放
- Trace 回放与发布版本对照
- GraphSpec 构建和草稿保存逻辑

结构化初始输入：

- 将当前 `debugInputFields` 转换为本地 `form` 类型 `UiRequestV1`。
- 使用例如 `local:workflow-initial-input` 的本地交互 ID。
- 提交此本地表单时调用 `createWorkflowDebugSession()`，而不是 `/submit`。
- 已存在 session 且处于 `WAITING` 时，提交真实 `interactionId` 到 `/submit`。

REST 适配：

- 第一阶段 `createWorkflowWorkingCopyTransport` 继续使用现有 REST。
- 收到 `WorkflowDebugSessionView` 后，根据 message ID 和 step index 生成公共事件与 `debug.workflow.node.*` 事件。
- `restoreSession()` 使用现有 GET 路由。
- 不能因为统一 UI 而丢失现有恢复会话能力。

### 7.3 Embed Chat

必须保持现有公开 API：

- `createEafChat(options)`
- `EafChatClient.open/close/toggle/send/registerPageCatalog/setContext/destroy`
- `createEafPageBridge()`
- `resolveEafChatEmbedApiRoot()`
- `resolveEafChatPlatformBase()`
- `apiBase`、`embedPathPrefix`、`position`、`initialOpen`、`theme` 等现有配置

迁移方式：

- `eafChat.ts` 最终收敛为兼容 facade，不再负责手写 DOM 和卡片实现。
- facade 创建 `EmbedTransport + ConversationController + ReachAiChatElement`。
- tokenProvider、401 刷新、session 重建、Page Action pending 轮询、result 回传全部迁移到 Embed Transport 或专属服务。
- `destroy()` 必须清理 timer、AbortController、事件监听和 Web Component。
- 不允许在迁移过程中重复发送用户消息或在 SSE fallback JSON 时重复追加回答。

对外构建：

- 继续输出 ESM、CJS、UMD、CSS。
- 新增或补全 `index.d.ts`，并在生成的 `package.json` 中设置 `types`。
- Web Component bundle 不把整个 Element Plus 打进去。
- `createEafChat()` 仍是推荐入口；直接使用 Custom Element 属于高级入口，因为 `tokenProvider` 是函数属性，不能安全放在 HTML attribute 中。
- 是否增加 `./core`、`./vue`、`./web-component` 子路径导出，应以真实构建产物为准；不要只写 package exports 而没有对应文件。

## 8. 后端流式改造

后端流式分成两层，不得混淆。

### 8.1 第一层：执行事件流

目标：用户能尽早看到“开始执行、节点推进、等待交互、失败、完成”，即使最终文字暂时仍整段返回。

#### Agent

- 复用现有 Runtime Agent SSE。
- 保持 10 分钟超时和 Control 流代理的无缓冲语义。
- 保持 `Cache-Control: no-cache`、`X-Accel-Buffering: no`、`text/event-stream`。
- 统一前端事件映射，不必第一阶段重命名后端现有事件。

#### Embed

- 不再先调用同步 `executeEmbedMessage()` 再创建 emitter。
- Token、session、principal、agentId、page context 校验必须在开始代理前完成。
- 构造 `entryType=EMBED` 的 runtime request。
- 以流方式调用 Runtime `/api/runtime/agents/execute/stream`。
- 转换并转发事件：
  - Runtime `message.delta` -> Embed `message.delta`
  - Runtime `ui.requested` -> Embed `ui.requested`
  - Runtime `execution.completed` -> Embed `message.completed`
  - Runtime `execution.error` -> Embed `error`
  - 默认不向业务 Embed 暴露 `supervisor.step`
- 在 completion 时调用现有 chat event service 记录 assistant message、traceId 和结果。
- 客户端断开时及时停止上游读取。
- 不能使用 OpenFeign 缓冲 SSE；可以抽取或泛化现有 `RuntimeAgentStreamProxy`，但路径必须 allowlist，不能形成任意 URL 代理。

建议增加交互提交流式路由，同时保留现有 JSON 路由：

- `POST /api/embed/chat/sessions/{sessionId}/interactions/{interactionId}/submit/stream`

该路由将 `interactionId + uiSubmit` 转为 Runtime Agent 流请求，使卡片提交后的继续执行也可以产生流式事件。

#### Workflow Debug

第一阶段可以保持 REST，但完整目标应提供增量调试事件：

- `POST /api/runtime/debug-sessions/stream`
- `POST /api/runtime/debug-sessions/{sessionId}/submit/stream`

要求：

- 现有 REST 路由继续保留。
- Runtime Workflow Debug 在节点开始、结束、WAITING、失败时发出事件。
- Control Service 为公共 `/api/runtime/**` 入口提供无缓冲流代理。
- Workflow Studio Transport 优先使用流式路由；不支持时可回退 REST，但要显式标记 `capabilities.eventStreaming=false`。

### 8.2 第二层：真正 Token 级文字流

当前代码还不能安全宣称已经完成 Token 级 Agent 流式：

- `RuntimePublicController` 目前在 Agent 完成后发送整段 `answer`（或按既有实现输出）。
- Model Service 结构化流唯一入口为 `POST /model/chat/stream/events`，事件类型包括 `content.delta` / `reasoning.delta` / `tool_call.delta` / `usage` / `completed` / `error`。旧 `POST /model/chat/stream` 文本流（`Flux<String>`）已删除，不提供兼容。
- Runtime 已通过 `RuntimeModelStreamHttpClient` 消费 `/model/chat/stream/events`；模型调试台（Playground）同样只走结构化事件，并区分 content、reasoning、tool call、usage 与错误。

正确改造要求：

1. Model Service 继续以 `ModelStreamEvent` 为唯一流契约（已落地）。
2. `OpenAiCompatibleRuntimeClient` 正确聚合 OpenAI-compatible 流中的 tool call index、id、function name、arguments 片段。
3. Runtime 到 Model Service 使用真正的流式 HTTP client，不要用会缓冲完整响应的普通 Feign 方法。
4. `ReachAiAgentScopeChatModel#doStream` 返回 AgentScope 可正确消费的增量 `ChatResponse`，同时保持工具调用语义。
5. 只有 assistant 文本增量进入面向用户的 `message.delta`；内部规划、工具参数和敏感 reasoning 不直接暴露。
6. Workflow LLM 节点如果需要 Token 流，也要通过 GraphSpec executor 的 event sink 向上游传播；不能仅在前端模拟打字机效果。
7. 保留同步 `/model/chat` 和同步 Agent/Workflow 路由作为非流式入口。

严禁为了“看起来流式”而把完整答案在前端切字符定时播放，并宣称后端已完成流式。

## 9. 分阶段实施步骤

每一阶段完成后单独运行目标测试和构建，不要在前一阶段失败时继续堆叠改动。

### Phase 0：基线与保护

任务：

1. 阅读根 `AGENTS.md`、本计划和相关事实源。
2. 运行 `git status --short`，记录并保护所有既有用户改动。
3. 记录当前前端构建、SDK 构建和目标后端测试结果。
4. 截图或记录三个对话入口当前行为，形成迁移对照。
5. 列出当前 `createEafChat` 类型和构建产物，作为兼容清单。

退出标准：

- 已知哪些失败是基线问题。
- 没有覆盖或回滚用户已有修改。
- 已形成三端功能矩阵。

### Phase 1：公共类型、事件和 reducer

任务：

1. 新增 conversation types、events、UI request normalizer。
2. 抽取唯一 SSE parser，支持 CRLF、多行 data、不完整 frame、纯文本 data、JSON data、error event、Abort。
3. 实现 conversation reducer/controller。
4. 为现有 Agent SSE 增加 Adapter，但暂不替换页面。
5. 为 Workflow SessionView 增加 snapshot-to-event Adapter。
6. 为 Embed event 增加 Adapter。

测试：

- delta 合并到同一 text block。
- 重复 completion 不产生重复消息。
- UI request 按事件顺序插入。
- WAITING、resume、cancel、error 状态正确。
- Abort 不显示成普通网络错误。
- CRLF 和跨 chunk SSE frame 正确解析。

退出标准：

- 三种现有响应都能转换成同一 `ConversationSnapshot`。
- 页面 UI 尚未迁移时，现有功能不受影响。

### Phase 2：唯一交互卡片注册表

任务：

1. 以现有 `InteractionRenderer.vue` 的较完整能力为输入，不直接把 Element Plus 依赖带进对外组件。
2. 建立 `normalizeUiRequest()` 与 `interactionRegistry`。
3. 实现无 Element Plus 的公共卡片组件。
4. 支持内置组件清单、安全 fallback、自定义 `rendererKey` 注册。
5. 实现 waiting/submitting/resolved/failed/expired/cancelled 状态。
6. 先让 `DynamicInteraction.vue` 和 `InteractionRenderer.vue` 变成兼容 wrapper，内部委托统一 renderer。
7. 等调用方完成迁移和测试后再删除重复实现。

测试：

- 每种内置 component 至少一个渲染测试。
- form 必填、数字、布尔、日期、select、multi-select。
- confirm reject/confirm action payload。
- 表格和详情对空值、数组、JSON 字符串的处理。
- 重复点击、失败重试、过期状态。
- custom 未注册时安全显示，不执行服务端 HTML。

退出标准：

- 同一 `UiRequestV1` 在 Agent、Workflow 和 SDK 测试宿主中使用同一个 renderer。
- 不再新增第四套卡片判断分支。

### Phase 3：共享 Conversation UI

任务：

1. 实现 `ConversationView`、message list、composer、block renderer。
2. 支持 density、slots、主题 tokens、移动端、键盘和 reduced motion。
3. 实现滚动跟随与“回到底部”。
4. 实现 sending/streaming/waiting/error/cancelled 视觉状态。
5. 建立最小开发演示页面或组件测试宿主，覆盖文本、流式、错误和多卡片。

退出标准：

- 共享组件自身不 import 三类具体 API。
- 共享组件不依赖 Element Plus。
- 可在普通 Vue 页面和 Custom Element 宿主中运行。

### Phase 4：迁移三个入口

#### 4A Workflow Studio

优先迁移 Workflow，因为它同时覆盖结构化初始输入、WAITING 恢复和只读结果卡片：

1. 保留右侧节点轨迹与高级调试区域。
2. 将左侧 `debug-chat-panel` 内消息和 composer 替换为共享组件。
3. 使用 Workflow Draft Transport 适配现有 REST。
4. 将初始 `debugInputFields` 转换为本地 form request。
5. 保持 stored session restore、cancel、trace replay、节点高亮。

#### 4B Agent 调试台

1. 使用 Agent Debug Transport 和共享组件。
2. 保留 Agent header、Supervisor 洞察、人工审批和 Trace。
3. 将 `supervisor.step` 交给 Shell。
4. 保持停止执行、清空 session、消息 meta。
5. 移除页面内重复的 SSE 和 interaction 状态逻辑，但保留兼容 API wrapper。

#### 4C Embed SDK

1. 实现 Web Component wrapper。
2. `createEafChat` 内部挂载同一共享组件。
3. 保持全部公开 API 和 Page Bridge 行为。
4. 迁移 timer、token refresh、page action polling、fallback、destroy cleanup。
5. 构建并检查 ESM/CJS/UMD/CSS/types。

退出标准：

- 三端基础消息、composer 和卡片来自同一份组件源码。
- 三端专属面板仍正常。
- `createEafChat()` 现有示例无需重写即可工作。

### Phase 5：执行事件流

任务：

1. Embed 改为实时代理 Runtime Agent SSE。
2. Embed interaction submit 增加流式继续执行入口。
3. Workflow Debug 增加增量 session/node event SSE，REST 保留兼容。
4. 三端 Transport 标注真实 capability。
5. 增加 disconnect、timeout、上游非 2xx、错误事件测试。

退出标准：

- Embed 在 Agent 完成前能收到至少一个执行期事件或真实文本 delta。
- Workflow Studio 能在整个 Workflow 完成前看到节点状态推进。
- 代理不缓冲、不断开、不过早触发 Spring MVC 超时。

### Phase 6：Token 级流式

任务：

1. 引入结构化 Model Stream Event。
2. 保持 tool call 流的完整组装和 AgentScope 语义。
3. 从 Model Service 一直把安全的 assistant content delta 传播到三个前端入口。
4. Workflow LLM/Answer 路径按可行边界传播 delta。
5. 增加真实 provider 或可控 fake provider 的流式集成测试。

退出标准：

- 第一个 `message.delta` 在模型完整回答完成前到达客户端。
- 工具调用场景仍能正确规划、调用和完成。
- 不能通过前端字符切片伪造验收。

### Phase 7：文档、示例与接入提示词

任务：

1. 更新 `docs/reference/嵌入式对话与页面动作.md`。
2. 更新 SDK 接入向导生成的 AI 提示词。
3. 示例只生成 mount、agentId、tokenProvider、Page Bridge 和主题，不复制内部对话实现。
4. 提供 Vue、Angular、UMD 最小示例。
5. 标注事件流与 Token 流的真实支持状态。
6. 记录包体积和浏览器兼容性。

退出标准：

- AI 工具接入业务仓库时默认使用 `@reachai/embed-chat`。
- 示例中没有 appSecret、平台管理 Token 或复制的 SSE parser。

## 10. 验收矩阵

### 10.1 公共 UI

- [ ] 用户消息和助手消息显示正确。
- [ ] 流式 delta 追加到当前消息，不重复创建消息。
- [ ] 发送中可停止；停止后状态为 cancelled。
- [ ] 网络错误可重试，不重复提交上一条消息。
- [ ] 多个 text/card block 顺序正确。
- [ ] 用户向上滚动时不会被强制拉到底部。
- [ ] 键盘、移动端、暗色主题、reduced motion 可用。

### 10.2 交互卡片

- [ ] form/select/confirm/table/detail/summary/list/output/page_action 正常。
- [ ] 提交中禁用重复操作。
- [ ] 提交失败可重试。
- [ ] resolved 后保持结果状态，不再次提交。
- [ ] expired 显示过期，不允许提交。
- [ ] custom 未注册时安全 fallback。

### 10.3 Agent 调试台

- [ ] `/execute/stream` 正常消费。
- [ ] Supervisor 步骤继续显示。
- [ ] Trace、工具、耗时继续显示。
- [ ] UI interaction 可恢复。
- [ ] 停止执行和清空 session 正常。
- [ ] 右侧执行洞察不进入业务 Embed。

### 10.4 Workflow Studio

- [ ] 当前 Working Copy 可以运行。
- [ ] 普通问题和多字段初始输入都可提交。
- [ ] `WAITING` 卡片可恢复、取消。
- [ ] 刷新后可恢复已有 debug session。
- [ ] 节点轨迹、画布高亮、状态快照、Trace 回放正常。
- [ ] 发布版本验证不受影响。

### 10.5 Embed

- [ ] `createEafChat()` 现有调用兼容。
- [ ] inline、bottom-right、bottom-left 正常。
- [ ] Token 过期刷新不重复发送。
- [ ] session 重建符合现有规则。
- [ ] Page Action 只作用于匹配的 `pageInstanceId`。
- [ ] pending polling 和 result 回传正常。
- [ ] destroy 后没有 timer、listener、fetch 泄漏。
- [ ] ESM/CJS/UMD/CSS/types 产物完整。
- [ ] Angular/传统页面可以接入。

### 10.6 后端流式

- [ ] SSE content type、no-cache、no-buffer headers 正确。
- [ ] Control 与 Runtime 超时均允许长任务。
- [ ] 客户端断开可终止上游读取。
- [ ] Embed completion 正确写入 chat event/audit。
- [ ] Runtime error 映射为稳定 error event。
- [ ] Token 流不破坏 tool call。

## 11. 测试与验证命令

实施者应根据实际新增测试脚本调整命令，但至少覆盖：

```powershell
cd D:\work\EnterpriseAgentFramework\ai-admin-front
npm run build
npm run build:sdk
```

如果增加 Vitest，建议提供稳定脚本，例如：

```powershell
npm run test:conversation
```

后端目标测试示例：

```powershell
cd D:\work\EnterpriseAgentFramework
mvn -pl reachai-runtime-service "-Dtest=RuntimePublicControllerTest,RuntimeExecutableDebugSessionServiceTest,RuntimeDebugSessionCompatibilityControllerTest" test
mvn -pl reachai-control-service "-Dtest=ControlRuntimePublicControllerTest,PlatformEmbedPublicControllerTest" test
```

最终检查：

```powershell
git diff --check
git status --short
```

测试要求：

- 前端公共 reducer、SSE parser、三类 Transport、renderer registry 必须有自动化测试。
- 后端流接口必须有事件顺序、错误、超时/断开和 completion 测试。
- SDK 必须保留/扩展类型测试，并检查实际打包内容。
- 至少对三个真实页面做浏览器 smoke test；需要截图对比关键状态。

## 12. 风险与处理

### 12.1 大爆炸重写

风险：同时改 UI、协议、三个入口和模型流，难以定位回归。

处理：按 Phase 1-7 逐步迁移；旧组件先变 wrapper，最后再删除。

### 12.2 Embed 包体积失控

风险：直接复用 Element Plus 组件导致外部 bundle 过大和样式冲突。

处理：共享 UI 不依赖 Element Plus；记录构建前后体积并检查依赖图。

### 12.3 伪流式

风险：SSE 只在完成后发整段，或前端定时切字符。

处理：分别验收执行事件流和 Token 流；记录首 delta 到达时间。

### 12.4 Tool call 被流式改造破坏

风险：Agent 面向用户的 Token 级 `message.delta` 仍需按 Supervisor 边界继续收口；Model Service 结构化流与 Runtime `RuntimeModelStreamHttpClient` 已落地。

处理：先定义结构化 Model Stream Event，完成 tool-call 聚合测试后才能替换 AgentScope 同步模型调用。

### 12.5 页面动作越界

风险：共享组件错误执行其他页面或标签页的 action。

处理：Page Bridge 继续校验 session、agent、pageKey、pageInstanceId；共享 UI 不直接执行页面动作。

### 12.6 用户已有改动被覆盖

风险：当前工作树可能已有 AgentDebug、配置、文档或后端修改。

处理：实施前读 `git status` 和相关 diff；禁止 reset/checkout；只修改任务文件并保留重叠改动。

## 13. 完成定义

只有同时满足以下条件，才能声明统一对话组件完成：

1. 三端基础对话 UI 使用同一份组件源码。
2. 三端交互卡片使用同一协议和 renderer registry。
3. 三个 Transport 有清晰边界和测试。
4. Workflow 的结构化初始输入、WAITING 恢复、节点轨迹没有回归。
5. Agent 的 Supervisor、Trace、停止和审批没有回归。
6. Embed 的 token、session、Page Bridge、构建产物和公开 API 没有回归。
7. 文档准确描述当前真实流式级别。
8. 如果声称 Token 级流式，必须有模型完成前到达 delta 且 tool call 不回归的证据。
9. 前端构建、SDK 构建、目标 Maven 测试和浏览器 smoke test通过。
10. 最终报告列出修改文件、架构决策、测试证据、剩余限制和兼容说明。

## 14. 可直接复制给 Cursor 的执行提示词

以下内容可直接复制到 Cursor Agent。建议让 Cursor 在仓库根目录执行，并允许它持续工作到所有阶段完成；如果一次上下文不足，要求它以本计划为事实源分阶段继续，而不是重新设计。

```text
你现在位于仓库 D:\work\EnterpriseAgentFramework。

任务：完整实施 ReachAI 的统一对话组件，使以下三个入口共享同一套消息模型、对话状态机、消息列表、输入框、流式状态和交互卡片：

1. Agent 调试台：ai-admin-front/src/views/agent/AgentDebug.vue
2. Workflow Studio 调试对话框：ai-admin-front/src/views/workflow/WorkflowStudio.vue
3. 业务前端 Embed Chat：ai-admin-front/src/sdk/eafChat.ts，对外包 @reachai/embed-chat

开始前必须完整阅读：

- 根目录 AGENTS.md
- docs/architecture/unified-conversation-surfaces-implementation-plan.md
- docs/reference/嵌入式对话与页面动作.md
- 与三端相关的真实前后端代码、类型、测试和构建配置

以 docs/architecture/unified-conversation-surfaces-implementation-plan.md 为本任务的实施合同。不要跳过其中的当前事实、阶段、兼容要求、验收矩阵和完成定义。如果计划与当前代码冲突，以当前代码为准，但必须在最终报告中说明差异和采用的处理。

工作方式：

1. 先运行 git status --short 和相关 git diff，识别并保护用户已有改动。禁止 git reset --hard、git checkout --、大范围格式化或覆盖无关修改。
2. 先做 Phase 0 基线检查，记录当前 npm build、SDK build、目标 Maven 测试和三个页面现状。
3. 严格按 Phase 1 到 Phase 7 递进实施。每个阶段完成后运行对应测试；前一阶段未通过时不要继续叠加大范围改动。
4. 不要只交付设计或伪代码；完成实际代码、测试、构建、文档和浏览器 smoke test。
5. 不要为了赶进度复制第三或第四套消息、SSE parser 或交互 renderer。

必须实现的架构边界：

- 新建 framework-independent 的 Conversation Core：公共 types、events、SSE parser、reducer/controller、UiRequest normalizer。
- 定义 AgentDebugTransport、WorkflowWorkingCopyTransport、EmbedTransport，UI 不直接调用具体 API。
- 新建共享 ConversationView、message list、composer、block renderer 和唯一 UnifiedInteractionRenderer。
- 共享 UI 不依赖 Element Plus；管理端 Shell 可以继续使用 Element Plus。
- 同一份 Vue 组件源码既供管理端使用，也编译为 Web Component 给外部业务前端使用。
- Agent、Workflow、Embed 各自保留 Shell：Supervisor 洞察、Workflow 节点轨迹、Page Bridge 等专属能力不得混入公共 UI。

公共消息模型必须支持 blocks，而不是只支持 answer + 单个 uiRequest。至少支持 text、interaction、page_action、notice、error block，并提供旧响应到新模型的兼容适配。

交互协议必须在现有 ai-admin-front/src/types/interaction.ts 基础上收敛为版本化 UiRequestV1。内置 renderer 至少覆盖 form、text_question、select、choice、multi_select、confirm、table、detail、card、report、summary_card、list_card、output_card、page_action 和 custom 安全 fallback。交互状态必须覆盖 waiting、submitting、resolved、failed、expired、cancelled。禁止直接渲染服务端任意 HTML。

迁移要求：

- AgentDebug.vue 使用共享 ConversationView 和 AgentDebugTransport；保留 Supervisor 步骤、Trace、工具、耗时、人工审批、停止与清空 session。
- WorkflowStudio.vue 只替换对话部分；保留节点轨迹、画布高亮、变量快照、Trace 回放和发布版本验证。把 USER_INPUT 初始字段转换为本地 form request；真实 WAITING 交互继续通过 debug session submit 恢复。保留刷新后恢复 debug session。
- eafChat.ts 变成兼容 facade，内部挂载 EmbedTransport + ConversationController + Web Component。保持 createEafChat、EafChatClient、Page Bridge、apiBase/embedPathPrefix、theme、position 和现有 SDK 示例兼容。
- SDK 继续产出 ESM、CJS、UMD、CSS，并补齐可发布的 .d.ts/types 字段。不要把整个 Element Plus 打进 Embed bundle。

流式要求必须分层完成和验证：

A. 执行事件流：
- 复用 Agent Runtime SSE。
- Embed 不再等待同步执行完成后包装 SSE，要校验 Embed Token/session/context 后实时代理 Runtime SSE，并在 completion 时记录 chat event/audit。
- 为 Embed interaction submit 提供可继续流式执行的 additive 路由，保留 JSON 路由。
- 为 Workflow Debug 增加 additive SSE create/submit 路由和节点增量事件，保留现有 REST 路由和回退能力。
- Control 流代理不得用普通 Feign 缓冲；路径必须 allowlist；设置 no-cache、X-Accel-Buffering=no、合理超时，并处理客户端断开。

B. 真正 Token 级文字流：
- 当前 RuntimePublicController、ReachAiAgentScopeChatModel 和 Model Service 的实现还不能安全完成 Agent tool-call 场景的 Token 流。
- 不能使用前端打字机或把完整答案切字符伪装流式。
- 必须定义结构化 ModelStreamEvent，支持 content delta、reasoning delta、tool-call delta、usage、completed、error。
- OpenAI-compatible tool call 的 index/id/name/arguments 片段必须正确聚合。
- Runtime 到 Model Service 使用真正的 streaming HTTP client；AgentScope doStream 必须保持工具调用语义。
- 只把安全的 assistant content delta 暴露为 message.delta，不暴露内部敏感 reasoning。
- 必须用测试证明首 delta 在模型完整回答前到达，且工具调用仍正确。

兼容和安全硬约束：

- 不改变 GraphSpec 运行语义，不用 canvasJson 替代 GraphSpec。
- 不修改数据库 schema，除非先明确证明必要并按 AGENTS.md 的 SQL 规则补齐 initV2/upgrade/docs。
- 不移除现有公共路由；新增流式路由必须是 additive。
- 不在浏览器保存 appSecret、AI Coding Key 或平台管理 Token。
- Page Bridge 继续校验 pageKey/pageInstanceId/session/agent，不允许共享 UI 绕过 Page Bridge 执行动作。
- 不允许任意 HTML/JavaScript 注入。
- Token 刷新、SSE fallback、重试和恢复不能重复发送消息或重复提交 interaction。
- destroy/unmount 必须清理 timer、AbortController、listener 和上游连接。

测试要求：

- 为 reducer、SSE parser、三类 Transport、UiRequest normalizer、renderer registry 和共享组件增加自动化测试。
- SSE parser 覆盖 CRLF、多行 data、跨 chunk frame、JSON/文本、error、abort。
- Workflow 覆盖结构化初始输入、WAITING resume/cancel、session restore、节点轨迹。
- Embed 覆盖 401 token refresh、session rebuild、Page Action、pending polling、interaction submit、destroy cleanup 和 SDK 兼容。
- 后端覆盖事件顺序、completion、error、timeout/断开、无缓冲代理、audit 记录和 tool-call 流聚合。
- 运行 npm run build、npm run build:sdk、目标 Maven 测试、git diff --check。
- 用真实浏览器分别 smoke test Agent 调试台、Workflow Studio 调试抽屉和 Embed 示例，保存关键状态截图或清晰记录。

实施过程中不要停留在“还需要做什么”的说明。只要没有必须由用户决定的产品分歧，就持续完成代码、测试和验证。如果发现现有方案在 tool-call 流、AgentScope API 或 Workflow executor 上有真实技术阻塞，不要伪造完成；先完成不依赖该阻塞的阶段，然后提供最小复现、证据、已尝试方案和准确剩余范围。

最终回复必须包含：

1. 完成的架构和三个入口的复用边界。
2. 修改文件清单。
3. 公共 API、事件协议和 UiRequestV1 摘要。
4. createEafChat 向后兼容说明。
5. 执行事件流与 Token 流分别达到了什么级别，并给出证据。
6. 所有构建、测试、浏览器 smoke test 的命令与结果。
7. 尚未完成或受外部环境限制的项目，禁止含糊表述。
8. 确认没有覆盖用户原有无关修改。
```
