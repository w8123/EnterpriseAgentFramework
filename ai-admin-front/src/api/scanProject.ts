import { agentRequest } from './request'
import type {
  BatchPromoteToToolsResult,
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
  return agentRequest.get<ScanProject[]>('/api/scan-projects')
}

export function getScanProjectDetail(id: number) {
  return agentRequest.get<ScanProject>(`/api/scan-projects/${id}`)
}

/** 删除/重扫前：是否仍被 Agent 引用本项目的全局 Tool、粗粒度能力（kind=SKILL，legacy） */
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

export function updateScanProjectRegistryCredential(id: number, data: ScanProjectRegistryCredentialSaveRequest) {
  return agentRequest.patch<ScanProject>(`/api/scan-projects/${id}/registry-credential`, data)
}

export function runSdkAccessCheck(id: number, data: SdkAccessCheckRequest) {
  return agentRequest.post<SdkAccessCheckResponse>(`/api/scan-projects/${id}/sdk-access-check`, data)
}

export function startAiAccessSession(id: number, toolName?: string) {
  return agentRequest.post<AiAccessSession>(`/api/ai-assist/projects/${id}/access-sessions`, null, {
    params: toolName ? { toolName } : {},
  })
}

export function getLatestAiAccessSession(id: number) {
  return agentRequest.get<AiAccessSession>(`/api/ai-assist/projects/${id}/access-sessions/latest`)
}

export function runAiAccessSessionChecks(id: number, sessionId: string, data: SdkAccessCheckRequest) {
  return agentRequest.post<AiAccessCheckRunResponse>(
    `/api/ai-assist/projects/${id}/access-sessions/${sessionId}/checks/run`,
    data,
  )
}

export function getAiOnboardingManifest(id: number) {
  return agentRequest.get<AiOnboardingManifest>(`/api/ai-assist/projects/${id}/onboarding-manifest`)
}

export function getPageAssistantOnboardingManifest(
  id: number,
  data?: PageAssistantSessionRequest,
  aiCodingKey?: string | null,
) {
  return agentRequest.get<PageAssistantOnboardingManifest>(
    `/api/ai-assist/projects/${id}/page-assistant/onboarding-manifest`,
    {
      params: {
        toolName: data?.toolName,
        pageKey: data?.pageKey,
        routePattern: data?.routePattern,
        actionKeys: data?.actionKeys,
        aiCodingKey: aiCodingKey || undefined,
      },
    },
  )
}

export function startPageAssistantAccessSession(id: number, data: PageAssistantSessionRequest) {
  return agentRequest.post<AiAccessSession>(`/api/ai-assist/projects/${id}/page-assistant/sessions`, data)
}

export function getLatestPageAssistantAccessSession(id: number, pageKey?: string | null, aiCodingKey?: string | null) {
  return agentRequest.get<AiAccessSession>(`/api/ai-assist/projects/${id}/page-assistant/sessions/latest`, {
    params: {
      pageKey: pageKey || undefined,
      aiCodingKey: aiCodingKey || undefined,
    },
  })
}

export function getPageAssistantAccessSessions(id: number, pageKey?: string | null, aiCodingKey?: string | null) {
  return agentRequest.get<PageAssistantSessionSummary[]>(`/api/ai-assist/projects/${id}/page-assistant/sessions`, {
    params: {
      pageKey: pageKey || undefined,
      aiCodingKey: aiCodingKey || undefined,
    },
  })
}

export function bindPageAssistantAccessSessionTarget(
  id: number,
  sessionId: string,
  data: PageAssistantTargetRequest,
  aiCodingKey?: string | null,
) {
  return agentRequest.put<AiAccessSession>(
    `/api/ai-assist/projects/${id}/page-assistant/sessions/${sessionId}/target`,
    data,
    { params: aiCodingKey ? { aiCodingKey } : {} },
  )
}

export function syncPageAssistantAccessCatalog(
  id: number,
  sessionId: string,
  data: PageAssistantCatalogSyncRequest,
  aiCodingKey?: string | null,
) {
  return agentRequest.post<PageAssistantCatalogSyncResponse>(
    `/api/ai-assist/projects/${id}/page-assistant/sessions/${sessionId}/catalog/sync`,
    data,
    { params: aiCodingKey ? { aiCodingKey } : {} },
  )
}

export function runPageAssistantAccessSessionChecks(
  id: number,
  sessionId: string,
  data: PageAssistantCheckRequest,
  aiCodingKey?: string | null,
) {
  return agentRequest.post<PageAssistantCheckRunResponse>(
    `/api/ai-assist/projects/${id}/page-assistant/sessions/${sessionId}/checks/run`,
    data,
    { params: aiCodingKey ? { aiCodingKey } : {} },
  )
}

export function registerPageAssistantPage(
  id: number,
  data: PageAssistantPageRegisterRequest,
  aiCodingKey?: string | null,
) {
  return agentRequest.post<PageAssistantPageRegisterResponse>(
    `/api/ai-assist/projects/${id}/page-assistant/pages/register`,
    data,
    { params: aiCodingKey ? { aiCodingKey } : {} },
  )
}

export function updateAiCodingAccess(id: number, data: AiCodingAccessUpdateRequest) {
  return agentRequest.patch<AiCodingAccessResponse>(`/api/ai-assist/projects/${id}/ai-coding-access`, data)
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

/** 单条接口：从源码/OpenAPI 重新解析并更新当前扫描行（主键与工具名不变） */
export function rescanScanToolFromSource(projectId: number, scanToolId: number) {
  return agentRequest.post<ProjectToolInfo>(
    `/api/scan-projects/${projectId}/scan-tools/${scanToolId}/rescan-from-source`,
  )
}

export function getScanProjectTools(id: number, view?: 'summary' | 'full') {
  return agentRequest.get<ProjectToolInfo[]>(`/api/scan-projects/${id}/tools`, {
    params: view ? { view } : {},
  })
}

export function getScanProjectTool(projectId: number, scanToolId: number) {
  return agentRequest.get<ProjectToolInfo>(`/api/scan-projects/${projectId}/scan-tools/${scanToolId}`)
}

/** 补齐 SDK 镜像行并汇总 API 与全局 Tool 关联状态 */
export function reconcileScanProjectTools(projectId: number) {
  return agentRequest.post<ToolReconcileSummary>(`/api/scan-projects/${projectId}/tools/reconcile`)
}

export function getScanProjectDiffSummary(id: number) {
  return agentRequest.get<ScanDiffSummary>(`/api/scan-projects/${id}/diff-summary`)
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

/** 与 AI 理解生成使用相同的模型实例查询参数 */
function sensitiveScanLlmQuery(llm?: SemanticLlmParams): Record<string, string> {
  const q: Record<string, string> = {}
  const id = llm?.modelInstanceId?.trim()
  if (id) q.modelInstanceId = id
  return q
}

/** 异步批量敏感数据扫描（HTTP 202） */
export function startSensitiveDataScan(projectId: number, llm?: SemanticLlmParams) {
  return agentRequest.post<{ taskId: string }>(
    `/api/scan-projects/${projectId}/sensitive-data/scan`,
    null,
    { params: sensitiveScanLlmQuery(llm) },
  )
}

/** 无进行中任务时响应体为 null */
export function getSensitiveDataScanStatus(projectId: number, taskId?: string) {
  return agentRequest.get<SensitiveScanTask | null>(
    `/api/scan-projects/${projectId}/sensitive-data/status`,
    { params: taskId ? { taskId } : {} },
  )
}
