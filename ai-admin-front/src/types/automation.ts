export type AutomationStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'ARCHIVED'
export type AutomationTargetType = 'AGENT' | 'WORKFLOW'
export type AutomationTriggerType = 'CRON' | 'ONCE'
export type AutomationMisfirePolicy = 'SKIP' | 'FIRE_ONCE' | 'CATCH_UP'
export type AutomationConcurrencyPolicy = 'SKIP' | 'QUEUE' | 'ALLOW'
export type AutomationOccurrenceStatus =
  | 'PENDING'
  | 'RETRY'
  | 'LEASED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'DEAD'
  | 'CANCELLED'
  | 'SKIPPED'

export interface AutomationTargetCommand {
  type: AutomationTargetType
  id: string
  versionId: number
}

export interface AutomationScheduleCommand {
  type: AutomationTriggerType
  cronExpression?: string
  fireAt?: string
  timeZone: string
  misfirePolicy: AutomationMisfirePolicy
  misfireGraceSeconds: number
  maxCatchUp: number
}

export interface AutomationExecutionPolicyCommand {
  concurrencyPolicy: AutomationConcurrencyPolicy
  maxConcurrentRuns: number
  timeoutSeconds: number
  maxAttempts: number
  initialBackoffSeconds: number
  maxBackoffSeconds: number
}

export interface AutomationUpsertCommand {
  name: string
  description?: string
  tenantId?: string
  projectId?: number
  projectCode: string
  expectedRevision?: number
  activate: boolean
  target: AutomationTargetCommand
  schedule: AutomationScheduleCommand
  executionPolicy: AutomationExecutionPolicyCommand
  input: Record<string, unknown>
}

export interface AutomationSummary {
  automationKey: string
  name: string
  description?: string
  tenantId: string
  projectId?: number
  projectCode?: string
  status: AutomationStatus
  revision: number
  targetType?: AutomationTargetType
  targetId?: string
  targetVersionId?: number
  triggerType?: AutomationTriggerType
  scheduleLabel?: string
  timeZone?: string
  nextFireAt?: string
  lastFireAt?: string
  updatedAt?: string
}

export interface AutomationVersion {
  id: number
  versionNo: number
  targetType: AutomationTargetType
  targetId: string
  targetVersionId: number
  targetSnapshot: Record<string, unknown>
  triggerType: AutomationTriggerType
  cronExpression?: string
  fireAt?: string
  timeZone: string
  misfirePolicy: AutomationMisfirePolicy
  misfireGraceSeconds: number
  maxCatchUp: number
  concurrencyPolicy: AutomationConcurrencyPolicy
  maxConcurrentRuns: number
  timeoutSeconds: number
  maxAttempts: number
  initialBackoffSeconds: number
  maxBackoffSeconds: number
  input: Record<string, unknown>
  principalType: string
  principalId: string
  fingerprintSha256: string
  createdBy?: string
  createdAt?: string
}

export interface AutomationAttempt {
  id: number
  attemptNo: number
  status: string
  workerId?: string
  traceId?: string
  resultSummary?: string
  errorCode?: string
  errorMessage?: string
  startedAt?: string
  endedAt?: string
}

export interface AutomationOccurrence {
  id: number
  occurrenceKey: string
  automationKey: string
  automationVersionId: number
  sourceType: 'SCHEDULE' | 'MANUAL' | 'RETRY'
  scheduledAt: string
  availableAt: string
  status: AutomationOccurrenceStatus
  attemptCount: number
  maxAttempts: number
  traceId?: string
  interactionId?: string
  errorCode?: string
  errorMessage?: string
  startedAt?: string
  completedAt?: string
  createdAt: string
  attempts: AutomationAttempt[]
}

export interface AutomationDetail {
  automation: AutomationSummary
  currentVersion?: AutomationVersion
  versions: AutomationVersion[]
  recentOccurrences: AutomationOccurrence[]
}

export interface AutomationReadiness {
  enabled: boolean
  engine: string
  engineVersion: string
  clockOwnership: string
  executionOwnership: string
  timestampMode: string
  message: string
}
