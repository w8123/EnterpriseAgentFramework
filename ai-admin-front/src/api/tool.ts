import { agentRequest } from './request'
import type { ToolInfo, ToolTestResult } from '@/types/tool'

export function getTools() {
  return agentRequest.get<ToolInfo[]>('/api/tools')
}

export function getToolDetail(name: string) {
  return agentRequest.get<ToolInfo>(`/api/tools/${name}`)
}

export function testTool(name: string, args: Record<string, unknown>) {
  return agentRequest.post<ToolTestResult>(`/api/tools/${name}/test`, { args })
}
