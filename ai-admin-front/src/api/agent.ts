import { agentRequest } from './request'
import type {
  AgentDefinition,
  AgentForm,
  AgentResult,
  AgentVersion,
  PublishVersionRequest,
} from '@/types/agent'
import type { ChatResponse } from '@/types/chat'

export function getAgentList() {
  return agentRequest.get<AgentDefinition[]>('/api/agent/definitions')
}

export function getAgent(id: string) {
  return agentRequest.get<AgentDefinition>(`/api/agent/definitions/${id}`)
}

export function createAgent(data: AgentForm) {
  return agentRequest.post<AgentDefinition>('/api/agent/definitions', data)
}

export function updateAgent(id: string, data: Partial<AgentForm>) {
  return agentRequest.put<AgentDefinition>(`/api/agent/definitions/${id}`, data)
}

export function deleteAgent(id: string) {
  return agentRequest.delete(`/api/agent/definitions/${id}`)
}

export function executeAgent(data: { message: string; sessionId?: string; userId?: string }) {
  return agentRequest.post<ChatResponse>('/api/agent/execute', data)
}

export function executeAgentDetailed(data: { message: string; sessionId?: string; userId?: string }) {
  return agentRequest.post<AgentResult>('/api/agent/execute/detailed', data)
}

// ── Phase 3.0 Agent Studio: 版本 & 发布 ─────────────────────────

export function listAgentVersions(agentId: string) {
  return agentRequest.get<AgentVersion[]>(`/api/agents/${agentId}/versions`)
}

export function publishAgentVersion(agentId: string, data: PublishVersionRequest) {
  return agentRequest.post<AgentVersion>(`/api/agents/${agentId}/versions`, data)
}

export function rollbackAgentVersion(agentId: string, versionId: number, operator?: string) {
  return agentRequest.post<AgentVersion>(
    `/api/agents/${agentId}/versions/${versionId}/rollback`,
    { operator },
  )
}

/** 通过发布端点调用 Agent（{key} 对应 keySlug） */
export function gatewayChat(key: string, data: { message: string; sessionId?: string; userId?: string }) {
  return agentRequest.post<ChatResponse>(`/api/v1/agents/${key}/chat`, data)
}
