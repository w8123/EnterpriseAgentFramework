export type MemoryLane = 'PROJECT_DEV' | 'RUNTIME_USER'
export type ContextRetrievalMode = 'KEYWORD' | 'HYBRID'

export type ContextNamespaceType =
  | 'PERSONAL'
  | 'PROJECT'
  | 'MODULE'
  | 'FEATURE'
  | 'PAGE'
  | 'API'
  | 'WORKFLOW'
  | 'AGENT'
  | 'USER'
  | 'TENANT'
  | 'SESSION'
  | 'GLOBAL'

export type ContextItemType =
  | 'FACT'
  | 'PREFERENCE'
  | 'RULE'
  | 'DECISION'
  | 'PITFALL'
  | 'PAGE_CONTEXT'
  | 'API_CONTRACT'
  | 'WORKFLOW_CONTEXT'
  | 'SESSION_SUMMARY'
  | 'TRACE_LEARNING'
  | 'NOTE'

export type ContextStatus = 'ACTIVE' | 'STALE' | 'REVOKED' | 'DELETED'
export type ContextVisibility = 'PRIVATE' | 'PROJECT' | 'TENANT' | 'GLOBAL'
export type ContextTrustLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'VERIFIED'
export type ContextSourceType =
  | 'USER_CONFIRMED'
  | 'USER_MESSAGE'
  | 'AGENT_OUTPUT'
  | 'CODE'
  | 'SQL'
  | 'DOC'
  | 'API'
  | 'TRACE'
  | 'PAGE'
  | 'WORKFLOW'
  | 'SYSTEM'
  | 'MANUAL'

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

export interface ContextScope {
  tenantId: string
  projectCode?: string | null
  projectId?: number | null
  memoryLane: MemoryLane
}

export interface ContextNamespace {
  id: number
  namespaceKey: string
  namespaceType: ContextNamespaceType
  tenantId: string
  projectId?: number | null
  projectCode?: string | null
  ownerType?: string | null
  ownerId?: string | null
  displayName?: string | null
  description?: string | null
  status: string
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ContextNamespaceRequest {
  namespaceKey?: string
  namespaceType: ContextNamespaceType
  tenantId: string
  projectId?: number | null
  projectCode?: string | null
  ownerType?: string | null
  ownerId?: string | null
  displayName?: string | null
  description?: string | null
  createdBy?: string | null
}

export interface ContextItem {
  id: number
  itemKey: string
  namespaceId: number
  itemType: ContextItemType
  memoryLane: MemoryLane
  title?: string | null
  content: string
  summary?: string | null
  metadataJson?: string | null
  sourceType: ContextSourceType
  sourceRef?: string | null
  confidence?: number | null
  trustLevel?: ContextTrustLevel | null
  visibility?: ContextVisibility | null
  status: ContextStatus
  effectiveFrom?: string | null
  expiresAt?: string | null
  lastVerifiedAt?: string | null
  staleAfter?: string | null
  createdBy?: string | null
  updatedBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ContextItemCreateRequest {
  namespaceId?: number
  namespaceKey?: string
  itemType: ContextItemType
  memoryLane: MemoryLane
  title?: string
  content: string
  summary?: string
  metadataJson?: string
  sourceType: ContextSourceType
  sourceRef?: string
  confidence?: number
  trustLevel?: ContextTrustLevel
  visibility?: ContextVisibility
  effectiveFrom?: string
  expiresAt?: string
  tenantId: string
  projectId?: number | null
  projectCode?: string | null
  createdBy?: string
  pageInstanceId?: string
  workflowId?: string
  agentId?: string
  sessionId?: string
  userId?: string
  bindings?: ContextBindingRequest[]
  evidence?: ContextEvidenceRequest[]
}

export interface ContextItemUpdateRequest {
  title?: string
  content?: string
  summary?: string
  metadataJson?: string
  sourceType?: ContextSourceType
  sourceRef?: string
  confidence?: number
  trustLevel?: ContextTrustLevel
  visibility?: ContextVisibility
  effectiveFrom?: string
  expiresAt?: string
  updatedBy?: string
}

export interface ContextEvidence {
  id: number
  itemId: number
  evidenceType: string
  evidenceRef?: string | null
  evidenceExcerpt?: string | null
  traceId?: string | null
  confidence?: number | null
  metadataJson?: string | null
  createdAt?: string | null
}

export interface ContextEvidenceRequest {
  evidenceType: string
  evidenceRef?: string
  evidenceExcerpt?: string
  traceId?: string
  confidence?: number
  metadataJson?: string
}

export interface ContextBinding {
  id: number
  itemId: number
  bindType: string
  bindId: string
  bindKey?: string | null
  tenantId?: string | null
  projectId?: number | null
  projectCode?: string | null
  status: string
  createdAt?: string | null
}

export interface ContextBindingRequest {
  bindType: string
  bindId: string
  bindKey?: string
  tenantId: string
  projectId?: number | null
  projectCode?: string | null
}

export type ContextMemoryCandidateStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'DELETED'
export type ContextMemoryCandidateType =
  | 'PREFERENCE'
  | 'FACT'
  | 'RULE'
  | 'PAGE_CONTEXT'
  | 'WORKFLOW_CONTEXT'
  | 'API_CONTEXT'
  | 'NOTE'

export interface ContextMemoryCandidate {
  id: number
  candidateKey: string
  tenantId: string
  projectId?: number | null
  projectCode?: string | null
  namespaceId?: number | null
  namespaceKey?: string | null
  memoryLane: MemoryLane
  candidateType: ContextMemoryCandidateType
  title?: string | null
  content: string
  summary?: string | null
  reason?: string | null
  sourceType: ContextSourceType
  sourceRef?: string | null
  traceId?: string | null
  sessionId?: string | null
  userId?: string | null
  externalUserId?: string | null
  globalUserId?: string | null
  agentId?: string | null
  agentKey?: string | null
  workflowId?: string | null
  workflowKey?: string | null
  pageInstanceId?: string | null
  origin?: string | null
  confidence?: number | null
  trustLevel?: ContextTrustLevel | null
  visibility?: ContextVisibility | null
  status: ContextMemoryCandidateStatus
  proposedBy?: string | null
  reviewedBy?: string | null
  reviewedAt?: string | null
  reviewReason?: string | null
  approvedItemId?: number | null
  metadataJson?: string | null
  expiresAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ContextMemoryCandidateReviewRequest extends ContextScope {
  userId?: string
  reviewedBy?: string
  reviewReason?: string
  confidence?: number
  trustLevel?: ContextTrustLevel
}

export interface ContextMemoryCandidateCreateRequest {
  tenantId: string
  projectId?: number | null
  projectCode?: string | null
  namespaceId?: number | null
  namespaceKey?: string
  memoryLane: MemoryLane
  candidateType?: ContextMemoryCandidateType
  title?: string
  content: string
  summary?: string
  reason?: string
  sourceType: ContextSourceType
  sourceRef?: string
  traceId?: string
  sessionId?: string
  userId?: string
  externalUserId?: string
  globalUserId?: string
  agentId?: string
  agentKey?: string
  workflowId?: string
  workflowKey?: string
  pageInstanceId?: string
  origin?: string
  confidence?: number
  trustLevel?: ContextTrustLevel
  visibility?: ContextVisibility
  proposedBy?: string
  expiresAt?: string
  metadataJson?: string
}

export interface ContextMemoryCandidateBatchReviewRequest extends ContextMemoryCandidateReviewRequest {
  candidateIds: number[]
}

export interface ContextMemoryCandidateUpdateRequest extends ContextScope {
  userId?: string
  updatedBy?: string
  updateReason?: string
  namespaceId?: number | null
  namespaceKey?: string
  candidateType?: ContextMemoryCandidateType
  title?: string
  content?: string
  summary?: string
  reason?: string
  sourceType?: ContextSourceType
  sourceRef?: string
  workflowId?: string
  workflowKey?: string
  pageInstanceId?: string
  origin?: string
  confidence?: number
  trustLevel?: ContextTrustLevel
  visibility?: ContextVisibility
  expiresAt?: string
  metadataJson?: string
}

export interface ContextAuditEvent {
  id: number
  eventType: string
  itemId?: number | null
  namespaceId?: number | null
  actorType?: string | null
  actorId?: string | null
  tenantId?: string | null
  projectCode?: string | null
  traceId?: string | null
  decision?: string | null
  reason?: string | null
  metadataJson?: string | null
  createdAt?: string | null
}

export interface ContextSearchResult {
  item: ContextItem
  rankScore?: number
  hitReason?: string
  scoreBreakdown?: string
}

export interface ContextPackageResponse {
  memoryLane: MemoryLane
  tenantId: string
  projectCode?: string | null
  totalItems: number
  truncatedCount: number
  projectMemory: ContextSearchResult[]
  userMemory?: ContextSearchResult[]
  pageContext: ContextSearchResult[]
  workflowContext: ContextSearchResult[]
  apiContext: ContextSearchResult[]
  rules: ContextSearchResult[]
  evidenceSummary: ContextSearchResult[]
}

export interface ContextItemListParams extends ContextScope {
  namespaceId?: number
  itemType?: string
  status?: string
  keyword?: string
  limit?: number
  offset?: number
}

export interface ContextMemoryCandidateListParams extends ContextScope {
  userId?: string
  status?: string
  traceId?: string
  namespaceId?: number | null
  candidateType?: string
  sourceType?: string
  pageInstanceId?: string
  origin?: string
  includeExpired?: boolean
  limit?: number
}

export interface ContextOpsSummary {
  tenantId: string
  projectCode?: string | null
  projectId?: number | null
  memoryLane: MemoryLane
  namespaceCount: number
  itemCount: number
  activeItemCount: number
  staleItemCount: number
  revokedItemCount: number
  deletedItemCount: number
  expiringItemCount: number
  pendingCandidateCount: number
  expiredCandidateCount: number
  auditEventCountRecent: number
  staleDueItemCount: number
  runtimeUserExcludedCount: number
  warnings?: string[]
}

export interface ContextLifecycleRunResult {
  tenantId: string
  projectCode?: string | null
  projectId?: number | null
  dryRun: boolean
  expiredCandidateCount: number
  staleItemCount: number
  skippedRuntimeUserItemCount: number
  scannedItemCount: number
  warnings?: string[]
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
