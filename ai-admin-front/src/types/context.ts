export type ContextStatus = 'ACTIVE' | 'STALE' | 'REVOKED' | 'DELETED'
export type ContextTrustLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'VERIFIED'

export type PersonalMemoryType = 'FACT' | 'PREFERENCE' | 'RULE' | 'NOTE'

export interface PersonalMemory {
  id: number
  itemKey: string
  type: PersonalMemoryType
  title?: string | null
  content: string
  summary?: string | null
  trustLevel: ContextTrustLevel
  status: ContextStatus
  effectiveFrom?: string | null
  expiresAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface PersonalMemoryPage {
  items: PersonalMemory[]
  total: number
  limit: number
  offset: number
}

export interface PersonalMemoryRememberRequest {
  type: PersonalMemoryType
  semanticKey?: string
  title?: string
  content: string
  summary?: string
  tags?: string[]
  expiresAt?: string
  clientRequestId?: string
}

export type PersonalMemoryUpdateRequest = Partial<Omit<PersonalMemoryRememberRequest, 'clientRequestId'>>

export interface PersonalMemoryEraseAllRequest {
  confirmation: 'ERASE_ALL_PERSONAL_MEMORIES'
  reason?: string
}

export interface PersonalMemoryEraseAllResult {
  status: 'CONTROL_PERSONAL_MEMORY_ERASED'
  memoriesErased: number
  candidatesErased: number
  erasedAt: string
  idempotent: boolean
  retainedDomains: string[]
}

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

export interface PersonalMemoryCandidate {
  id: number
  candidateKey: string
  type: PersonalMemoryType
  semanticKey?: string | null
  title?: string | null
  content: string
  reason?: string | null
  confidence?: number | null
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED'
  conflictItemId?: number | null
  conflictType?: string | null
  occurrenceCount?: number | null
  sessionId?: string | null
  traceId?: string | null
  agentId?: string | null
  expiresAt?: string | null
  createdAt?: string | null
  lastSeenAt?: string | null
}

export interface PersonalMemoryCandidatePage {
  items: PersonalMemoryCandidate[]
  total: number
  limit: number
  offset: number
}

export interface PersonalMemoryAudit {
  id: number
  eventType: string
  itemId?: number | null
  decision?: string | null
  reason?: string | null
  sessionId?: string | null
  traceId?: string | null
  agentId?: string | null
  createdAt: string
}

export interface ContextRuntimeUserMapping {
  id: number
  tenantId: string
  platformUserId: number
  runtimeUserId: string
  globalUserId?: string | null
  externalUserId?: string | null
  projectId?: number | null
  projectCode?: string | null
  status: string
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  deletedAt?: string | null
}

export interface ContextRuntimeUserMappingCreateRequest {
  tenantId: string
  platformUserId: number
  runtimeUserId?: string
  globalUserId?: string
  externalUserId?: string
  projectId?: number | null
  projectCode?: string | null
}

export interface ContextRuntimeUserMappingListParams {
  tenantId: string
  platformUserId?: number
  runtimeUserId?: string
  projectId?: number | null
  projectCode?: string | null
  status?: string
  limit?: number
}
