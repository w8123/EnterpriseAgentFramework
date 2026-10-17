export type MemoryErasureRequestStatus =
  | 'REQUESTED'
  | 'RUNNING'
  | 'RETRY'
  | 'BLOCKED_LEGAL_HOLD'
  | 'ACTION_REQUIRED'
  | 'FAILED'
  | 'COMPLETED'
  | 'COMPLETED_WITH_RETENTION'

export type MemoryErasureDomainStatus =
  | 'PENDING'
  | 'WAITING_DEPENDENCY'
  | 'WAITING_EVIDENCE'
  | 'BLOCKED_LEGAL_HOLD'
  | 'FAILED'
  | 'COMPLETED'

export interface MemoryErasureDomain {
  domainCode: string
  ownerService: string
  executionMode: 'AUTOMATED' | 'MANUAL_EVIDENCE'
  status: MemoryErasureDomainStatus
  resultCode?: string | null
  affectedCount?: number | null
  evidenceReference?: string | null
  attemptCount: number
  lastFailureCode?: string | null
  completedAt?: string | null
  updatedAt?: string | null
}

export interface MemoryErasureRequest {
  requestId: string
  clientRequestId: string
  tenantId: string
  runtimeUserHash: string
  status: MemoryErasureRequestStatus
  reasonCode: string
  referenceId: string
  attemptCount: number
  lastFailureCode?: string | null
  automatedCompletedAt?: string | null
  completedAt?: string | null
  createdAt: string
  updatedAt: string
  created: boolean
  domains: MemoryErasureDomain[]
}

export interface MemoryErasureCreateRequest {
  confirmation: 'ERASE_ALL_AGENT_MEMORY_DOMAINS'
  clientRequestId: string
  tenantId: string
  runtimeUserId: string
  reasonCode: string
  referenceId: string
}

export interface MemoryErasureEvidenceRequest {
  resultCode: 'ERASED' | 'NOT_APPLICABLE' | 'RETAINED_LEGAL'
  evidenceReference: string
}
