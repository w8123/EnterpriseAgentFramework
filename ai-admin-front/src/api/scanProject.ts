import { controlRequest } from './request'
import type { AxiosRequestConfig } from 'axios'
import type {
  AiCodingGatewayManifest,
  ProjectToolInfo,
  ScanProject,
  ScanProjectAuthSaveRequest,
  AiOnboardingManifest,
  AiCodingAccessResponse,
  AiCodingAccessUpdateRequest,
  ScanProjectBlockers,
  ScanProjectOperation,
  ScanProjectRegistryCredentialSaveRequest,
  SdkAccessCheckResponse,
  SdkCapabilityScanResult,
  ScanProjectScanResult,
  ScanProjectUpsertRequest,
  ScanSettings,
  SensitiveScanTask,
  ToolReconcileSummary,
} from '@/types/scanProject'
import type { SemanticLlmParams } from '@/api/semanticDoc'

export interface ScanDiffSummary {
  projectId: number
  toolCount: number
  promotedCount: number
  missingDescriptionCount: number
  missingAiDescriptionCount: number
  duplicateStableKeyCount: number
  duplicates: Array<{
    stableKey: string
    scanToolIds: number[]
  }>
}

export interface ScanProjectListQuery {
  keyword?: string
  projectKind?: ScanProject['projectKind'] | ''
  status?: ScanProject['status'] | ''
}

export function getScanProjects(query: ScanProjectListQuery = {}, config?: AxiosRequestConfig) {
  const keyword = query.keyword?.trim()
  return controlRequest.get<ScanProject[]>('/api/scan-projects', {
    ...config,
    params: {
      keyword: keyword || undefined,
      projectKind: query.projectKind || undefined,
      status: query.status || undefined,
    },
  })
}

export function getScanProjectDetail(id: number) {
  return controlRequest.get<ScanProject>(`/api/scan-projects/${id}`)
}

/** 删除核对源资产和引用；重扫仅核对受保护的引用。 */
export function getScanProjectOperationBlockers(id: number, operation: ScanProjectOperation) {
  return controlRequest.get<ScanProjectBlockers>(`/api/scan-projects/${id}/operation-blockers`, { params: { operation } })
}

export function createScanProject(data: ScanProjectUpsertRequest) {
  return controlRequest.post<ScanProject>('/api/scan-projects', data)
}

export function updateScanProject(id: number, data: ScanProjectUpsertRequest) {
  return controlRequest.put<ScanProject>(`/api/scan-projects/${id}`, data)
}

export function updateScanProjectAuthSettings(id: number, data: ScanProjectAuthSaveRequest) {
  return controlRequest.patch<ScanProject>(`/api/scan-projects/${id}/auth-settings`, data)
}

export function updateScanProjectRegistryCredential(id: number, data: ScanProjectRegistryCredentialSaveRequest) {
  return controlRequest.patch<ScanProject>(`/api/scan-projects/${id}/registry-credential`, data)
}

export function runSdkAccessCheck(id: number) {
  return controlRequest.post<SdkAccessCheckResponse>(`/api/scan-projects/${id}/sdk-access-check`, {})
}

export function getAiOnboardingManifest(id: number) {
  return controlRequest.get<AiOnboardingManifest>(`/api/ai-assist/projects/${id}/onboarding-manifest`)
}

export function provisionProjectAgent(id: number, requestedBy: string) {
  return controlRequest.post<Record<string, unknown>>(
    `/api/ai-assist/projects/${id}/agents/provision`,
    { requestedBy },
  )
}

export function getAiCodingGatewayManifest(id: number) {
  return controlRequest.get<AiCodingGatewayManifest>(`/api/ai-coding/projects/${id}/manifest`)
}

export function updateAiCodingAccess(id: number, data: AiCodingAccessUpdateRequest) {
  return controlRequest.patch<AiCodingAccessResponse>(`/api/ai-assist/projects/${id}/ai-coding-access`, data)
}

export function updateScanProjectScanSettings(id: number, data: ScanSettings) {
  return controlRequest.patch<ScanProject>(`/api/scan-projects/${id}/scan-settings`, data)
}

export function deleteScanProject(id: number) {
  return controlRequest.delete(`/api/scan-projects/${id}`)
}

export function triggerScan(id: number) {
  return controlRequest.post<ScanProjectScanResult>(`/api/scan-projects/${id}/scan`)
}

export function triggerRescan(id: number) {
  return controlRequest.post<ScanProjectScanResult>(`/api/scan-projects/${id}/rescan`)
}

export function triggerSdkCapabilityScan(projectId: number) {
  return controlRequest.post<SdkCapabilityScanResult>(`/api/scan-projects/${projectId}/sdk-sync/scan`)
}

/** 单条接口：从源码 / OpenAPI 重新解析并更新该扫描项。 */
export function rescanScanToolFromSource(projectId: number, scanToolId: number) {
  return controlRequest.post<ProjectToolInfo>(
    `/api/scan-projects/${projectId}/scan-tools/${scanToolId}/rescan-from-source`,
  )
}

export function getScanProjectTools(id: number, view?: 'summary' | 'full') {
  return controlRequest.get<ProjectToolInfo[]>(`/api/scan-projects/${id}/tools`, {
    params: view ? { view } : {},
  })
}

export function getScanProjectTool(projectId: number, scanToolId: number) {
  return controlRequest.get<ProjectToolInfo>(`/api/scan-projects/${projectId}/scan-tools/${scanToolId}`)
}

/** 只读核对来源与历史投影关联，不创建或修改执行定义。 */
export function reconcileScanProjectTools(projectId: number) {
  return controlRequest.post<ToolReconcileSummary>(`/api/scan-projects/${projectId}/tools/reconcile`)
}

export function getScanProjectDiffSummary(id: number) {
  return controlRequest.get<ScanDiffSummary>(`/api/scan-projects/${id}/diff-summary`)
}


/** 与 AI 生成功能共享的 LLM 参数。 */
function sensitiveScanLlmQuery(llm?: SemanticLlmParams): Record<string, string> {
  const q: Record<string, string> = {}
  const id = llm?.modelInstanceId?.trim()
  if (id) q.modelInstanceId = id
  return q
}

/** 异步批量敏感数据扫描（HTTP 202）。 */
export function startSensitiveDataScan(projectId: number, llm?: SemanticLlmParams) {
  return controlRequest.post<{ taskId: string }>(
    `/api/scan-projects/${projectId}/sensitive-data/scan`,
    null,
    { params: sensitiveScanLlmQuery(llm) },
  )
}

/** 无进行中任务时响应体可能为 null。 */
export function getSensitiveDataScanStatus(projectId: number, taskId?: string) {
  return controlRequest.get<SensitiveScanTask | null>(
    `/api/scan-projects/${projectId}/sensitive-data/status`,
    { params: taskId ? { taskId } : {} },
  )
}
