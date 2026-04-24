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

/** 语义生成（项目摘要 / 模块 / 工具 / 批量）可选的模型网关参数 */
export type SemanticLlmParams = {
  provider?: string
  model?: string
}

function llmQueryParams(llm?: SemanticLlmParams): Record<string, string> {
  const q: Record<string, string> = {}
  const p = llm?.provider?.trim()
  const m = llm?.model?.trim()
  if (p) q.provider = p
  if (m) q.model = m
  return q
}

export function startProjectBatchGenerate(projectId: number, force = false, llm?: SemanticLlmParams) {
  return agentRequest.post<BatchStartResponse>(
    `/api/scan-projects/${projectId}/semantic/generate`,
    null,
    { params: { force, ...llmQueryParams(llm) } },
  )
}

/** 无进行中/历史任务时响应体为 null（HTTP 200） */
export function getProjectBatchStatus(projectId: number, taskId?: string) {
  return agentRequest.get<SemanticTask | null>(
    `/api/scan-projects/${projectId}/semantic/status`,
    { params: taskId ? { taskId } : {} },
  )
}

export function generateProjectDoc(projectId: number, force = true, llm?: SemanticLlmParams) {
  return agentRequest.post<SemanticDoc>(
    `/api/scan-projects/${projectId}/semantic/generate-project`,
    null,
    { params: { force, ...llmQueryParams(llm) } },
  )
}

export function generateModuleDoc(moduleId: number, force = true, llm?: SemanticLlmParams) {
  return agentRequest.post<SemanticDoc>(
    `/api/scan-modules/${moduleId}/semantic/generate`,
    null,
    { params: { force, ...llmQueryParams(llm) } },
  )
}

export function generateToolDoc(toolName: string, force = true, llm?: SemanticLlmParams) {
  return agentRequest.post<SemanticDoc>(
    `/api/tools/${encodeURIComponent(toolName)}/semantic/generate`,
    null,
    { params: { force, ...llmQueryParams(llm) } },
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
