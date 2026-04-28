import type { UiRequestPayload, UiSubmitPayload } from './interaction'

/** 对话请求 */
export interface ChatRequest {
  message?: string
  sessionId?: string
  userId?: string
  intentHint?: string
  /** 调试台：直执该 agent 定义，跳过意图路由 */
  agentDefinitionId?: string
  roles?: string[]
  /** 恢复挂起的 InteractiveFormSkill */
  interactionId?: string
  uiSubmit?: UiSubmitPayload
}

/** 对话响应 */
export interface ChatResponse {
  answer: string
  sessionId?: string
  intentType?: string
  toolCalls?: string[]
  reasoningSteps?: string[]
  metadata?: Record<string, unknown>
  uiRequest?: UiRequestPayload
}

/** 对话消息（前端展示用） */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  timestamp: number
  loading?: boolean
  toolCalls?: string[]
  reasoningSteps?: string[]
  traceId?: string
  /** 交互式表单挂起 */
  uiRequest?: UiRequestPayload
}
