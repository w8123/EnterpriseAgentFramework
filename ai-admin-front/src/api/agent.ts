import { agentRequest } from './request'
import type { AgentDefinition, AgentForm, AgentResult } from '@/types/agent'
import type { ChatResponse } from '@/types/chat'
import type { ApiResult } from '@/types/import'

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
