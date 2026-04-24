import { agentRequest } from './request'
import type {
  BatchPromoteToToolsResult,
  ProjectToolInfo,
  PromotedGlobalTool,
  ScanProject,
  ScanProjectScanResult,
  ScanProjectUpsertRequest,
} from '@/types/scanProject'
import type { ToolTestResult, ToolUpsertRequest } from '@/types/tool'

export function getScanProjects() {
  return agentRequest.get<ScanProject[]>('/api/scan-projects')
}

export function getScanProjectDetail(id: number) {
  return agentRequest.get<ScanProject>(`/api/scan-projects/${id}`)
}

export function createScanProject(data: ScanProjectUpsertRequest) {
  return agentRequest.post<ScanProject>('/api/scan-projects', data)
}

export function updateScanProject(id: number, data: ScanProjectUpsertRequest) {
  return agentRequest.put<ScanProject>(`/api/scan-projects/${id}`, data)
}

export function deleteScanProject(id: number) {
  return agentRequest.delete(`/api/scan-projects/${id}`)
}

export function triggerScan(id: number) {
  return agentRequest.post<ScanProjectScanResult>(`/api/scan-projects/${id}/scan`)
}

export function triggerRescan(id: number) {
  return agentRequest.post<ScanProjectScanResult>(`/api/scan-projects/${id}/rescan`)
}

export function getScanProjectTools(id: number) {
  return agentRequest.get<ProjectToolInfo[]>(`/api/scan-projects/${id}/tools`)
}

export function updateScanProjectTool(projectId: number, scanToolId: number, data: ToolUpsertRequest) {
  return agentRequest.put<ProjectToolInfo>(`/api/scan-projects/${projectId}/scan-tools/${scanToolId}`, data)
}

export function toggleScanProjectTool(projectId: number, scanToolId: number, enabled: boolean) {
  return agentRequest.put<ProjectToolInfo>(`/api/scan-projects/${projectId}/scan-tools/${scanToolId}/toggle`, {
    enabled,
  })
}

export function testScanProjectTool(projectId: number, scanToolId: number, args: Record<string, unknown>) {
  return agentRequest.post<ToolTestResult>(`/api/scan-projects/${projectId}/scan-tools/${scanToolId}/test`, {
    args,
  })
}

export function promoteScanProjectToolToGlobal(projectId: number, scanToolId: number) {
  return agentRequest.post<PromotedGlobalTool>(
    `/api/scan-projects/${projectId}/scan-tools/${scanToolId}/promote-to-tool`,
  )
}

/** 将某模块下（或未关联模块）全部扫描接口注册为全局 Tool */
export function promoteScanModuleToolsToGlobal(projectId: number, moduleId: number | null) {
  return agentRequest.post<BatchPromoteToToolsResult>(`/api/scan-projects/${projectId}/scan-tools/promote-by-module`, {
    moduleId,
  })
}
