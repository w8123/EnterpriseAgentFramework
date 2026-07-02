import { controlRequest } from './request'
import type {
  BatchPromoteToToolsResult,
  AiCodingGatewayManifest,
  ProjectToolInfo,
  PromotedGlobalTool,
  ScanProject,
  ScanProjectAuthSaveRequest,
  AiOnboardingManifest,
  AiAccessCheckRunResponse,
  AiAccessSession,
  AiCodingAccessResponse,
  AiCodingAccessUpdateRequest,
  PageAssistantCheckRequest,
  PageAssistantCheckRunResponse,
  PageAssistantCatalogSyncRequest,
  PageAssistantCatalogSyncResponse,
  PageAssistantOnboardingManifest,
  PageAssistantPageRegisterRequest,
  PageAssistantPageRegisterResponse,
  PageAssistantSessionRequest,
  PageAssistantSessionSummary,
  PageAssistantTargetRequest,
  PageAssistantWorkflowAiCodingResultRequest,
  ScanProjectBlockers,
  ScanProjectRegistryCredentialSaveRequest,
  SdkAccessCheckRequest,
  SdkAccessCheckResponse,
  ScanProjectScanResult,
  ScanProjectUpsertRequest,
  ScanSettings,
  SensitiveScanTask,
  ToolReconcileSummary,
} from '@/types/scanProject'
import type { ToolTestResult, ToolUpsertRequest } from '@/types/tool'
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

export function getScanProjects() {
  return controlRequest.get<ScanProject[]>('/api/scan-projects')
}

export function getScanProjectDetail(id: number) {
  return controlRequest.get<ScanProject>(`/api/scan-projects/${id}`)
}

/** 获取项目工具是否仍被 Agent 使用；legacy 工具名（如 SKILL）也会返回。 */
export function getScanProjectOperationBlockers(id: number) {
  return controlRequest.get<ScanProjectBlockers>(`/api/scan-projects/${id}/operation-blockers`)
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

export function runSdkAccessCheck(id: number, data: SdkAccessCheckRequest) {
  return controlRequest.post<SdkAccessCheckResponse>(`/api/scan-projects/${id}/sdk-access-check`, data)
}

export function startAiAccessSession(id: number, toolName?: string) {
  return controlRequest.post<AiAccessSession>(`/api/ai-assist/projects/${id}/access-sessions`, null, {
    params: toolName ? { toolName } : {},
  })
}

export function getLatestAiAccessSession(id: number) {
  return controlRequest.get<AiAccessSession>(`/api/ai-assist/projects/${id}/access-sessions/latest`)
}

export function runAiAccessSessionChecks(id: number, sessionId: string, data: SdkAccessCheckRequest) {
  return controlRequest.post<AiAccessCheckRunResponse>(
    `/api/ai-assist/projects/${id}/access-sessions/${sessionId}/checks/run`,
    data,
  )
}

export function getAiOnboardingManifest(id: number) {
  return controlRequest.get<AiOnboardingManifest>(`/api/ai-assist/projects/${id}/onboarding-manifest`)
}

export function getAiCodingGatewayManifest(id: number) {
  return controlRequest.get<AiCodingGatewayManifest>(`/api/ai-coding/projects/${id}/manifest`)
}

export function getPageAssistantOnboardingManifest(
  id: number,
  data?: PageAssistantSessionRequest,
) {
  return controlRequest.get<PageAssistantOnboardingManifest>(
    `/api/ai-assist/projects/${id}/page-assistant/onboarding-manifest`,
    {
      params: {
        toolName: data?.toolName,
        pageKey: data?.pageKey,
        routePattern: data?.routePattern,
        actionKeys: data?.actionKeys,
      },
    },
  )
}

export function startPageAssistantAccessSession(id: number, data: PageAssistantSessionRequest) {
  return controlRequest.post<AiAccessSession>(`/api/ai-assist/projects/${id}/page-assistant/sessions`, data)
}

export function getLatestPageAssistantAccessSession(id: number, pageKey?: string | null) {
  return controlRequest.get<AiAccessSession>(`/api/ai-assist/projects/${id}/page-assistant/sessions/latest`, {
    params: {
      pageKey: pageKey || undefined,
    },
  })
}

export function getPageAssistantAccessSessions(id: number, pageKey?: string | null) {
  return controlRequest.get<PageAssistantSessionSummary[]>(`/api/ai-assist/projects/${id}/page-assistant/sessions`, {
    params: {
      pageKey: pageKey || undefined,
    },
  })
}

export function bindPageAssistantAccessSessionTarget(
  id: number,
  sessionId: string,
  data: PageAssistantTargetRequest,
) {
  return controlRequest.put<AiAccessSession>(
    `/api/ai-assist/projects/${id}/page-assistant/sessions/${sessionId}/target`,
    data,
  )
}

export function syncPageAssistantAccessCatalog(
  id: number,
  sessionId: string,
  data: PageAssistantCatalogSyncRequest,
) {
  return controlRequest.post<PageAssistantCatalogSyncResponse>(
    `/api/ai-assist/projects/${id}/page-assistant/sessions/${sessionId}/catalog/sync`,
    data,
  )
}

export function runPageAssistantAccessSessionChecks(
  id: number,
  sessionId: string,
  data: PageAssistantCheckRequest,
) {
  return controlRequest.post<PageAssistantCheckRunResponse>(
    `/api/ai-assist/projects/${id}/page-assistant/sessions/${sessionId}/checks/run`,
    data,
  )
}

export function registerPageAssistantPage(
  id: number,
  data: PageAssistantPageRegisterRequest,
) {
  return controlRequest.post<PageAssistantPageRegisterResponse>(
    `/api/ai-assist/projects/${id}/page-assistant/pages/register`,
    data,
  )
}

export function reportPageAssistantWorkflowAiCodingResult(
  id: number,
  sessionId: string,
  data: PageAssistantWorkflowAiCodingResultRequest,
) {
  return controlRequest.post<AiAccessSession>(
    `/api/ai-assist/projects/${id}/page-assistant/sessions/${sessionId}/workflow-ai-coding-result`,
    data,
  )
}

export function resetPageAssistantWorkflowAiCodingResult(
  id: number,
  sessionId: string,
  deleteWorkflow = true,
) {
  return controlRequest.delete<AiAccessSession>(
    `/api/ai-assist/projects/${id}/page-assistant/sessions/${sessionId}/workflow-ai-coding-result`,
    {
      params: {
        deleteWorkflow,
      },
    },
  )
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

/** 补齐 SDK 镜像并汇总 API 与全局 Tool 关联状态。 */
export function reconcileScanProjectTools(projectId: number) {
  return controlRequest.post<ToolReconcileSummary>(`/api/scan-projects/${projectId}/tools/reconcile`)
}

export function getScanProjectDiffSummary(id: number) {
  return controlRequest.get<ScanDiffSummary>(`/api/scan-projects/${id}/diff-summary`)
}

export function updateScanProjectTool(projectId: number, scanToolId: number, data: ToolUpsertRequest) {
  return controlRequest.put<ProjectToolInfo>(`/api/scan-projects/${projectId}/scan-tools/${scanToolId}`, data)
}

export function toggleScanProjectTool(projectId: number, scanToolId: number, enabled: boolean) {
  return controlRequest.put<ProjectToolInfo>(`/api/scan-projects/${projectId}/scan-tools/${scanToolId}/toggle`, {
    enabled,
  })
}

export function testScanProjectTool(projectId: number, scanToolId: number, args: Record<string, unknown>) {
  return controlRequest.post<ToolTestResult>(`/api/scan-projects/${projectId}/scan-tools/${scanToolId}/test`, {
    args,
  })
}

export function promoteScanProjectToolToGlobal(projectId: number, scanToolId: number) {
  return controlRequest.post<PromotedGlobalTool>(
    `/api/scan-projects/${projectId}/scan-tools/${scanToolId}/promote-to-tool`,
  )
}

/** 从全局 Tool 中下架并解除关联。 */
export function unpromoteScanProjectToolFromGlobal(projectId: number, scanToolId: number) {
  return controlRequest.post<ProjectToolInfo>(
    `/api/scan-projects/${projectId}/scan-tools/${scanToolId}/unpromote-from-global`,
  )
}

/** 用当前扫描行覆盖已关联的全局 Tool。 */
export function pushScanProjectToolToGlobalTool(projectId: number, scanToolId: number) {
  return controlRequest.post<ProjectToolInfo>(
    `/api/scan-projects/${projectId}/scan-tools/${scanToolId}/push-to-global-tool`,
  )
}

/** 将某模块下或未关联模块的扫描接口注册为全局 Tool。 */
export function promoteScanModuleToolsToGlobal(projectId: number, moduleId: number | null) {
  return controlRequest.post<BatchPromoteToToolsResult>(`/api/scan-projects/${projectId}/scan-tools/promote-by-module`, {
    moduleId,
  })
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
