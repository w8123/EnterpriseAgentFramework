import { agentRequest } from './request'
import type {
  DomainAssignment,
  DomainClassifyResponse,
  DomainCoverageRow,
  DomainDef,
  TargetRefBody,
} from '@/types/domain'

export function listDomains() {
  return agentRequest.get<DomainDef[]>('/api/domains')
}

export function createDomain(body: DomainDef) {
  return agentRequest.post<DomainDef>('/api/domains', body)
}

export function updateDomain(id: number, body: DomainDef) {
  return agentRequest.put<DomainDef>(`/api/domains/${id}`, body)
}

export function deleteDomain(id: number) {
  return agentRequest.delete(`/api/domains/${id}`)
}

export function listAssignments(code: string) {
  return agentRequest.get<DomainAssignment[]>(`/api/domains/${code}/assignments`)
}

export function grantAssignmentBatch(code: string, targets: TargetRefBody[]) {
  return agentRequest.post<{ ok: boolean; count: number }>(
    `/api/domains/${code}/assignments`,
    { targets },
  )
}

export function deleteAssignment(id: number) {
  return agentRequest.delete(`/api/domains/assignments/${id}`)
}

export function classifyDomain(text: string, topK = 5) {
  return agentRequest.post<DomainClassifyResponse>('/api/domains/classify', { text, topK })
}

export function getDomainCoverage() {
  return agentRequest.get<DomainCoverageRow[]>('/api/domains/coverage')
}
