export type CanvasNodeKind =
  | 'start'
  | 'end'
  | 'userInput'
  | 'interaction'
  | 'pageAction'
  | 'llm'
  | 'skill'
  | 'tool'
  | 'knowledge'
  | 'condition'
  | 'variable'
  | 'template'
  | 'parameter'
  | 'http'
  | 'answer'
  | 'code'
  | 'classifier'
  | 'aggregate'
  | 'approval'
  | 'loop'
  | 'knowledgeWrite'
  | 'documentExtract'
  | 'mcp'

export type FieldType = 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array' | 'file'
export type ParameterExtractMode = 'expression' | 'llm'
export type ConditionLogic = 'AND' | 'OR'
export type ConditionOperator =
  | 'exists'
  | 'empty'
  | 'not_empty'
  | 'equals'
  | 'not_equals'
  | 'contains'
  | 'not_contains'
  | 'gt'
  | 'gte'
  | 'lt'
  | 'lte'

export interface StudioFieldSchema {
  name: string
  key?: string
  type: FieldType
  required?: boolean
  description?: string
  defaultValue?: string
  source?: string
  targetPath?: string
  component?: string
  datasource?: string
  options?: Array<{ label: string; value: string | number | boolean }>
  slotFilling?: StudioSlotFillingConfig
}

export type StudioSlotFillingStrategy = 'USER_INPUT' | 'RULE' | 'LLM' | 'DICTIONARY'
export type StudioSlotConfirmPolicy = 'NEVER' | 'LOW_CONFIDENCE' | 'ALWAYS'

export interface StudioSlotFillingConfig {
  enabled: boolean
  strategies: StudioSlotFillingStrategy[]
  confirmPolicy: StudioSlotConfirmPolicy
  confidenceThreshold: number
  llmPrompt?: string
  modelInstanceId?: string
  patterns?: string[]
  dictionaryValues?: string[]
}

export interface StudioPort {
  id: string
  name?: string
  type?: FieldType | 'any' | 'file' | 'message'
  required?: boolean
  schema?: string
  source?: string
}

export interface StudioVariableOption {
  value: string
  label: string
  group: '用户输入' | '系统变量' | '节点输出' | '运行态变量' | '高级表达式' | string
  description?: string
  nodeId?: string
  source?: string
}

export interface StudioRetryPolicy {
  enabled: boolean
  maxAttempts: number
  backoffMs: number
}

export interface StudioErrorPolicy {
  strategy: 'TERMINATE' | 'CONTINUE' | 'FALLBACK'
  fallbackNodeId?: string
  defaultOutput?: Record<string, unknown>
}

export interface StudioCondition {
  left: string
  operator: ConditionOperator
  right?: string
}

export interface StudioConditionGroup {
  id: string
  label?: string
  logic: ConditionLogic
  conditions: StudioCondition[]
}

export interface LlmNodeConfig {
  modelInstanceId?: string
  messages?: LlmPromptMessage[]
  systemPrompt?: string
  userPrompt?: string
  contextVariables?: string[]
  modelParams?: Record<string, string | number | boolean>
  outputFormat?: 'text' | 'json'
  structuredOutput?: boolean
  strictJsonSchema?: boolean
  outputSchema?: StudioFieldSchema[]
  visionEnabled?: boolean
  visionInputs?: string[]
  promptTemplateMode?: 'simple' | 'messages'
}

export interface LlmPromptMessage {
  id: string
  role: 'system' | 'user' | 'assistant'
  content: string
  templateEngine?: 'mustache'
  enabled?: boolean
}

export interface KnowledgeNodeConfig {
  knowledgeBaseCodes: string[]
  query: string
  topK: number
  similarityThreshold?: number
  searchMode: string
  rerankEnabled: boolean
  directReturnEnabled?: boolean
  directReturnThreshold?: number
}

export interface HttpNodeConfig {
  method: string
  url: string
  queryParams: Record<string, string>
  headers: Record<string, string>
  bodyType: 'none' | 'json' | 'text'
  body: string
  timeoutMs: number
  credentialRef?: string
}

export interface ParameterNodeConfig {
  mode: ParameterExtractMode
  modelInstanceId?: string
  fields: StudioFieldSchema[]
}

export interface UserInputNodeConfig {
  fields: StudioFieldSchema[]
  outputAlias: string
}

export type InteractionNodeType = 'COLLECT_INPUT' | 'PRESENT_OUTPUT' | 'USER_CHOICE' | 'CONFIRM_ACTION' | 'REVIEW_EDIT'
export type InteractionBindingSourceKind = 'NONE' | 'TOOL' | 'COMPOSITION' | 'API'

export interface InteractionBindingConfig {
  sourceKind: InteractionBindingSourceKind
  ref?: string
  qualifiedName?: string | null
  projectCode?: string | null
  projectId?: number | null
  apiNodeId?: number | null
  apiMethod?: string | null
  apiPath?: string | null
  generatedFrom?: string
  autoCreateCallNode?: boolean
  autoCreateDisplayNode?: boolean
  callNodeId?: string
  displayNodeId?: string
}

export interface InteractionCallNodeRequest {
  sourceKind: InteractionBindingSourceKind
  ref: string
  qualifiedName?: string | null
  projectCode?: string | null
  projectId?: number | null
  visibility?: string | null
  apiMethod?: string | null
  apiPath?: string | null
  responseType?: string | null
  autoCreateDisplayNode?: boolean
  label?: string
  description?: string
  outputAlias: string
  inputMapping: Record<string, string>
}

export interface InteractionNodeConfig {
  interactionType: InteractionNodeType
  qualifiedName?: string
  binding?: InteractionBindingConfig
  title: string
  component: 'FORM' | 'DETAIL' | 'TABLE' | 'CARD' | 'REPORT' | 'CUSTOM'
  fields: StudioFieldSchema[]
  dataExpression?: string
  outputAlias: string
  dataSources?: Record<string, unknown>
  behavior?: Record<string, unknown>
  renderSchema?: Record<string, unknown>
}

export interface PageActionNodeConfig {
  actionKey: string
  title: string
  confirm: boolean
  args: Record<string, string>
  outputAlias: string
  metadata?: Record<string, unknown>
}

export interface AnswerNodeConfig {
  template: string
}

export interface CodeNodeConfig {
  language: 'expression'
  code?: string
  outputs: Record<string, string>
}

export interface IntentClassConfig {
  id: string
  label?: string
  description?: string
  keywords: string[]
}

export interface IntentClassifierNodeConfig {
  inputExpression: string
  classes: IntentClassConfig[]
  defaultRoute?: string
}

export interface VariableAggregateItem {
  name: string
  source: string
}

export interface VariableAggregateNodeConfig {
  mode: 'object' | 'array' | 'text'
  items: VariableAggregateItem[]
  template?: string
}

export interface HumanApprovalNodeConfig {
  title: string
  prompt: string
  approvers: string[]
  timeoutSeconds?: number
  defaultRoute?: string
}

export interface LoopNodeConfig {
  loopKey: string
  maxIterations: number
  itemExpression?: string
  breakCondition?: string
}

export interface KnowledgeWriteNodeConfig {
  knowledgeBaseCode: string
  titleExpression: string
  contentExpression: string
  tags: string[]
  mode: 'draft' | 'publish'
}

export interface DocumentExtractNodeConfig {
  sourceExpression: string
  format: 'text' | 'markdown' | 'json'
  fields: StudioFieldSchema[]
}

export interface McpNodeConfig {
  serverRef?: string
  toolName: string
  inputMapping: Record<string, string>
}

export interface ConditionNodeConfig {
  groups: StudioConditionGroup[]
  defaultRoute?: string
}

export interface ToolNodeConfig {
  ref?: string
  qualifiedName?: string | null
  projectCode?: string | null
  visibility?: string | null
  credentialRef?: string
  maxRequestTimeMs?: number
  inputMapping: Record<string, string>
  mappingNote?: string
}

export interface CanvasNodeData {
  label: string
  kind: CanvasNodeKind
  configVersion: 2
  description?: string
  source?: 'CANVAS' | 'SDK'
  icon?: string
  category?: string
  collapsed?: boolean
  inputs?: StudioPort[]
  outputs?: StudioPort[]
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  inputMapping?: Record<string, string>
  retry?: StudioRetryPolicy
  errorPolicy?: StudioErrorPolicy
  outputAlias?: string
  needsConfiguration?: boolean
  placeholderReason?: string
  userInputConfig?: UserInputNodeConfig
  interactionConfig?: InteractionNodeConfig
  pageActionConfig?: PageActionNodeConfig
  llmConfig?: LlmNodeConfig
  knowledgeConfig?: KnowledgeNodeConfig
  httpConfig?: HttpNodeConfig
  parameterConfig?: ParameterNodeConfig
  conditionConfig?: ConditionNodeConfig
  answerConfig?: AnswerNodeConfig
  codeConfig?: CodeNodeConfig
  classifierConfig?: IntentClassifierNodeConfig
  aggregateConfig?: VariableAggregateNodeConfig
  approvalConfig?: HumanApprovalNodeConfig
  loopConfig?: LoopNodeConfig
  knowledgeWriteConfig?: KnowledgeWriteNodeConfig
  documentExtractConfig?: DocumentExtractNodeConfig
  mcpConfig?: McpNodeConfig
  toolConfig?: ToolNodeConfig
  assignments?: Record<string, string>
  template?: string
  writeToAnswer?: boolean
}

export interface CanvasNode {
  id: string
  type: CanvasNodeKind
  position: { x: number; y: number }
  data: CanvasNodeData
}

export interface CanvasEdge {
  id: string
  source: string
  target: string
  label?: string
  condition?: string
  sourceHandle?: string
  targetHandle?: string
  type?: string
  class?: string
  animated?: boolean
  markerEnd?: string
  interactionWidth?: number
}

export interface CanvasSnapshot {
  version: 2
  nodes: CanvasNode[]
  edges: CanvasEdge[]
}
