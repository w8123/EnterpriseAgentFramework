/** Agent 定义 */
export interface AgentDefinition {
  id: string
  name: string
  description: string
  intentType: string
  systemPrompt: string
  tools: string[]
  modelName: string
  maxSteps: number
  enabled: boolean
  type: 'single' | 'pipeline'
  pipelineAgentIds: string[]
  knowledgeBaseGroupId: string
  promptTemplateId: string
  outputSchemaType: string
  triggerMode: string
  useMultiAgentModel: boolean
  extra: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

/** Agent 创建 / 编辑表单 */
export interface AgentForm {
  name: string
  description: string
  intentType: string
  systemPrompt: string
  tools: string[]
  modelName: string
  maxSteps: number
  enabled: boolean
  type: 'single' | 'pipeline'
  pipelineAgentIds: string[]
  knowledgeBaseGroupId: string
  promptTemplateId: string
  outputSchemaType: string
  triggerMode: string
  useMultiAgentModel: boolean
  extra: Record<string, unknown>
}

/** 预置意图类型（可通过管理后台自定义扩展） */
export const INTENT_TYPES = [
  { value: 'KNOWLEDGE_QA', label: '知识问答' },
  { value: 'QUERY_DATA', label: '数据查询' },
  { value: 'BUSINESS_OPERATION', label: '业务操作' },
  { value: 'ANALYSIS', label: '分析推理' },
  { value: 'CREATIVE_TASK', label: '创意任务' },
  { value: 'GENERAL_CHAT', label: '通用对话' },
] as const

/** 触发方式选项 */
export const TRIGGER_MODES = [
  { value: 'all', label: '全部' },
  { value: 'chat', label: '仅对话' },
  { value: 'api', label: '仅 API' },
  { value: 'event', label: '仅事件' },
] as const

/** Agent 执行结果 */
export interface AgentResult {
  success: boolean
  answer: string
  steps: StepRecord[]
  toolResults: Record<string, unknown>
  metadata: Record<string, unknown>
}

export interface StepRecord {
  name: string
  detail: string
}
