import type { AgentVisibility } from './workflow'
import type { CanvasSnapshot } from './studio'
import type { AgentSkillBindingConfig } from './skill'

export type { Agent, AgentVisibility } from './workflow'

/** Agent 创建 / 编辑表单。Supervisor 配置通过 AgentConfigDraft 独立保存。 */
export interface AgentIdentityForm {
  keySlug: string
  name: string
  description?: string | null
  projectId?: number | null
  projectCode?: string | null
  visibility?: AgentVisibility | null
  allowedRoles?: string[]
  enabled?: boolean | null
}

export interface AgentWorkflowToolConfig {
  id?: number
  workflowId: string
  workflowKeySlug?: string
  workflowName?: string
  workflowVersion?: string
  workflowVersionId?: number
  toolName: string
  descriptionOverride?: string | null
  description?: string | null
  inputSchemaOverrideJson?: string | null
  outputSchemaOverrideJson?: string | null
  riskLevel?: 'READ' | 'WRITE' | string
  permissionKey?: string | null
  readOnly?: boolean
  enabled?: boolean
  priority?: number
}

/** Immutable A2A Hub remote-Agent binding snapshot attached to one Agent config version. */
export interface AgentA2aRemoteBindingConfig {
  id?: number
  principalId: number
  remoteAgentId: number
  remoteAgentRevisionId: number
  remoteAgentKey?: string
  toolName: string
  description?: string | null
  allowedSkillIds: string[]
  inputModes?: string[]
  outputModes?: string[]
  riskLevel: 'READ' | 'WRITE' | 'IRREVERSIBLE' | string
  permissionKey: string
  timeoutMs?: number | null
  enabled?: boolean
  priority?: number
}

export interface AgentConfigVersion {
  id: number
  agentId: string
  versionNo: number
  status: 'DRAFT' | 'ACTIVE' | 'ARCHIVED' | string
  runtimeType: 'AGENTSCOPE' | string
  systemPrompt?: string | null
  modelInstanceId?: string | null
  maxPlanSteps: number
  maxWorkflowCalls: number
  maxReplans: number
  totalTimeoutMs: number
  workflowTimeoutMs: number
  pageBridgeTimeoutMs: number
  parallelReadOnly: boolean
  policyProfile: string
  toolCatalogMode: 'ALLOW_LIST' | string
  configJson?: string | null
  publishedBy?: string | null
  publishedAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  tools: AgentWorkflowToolConfig[]
  skills?: AgentSkillBindingConfig[]
  remoteAgents?: AgentA2aRemoteBindingConfig[]
}

export type AgentConfigDraft = Omit<AgentConfigVersion,
  'id' | 'agentId' | 'versionNo' | 'status' | 'publishedBy' | 'publishedAt' | 'createdAt' | 'updatedAt'>

export interface CapabilityReference {
  kind: 'TOOL'
  projectCode?: string | null
  name: string
  qualifiedName?: string | null
  definitionId?: number | null
  version?: string | null
}

export type AgentRuntimeType = 'AGENTSCOPE' | 'LANGGRAPH4J' | 'OPENAI_AGENTS' | 'CURSOR_CODE_AGENT'
export type AgentRuntimePlacement = 'CENTRAL' | 'EMBEDDED' | 'HYBRID' | 'CAPABILITY_HOST'
export type AgentMode = 'AUTONOMOUS' | 'WORKFLOW' | 'CODE' | 'EXTERNAL'
export type AgentConfigurationSurface = 'FORM' | 'STUDIO' | 'CODE_WORKSPACE' | 'EXTERNAL_CONSOLE' | string

/** Workflow 运行语义（GraphSpec）；画布布局见 canvasJson */
export interface WorkflowGraphSpec {
  schemaVersion: 2
  entryNodeId: string
  exitNodeIds: string[]
  inputSchema?: Record<string, unknown>
  stateSchema?: Record<string, unknown>
  nodes: WorkflowGraphNode[]
  edges: WorkflowGraphEdge[]
}

export interface WorkflowGraphNode {
  id: string
  type:
    | 'LLM'
    | 'USER_INPUT'
    | 'INTERACTION'
    | 'PAGE_ACTION'
    | 'TOOL'
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
  name?: string
  description?: string
  ref?: WorkflowGraphCapabilityRef
  inputs?: WorkflowGraphPort[]
  outputs?: WorkflowGraphPort[]
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  retry?: WorkflowGraphRetryPolicy
  errorPolicy?: WorkflowGraphErrorPolicy
  config?: Record<string, unknown>
}

export interface WorkflowGraphEdge {
  id?: string
  from: string
  to: string
  condition?: string
  sourceHandle?: string
  targetHandle?: string
  priority?: number
}

  export interface WorkflowGraphCapabilityRef {
  kind: 'TOOL' | 'INTERACTION'
  name?: string
  qualifiedName?: string
  definitionId?: number | null
    projectCode?: string | null
    contractHash?: string | null
}

export interface WorkflowGraphPort {
  id: string
  name?: string
  type?: string
  required?: boolean
  schema?: string
  source?: string
}

export interface WorkflowGraphRetryPolicy {
  enabled?: boolean
  maxAttempts?: number
  backoffMs?: number
}

export interface WorkflowGraphErrorPolicy {
  strategy?: 'TERMINATE' | 'CONTINUE' | 'FALLBACK' | string
  fallbackNodeId?: string
  defaultOutput?: Record<string, unknown>
}

export interface WorkflowProposalResource {
  kind: 'TOOL' | 'KNOWLEDGE' | string
  name: string
  qualifiedName?: string | null
  definitionId?: number | null
  projectCode?: string | null
  description?: string | null
  metadata?: Record<string, unknown> | null
}

export interface WorkflowProposalPlaceholder {
  nodeId: string
  kind: string
  label: string
  reason: string
}

export interface WorkflowProposalGenerationRequest {
  workflowId?: string
  workflowName?: string
  requirement: string
  projectCode?: string | null
  modelInstanceId?: string
  workflowKind?: 'GENERAL' | 'PAGE_ASSISTANT' | string
  tools?: WorkflowProposalResource[]
  capabilities?: WorkflowProposalResource[]
  knowledgeBases?: WorkflowProposalResource[]
  pageActions?: WorkflowProposalResource[]
}

export interface WorkflowProposalGenerationResult {
  provider: string
  canvasSnapshot: Record<string, unknown>
  graphSpec: WorkflowGraphSpec
  warnings: string[]
  placeholderNodes: WorkflowProposalPlaceholder[]
  validationErrors: string[]
}

export type WorkflowProposalEditOperationType =
  | 'ADD_NODE'
  | 'UPDATE_NODE'
  | 'DELETE_NODE'
  | 'ADD_EDGE'
  | 'UPDATE_EDGE'
  | 'DELETE_EDGE'
  | 'SET_ENTRY_NODE'
  | 'SET_EXIT_NODES'
  | 'SET_INPUT_SCHEMA'

export type WorkflowProposalEditStatus = 'SUCCEEDED' | 'FAILED'

export interface WorkflowProposalEditOperation {
  type: WorkflowProposalEditOperationType
  nodeId?: string
  edgeId?: string
  node?: Record<string, unknown>
  edge?: Record<string, unknown>
  patch?: Record<string, unknown>
  reason?: string
}

export interface WorkflowProposalEditRequest {
  workflowId?: string
  workflowName?: string
  instruction: string
  projectCode?: string | null
  workflowKind?: 'GENERAL' | 'PAGE_ASSISTANT' | string
  modelInstanceId?: string
  currentCanvas?: CanvasSnapshot | Record<string, unknown>
  currentGraphSpec: WorkflowGraphSpec
  selectedNodeIds?: string[]
  selectedEdgeIds?: string[]
  tools?: WorkflowProposalResource[]
  capabilities?: WorkflowProposalResource[]
  knowledgeBases?: WorkflowProposalResource[]
  pageActions?: WorkflowProposalResource[]
}

export interface WorkflowProposalEditResult {
  status: WorkflowProposalEditStatus | string
  provider: string
  summary: string
  operations: WorkflowProposalEditOperation[]
  canvasSnapshot: Record<string, unknown>
  graphSpec: WorkflowGraphSpec
  warnings: string[]
  placeholderNodes: WorkflowProposalPlaceholder[]
  validationErrors: string[]
  attempts?: number
  failureCode?: string | null
  /** Correlates Studio preview with runtime authoring logs (sessionId). */
  authoringId?: string | null
}

export type WorkflowNodeMaturity = 'STABLE' | 'BETA' | 'PLANNED'

export interface WorkflowGraphNodeTypeDescriptor {
  type: WorkflowGraphNode['type']
  canvasKind: string
  canvasCategory: string
  family: 'LLM' | 'TOOL' | 'FLOW' | string
  retryable: boolean
  /** Product maturity from unified node capability registry. */
  maturity?: WorkflowNodeMaturity
  /** Whether Runtime currently has a real handler. */
  runtimeExecutable?: boolean
  /** Whether the node may be published. */
  publishable?: boolean
  /** Whether Studio may add the node from the palette. */
  studioEnabled?: boolean
  /** Whether web AI authoring / shared mutation may add or update the node. */
  aiAuthoringEnabled?: boolean
  /** Explicitly enabled variants when only part of a node type is open; empty means unrestricted. */
  enabledVariants?: string[]
  /** Explicit reason when the node is closed or variant-restricted for product openness. */
  unavailableReason?: string | null
}

/** Workflow GraphSpec 与 Studio 内存画布之间的最小投影输入。 */
export interface WorkflowCanvasSource {
  id?: string
  keySlug?: string
  canvasJson?: string
  graphSpec?: WorkflowGraphSpec | null
  modelInstanceId?: string
  systemPrompt?: string
  extra?: Record<string, unknown>
}

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
  modelInstanceId: string
  runtimeType?: AgentRuntimeType
  runtimePlacement: AgentRuntimePlacement
  runtimeConfig: Record<string, unknown>
  defaultResourceConfig: Record<string, unknown>
  graphSpec?: WorkflowGraphSpec | null
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
  status: 'RUNNING' | 'SUSPENDED' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' | string
  answer?: string
  currentNodeId?: string
  messages?: ExecutableDebugMessage[]
  uiRequest?: UiRequestPayload
  steps: AgentWorkflowDebugStepResult[]
  stateSnapshot?: Record<string, unknown>
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
  targetType: 'AGENT_WORKING_COPY' | 'COMPOSITION_WORKING_COPY' | 'EXECUTABLE_WORKING_COPY' | string
  workingCopyDefinition: Record<string, unknown>
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
  sessionId?: string
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
  agentId?: string
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
  detail: unknown
  /** 同一阶段稳定标识；有则前端按 stepId upsert */
  stepId?: string
  state?: 'started' | 'completed' | 'failed' | 'cancelled' | 'waiting' | string
  sequence?: number
  source?: string
  title?: string
  timestamp?: string
}
