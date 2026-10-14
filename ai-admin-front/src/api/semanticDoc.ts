import { controlRequest } from './request'
import type { BatchStartResponse, CapabilityEnrichment, CatalogClassificationIndex, CatalogClassificationSubjectType, ScanModule, SemanticDoc,
  SemanticDocIndex, SemanticEditRequest, SemanticSubjectType, SemanticTask } from '@/types/semanticDoc'

export type SemanticLlmParams = { modelInstanceId?: string }
const projectPath = (projectId: number) => `/api/business-capabilities/projects/${projectId}`
const subjectPath = (projectId: number, subjectType: SemanticSubjectType, subjectId: number) =>
  `${projectPath(projectId)}/subjects/${subjectType}/${subjectId}`
const llmQueryParams = (llm?: SemanticLlmParams) => llm?.modelInstanceId?.trim() ? { modelInstanceId: llm.modelInstanceId.trim() } : {}

export function startProjectBatchGenerate(projectId: number, force = false, llm?: SemanticLlmParams) {
  return controlRequest.post<BatchStartResponse>(`${projectPath(projectId)}/semantic/generate`, null,
    { params: { force, ...llmQueryParams(llm) } })
}
export function getProjectBatchStatus(projectId: number, taskId?: string) {
  return controlRequest.get<SemanticTask | null>(`${projectPath(projectId)}/semantic/status`, { params: taskId ? { taskId } : {} })
}
export function generateSubjectSemanticDoc(projectId: number, subjectType: SemanticSubjectType, subjectId: number,
  force = true, llm?: SemanticLlmParams) {
  return controlRequest.post<SemanticDoc>(`${subjectPath(projectId, subjectType, subjectId)}/semantic/generate`, null,
    { params: { force, ...llmQueryParams(llm) } })
}
export function listProjectSemanticDocs(projectId: number) {
  return controlRequest.get<SemanticDocIndex[]>(`${projectPath(projectId)}/semantic-docs`)
}
export function getSubjectSemanticDoc(projectId: number, subjectType: SemanticSubjectType, subjectId: number) {
  return controlRequest.get<SemanticDoc | null>(`${subjectPath(projectId, subjectType, subjectId)}/semantic`, { errorFeedback: 'local' })
}
export function editSubjectSemanticDoc(projectId: number, subjectType: SemanticSubjectType, subjectId: number, payload: SemanticEditRequest) {
  return controlRequest.put<SemanticDoc>(`${subjectPath(projectId, subjectType, subjectId)}/semantic`, payload)
}
export function getCapabilityEnrichment(projectId: number, subjectType: SemanticSubjectType, subjectId: number) {
  return controlRequest.get<CapabilityEnrichment>(`${subjectPath(projectId, subjectType, subjectId)}/enrichment`)
}
export function listScanModules(projectId: number) {
  return controlRequest.get<ScanModule[]>(`${projectPath(projectId)}/groups`, { errorFeedback: 'local' })
}
export function createScanModule(projectId: number, displayName: string) {
  return controlRequest.post<ScanModule>(`${projectPath(projectId)}/groups`, { displayName }, { errorFeedback: 'local' })
}
export function getCatalogClassificationIndex(projectId: number, subjectType: CatalogClassificationSubjectType) {
  return controlRequest.get<CatalogClassificationIndex>(`${projectPath(projectId)}/classification/${subjectType}`, { errorFeedback: 'local' })
}
export interface ClassificationAssignment {
  projectId: number; subjectType: CatalogClassificationSubjectType; subjectId: number; revision: number; groupIds: number[]
}
export function getClassificationAssignments(projectId: number, type: CatalogClassificationSubjectType, subjectIds: number[]) {
  return controlRequest.get<ClassificationAssignment[]>(`${projectPath(projectId)}/classification/${type}/assignments`,
    { params: { subjectIds: subjectIds.join(',') }, errorFeedback: 'local' })
}
export function replaceClassifications(projectId: number, type: CatalogClassificationSubjectType,
  body: { subjects: { subjectId: number; expectedRevision: number }[]; groupIds: number[]; newNames: string[] }) {
  return controlRequest.put<ClassificationAssignment[]>(`${projectPath(projectId)}/classification/${type}`, body, { errorFeedback: 'local' })
}
export function renameScanModule(projectId: number, id: number, displayName: string) {
  return controlRequest.put<ScanModule>(`${projectPath(projectId)}/groups/${id}`, { displayName })
}
