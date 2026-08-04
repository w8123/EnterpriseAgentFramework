export type AiCodingExecutorProvider =
  | 'CODEX'
  | 'CURSOR'
  | 'TRAE'
  | 'CLAUDE_CODE'
export type AiCodingAccessMode = 'READ_ONLY' | 'READ_WRITE'
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
  title: string
  objective: string
  createdBy?: string
  targets: Omit<AiCodingTaskTarget, 'id'>[]
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
