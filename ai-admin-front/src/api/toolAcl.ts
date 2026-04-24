import { agentRequest } from './request'
import type {
  ToolAclBatchGrantRequest,
  ToolAclDecision,
  ToolAclExplainRequest,
  ToolAclRule,
} from '@/types/toolAcl'

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

export function listToolAcl(params: {
  current?: number
  size?: number
  roleCode?: string
  targetKind?: string
}) {
  return agentRequest.get<PageResult<ToolAclRule>>('/api/tool-acl', { params })
}

export function listToolAclRoles() {
  return agentRequest.get<string[]>('/api/tool-acl/roles')
}

export function createToolAcl(body: Partial<ToolAclRule>) {
  return agentRequest.post<ToolAclRule>('/api/tool-acl', body)
}

export function updateToolAcl(id: number, body: Partial<ToolAclRule>) {
  return agentRequest.put<ToolAclRule>(`/api/tool-acl/${id}`, body)
}

export function deleteToolAcl(id: number) {
  return agentRequest.delete(`/api/tool-acl/${id}`)
}

export function toggleToolAcl(id: number, enabled: boolean) {
  return agentRequest.post<ToolAclRule>(`/api/tool-acl/${id}/toggle`, { enabled })
}

export function grantToolAclBatch(body: ToolAclBatchGrantRequest) {
  return agentRequest.post<{ ok: boolean; count: number }>('/api/tool-acl/batch', body)
}

export function explainToolAcl(body: ToolAclExplainRequest) {
  return agentRequest.post<Record<string, ToolAclDecision>>('/api/tool-acl/explain', body)
}
