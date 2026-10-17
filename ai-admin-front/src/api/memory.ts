import { controlRequest } from './request'
import type {
  PersonalMemory,
  PersonalMemoryAudit,
  PersonalMemoryCandidate,
  PersonalMemoryCandidatePage,
  PersonalMemoryCandidateReviewRequest,
  PersonalMemoryEraseAllRequest,
  PersonalMemoryEraseAllResult,
  PersonalMemoryListParams,
  PersonalMemoryPage,
  PersonalMemoryRememberRequest,
  PersonalMemoryRememberResult,
  PersonalMemoryUpdateRequest,
} from '@/types/memory'

const ROOT = '/api/me/memories'

export function listMyMemories(params: PersonalMemoryListParams) {
  return controlRequest.get<PersonalMemoryPage>(ROOT, { params })
}

export function rememberMyMemory(data: PersonalMemoryRememberRequest) {
  return controlRequest.post<PersonalMemoryRememberResult>(ROOT, data)
}

export function updateMyMemory(id: number, data: PersonalMemoryUpdateRequest) {
  return controlRequest.put<PersonalMemory>(`${ROOT}/${id}`, data)
}

export function forgetMyMemory(id: number) {
  return controlRequest.delete<{ id: number; deletedAt: string }>(`${ROOT}/${id}`)
}

export function eraseAllMyMemories(data: PersonalMemoryEraseAllRequest) {
  return controlRequest.post<PersonalMemoryEraseAllResult>(`${ROOT}/erase-all`, data)
}

export function listMyMemoryAudit(limit = 100) {
  return controlRequest.get<PersonalMemoryAudit[]>(`${ROOT}/audit`, { params: { limit } })
}

export function exportMyMemories() {
  return controlRequest.get(`${ROOT}/export`, { responseType: 'blob' })
}

export function listMyMemoryCandidates(params: { status?: string; limit?: number; offset?: number }) {
  return controlRequest.get<PersonalMemoryCandidatePage>(`${ROOT}/candidates`, { params })
}

export function approveMyMemoryCandidate(id: number, data?: PersonalMemoryCandidateReviewRequest) {
  return controlRequest.post<{ candidate: PersonalMemoryCandidate }>(`${ROOT}/candidates/${id}/approve`, data ?? {})
}

export function rejectMyMemoryCandidate(id: number) {
  return controlRequest.post<PersonalMemoryCandidate>(`${ROOT}/candidates/${id}/reject`)
}
