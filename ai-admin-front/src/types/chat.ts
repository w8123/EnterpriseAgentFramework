/** 对话请求 */
export interface ChatRequest {
  message: string
  sessionId?: string
  userId?: string
  intentHint?: string
}

/** 对话响应 */
export interface ChatResponse {
  answer: string
  sessionId: string
  intentType: string
  toolCalls: string[]
  reasoningSteps: string[]
  metadata: Record<string, unknown>
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
}
