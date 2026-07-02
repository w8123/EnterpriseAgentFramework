import { controlRequest } from './request'
import type {
  BatchStartResponse,
  ModuleMergeRequest,
  ScanModule,
  SemanticDoc,
  SemanticEditRequest,
  SemanticLevel,
  SemanticTask,
} from '@/types/semanticDoc'

/** 生成语义文档，可用于项目/模块/工具级别的提示词生成。 */
export type SemanticLlmParams = {
  modelInstanceId?: string
}

function llmQueryParams(llm?: SemanticLlmParams): Record<string, string> {
  const q: Record<string, string> = {}
  const id = llm?.modelInstanceId?.trim()
  if (id) q.modelInstanceId = id
  return q
}

export function startProjectBatchGenerate(projectId: number, force = false, llm?: SemanticLlmParams) {
  return controlRequest.post<BatchStartResponse>(
    `/api/scan-projects/${projectId}/semantic/generate`,
    null,
    { params: { force, ...llmQueryParams(llm) } },
  )
}

/** 查询异步任务状态接口，返回 200 或 404。 */
export function getProjectBatchStatus(projectId: number, taskId?: string) {
  return controlRequest.get<SemanticTask | null>(
    `/api/scan-projects/${projectId}/semantic/status`,
    { params: taskId ? { taskId } : {} },
  )
}

export function generateProjectDoc(projectId: number, force = true, llm?: SemanticLlmParams) {
  return controlRequest.post<SemanticDoc>(
    `/api/scan-projects/${projectId}/semantic/generate-project`,
    null,
    { params: { force, ...llmQueryParams(llm) } },
  )
}

export function generateModuleDoc(moduleId: number, force = true, llm?: SemanticLlmParams) {
  return controlRequest.post<SemanticDoc>(
    `/api/scan-modules/${moduleId}/semantic/generate`,
    null,
    { params: { force, ...llmQueryParams(llm) } },
  )
}

export function generateToolDoc(toolName: string, force = true, llm?: SemanticLlmParams) {
  return controlRequest.post<SemanticDoc>(
    `/api/tools/${encodeURIComponent(toolName)}/semantic/generate`,
    null,
    { params: { force, ...llmQueryParams(llm) } },
  )
}

/** 生成扫描工具对应的语义文档，scanTool 为 scan_tool.id。 */
export function generateScanToolDoc(projectId: number, scanToolId: number, force = true, llm?: SemanticLlmParams) {
  return controlRequest.post<SemanticDoc>(
    `/api/scan-projects/${projectId}/scan-tools/${scanToolId}/semantic/generate`,
    null,
    { params: { force, ...llmQueryParams(llm) } },
  )
}

export function findSemanticDoc(params: {
  level: SemanticLevel
  projectId?: number
  moduleId?: number
  toolName?: string
  /** level 为 scan_tool 时，对应 scan_project_tool.id */
  scanToolId?: number
}) {
  /** 当前记录不存在时返回 404，表示无需生成语义文档。 */
  return controlRequest.get<SemanticDoc | null>('/api/semantic-docs', {
    params,
    validateStatus: (status) => (status >= 200 && status < 300) || status === 404,
  })
}

export function listProjectSemanticDocs(projectId: number) {
  return controlRequest.get<SemanticDoc[]>(`/api/scan-projects/${projectId}/semantic-docs`)
}

export function editSemanticDoc(id: number, payload: SemanticEditRequest) {
  return controlRequest.put<SemanticDoc>(`/api/semantic-docs/${id}`, payload)
}

export function listScanModules(projectId: number) {
  return controlRequest.get<ScanModule[]>(`/api/scan-projects/${projectId}/modules`)
}

export function renameScanModule(id: number, displayName: string) {
  return controlRequest.put<ScanModule>(`/api/scan-modules/${id}`, { displayName })
}

export function mergeScanModules(payload: ModuleMergeRequest) {
  return controlRequest.post<ScanModule>('/api/scan-modules/merge', payload)
}
