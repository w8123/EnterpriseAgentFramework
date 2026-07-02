import { controlRequest } from './request'
import type {
  DomainAssignment,
  DomainClassifyResponse,
  DomainCoverageRow,
  DomainDef,
  TargetRefBody,
} from '@/types/domain'

export function listDomains() {
  return controlRequest.get<DomainDef[]>('/api/domains')
}

export function createDomain(body: DomainDef) {
  return controlRequest.post<DomainDef>('/api/domains', body)
}

export function updateDomain(id: number, body: DomainDef) {
  return controlRequest.put<DomainDef>(`/api/domains/${id}`, body)
}

export function deleteDomain(id: number) {
  return controlRequest.delete(`/api/domains/${id}`)
}

export function listAssignments(code: string) {
  return controlRequest.get<DomainAssignment[]>(`/api/domains/${code}/assignments`)
}

export function grantAssignmentBatch(code: string, targets: TargetRefBody[]) {
  return controlRequest.post<{ ok: boolean; count: number }>(
    `/api/domains/${code}/assignments`,
    { targets },
  )
}

export function deleteAssignment(id: number) {
  return controlRequest.delete(`/api/domains/assignments/${id}`)
}

export function classifyDomain(text: string, topK = 5) {
  return controlRequest.post<DomainClassifyResponse>('/api/domains/classify', { text, topK })
}

export function getDomainCoverage() {
  return controlRequest.get<DomainCoverageRow[]>('/api/domains/coverage')
}
