export type AiCodingExecutorProvider =
  | 'CODEX'
  | 'CURSOR'
  | 'TRAE'
  | 'CLAUDE_CODE'
export type AiCodingAccessMode = 'READ_ONLY' | 'READ_WRITE'
export type AiCodingExecutionMode = 'EXTERNAL_CLIENT' | 'MANAGED_SANDBOX'
export type AiCodingSandboxProfile = 'ANALYZE_READONLY' | 'WORKSPACE_PATCH'
export type ManagedExecutionStatus =
  | 'REQUESTED'
  | 'QUEUED'
  | 'PROVISIONING'
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'WAITING_USER'
  | 'FINALIZING'
  | 'CANCELLING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'TIMED_OUT'
  | 'CANCELLED'
export type AiCodingExecutionStatus =
  | 'READY'
  | 'RUNNING'
  | 'WAITING_USER'
  | 'RESULT_SUBMITTED'
  | 'RESULT_APPLIED'
  | 'ACCEPTANCE_READY'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
export type AiCodingConnectionStatus =
  | 'WAITING_CONNECT'
  | 'ACTIVE'
  | 'TIMED_OUT'
  | 'CLOSED'
  | 'NOT_APPLICABLE'
export type AiCodingTargetRole = 'PRIMARY' | 'RELATED'

export interface AiCodingTaskTarget {
  id?: number
  targetType: string
  targetKey: string
  targetRole: AiCodingTargetRole
  accessMode: AiCodingAccessMode
  snapshot?: unknown
}

export interface AiCodingTaskConnection {
  status: AiCodingConnectionStatus
  timeoutReason?: string
  clientProvider?: string
  clientSessionRef?: string
  activationExpiresAt?: string
  activatedAt?: string
  lastSeenAt?: string
  leaseExpiresAt?: string
  tokenExpiresAt?: string
  closedAt?: string
}

export interface AiCodingTaskQuestion {
  questionId: string
  title: string
  body: string
  options: string[]
  status: 'OPEN' | 'ANSWERED' | 'CLOSED'
  answer?: string
  askedBy?: string
  answeredBy?: string
  askedAt: string
  answeredAt?: string
  updatedAt: string
}

export interface AiCodingTask {
  taskId: string
  projectId: number
  projectCode: string
  capabilityKey: string
  taskKind: string
  protocolVersion: string
  executorProvider: AiCodingExecutorProvider
  executionMode: AiCodingExecutionMode
  managedExecutionId?: string
  sandboxProfile?: AiCodingSandboxProfile
  managedExecutionStatus?: ManagedExecutionStatus
  managedPendingInteractionId?: string
  title: string
  objective: string
  accessMode: AiCodingAccessMode
  executionStatus: AiCodingExecutionStatus
  resultContractKey: string
  resultContractVersion: string
  lastMessage?: string
  createdBy?: string
  startedAt?: string
  resultSubmittedAt?: string
  completedAt?: string
  createdAt: string
  updatedAt: string
  connection: AiCodingTaskConnection
  targets: AiCodingTaskTarget[]
  openQuestions: AiCodingTaskQuestion[]
}

export interface AiCodingTaskEvent {
  id: number
  clientEventId?: string
  eventType: string
  executionStatusAfter?: AiCodingExecutionStatus
  message?: string
  payload?: unknown
  actorType: string
  actorName?: string
  createdAt: string
}

export interface AiCodingTaskArtifact {
  artifactId: number
  artifactKey: string
  contractKey: string
  contractVersion: string
  contentHash: string
  processingStatus: 'RECEIVED' | 'VALIDATED' | 'APPLIED' | 'REJECTED'
  validationMessage?: string
  applicationResult?: unknown
  reportedBy?: string
  createdAt: string
  validatedAt?: string
  appliedAt?: string
}

export interface AiCodingReadinessItem {
  key: string
  label: string
  status: 'PASS' | 'WARN' | 'FAIL' | 'PENDING' | string
  message: string
  evidence?: unknown
}

export interface AiCodingTaskDetail {
  task: AiCodingTask
  readiness: AiCodingReadinessItem[]
  events: AiCodingTaskEvent[]
  questions: AiCodingTaskQuestion[]
  artifacts: AiCodingTaskArtifact[]
}

export interface AiCodingAcceptanceVerification {
  task: AiCodingTask
  readiness: AiCodingReadinessItem[]
  acceptanceReady: boolean
  blockers: string[]
}

export interface AiCodingTaskCreateRequest {
  projectId: number
  projectCode: string
  taskKind: string
  executorProvider: AiCodingExecutorProvider
  executionMode?: AiCodingExecutionMode
  sandboxProfile?: AiCodingSandboxProfile
  title: string
  objective: string
  createdBy?: string
  targets: Omit<AiCodingTaskTarget, 'id'>[]
}

export interface ManagedExecutionStartRequest {
  modelRef?: string
  acceptanceProfile?: string
  priority?: number
  maxWallTimeSeconds?: number
  approvalTimeoutSeconds?: number
}

export interface ManagedExecution {
  executionId: string
  tenantId: string
  projectCode: string
  requestedByUserId: string
  sourceType: 'AI_CODING_TASK' | 'AGENT_DELEGATION' | 'OPERATOR' | string
  sourceRef?: string
  executorProvider: 'CODEX' | string
  sandboxProfile: AiCodingSandboxProfile
  modelRef?: string
  acceptanceProfile: string
  objectiveSha256: string
  status: ManagedExecutionStatus
  cleanupStatus: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | string
  pendingInteractionId?: string
  pendingApprovalRequestId?: string
  approvalCount: number
  priority: number
  maxWallTimeSeconds: number
  approvalTimeoutSeconds: number
  lastEventSequence: number
  cancelRequested: boolean
  errorCode?: string
  errorMessage?: string
  createdAt: string
  updatedAt: string
  startedAt?: string
  finalizingAt?: string
  completedAt?: string
}

export interface ManagedExecutionArtifact {
  schema: string
  executionId: string
  artifactId: string
  artifactType:
    | 'PATCH'
    | 'TEST_REPORT'
    | 'EXECUTION_SUMMARY'
    | 'EVIDENCE_MANIFEST'
    | 'EVENT_LOG'
    | string
  sha256: string
  sizeBytes: number
  mediaType: string
  validationStatus: string
  scanStatus: string
  createdAt: string
  updatedAt: string
  retentionExpiresAt: string
}

export interface ManagedExecutionApproval {
  schema: string
  executionId: string
  interactionId: string
  approvalRequestId: string
  status: string
  uiRequest: {
    schema?: string
    approvalKind?: 'COMMAND' | 'FILE_CHANGE' | string
    message?: string
    command?: string[]
    reason?: string
    actions?: string[]
  }
  expiresAt?: string
  updatedAt?: string
}

export interface AiCodingManagedExecutionDetail {
  schema: string
  task: AiCodingTask
  execution: ManagedExecution
  artifacts: ManagedExecutionArtifact[]
  approval?: ManagedExecutionApproval
}

export interface ManagedExecutionProgressEvent {
  schema: 'reachai.ai-coding.managed-progress.v1' | string
  taskId: string
  executionId: string
  status: ManagedExecutionStatus
  cleanupStatus: string
  pendingInteractionId?: string
  lastEventSequence: number
  approvalCount: number
  terminal: boolean
  observedAt: string
}

export interface ManagedApprovalDecisionRequest {
  decision: 'APPROVE' | 'REJECT'
  idempotencyKey?: string
}

export interface AiCodingHandoffPackage {
  schema: 'reachai.ai-coding.handoff-package.v1' | string
  taskId: string
  handoffId: string
  protocolVersion: string
  executorProvider: AiCodingExecutorProvider
  activationUrl: string
  activationCode: string
  activationExpiresAt: string
  prompt: string
  promptCharacters: number
  promptCharacterLimit?: number
}

export interface AiCodingAcceptanceRequest {
  passed: boolean
  message: string
  actor?: string
}

export interface AiCodingCredentialPolicy {
  projectId: number
  handoffActivationTtlHours: number
  taskTokenTtlHours: number
  customized: boolean
  updatedBy?: string
  updatedAt?: string
}

export interface AiCodingCredentialPolicyUpdateRequest {
  handoffActivationTtlHours: number
  taskTokenTtlHours: number
}
