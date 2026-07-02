import { controlRequest } from './request'
import type {
  ContextAuditEvent,
  ContextBinding,
  ContextEvidence,
  ContextEvidenceRequest,
  ContextItem,
  ContextItemCreateRequest,
  ContextItemListParams,
  ContextItemUpdateRequest,
  ContextLifecycleRunResult,
  ContextMemoryCandidate,
  ContextMemoryCandidateBatchReviewRequest,
  ContextMemoryCandidateCreateRequest,
  ContextMemoryCandidateListParams,
  ContextMemoryCandidateReviewRequest,
  ContextMemoryCandidateUpdateRequest,
  ContextNamespace,
  ContextNamespaceRequest,
  ContextOpsSummary,
  ContextPackageResponse,
  ContextRetrievalMode,
  ContextRuntimeUserMapping,
  ContextRuntimeUserMappingCreateRequest,
  ContextRuntimeUserMappingListParams,
  ContextScope,
  ContextSearchResult,
  MemoryLane,
} from '@/types/context'

function scopeParams(scope: ContextScope) {
  return {
    tenantId: scope.tenantId,
    projectCode: scope.projectCode ?? undefined,
    projectId: scope.projectId ?? undefined,
    memoryLane: scope.memoryLane,
  }
}

export function listContextNamespaces(params: {
  tenantId: string
  projectCode?: string
  projectId?: number | null
  namespaceType?: string
  status?: string
}) {
  return controlRequest.get<ContextNamespace[]>('/api/context/namespaces', { params })
}

export function createContextNamespace(data: ContextNamespaceRequest) {
  return controlRequest.post<ContextNamespace>('/api/context/namespaces', data)
}

export function getContextNamespace(id: number) {
  return controlRequest.get<ContextNamespace>(`/api/context/namespaces/${id}`)
}

export function deleteContextNamespace(id: number) {
  return controlRequest.delete<ContextNamespace>(`/api/context/namespaces/${id}`)
}

export function listContextItems(params: ContextItemListParams) {
  return controlRequest.get<ContextItem[]>('/api/context/items', { params })
}

export function getContextItem(id: number, scope: ContextScope) {
  return controlRequest.get<ContextItem>(`/api/context/items/${id}`, { params: scopeParams(scope) })
}

export function createContextItem(data: ContextItemCreateRequest) {
  return controlRequest.post<ContextItem>('/api/context/items', data)
}

export function updateContextItem(id: number, data: ContextItemUpdateRequest, scope: ContextScope) {
  return controlRequest.put<ContextItem>(`/api/context/items/${id}`, data, { params: scopeParams(scope) })
}

export function revokeContextItem(id: number, scope: ContextScope) {
  return controlRequest.post<ContextItem>(`/api/context/items/${id}/revoke`, scopeParams(scope))
}

export function markContextItemStale(id: number, scope: ContextScope) {
  return controlRequest.post<ContextItem>(`/api/context/items/${id}/stale`, scopeParams(scope))
}

export function verifyContextItem(
  id: number,
  body: { confidence?: number; trustLevel?: string } & ContextScope,
) {
  return controlRequest.post<ContextItem>(`/api/context/items/${id}/verify`, body)
}

export function deleteContextItem(id: number, scope: ContextScope) {
  return controlRequest.delete<ContextItem>(`/api/context/items/${id}`, { data: scopeParams(scope) })
}

export function listContextEvidence(itemId: number, scope: ContextScope) {
  return controlRequest.get<ContextEvidence[]>(`/api/context/items/${itemId}/evidence`, {
    params: scopeParams(scope),
  })
}

export function addContextEvidence(itemId: number, data: ContextEvidenceRequest, scope: ContextScope) {
  return controlRequest.post<ContextEvidence>(`/api/context/items/${itemId}/evidence`, data, {
    params: scopeParams(scope),
  })
}

export function listContextBindings(itemId: number, scope: ContextScope) {
  return controlRequest.get<ContextBinding[]>(`/api/context/items/${itemId}/bindings`, {
    params: scopeParams(scope),
  })
}

export function listContextAudit(params: {
  tenantId?: string
  projectCode?: string
  projectId?: number | null
  itemId?: number
  namespaceId?: number
  eventType?: string
  actorType?: string
  actorId?: string
  decision?: string
  traceId?: string
  dateFrom?: string
  dateTo?: string
  limit?: number
}) {
  return controlRequest.get<ContextAuditEvent[]>('/api/context/audit', { params })
}

export function listContextMemoryCandidates(params: ContextMemoryCandidateListParams) {
  return controlRequest.get<ContextMemoryCandidate[]>('/api/context/memory/candidates', { params })
}

export function createContextMemoryCandidate(data: ContextMemoryCandidateCreateRequest) {
  return controlRequest.post<ContextMemoryCandidate>('/api/context/memory/candidates', data)
}

export function approveContextMemoryCandidate(id: number, data: ContextMemoryCandidateReviewRequest) {
  return controlRequest.post<ContextMemoryCandidate>(`/api/context/memory/candidates/${id}/approve`, data)
}

export function rejectContextMemoryCandidate(id: number, data: ContextMemoryCandidateReviewRequest) {
  return controlRequest.post<ContextMemoryCandidate>(`/api/context/memory/candidates/${id}/reject`, data)
}

export function updateContextMemoryCandidate(id: number, data: ContextMemoryCandidateUpdateRequest) {
  return controlRequest.put<ContextMemoryCandidate>(`/api/context/memory/candidates/${id}`, data)
}

export function approveContextMemoryCandidateBatch(data: ContextMemoryCandidateBatchReviewRequest) {
  return controlRequest.post<ContextMemoryCandidate[]>('/api/context/memory/candidates/batch/approve', data)
}

export function rejectContextMemoryCandidateBatch(data: ContextMemoryCandidateBatchReviewRequest) {
  return controlRequest.post<ContextMemoryCandidate[]>('/api/context/memory/candidates/batch/reject', data)
}

export function getContextOpsSummary(params: {
  tenantId: string
  projectCode?: string
  projectId?: number | null
  memoryLane?: MemoryLane
  includeRuntimeUser?: boolean
}) {
  return controlRequest.get<ContextOpsSummary>('/api/context/ops/summary', { params })
}

export function runContextLifecycleDryRun(body: {
  tenantId: string
  projectCode?: string
  projectId?: number | null
  dryRun: boolean
  includeRuntimeUserItems?: boolean
}) {
  return controlRequest.post<ContextLifecycleRunResult>('/api/context/lifecycle/run', body)
}

export function composeContextPackage(body: {
  query: {
    tenantId: string
    projectCode?: string
    projectId?: number
    memoryLane: 'PROJECT_DEV'
    retrievalMode?: ContextRetrievalMode
    query?: string
  }
  maxItems?: number
  tokenBudget?: number
}) {
  return controlRequest.post<ContextPackageResponse>('/api/context/package', body)
}

export function queryContextItems(body: {
  tenantId: string
  projectCode?: string
  projectId?: number | null
  memoryLane: 'PROJECT_DEV'
  retrievalMode?: ContextRetrievalMode
  query?: string
  itemTypes?: string[]
  topK?: number
}) {
  return controlRequest.post<ContextSearchResult[]>('/api/context/query', body)
}

export function listContextRuntimeUserMappings(params: ContextRuntimeUserMappingListParams) {
  return controlRequest.get<ContextRuntimeUserMapping[]>('/api/context/runtime-user-mappings', { params })
}

export function createContextRuntimeUserMapping(data: ContextRuntimeUserMappingCreateRequest) {
  return controlRequest.post<ContextRuntimeUserMapping>('/api/context/runtime-user-mappings', data)
}

export function deleteContextRuntimeUserMapping(id: number) {
  return controlRequest.delete<ContextRuntimeUserMapping>(`/api/context/runtime-user-mappings/${id}`)
}
