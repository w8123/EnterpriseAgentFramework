/** ReachAI 统一对话消息与 Block 模型（framework-independent）。 */

export type ConversationRole = 'user' | 'assistant' | 'system' | 'runtime'

export type ConversationMessageStatus =
  | 'pending'
  | 'streaming'
  | 'completed'
  | 'failed'
  | 'cancelled'

export type InteractionRenderState =
  | 'waiting'
  | 'submitting'
  | 'resolved'
  | 'failed'
  | 'expired'
  | 'cancelled'

export type ConversationTurnStatus =
  | 'idle'
  | 'sending'
  | 'streaming'
  | 'waiting'
  | 'completed'
  | 'failed'
  | 'cancelled'

export interface UiFieldOptionPayload {
  value: string | number | boolean
  label: string
}

export interface UiFieldPayload {
  key: string
  name?: string
  label: string
  type: string
  required?: boolean
  targetPath?: string
  options?: UiFieldOptionPayload[]
  placeholder?: string
  source?: Record<string, unknown>
}

export interface UiActionPayload {
  key?: string
  action?: string
  label?: string
  type?: string
  values?: Record<string, unknown>
  [key: string]: unknown
}

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
  result?: {
    action: string
    values?: Record<string, unknown>
  }
  errorMessage?: string
}

export interface ConversationPageActionBlock {
  id: string
  type: 'page_action'
  requestId: string
  actionKey: string
  title?: string
  args?: Record<string, unknown>
  status: 'pending' | 'running' | 'success' | 'failed' | 'cancelled' | 'not_found'
  message?: string
}

export interface ConversationNoticeBlock {
  id: string
  type: 'notice'
  level?: 'info' | 'warning' | 'success'
  text: string
}

export interface ConversationErrorBlock {
  id: string
  type: 'error'
  text: string
  retryable?: boolean
}

export type ConversationBlock =
  | ConversationTextBlock
  | ConversationInteractionBlock
  | ConversationPageActionBlock
  | ConversationNoticeBlock
  | ConversationErrorBlock

/**
 * 共享思考详情展示（仅 UI；由 Shell 解析安全数据注入，不写死业务语义）。
 * 不得承载 reasoning / prompt / 原始 CoT。
 */
export type ConversationThinkingStepState = 'complete' | 'active' | 'pending' | 'error'

export interface ConversationThinkingStep {
  id: string
  title: string
  detail?: string
  state: ConversationThinkingStepState
}

export interface ConversationThinkingPresentation {
  /** 例如「本轮推理 4 步」「节点执行 3 步」 */
  label?: string
  count?: number
  steps?: ConversationThinkingStep[]
  expandedLabel?: string
  collapsedLabel?: string
}

export interface ConversationMessage {
  id: string
  role: ConversationRole
  status: ConversationMessageStatus
  blocks: ConversationBlock[]
  createdAt: string
  updatedAt?: string
  /**
   * 本轮本地 turn 关联（内部字段，不进入对外 metadata / Embed completion）。
   */
  turnId?: string
  /**
   * 标记由 Controller 在 fetch 前创建的本地占位消息。
   * 不进入对外 metadata，不伪造服务端 message.started。
   */
  localPlaceholder?: boolean
  /**
   * 服务端 messageId；与本地 id 分离，避免 Vue key 因绑定而闪烁重建。
   */
  transportMessageId?: string
  /** 公开 metadata（对应后端 response.metadata，不得嵌套整份 completion） */
  metadata?: Record<string, unknown>
  /** Embed/Agent 完成信封中的顶层字段（不进入 metadata） */
  completion?: {
    intentType?: unknown
    toolCalls?: unknown
    result?: unknown
  }
}

export interface ConversationSnapshot {
  sessionId?: string
  turnId?: string
  turnStatus: ConversationTurnStatus
  messages: ConversationMessage[]
  error?: string | null
  metadata?: Record<string, unknown>
}

export interface ConversationTurnInput {
  message?: string
  values?: Record<string, unknown>
  interactionId?: string
  uiSubmit?: {
    action: string
    values?: Record<string, unknown>
  }
  metadata?: Record<string, unknown>
}

export interface UiSubmitPayload {
  action: string
  values?: Record<string, unknown>
}

let idCounter = 0

export function createId(prefix = 'c'): string {
  idCounter += 1
  const rand = Math.random().toString(36).slice(2, 8)
  return `${prefix}_${Date.now().toString(36)}_${idCounter}_${rand}`
}

export function nowIso(): string {
  return new Date().toISOString()
}

export function createEmptySnapshot(sessionId?: string): ConversationSnapshot {
  return {
    sessionId,
    turnStatus: 'idle',
    messages: [],
    error: null,
  }
}

export function textContentOf(message: ConversationMessage): string {
  return message.blocks
    .filter((b): b is ConversationTextBlock => b.type === 'text')
    .map((b) => b.text)
    .join('')
}
