import { agentRequest } from './request'
import type {
  BatchStartResponse,
  ModuleMergeRequest,
  ScanModule,
  SemanticDoc,
  SemanticEditRequest,
  SemanticLevel,
  SemanticTask,
} from '@/types/semanticDoc'

export function startProjectBatchGenerate(projectId: number, force = false) {
  return agentRequest.post<BatchStartResponse>(
    `/api/scan-projects/${projectId}/semantic/generate`,
    null,
    { params: { force } },
  )
}

/** 无进行中/历史任务时响应体为 null（HTTP 200） */
export function getProjectBatchStatus(projectId: number, taskId?: string) {
  return agentRequest.get<SemanticTask | null>(
    `/api/scan-projects/${projectId}/semantic/status`,
    { params: taskId ? { taskId } : {} },
  )
}

export function generateProjectDoc(projectId: number, force = true) {
  return agentRequest.post<SemanticDoc>(
    `/api/scan-projects/${projectId}/semantic/generate-project`,
    null,
    { params: { force } },
  )
}

export function generateModuleDoc(moduleId: number, force = true) {
  return agentRequest.post<SemanticDoc>(
    `/api/scan-modules/${moduleId}/semantic/generate`,
    null,
    { params: { force } },
  )
}

export function generateToolDoc(toolName: string, force = true) {
  return agentRequest.post<SemanticDoc>(
    `/api/tools/${encodeURIComponent(toolName)}/semantic/generate`,
    null,
    { params: { force } },
  )
}

export function findSemanticDoc(params: {
  level: SemanticLevel
  projectId?: number
  moduleId?: number
  toolName?: string
}) {
  return agentRequest.get<SemanticDoc>('/api/semantic-docs', { params })
}

export function listProjectSemanticDocs(projectId: number) {
  return agentRequest.get<SemanticDoc[]>(`/api/scan-projects/${projectId}/semantic-docs`)
}

export function editSemanticDoc(id: number, payload: SemanticEditRequest) {
  return agentRequest.put<SemanticDoc>(`/api/semantic-docs/${id}`, payload)
}

export function listScanModules(projectId: number) {
  return agentRequest.get<ScanModule[]>(`/api/scan-projects/${projectId}/modules`)
}

export function renameScanModule(id: number, displayName: string) {
  return agentRequest.put<ScanModule>(`/api/scan-modules/${id}`, { displayName })
}

export function mergeScanModules(payload: ModuleMergeRequest) {
  return agentRequest.post<ScanModule>('/api/scan-modules/merge', payload)
}
