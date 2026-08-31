export interface ModelChatRequest {
  modelInstanceId?: string
  messages: ModelChatMessage[]
  options?: Record<string, unknown>
  tools?: unknown[]
  toolChoice?: string | Record<string, unknown>
}

export interface ModelChatMessage {
  role: 'system' | 'user' | 'assistant' | 'tool'
  content: string
  reasoningContent?: string
  toolCalls?: ModelStreamToolCall[] | unknown[]
  toolCallId?: string
  name?: string
  finishReason?: string
  /** Present when stream ended via abort / interrupt / error with partial content. */
  incompleteReason?: 'aborted' | 'interrupted' | 'error'
  errorCode?: string
  errorMessage?: string
}

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

/** Structured model stream event types — mirror ModelStreamEvent on model-service. */
export type ModelStreamEventType =
  | 'content.delta'
  | 'reasoning.delta'
  | 'tool_call.delta'
  | 'usage'
  | 'completed'
  | 'error'

export interface ModelStreamToolCallDelta {
  index?: number | null
  id?: string | null
  type?: string | null
  name?: string | null
  arguments?: string | null
}

export interface ModelStreamToolCall {
  index: number
  id?: string
  type?: string
  name?: string
  arguments: string
}

export interface ModelStreamEvent {
  type: ModelStreamEventType | string
  text?: string | null
  toolCall?: ModelStreamToolCallDelta | null
  usage?: TokenUsage | null
  finishReason?: string | null
  message?: string | null
  code?: string | null
  raw?: unknown
}

export type ModelStreamTerminalReason = 'completed' | 'error' | 'aborted' | 'interrupted' | null

export interface ModelStreamState {
  content: string
  reasoningContent: string
  toolCalls: ModelStreamToolCall[]
  usage: TokenUsage | null
  finishReason: string | null
  errorCode: string | null
  errorMessage: string | null
  terminal: ModelStreamTerminalReason
}

export const MODEL_STREAM_INTERRUPTED = 'MODEL_STREAM_INTERRUPTED'

export type ModelType = 'LLM' | 'EMBEDDING' | 'RERANKER'
export type ModelProtocol = 'OPENAI_COMPATIBLE'
export type ModelInstanceStatus = 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
export type ModelTestStatus = 'UNKNOWN' | 'SUCCESS' | 'FAILED'
export type ModelLifecycleStatus = 'UNKNOWN' | 'PREVIEW' | 'ACTIVE' | 'DEPRECATED' | 'RETIRED'
export type ModelRecommendationStatus = 'UNASSESSED' | 'EVAL_VERIFIED' | 'MANUAL'

export interface ModelConnectionConfig {
  baseUrl?: string
  chatPath?: string
  embeddingPath?: string
  rerankPath?: string
  apiKey?: string
  authHeader?: string
  authPrefix?: string
}

export interface ModelCredentialSchemaField {
  key: string
  label: string
  required?: boolean
  secret?: boolean
}

export interface ModelInstance {
  id: string
  name: string
  provider: string
  modelType: ModelType
  modelName: string
  protocol: ModelProtocol | string
  projectCode?: string | null
  connection: ModelConnectionConfig
  defaultOptions: Record<string, unknown>
  paramsSchema: unknown
  status: ModelInstanceStatus
  lastTestStatus: ModelTestStatus
  lastTestAt?: string | null
  lastTestLatencyMs?: number | null
  lastTestError?: string | null
  remark?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface ModelTemplate {
  id: string
  name: string
  provider: string
  modelType: ModelType
  modelName: string
  protocol: ModelProtocol | string
  connectionDefaults: ModelConnectionConfig
  credentialSchema: ModelCredentialSchemaField[] | unknown
  defaultOptions: Record<string, unknown>
  paramsSchema: unknown
  capabilities?: unknown
  sourceKey?: string | null
  lifecycleStatus?: ModelLifecycleStatus
  recommendationStatus?: ModelRecommendationStatus
  recommendationTier?: string | null
  recommendationReason?: string | null
  officialPositioning?: string | null
  releasedAt?: string | null
  deprecatedAt?: string | null
  retireAt?: string | null
  replacementModelName?: string | null
  lastSeenAt?: string | null
  lastVerifiedAt?: string | null
  sourceUrl?: string | null
  sourceRevision?: string | null
  syncManaged?: boolean
  iconKey?: string | null
  enabled: boolean
  sortOrder?: number
  remark?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface ModelInstanceCreateRequest {
  name: string
  provider: string
  modelType: ModelType
  modelName: string
  protocol?: ModelProtocol
  connection: ModelConnectionConfig
  defaultOptions?: Record<string, unknown>
  paramsSchema?: unknown
  status?: 'ACTIVE' | 'DISABLED'
  remark?: string | null
}

export interface ModelInstanceFromTemplateRequest {
  name: string
  modelName?: string
  connection?: ModelConnectionConfig
  defaultOptions?: Record<string, unknown>
  status?: 'ACTIVE' | 'DISABLED'
  remark?: string | null
}

export interface ModelInstanceUpdateRequest {
  name: string
  provider?: string
  modelName?: string
  connection?: ModelConnectionConfig
  defaultOptions?: Record<string, unknown>
  paramsSchema?: unknown
  status?: 'ACTIVE' | 'DISABLED'
  remark?: string | null
}

export interface ModelInstanceDraftTestRequest {
  id?: string
  name?: string
  provider: string
  modelType: ModelType
  modelName: string
  protocol?: ModelProtocol
  connection: ModelConnectionConfig
  defaultOptions?: Record<string, unknown>
}

export interface ModelInstanceTestResult {
  success: boolean
  latencyMs: number
  message: string
  modelInstanceId?: string
  provider?: string
  modelName?: string
  modelType?: string
  lastTestStatus?: ModelTestStatus
  dimension?: number
}

export interface ModelInstanceListParams {
  keyword?: string
  provider?: string
  modelType?: ModelType | string
  projectCode?: string
  includeArchived?: boolean
}

export interface ModelTemplateListParams {
  keyword?: string
  provider?: string
  modelType?: ModelType | string
  enabled?: boolean
}

export interface ModelCatalogSourceStatus {
  sourceKey: string
  provider: string
  name: string
  sourceKind: string
  sourceUrl: string
  lastCheckedAt?: string | null
  lastSuccessAt?: string | null
  lastRunStatus?: string | null
  attemptCount?: number | null
  candidateCount?: number | null
  publishedCount?: number | null
  reviewCount?: number | null
  errorCode?: string | null
  errorMessage?: string | null
}

export interface ModelCatalogStatus {
  enabled: boolean
  autoSyncEnabled: boolean
  zoneId: string
  businessDate: string
  analyzerConfigured: boolean
  stale: boolean
  catalogVerifiedAt?: string | null
  lastAnySuccessfulAt?: string | null
  sourceCount: number
  completedToday: number
  message: string
  sources: ModelCatalogSourceStatus[]
}

export interface ModelCatalogSettingsRequest {
  autoSyncEnabled: boolean
}

export interface ModelCatalogManualSyncResponse {
  businessDate: string
  accepted: boolean
  sourceCount: number
  completedToday: number
  queuedCount: number
  message: string
}
