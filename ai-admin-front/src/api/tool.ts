import { agentRequest } from './request'
import type { ToolInfo, ToolListQuery, ToolPageResult, ToolTestResult, ToolUpsertRequest } from '@/types/tool'

export function getTools(params?: ToolListQuery) {
  return agentRequest.get<ToolPageResult>('/api/tools', { params })
}

export function getToolDetail(name: string) {
  return agentRequest.get<ToolInfo>(`/api/tools/${name}`)
}

export function createTool(data: ToolUpsertRequest) {
  return agentRequest.post<ToolInfo>('/api/tools', data)
}

export function updateTool(name: string, data: ToolUpsertRequest) {
  return agentRequest.put<ToolInfo>(`/api/tools/${name}`, data)
}

export function deleteTool(name: string) {
  return agentRequest.delete(`/api/tools/${name}`)
}

export function toggleTool(name: string, enabled: boolean) {
  return agentRequest.put<ToolInfo>(`/api/tools/${name}/toggle`, { enabled })
}

export function testTool(name: string, args: Record<string, unknown>) {
  return agentRequest.post<ToolTestResult>(`/api/tools/${name}/test`, { args })
}
