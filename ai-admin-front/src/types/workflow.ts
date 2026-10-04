import type {
  WorkflowGraphSpec,
  AgentNodeDebugResult,
  AgentWorkflowDebugRunResult,
  AgentWorkflowDebugStepResult,
  ExecutableDebugMessage,
  WorkflowProposalEditRequest as BaseWorkflowProposalEditRequest,
  WorkflowProposalEditResult as BaseWorkflowProposalEditResult,
  WorkflowProposalEditOperation,
  WorkflowProposalEditOperationType,
  WorkflowProposalGenerationRequest as BaseWorkflowProposalGenerationRequest,
  WorkflowProposalGenerationResult as BaseWorkflowProposalGenerationResult,
  WorkflowProposalPlaceholder,
  WorkflowProposalResource,
} from './agent'
import type { UiRequestPayload } from './interaction'

export type AgentVisibility = 'PROJECT' | 'PRIVATE' | 'PUBLIC' | string
export type WorkflowKind = 'GENERAL' | 'PAGE_ASSISTANT' | string
export type WorkflowExecutionEngine = 'GRAPH_SPEC' | string
export type WorkflowDefinitionAuthority = 'USER' | 'SDK' | 'SYSTEM' | string
export type WorkflowCreationChannel =
  | 'STUDIO'
  | 'AI_CODING'
  | 'SDK_SYNC'
  | 'AI_QUICK_ACCESS'
  | 'SYSTEM_SEED'
  | string
export type WorkflowStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED' | string

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

export interface WorkflowWorkingCopy {
  id: string
  projectId?: number | null
  projectCode?: string | null
  keySlug: string
  name: string
  description?: string | null
  workflowKind?: WorkflowKind | null
  executionEngine?: WorkflowExecutionEngine | null
  definitionAuthority?: WorkflowDefinitionAuthority | null
  creationChannel?: WorkflowCreationChannel | null
  graphSpecJson?: string | null
  canvasJson?: string | null
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  defaultModelInstanceId?: string | null
  defaultResourceConfigJson?: string | null
  status?: WorkflowStatus | null
  extraJson?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  deletable?: boolean | null
}

export interface WorkflowWorkingCopyInput
  extends Partial<Omit<
    WorkflowWorkingCopy,
    'id' | 'createdAt' | 'updatedAt' | 'deletable' | 'graphSpecJson'
  >> {
  graphSpec?: WorkflowGraphSpec
  graphSpecJson?: string | null
}

export interface WorkflowWorkingCopyUpdateInput extends WorkflowWorkingCopyInput {
  baseRevision: string
}

export interface WorkflowWorkingCopyState {
  workflowId: string
  id?: string
  projectId?: number | null
  projectCode?: string | null
  keySlug?: string | null
  name?: string | null
  description?: string | null
  graphSpecJson: string
  canvasJson?: string | null
  workflowKind?: WorkflowKind | null
  executionEngine?: WorkflowExecutionEngine | null
  definitionAuthority?: WorkflowDefinitionAuthority | null
  creationChannel?: WorkflowCreationChannel | null
  defaultModelInstanceId?: string | null
  defaultResourceConfigJson?: string | null
  inputSchemaJson?: string | null
  outputSchemaJson?: string | null
  status: WorkflowStatus
  extraJson?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  deletable?: boolean | null
  revision?: string | null
  activeVersion?: WorkflowActiveVersionSummary | null
  hasUnpublishedChanges?: boolean
}

export interface SaveWorkflowWorkingCopyRequest {
  graphSpecJson: string
  canvasJson?: string | null
  extraJson?: string | null
  baseRevision: string
  keySlug?: string | null
  name?: string | null
  description?: string | null
  workflowKind?: WorkflowKind | null
  executionEngine?: WorkflowExecutionEngine | null
  definitionAuthority?: WorkflowDefinitionAuthority | null
  creationChannel?: WorkflowCreationChannel | null
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
  executionEngine?: WorkflowExecutionEngine
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

export type { WorkflowGraphNodeTypeDescriptor } from './agent'
export type {
  WorkflowProposalPlaceholder,
  WorkflowProposalResource,
}
export type WorkflowProposalGenerationRequest = BaseWorkflowProposalGenerationRequest
export type WorkflowProposalGenerationResult = BaseWorkflowProposalGenerationResult
export type WorkflowProposalEditRequest = BaseWorkflowProposalEditRequest
export type WorkflowProposalEditResult = BaseWorkflowProposalEditResult
export type { WorkflowProposalEditOperation, WorkflowProposalEditOperationType }

export interface WorkflowDebugBaseRequest {
  workflowId?: string
  workflowKeySlug?: string
  workflowName?: string
  workflowKind?: WorkflowKind
  projectCode?: string
  executionEngine?: WorkflowExecutionEngine
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

/** Explicit saved-draft read-only trial; ordinary debug does not gain this project-test authority. */
export interface WorkflowReadOnlyTrialResult {
  success: boolean
  status: string
  runId: string
  traceId: string
  workflowId: string
  revision: string
  apiId: number
  assetType?: 'HTTP_API' | 'BUSINESS_METHOD'
  methodName?: string | null
  methodOutput?: unknown | null
  qualifiedName: string
  environment: string | null
  apiOutput: unknown | null
  variables: Record<string, unknown>
  elapsedMs: number
  errorCode: string | null
  errorMessage: string | null
}
export type WorkflowDebugStepResult = AgentWorkflowDebugStepResult

/** Workflow Studio 可恢复调试会话消息 */
export type WorkflowDebugMessage = ExecutableDebugMessage

export interface WorkflowDebugSessionCreateRequest {
  idempotencyKey?: string
  targetType: 'WORKFLOW_WORKING_COPY' | 'WORKFLOW_VERSION' | string
  workingCopyDefinition: Record<string, unknown>
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
  baseRevision?: string | null
}

export type PublishWorkflowVersionRequest = WorkflowPublishRequest & { baseRevision: string }

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
  modelInstanceId?: string | null
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
