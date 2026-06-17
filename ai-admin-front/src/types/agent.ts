import type { AgentEntryKind, AgentEntryVisibility } from './workflow'

export type { AgentEntry, AgentEntryKind, AgentEntryVisibility } from './workflow'

/** AgentEntry 创建 / 编辑表单（身份与策略，不含 Workflow GraphSpec） */
export interface AgentEntryForm {
  keySlug: string
  name: string
  description?: string | null
  agentKind?: AgentEntryKind | null
  projectId?: number | null
  projectCode?: string | null
  visibility?: AgentEntryVisibility | null
  systemPrompt?: string | null
  modelInstanceId?: string | null
  allowedRoles?: string[]
  entryConfig?: Record<string, unknown>
  enabled?: boolean | null
}

export interface CapabilityReference {
  kind: 'TOOL' | 'SKILL'
  projectCode?: string | null
  name: string
  qualifiedName?: string | null
  definitionId?: number | null
  version?: string | null
}

export type AgentRuntimeType = 'AGENTSCOPE' | 'LANGGRAPH4J' | 'OPENAI_AGENTS' | 'CURSOR_CODE_AGENT'
export type AgentRuntimePlacement = 'CENTRAL' | 'EMBEDDED' | 'HYBRID' | 'CAPABILITY_HOST'
export type RuntimeRegistryRole = 'AGENT_RUNTIME' | 'CAPABILITY_HOST' | string
export type AgentMode = 'AUTONOMOUS' | 'WORKFLOW' | 'CODE' | 'EXTERNAL'
export type AgentConfigurationSurface = 'FORM' | 'STUDIO' | 'CODE_WORKSPACE' | 'EXTERNAL_CONSOLE' | string

/** Workflow 运行语义（GraphSpec）；画布布局见 canvasJson */
export interface AgentGraphSpec {
  code?: string
  name?: string
  mode?: 'WORKFLOW' | 'AUTONOMOUS'
  runtimeHint?: AgentRuntimeType
  inputSchema?: Record<string, unknown>
  stateSchema?: Record<string, unknown>
  layout?: AgentGraphLayout
  nodes: AgentGraphNode[]
  edges: AgentGraphEdge[]
  entry?: string
  finish?: string[]
}

export interface AgentGraphNode {
  id: string
  type:
    | 'LLM'
    | 'USER_INPUT'
    | 'INTERACTION'
    | 'PAGE_ACTION'
    | 'TOOL'
    | 'CAPABILITY'
    | 'IF_ELSE'
    | 'VARIABLE_ASSIGN'
    | 'TEMPLATE'
    | 'ANSWER'
    | 'CODE'
    | 'INTENT_CLASSIFIER'
    | 'VARIABLE_AGGREGATOR'
    | 'HUMAN_APPROVAL'
    | 'LOOP'
    | 'KNOWLEDGE_WRITE'
    | 'DOCUMENT_EXTRACT'
    | 'MCP_CALL'
    | 'PARAMETER_EXTRACT'
    | 'HTTP_REQUEST'
    | 'KNOWLEDGE_RETRIEVAL'
    | 'START'
    | 'END'
  name?: string
  description?: string
  ref?: AgentGraphCapabilityRef
  inputs?: AgentGraphPort[]
  outputs?: AgentGraphPort[]
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  retry?: AgentGraphRetryPolicy
  errorPolicy?: AgentGraphErrorPolicy
  layout?: AgentGraphNodeLayout
  config?: Record<string, unknown>
}

export interface AgentGraphEdge {
  id?: string
  from: string
  to: string
  condition?: string
  sourceHandle?: string
  targetHandle?: string
  priority?: number
  layout?: AgentGraphEdgeLayout
}

export interface AgentGraphCapabilityRef {
  kind: 'TOOL' | 'SKILL' | 'CAPABILITY' | 'INTERACTION'
  name?: string
  qualifiedName?: string
  definitionId?: number | null
  projectCode?: string | null
}

export interface AgentGraphPort {
  id: string
  name?: string
  type?: string
  required?: boolean
  schema?: string
  source?: string
}

export interface AgentGraphRetryPolicy {
  enabled?: boolean
  maxAttempts?: number
  backoffMs?: number
}

export interface AgentGraphErrorPolicy {
  strategy?: 'TERMINATE' | 'CONTINUE' | 'FALLBACK' | string
  fallbackNodeId?: string
  defaultOutput?: Record<string, unknown>
}

export interface AgentGraphLayout {
  engine?: string
  direction?: 'LR' | 'TB' | string
  viewport?: Record<string, unknown>
}

export interface AgentGraphNodeLayout {
  x?: number
  y?: number
  width?: number
  height?: number
  collapsed?: boolean
}

export interface AgentGraphEdgeLayout {
  label?: string
  style?: string
}

export interface WorkflowDraftResource {
  kind: 'TOOL' | 'SKILL' | 'CAPABILITY' | 'KNOWLEDGE' | string
  name: string
  qualifiedName?: string | null
  definitionId?: number | null
  projectCode?: string | null
  description?: string | null
  metadata?: Record<string, unknown> | null
}

export interface WorkflowDraftPlaceholder {
  nodeId: string
  kind: string
  label: string
  reason: string
}

export interface WorkflowDraftGenerationRequest {
  agentId?: string
  agentName?: string
  requirement: string
  projectCode?: string | null
  modelInstanceId?: string
  draftScenario?: 'PAGE_ASSISTANT' | string
  currentCanvas?: Record<string, unknown>
  tools?: WorkflowDraftResource[]
  capabilities?: WorkflowDraftResource[]
  knowledgeBases?: WorkflowDraftResource[]
  pageActions?: WorkflowDraftResource[]
}

export interface WorkflowDraftGenerationResult {
  provider: string
  canvasSnapshot: Record<string, unknown>
  graphSpec: AgentGraphSpec
  warnings: string[]
  placeholderNodes: WorkflowDraftPlaceholder[]
  validationErrors: string[]
}

export type WorkflowDraftEditOperationType =
  | 'ADD_NODE'
  | 'UPDATE_NODE'
  | 'DELETE_NODE'
  | 'ADD_EDGE'
  | 'UPDATE_EDGE'
  | 'DELETE_EDGE'

export interface WorkflowDraftEditOperation {
  type: WorkflowDraftEditOperationType
  nodeId?: string
  edgeId?: string
  node?: Record<string, unknown>
  edge?: Record<string, unknown>
  patch?: Record<string, unknown>
  reason?: string
}

export interface WorkflowDraftEditRequest {
  agentId?: string
  agentName?: string
  instruction: string
  projectCode?: string | null
  modelInstanceId?: string
  currentCanvas?: Record<string, unknown>
  selectedNodeIds?: string[]
  selectedEdgeIds?: string[]
  tools?: WorkflowDraftResource[]
  capabilities?: WorkflowDraftResource[]
  knowledgeBases?: WorkflowDraftResource[]
  pageActions?: WorkflowDraftResource[]
}

export interface WorkflowDraftEditResult {
  provider: string
  summary: string
  operations: WorkflowDraftEditOperation[]
  canvasSnapshot: Record<string, unknown>
  graphSpec: AgentGraphSpec
  warnings: string[]
  placeholderNodes: WorkflowDraftPlaceholder[]
  validationErrors: string[]
}

export interface AgentGraphNodeTypeDescriptor {
  type: AgentGraphNode['type']
  canvasKind: string
  canvasCategory: string
  family: 'LLM' | 'TOOL' | 'FLOW' | string
  retryable: boolean
  aliases: string[]
}

/**
 * Workflow 画布互操作过渡类型（@deprecated）。
 * 仅供 `studio.ts` / `workflowStudio.ts` 在 GraphSpec ↔ canvas_json 之间转换。
 * Agent 管理主类型请用 `AgentEntry`；编排主类型请用 `WorkflowDefinition`。
 */
export interface WorkflowCanvasSource {
  id?: string
  keySlug?: string
  canvasJson?: string
  graphSpec?: AgentGraphSpec | null
  modelInstanceId?: string
  systemPrompt?: string
  extra?: Record<string, unknown>
}

/**
 * @deprecated 历史名称，等价于 {@link WorkflowCanvasSource}。新代码请直接使用 WorkflowCanvasSource。
 */
export type AgentDefinition = WorkflowCanvasSource

/** Agent 创建 / 编辑表单 */
export interface AgentForm {
  keySlug?: string
  name: string
  description: string
  agentMode?: AgentMode
  projectId?: number | null
  projectCode?: string | null
  visibility?: 'PRIVATE' | 'PROJECT' | 'SHARED' | 'PUBLIC'
  allowedRoles?: string[]
  intentType: string
  systemPrompt: string
  tools: string[]
  toolRefs?: CapabilityReference[]
  skills: string[]
  skillRefs?: CapabilityReference[]
  modelInstanceId: string
  runtimeType?: AgentRuntimeType
  runtimePlacement: AgentRuntimePlacement
  runtimeConfig: Record<string, unknown>
  defaultResourceConfig: Record<string, unknown>
  graphSpec?: AgentGraphSpec | null
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
  canvasJson?: string
  allowIrreversible?: boolean
}

export interface AgentRuntimeCapability {
  runtimeType: AgentRuntimeType
  displayName: string
  description?: string
  agentMode?: AgentMode
  configurationSurface?: AgentConfigurationSurface
  primaryAction?: string
  resourcePolicy?: string
  available: boolean
  unavailableReason?: string
  supportedModelTypes?: string[]
  supportsStreaming: boolean
  supportsTools: boolean
  supportsHandoff: boolean
  supportsGraph: boolean
  supportsHumanInterrupt: boolean
  supportsArtifacts: boolean
  supportsCodeWorkspace: boolean
  supportsCloudExecution: boolean
  securityLevel?: string
}

export interface AgentRuntimeValidationResult {
  valid: boolean
  runtimeType?: string
  modelInstanceId?: string
  modelType?: string
  provider?: string
  message?: string
  errorCode?: string
}

export interface RuntimeRegistryEntry {
  id: string
  source: 'PLATFORM' | 'PROJECT_INSTANCE'
  runtimeRole: RuntimeRegistryRole
  runtimeType: string
  displayName?: string
  description?: string
  runtimePlacement: AgentRuntimePlacement
  status: 'ONLINE' | 'OFFLINE' | 'DISABLED' | 'STALE' | string
  available: boolean
  unavailableReason?: string
  supportsGraph: boolean
  supportsTools: boolean
  supportsAutonomous: boolean
  supportsWorkflow: boolean
  supportsEmbeddedExecution: boolean
  supportsHybridExecution: boolean
  projectCode?: string | null
  instanceId?: string | null
  baseUrl?: string | null
  host?: string | null
  port?: number | null
  appVersion?: string | null
  sdkVersion?: string | null
  lastHeartbeatAt?: string | null
  policyDisabled?: boolean
  minSdkVersion?: string | null
  allowEmbeddedExecution?: boolean | null
  allowHybridExecution?: boolean | null
  policyMessage?: string | null
  runtimeTypes?: string[]
  metadata?: Record<string, unknown>
}

/** Agent 发布版本（对应后端 agent_version 表） */
export interface AgentVersion {
  id: number
  agentId: string
  version: string
  snapshotJson: string
  rolloutPercent: number
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED'
  publishedBy?: string
  publishedAt?: string
  note?: string
  createTime: string
}

export interface AgentReleaseValidationItem {
  code: string
  level: 'ERROR' | 'WARN' | string
  nodeId?: string | null
  message: string
}

export interface AgentReleaseValidationResult {
  valid: boolean
  errors: AgentReleaseValidationItem[]
  warnings: AgentReleaseValidationItem[]
}

export interface AgentReleaseEvent {
  id: number
  agentId: string
  versionId?: number | null
  version?: string | null
  action: 'VALIDATE' | 'PUBLISH' | 'ROLLBACK' | string
  decision: 'PASSED' | 'BLOCKED' | 'COMPLETED' | string
  rolloutPercent?: number | null
  operator?: string | null
  summary?: string | null
  validationJson?: string | null
  metadataJson?: string | null
  createdAt: string
}

export interface AgentNodeDebugRequest {
  agentDefinition: Partial<AgentForm>
  nodeId: string
  message?: string
  state?: Record<string, unknown>
}

export interface AgentNodeDebugResult {
  nodeId: string
  nodeType?: string
  success: boolean
  elapsedMs?: number
  inputState?: Record<string, unknown>
  outputState?: Record<string, unknown>
  nodeOutput?: unknown
  lastRoute?: string
  errorCode?: string
  errorMessage?: string
  traceId?: string
}

export interface AgentWorkflowDebugRunRequest {
  agentDefinition: Partial<AgentForm>
  message?: string
  inputParams?: Record<string, unknown>
  debugOptions?: Record<string, unknown>
}

export interface AgentWorkflowDebugStepResult {
  index: number
  nodeId: string
  nodeType?: string
  nodeName?: string
  status: 'SUCCESS' | 'ERROR' | 'WAITING' | string
  startedAt?: string
  endedAt?: string
  elapsedMs?: number
  input?: Record<string, unknown>
  output?: unknown
  rawOutput?: unknown
  publishedVariables?: Record<string, unknown>
  statePatch?: Record<string, unknown>
  eventType?: 'NODE' | 'WAITING' | 'OUTPUT' | 'ERROR' | string
  uiRequest?: UiRequestPayload
  artifact?: Record<string, unknown>
  route?: string
  condition?: string
  nextNodeId?: string
  errorCode?: string
  errorMessage?: string
}

export interface AgentWorkflowDebugRunResult {
  runId: string
  traceId?: string
  sessionId?: string
  targetType?: string
  success: boolean
  status: 'SUCCESS' | 'ERROR' | 'WAITING' | string
  answer?: string
  currentNodeId?: string
  messages?: ExecutableDebugMessage[]
  uiRequest?: UiRequestPayload
  steps: AgentWorkflowDebugStepResult[]
  finalState?: Record<string, unknown>
  errorCode?: string
  errorMessage?: string
}

export interface ExecutableDebugMessage {
  id: string
  role: 'user' | 'assistant' | 'system' | 'runtime' | string
  content: string
  nodeId?: string
  traceId?: string
  uiRequest?: UiRequestPayload
  createdAt?: string
}

export interface ExecutableDebugSessionCreateRequest {
  targetType: 'AGENT_DRAFT' | 'COMPOSITION_DRAFT' | 'EXECUTABLE_DRAFT' | string
  draftDefinition: Record<string, unknown>
  message?: string
  inputParams?: Record<string, unknown>
  debugOptions?: Record<string, unknown>
}

export interface ExecutableDebugSessionSubmitRequest {
  action?: string
  values?: Record<string, unknown>
  message?: string
}

export interface ExecutableDebugSessionView extends AgentWorkflowDebugRunResult {
  sessionId: string
  targetType: string
  messages: ExecutableDebugMessage[]
  createdAt?: string
  updatedAt?: string
  expiresAt?: string
}

/** 发布请求体 */
export interface PublishVersionRequest {
  version: string
  rolloutPercent?: number
  note?: string
  publishedBy?: string
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

import type { UiRequestPayload } from './interaction'

/** Agent 执行结果 */
export interface AgentResult {
  success: boolean
  answer: string
  steps?: StepRecord[]
  toolResults?: Record<string, unknown>
  metadata?: Record<string, unknown>
  uiRequest?: UiRequestPayload
}

export interface PendingHumanApproval {
  interactionId: string
  traceId?: string
  sessionId?: string
  userId?: string
  agentId?: number
  nodeId: string
  status: string
  createdAt?: string
  updatedAt?: string
  expiresAt?: string
  title?: string
  message?: string
  uiRequest?: UiRequestPayload
  state?: Record<string, unknown>
}

export interface StepRecord {
  name: string
  detail: string
}
