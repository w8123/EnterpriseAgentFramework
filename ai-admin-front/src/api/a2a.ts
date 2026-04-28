import { agentRequest } from './request'
import type { A2aCallLog, A2aEndpoint, A2aEndpointDetail } from '@/types/a2a'

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

export function pageA2aEndpoints(params: {
  pageNum?: number
  pageSize?: number
  agentKey?: string
  enabled?: boolean
}) {
  return agentRequest.get<PageResult<A2aEndpoint>>('/api/admin/a2a/endpoints', { params })
}

export function getA2aEndpoint(id: number) {
  return agentRequest.get<A2aEndpointDetail>(`/api/admin/a2a/endpoints/${id}`)
}

export function upsertA2aEndpoint(body: {
  agentId: string
  card?: Record<string, unknown>
  enabled?: boolean
}) {
  return agentRequest.post<A2aEndpoint>('/api/admin/a2a/endpoints', body)
}

export function setA2aEndpointEnabled(id: number, enabled: boolean) {
  return agentRequest.put(`/api/admin/a2a/endpoints/${id}/enabled`, null, { params: { enabled } })
}

export function deleteA2aEndpoint(id: number) {
  return agentRequest.delete(`/api/admin/a2a/endpoints/${id}`)
}

export function pageA2aCallLogs(params: {
  pageNum?: number
  pageSize?: number
  agentKey?: string
  method?: string
  success?: boolean
}) {
  return agentRequest.get<PageResult<A2aCallLog>>('/api/admin/a2a/call-logs', { params })
}
