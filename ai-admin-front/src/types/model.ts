/** 模型 Provider 信息 */
export interface ProviderInfo {
  name: string
  models: string[]
}

/** 模型对话请求 */
export interface ModelChatRequest {
  provider?: string
  model?: string
  messages: ModelChatMessage[]
  options?: Record<string, unknown>
  /** OpenAI 格式 tools（如 MiMo 多轮工具调用） */
  tools?: unknown[]
  toolChoice?: string | Record<string, unknown>
}

export interface ModelChatMessage {
  role: 'system' | 'user' | 'assistant' | 'tool'
  content: string
  /** MiMo 思考链，多轮需随 assistant 回传 */
  reasoningContent?: string
  toolCalls?: unknown[]
  toolCallId?: string
  name?: string
}

/** 模型对话响应 */
export interface ModelChatResponse {
  content: string
  model: string
  provider: string
  usage: TokenUsage
  reasoningContent?: string
  toolCalls?: unknown
  finishReason?: string
}

export interface TokenUsage {
  promptTokens: number
  completionTokens: number
  totalTokens: number
}

/** Provider 连通性测试结果 */
export interface ProviderTestResult {
  provider: string
  success: boolean
  message: string
  latencyMs: number
}
