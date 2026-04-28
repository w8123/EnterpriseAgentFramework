import { agentRequest } from './request'
import type {
  BatchPromoteToToolsResult,
  ProjectToolInfo,
  PromotedGlobalTool,
  ScanProject,
  ScanProjectAuthSaveRequest,
  ScanProjectBlockers,
  ScanProjectScanResult,
  ScanProjectUpsertRequest,
  ScanSettings,
} from '@/types/scanProject'
import type { ToolTestResult, ToolUpsertRequest } from '@/types/tool'

export function getScanProjects() {
  return agentRequest.get<ScanProject[]>('/api/scan-projects')
}

export function getScanProjectDetail(id: number) {
  return agentRequest.get<ScanProject>(`/api/scan-projects/${id}`)
}

/** 删除/重扫前：是否仍被 Agent 引用本项目的全局 Tool、Skill */
export function getScanProjectOperationBlockers(id: number) {
  return agentRequest.get<ScanProjectBlockers>(`/api/scan-projects/${id}/operation-blockers`)
}

export function createScanProject(data: ScanProjectUpsertRequest) {
  return agentRequest.post<ScanProject>('/api/scan-projects', data)
}

export function updateScanProject(id: number, data: ScanProjectUpsertRequest) {
  return agentRequest.put<ScanProject>(`/api/scan-projects/${id}`, data)
}

export function updateScanProjectAuthSettings(id: number, data: ScanProjectAuthSaveRequest) {
  return agentRequest.patch<ScanProject>(`/api/scan-projects/${id}/auth-settings`, data)
}

export function updateScanProjectScanSettings(id: number, data: ScanSettings) {
  return agentRequest.patch<ScanProject>(`/api/scan-projects/${id}/scan-settings`, data)
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

/** 从全局 Tool 中下架并解除关联 */
export function unpromoteScanProjectToolFromGlobal(projectId: number, scanToolId: number) {
  return agentRequest.post<ProjectToolInfo>(
    `/api/scan-projects/${projectId}/scan-tools/${scanToolId}/unpromote-from-global`,
  )
}

/** 用当前扫描行内容覆盖已关联的全局 Tool */
export function pushScanProjectToolToGlobalTool(projectId: number, scanToolId: number) {
  return agentRequest.post<ProjectToolInfo>(
    `/api/scan-projects/${projectId}/scan-tools/${scanToolId}/push-to-global-tool`,
  )
}

/** 将某模块下（或未关联模块）全部扫描接口注册为全局 Tool */
export function promoteScanModuleToolsToGlobal(projectId: number, moduleId: number | null) {
  return agentRequest.post<BatchPromoteToToolsResult>(`/api/scan-projects/${projectId}/scan-tools/promote-by-module`, {
    moduleId,
  })
}
