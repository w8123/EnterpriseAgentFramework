import { controlRequest } from './request'
import type {
  ContextRuntimeUserMapping,
  ContextRuntimeUserMappingCreateRequest,
  ContextRuntimeUserMappingListParams,
  MemoryErasureCreateRequest,
  MemoryErasureEvidenceRequest,
  MemoryErasureRequest,
  PersonalMemory,
  PersonalMemoryAudit,
  PersonalMemoryCandidate,
  PersonalMemoryCandidatePage,
  PersonalMemoryEraseAllRequest,
  PersonalMemoryEraseAllResult,
  PersonalMemoryPage,
  PersonalMemoryRememberRequest,
  PersonalMemoryUpdateRequest,
} from '@/types/context'

export function listContextRuntimeUserMappings(params: ContextRuntimeUserMappingListParams) {
  return controlRequest.get<ContextRuntimeUserMapping[]>('/api/context/runtime-user-mappings', { params })
}

export function createContextRuntimeUserMapping(data: ContextRuntimeUserMappingCreateRequest) {
  return controlRequest.post<ContextRuntimeUserMapping>('/api/context/runtime-user-mappings', data)
}

export function deleteContextRuntimeUserMapping(id: number) {
  return controlRequest.delete<ContextRuntimeUserMapping>(`/api/context/runtime-user-mappings/${id}`)
}

export function listPersonalMemories(params: {
  type?: string
  status?: string
  keyword?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<PersonalMemoryPage>('/api/context/personal-memories', { params })
}

export function rememberPersonalMemory(data: PersonalMemoryRememberRequest) {
  return controlRequest.post<{ memory: PersonalMemory; created: boolean; idempotentReplay: boolean; updated: boolean }>(
    '/api/context/personal-memories', data,
  )
}

export function updatePersonalMemory(id: number, data: PersonalMemoryUpdateRequest) {
  return controlRequest.put<PersonalMemory>(`/api/context/personal-memories/${id}`, data)
}

export function forgetPersonalMemory(id: number, reason?: string) {
  return controlRequest.delete<{ id: number; status: string; deletedAt: string }>(
    `/api/context/personal-memories/${id}`,
    { data: { reason } },
  )
}

export function eraseAllPersonalMemories(data: PersonalMemoryEraseAllRequest) {
  return controlRequest.post<PersonalMemoryEraseAllResult>(
    '/api/context/personal-memories/erase-all', data,
  )
}

export function createMemoryErasureRequest(data: MemoryErasureCreateRequest) {
  return controlRequest.post<MemoryErasureRequest>('/api/context/memory-erasure-requests', data)
}

export function getMemoryErasureRequest(requestId: string) {
  return controlRequest.get<MemoryErasureRequest>(
    `/api/context/memory-erasure-requests/${encodeURIComponent(requestId)}`,
  )
}

export function retryMemoryErasureRequest(requestId: string) {
  return controlRequest.post<MemoryErasureRequest>(
    `/api/context/memory-erasure-requests/${encodeURIComponent(requestId)}/retry`,
  )
}

export function attestMemoryErasureDomain(
  requestId: string,
  domainCode: string,
  data: MemoryErasureEvidenceRequest,
) {
  return controlRequest.post<MemoryErasureRequest>(
    `/api/context/memory-erasure-requests/${encodeURIComponent(requestId)}`
      + `/domains/${encodeURIComponent(domainCode)}/evidence`,
    data,
  )
}

export function listPersonalMemoryCandidates(params: {
  status?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<PersonalMemoryCandidatePage>('/api/context/personal-memory-candidates', { params })
}

export function approvePersonalMemoryCandidate(
  id: number,
  data?: { type?: string; semanticKey?: string; title?: string; content?: string; reason?: string },
) {
  return controlRequest.post<{ candidate: PersonalMemoryCandidate }>(
    `/api/context/personal-memory-candidates/${id}/approve`, data ?? {},
  )
}

export function rejectPersonalMemoryCandidate(id: number, reason?: string) {
  return controlRequest.post<PersonalMemoryCandidate>(
    `/api/context/personal-memory-candidates/${id}/reject`, { reason },
  )
}

export function listPersonalMemoryAudit(limit = 100) {
  return controlRequest.get<PersonalMemoryAudit[]>('/api/context/personal-memories/audit', { params: { limit } })
}

export function exportPersonalMemory() {
  return controlRequest.get('/api/context/personal-memories/export', { responseType: 'blob' })
}
