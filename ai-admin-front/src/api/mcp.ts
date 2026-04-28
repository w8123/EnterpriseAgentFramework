import { agentRequest } from './request'
import type {
  McpCallLog,
  McpClient,
  McpClientCreateResult,
  McpVisibility,
} from '@/types/mcp'

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

// ===== Client =====
export function listMcpClients() {
  return agentRequest.get<McpClient[]>('/api/mcp/clients')
}

export function createMcpClient(body: {
  name: string
  roles: string[]
  toolWhitelist: string[]
  expiresAt?: string | null
}) {
  return agentRequest.post<McpClientCreateResult>('/api/mcp/clients', body)
}

export function updateMcpClient(id: number, body: Partial<McpClient> & { roles?: string[]; toolWhitelist?: string[] }) {
  return agentRequest.put<McpClient>(`/api/mcp/clients/${id}`, body)
}

export function deleteMcpClient(id: number) {
  return agentRequest.delete(`/api/mcp/clients/${id}`)
}

// ===== Visibility =====
export function listMcpVisibility() {
  return agentRequest.get<McpVisibility[]>('/api/mcp/visibility')
}

export function setMcpVisibility(body: { kind: 'TOOL' | 'SKILL'; name: string; exposed: boolean; note?: string }) {
  return agentRequest.post<McpVisibility>('/api/mcp/visibility', body)
}

// ===== Call Logs =====
export function pageMcpCallLogs(params: {
  current?: number
  size?: number
  clientId?: number
  method?: string
  success?: boolean
  days?: number
}) {
  return agentRequest.get<PageResult<McpCallLog>>('/api/mcp/call-logs', { params })
}
