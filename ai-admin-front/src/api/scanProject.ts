import { agentRequest } from './request'
import type { ProjectToolInfo, ScanProject, ScanProjectScanResult, ScanProjectUpsertRequest } from '@/types/scanProject'

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
