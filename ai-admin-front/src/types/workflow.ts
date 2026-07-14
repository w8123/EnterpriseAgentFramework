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

export type AgentVisibility = 'PROJECT' | 'PRIVATE' | 'PUBLIC' | string
export type WorkflowType = 'CHAT' | 'SDK_GRAPH' | 'PAGE_ASSISTANT' | string
export type WorkflowRuntimeType = AgentRuntimeType | string
export type WorkflowStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED' | string
export type WorkflowManagedBy = 'MANUAL' | 'SDK' | 'AI_QUICK_ACCESS' | string

export interface Agent {
  id: string
  projectId?: number | null
  projectCode?: string | null
  keySlug: string
  name: string
  description?: string | null
  visibility?: AgentVisibility | null
  allowedRolesJson?: string | null
  enabled?: boolean | null
  activeConfigVersionId?: number | null
  displayConfigVersionId?: number | null
  displayConfigVersionNo?: number | null
  configStatus?: 'NONE' | 'DRAFT' | 'ACTIVE' | 'ARCHIVED' | string | null
  runtimeType?: string | null
  workflowToolCount?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AgentStatistics {
  totalAgents: number
  enabledAgents: number
  workflowToolAgents: number
  activeWorkflowTools: number
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
  deletable?: boolean | null
}

export interface WorkflowDefinitionDraft
  extends Partial<Omit<WorkflowDefinition, 'id' | 'createdAt' | 'updatedAt' | 'graphSpecJson'>> {
  id?: string
  graphSpec?: AgentGraphSpec
  graphSpecJson?: string | null
}

export interface WorkflowStudioState {
  workflowId: string
  id?: string
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
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  status: WorkflowStatus
  managedBy: WorkflowManagedBy
  extraJson?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  deletable?: boolean | null
  revision?: string | null
  activeVersion?: WorkflowActiveVersionSummary | null
  hasUnpublishedChanges?: boolean
}

export interface WorkflowStudioSaveRequest {
  graphSpecJson: string
  canvasJson?: string | null
  extraJson?: string | null
  baseRevision?: string | null
  keySlug?: string | null
  name?: string | null
  description?: string | null
  workflowType?: WorkflowType | null
  runtimeType?: WorkflowRuntimeType | null
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  defaultModelInstanceId?: string | null
  defaultResourceConfigJson?: string | null
}

export interface WorkflowActiveVersionSummary {
  id: number
  version: string
  rolloutPercent?: number | null
  status?: string | null
  publishedBy?: string | null
  publishedAt?: string | null
  note?: string | null
}

export interface WorkflowRuntimeValidationRequest {
  workflowId?: string
  graphSpecJson?: string
  runtimeType?: WorkflowRuntimeType
  defaultModelInstanceId?: string | null
}

export interface WorkflowValidationItem {
  code: string
  target?: string | null
  message: string
}

export interface WorkflowRuntimeValidationResult {
  valid: boolean
  errors: WorkflowValidationItem[]
  warnings?: WorkflowValidationItem[]
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
  baseRevision?: string | null
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

export interface PageAssistantWorkflowAttachRequest {
  projectId?: number | null
  projectCode?: string | null
  agentId?: string | null
  modelInstanceId: string
  publishedBy?: string | null
}

export interface PageAssistantWorkflowAttachmentResult {
  agentId: string
  agentKeySlug: string
  workflowId: string
  workflowKeySlug: string
  toolName: string
  configVersionId: number
  configVersionNo: number
  configStatus: string
  published: boolean
}
