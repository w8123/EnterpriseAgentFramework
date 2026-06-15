import type {
  AgentGraphNodeTypeDescriptor,
  AgentGraphSpec,
  AgentNodeDebugResult,
  AgentRuntimeType,
  AgentWorkflowDebugRunResult,
  AgentWorkflowDebugStepResult,
  ExecutableDebugMessage,
  WorkflowDraftEditRequest as AgentWorkflowDraftEditRequest,
  WorkflowDraftEditResult as AgentWorkflowDraftEditResult,
  WorkflowDraftEditOperation,
  WorkflowDraftEditOperationType,
  WorkflowDraftGenerationRequest as AgentWorkflowDraftGenerationRequest,
  WorkflowDraftGenerationResult as AgentWorkflowDraftGenerationResult,
  WorkflowDraftPlaceholder,
  WorkflowDraftResource,
} from './agent'
import type { UiRequestPayload } from './interaction'

export type AgentEntryKind = 'PROJECT_ENTRY' | 'GLOBAL_EMBED' | 'PAGE_ENTRY' | string
export type AgentEntryVisibility = 'PROJECT' | 'PRIVATE' | 'PUBLIC' | string
export type WorkflowType = 'CHAT' | 'SDK_GRAPH' | 'PAGE_ACTION' | string
export type WorkflowRuntimeType = AgentRuntimeType | string
export type WorkflowStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED' | string
export type WorkflowManagedBy = 'MANUAL' | 'SDK' | 'AI_QUICK_ACCESS' | string
export type AgentWorkflowBindingType = 'DEFAULT' | 'PAGE' | 'ROUTE' | 'ACTION' | 'INTENT' | string

export interface AgentEntry {
  id: string
  projectId?: number | null
  projectCode?: string | null
  keySlug: string
  name: string
  description?: string | null
  agentKind?: AgentEntryKind | null
  visibility?: AgentEntryVisibility | null
  systemPrompt?: string | null
  modelInstanceId?: string | null
  allowedRolesJson?: string | null
  entryConfigJson?: string | null
  enabled?: boolean | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface WorkflowDefinition {
  id: string
  projectId?: number | null
  projectCode?: string | null
  keySlug: string
  name: string
  description?: string | null
  workflowType?: WorkflowType | null
  runtimeType?: WorkflowRuntimeType | null
  graphSpecJson?: string | null
  canvasJson?: string | null
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  defaultModelInstanceId?: string | null
  defaultResourceConfigJson?: string | null
  status?: WorkflowStatus | null
  managedBy?: WorkflowManagedBy | null
  extraJson?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface WorkflowDefinitionDraft
  extends Partial<Omit<WorkflowDefinition, 'id' | 'createdAt' | 'updatedAt' | 'graphSpecJson'>> {
  id?: string
  graphSpec?: AgentGraphSpec
  graphSpecJson?: string | null
}

export interface WorkflowStudioState {
  workflowId: string
  projectId?: number | null
  projectCode?: string | null
  keySlug?: string | null
  name?: string | null
  description?: string | null
  graphSpecJson: string
  canvasJson?: string | null
  workflowType?: WorkflowType | null
  runtimeType: WorkflowRuntimeType
  defaultModelInstanceId?: string | null
  defaultResourceConfigJson?: string | null
  status: WorkflowStatus
  managedBy: WorkflowManagedBy
  extraJson?: string | null
}

export interface WorkflowStudioSaveRequest {
  graphSpecJson: string
  canvasJson?: string | null
  extraJson?: string | null
}

export interface WorkflowRuntimeValidationRequest {
  workflowId?: string
  graphSpecJson?: string
  runtimeType?: WorkflowRuntimeType
}

export interface WorkflowValidationItem {
  code: string
  target?: string | null
  message: string
}

export interface WorkflowRuntimeValidationResult {
  valid: boolean
  errors: WorkflowValidationItem[]
}

export type WorkflowGraphNodeTypeDescriptor = AgentGraphNodeTypeDescriptor
export type {
  WorkflowDraftPlaceholder,
  WorkflowDraftResource,
}
export type WorkflowDraftGenerationRequest = AgentWorkflowDraftGenerationRequest & {
  workflowId?: string
  workflowName?: string
}
export type WorkflowDraftGenerationResult = AgentWorkflowDraftGenerationResult
export type WorkflowDraftEditRequest = AgentWorkflowDraftEditRequest & {
  workflowId?: string
  workflowName?: string
}
export type WorkflowDraftEditResult = AgentWorkflowDraftEditResult
export type { WorkflowDraftEditOperation, WorkflowDraftEditOperationType }

export interface WorkflowDebugBaseRequest {
  workflowId?: string
  workflowKeySlug?: string
  workflowName?: string
  workflowType?: string
  projectCode?: string
  runtimeType?: WorkflowRuntimeType
  modelInstanceId?: string
  graphSpecJson?: string
  canvasJson?: string
}

export interface WorkflowNodeDebugRequest extends WorkflowDebugBaseRequest {
  nodeId: string
  message?: string
  state?: Record<string, unknown>
}

export interface WorkflowDebugRunRequest extends WorkflowDebugBaseRequest {
  message?: string
  inputParams?: Record<string, unknown>
  debugOptions?: Record<string, unknown>
}

export type WorkflowNodeDebugResult = AgentNodeDebugResult
export type WorkflowDebugRunResult = AgentWorkflowDebugRunResult
export type WorkflowDebugStepResult = AgentWorkflowDebugStepResult

/** Workflow Studio 可恢复调试会话消息 */
export type WorkflowDebugMessage = ExecutableDebugMessage

export interface WorkflowDebugSessionCreateRequest {
  targetType: 'WORKFLOW_DRAFT' | 'WORKFLOW_VERSION' | string
  draftDefinition: Record<string, unknown>
  message?: string
  inputParams?: Record<string, unknown>
  debugOptions?: Record<string, unknown>
}

export interface WorkflowDebugSessionSubmitRequest {
  action?: string
  values?: Record<string, unknown>
  message?: string
}

/** Workflow Studio 可恢复调试会话视图 */
export interface WorkflowDebugSessionView extends WorkflowDebugRunResult {
  sessionId: string
  targetType: string
  messages: WorkflowDebugMessage[]
  uiRequest?: UiRequestPayload
  createdAt?: string
  updatedAt?: string
  expiresAt?: string
}

export interface WorkflowVersion {
  id: number
  workflowId: string
  version: string
  snapshotJson?: string | null
  graphSpecSnapshotJson?: string | null
  canvasSnapshotJson?: string | null
  rolloutPercent?: number | null
  status?: string | null
  publishedBy?: string | null
  publishedAt?: string | null
  note?: string | null
  createdAt?: string | null
}

export interface WorkflowPublishRequest {
  version: string
  rolloutPercent?: number
  note?: string
  publishedBy?: string
}

export type PublishWorkflowVersionRequest = WorkflowPublishRequest

export interface WorkflowReleaseValidationItem {
  code: string
  level: 'ERROR' | 'WARN' | string
  nodeId?: string | null
  message: string
}

export interface WorkflowReleaseValidationResult {
  valid: boolean
  errors: WorkflowReleaseValidationItem[]
  warnings: WorkflowReleaseValidationItem[]
}

export interface AgentWorkflowBinding {
  id?: number
  /** AgentEntry / chat·embed 入口 id，不是 WorkflowDefinition id */
  agentId: string
  workflowId: string
  projectCode?: string | null
  bindingType?: AgentWorkflowBindingType | null
  pageKey?: string | null
  routePattern?: string | null
  actionKey?: string | null
  intentType?: string | null
  priority?: number | null
  enabled?: boolean | null
  guardConfigJson?: string | null
  metadataJson?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AgentWorkflowResolveRequest {
  /** AgentEntry / chat·embed 入口 id 或 keySlug，不是 WorkflowDefinition id */
  agentId?: string
  projectCode?: string
  pageKey?: string
  route?: string
  actionKey?: string
  intentType?: string
}
