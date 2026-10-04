import type { ToolParameter } from './tool'

/**
 * Console-only invocation snapshot. The target and credential details are
 * deliberately owner-derived by Control and are never browser-supplied.
 */
export interface BusinessMethodInvocationContext {
  contractVersion: number
  name: string
  qualifiedName?: string | null
  sourceQualifiedName?: string | null
  assetType: 'BUSINESS_METHOD' | string
  projectId?: number | null
  projectCode?: string | null
  currentContractHash?: string | null
  acceptedContractHash?: string | null
  sourceContractHash?: string | null
  sourceAvailability?: string | null
  enabled: boolean
  sideEffect?: string | null
  parameters: ToolParameter[]
  requestBodyType?: string | null
  responseType?: string | null
  /** Safe owner-provided label, never a URL or credential. */
  targetDescription?: string | null
  targetInstanceId?: string | null
  targetInstanceStatus?: string | null
  credentialAvailable: boolean
  businessIdentityRequired: boolean
  executable: boolean
  blockingCode?: string | null
  blockingMessage?: string | null
  timeoutMs?: number | null
}

/** Exact public browser request accepted by Control. */
export interface BusinessMethodInvocationRequest {
  invocationId: string
  expectedContractHash: string
  input: Record<string, unknown>
  confirmedSideEffect: boolean
}

export type BusinessMethodInvocationStatus =
  | 'ACCEPTED'
  | 'DISPATCHING'
  | 'SUCCEEDED'
  | 'BUSINESS_FAILED'
  | 'NOT_DISPATCHED'
  | 'UNKNOWN'

export type BusinessMethodInvocationDispatchStage =
  | 'PERSISTED'
  | 'DISPATCHING'
  | 'CONFIRMED'
  | 'NOT_DISPATCHED'
  | 'UNCONFIRMED'

/** Safe Runtime result, returned from POST or a read-only GET by invocationId. */
export interface BusinessMethodInvocationOutcome {
  contractVersion: number
  invocationId: string
  runId?: number | null
  traceId?: string | null
  projectCode?: string | null
  projectId?: number | null
  qualifiedName?: string | null
  identityMode?: string | null
  status: BusinessMethodInvocationStatus
  dispatchStage: BusinessMethodInvocationDispatchStage
  terminal: boolean
  code?: string | null
  message?: string | null
  result?: unknown
  resultTruncated?: boolean
  resultExpiresAtEpochMs?: number | null
  latencyMs?: number | null
}

/** The special 202 response is only a query handle, never a confirmed lifecycle outcome. */
export interface BusinessMethodInvocationUnconfirmed {
  success: false
  code: 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED' | string
  message?: string | null
  invocationId: string
  status?: string | null
  terminal?: boolean
  queryable?: boolean
}

export interface BusinessMethodInvocationInputDiagnostic {
  path: string
  reason: string
}

export interface StoredBusinessMethodInvocationReference {
  invocationId: string
  createdAt: number
}
