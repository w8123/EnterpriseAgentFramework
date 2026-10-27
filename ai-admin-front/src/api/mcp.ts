import { controlRequest } from './request'
import type {
  McpWriteApproval,
  McpWriteApprovalPage,
  McpCallLogEntry,
  McpCallLogPageView,
  McpClientCredentialIssue,
  McpClientCreateRequest,
  McpClientUpdateRequest,
  McpClientView,
  McpEmployeeAccessView,
  McpEmployeeConnection,
  McpEmployeeConnectionPage,
  McpHubOverview,
  McpItemPageView,
  McpPrecheckReport,
  McpPublication,
  McpPublicationDetail,
  McpPublicationItem,
  McpPublicationItemAddRequest,
  McpPublicationUpsertRequest,
  McpResolvePreviewRow,
} from '@/types/mcp'
const ROOT = '/api/mcp'

// ===== 总览 =====

export function getMcpHubOverview(days?: number) {
  return controlRequest.get<McpHubOverview>(`${ROOT}/overview`, { params: { days } })
}

// ===== 发布管理 =====

export function listMcpPublications(params?: {
  search?: string
  state?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<McpItemPageView<McpPublication>>(`${ROOT}/publications`, { params })
}

export function getMcpPublication(id: number) {
  return controlRequest.get<McpPublicationDetail>(`${ROOT}/publications/${id}`)
}

export function createMcpPublication(body: McpPublicationUpsertRequest) {
  return controlRequest.post<McpPublication>(`${ROOT}/publications`, body)
}

export function updateMcpPublication(id: number, body: McpPublicationUpsertRequest) {
  return controlRequest.put<McpPublication>(`${ROOT}/publications/${id}`, body)
}

export function addMcpPublicationItem(publicationId: number, body: McpPublicationItemAddRequest) {
  return controlRequest.post<McpPublicationItem>(`${ROOT}/publications/${publicationId}/items`, body)
}

export function removeMcpPublicationItem(publicationId: number, itemId: number) {
  return controlRequest.delete<{ ok: boolean }>(`${ROOT}/publications/${publicationId}/items/${itemId}`)
}

export function resolveMcpPublicationPreview(publicationId: number) {
  return controlRequest.post<McpResolvePreviewRow[]>(`${ROOT}/publications/${publicationId}/resolve-preview`)
}

export function precheckMcpPublication(publicationId: number) {
  return controlRequest.post<McpPrecheckReport>(`${ROOT}/publications/${publicationId}/precheck`)
}

export function publishMcpPublication(publicationId: number, body?: { irreversibleAcknowledged?: boolean }) {
  return controlRequest.post<McpPublicationDetail>(`${ROOT}/publications/${publicationId}/publish`, body)
}

export function suspendMcpPublication(publicationId: number) {
  return controlRequest.post<McpPublicationDetail>(`${ROOT}/publications/${publicationId}/suspend`)
}

export function resumeMcpPublication(publicationId: number) {
  return controlRequest.post<McpPublicationDetail>(`${ROOT}/publications/${publicationId}/resume`)
}

export function archiveMcpPublication(publicationId: number) {
  return controlRequest.post<McpPublicationDetail>(`${ROOT}/publications/${publicationId}/archive`)
}

export function rollbackMcpPublication(publicationId: number, revisionId: number) {
  return controlRequest.post<McpPublicationDetail>(
    `${ROOT}/publications/${publicationId}/revisions/${revisionId}/rollback`,
  )
}

// ===== 凭证管理（发布子资源） =====

export function listMcpClients(publicationId: number) {
  return controlRequest.get<McpClientView[]>(`${ROOT}/publications/${publicationId}/clients`)
}

export function createMcpClient(publicationId: number, body: McpClientCreateRequest) {
  return controlRequest.post<McpClientCredentialIssue>(`${ROOT}/publications/${publicationId}/clients`, body)
}

export function rotateMcpClient(publicationId: number, clientId: number) {
  return controlRequest.post<McpClientCredentialIssue>(
    `${ROOT}/publications/${publicationId}/clients/${clientId}/rotate`,
  )
}

export function revokeMcpClient(publicationId: number, clientId: number) {
  return controlRequest.post<McpClientView>(`${ROOT}/publications/${publicationId}/clients/${clientId}/revoke`)
}

export function updateMcpClient(publicationId: number, clientId: number, body: McpClientUpdateRequest) {
  return controlRequest.put<McpClientView>(`${ROOT}/publications/${publicationId}/clients/${clientId}`, body)
}

// ===== 调用流水 =====

export function listMcpCallLogs(params?: {
  direction?: string
  publicationId?: number
  clientId?: number
  connectionId?: number
  method?: string
  toolName?: string
  success?: boolean
  errorCategory?: string
  days?: number
  limit?: number
  offset?: number
}) {
  return controlRequest.get<McpCallLogPageView>(`${ROOT}/call-logs`, { params })
}

export function getMcpCallLog(id: number) {
  return controlRequest.get<McpCallLogEntry>(`${ROOT}/call-logs/${id}`)
}

// ===== 员工使用（员工在自己的 AI 工具里办业务） =====

export function getMcpEmployeeAccess(publicationId: number) {
  return controlRequest.get<McpEmployeeAccessView>(`${ROOT}/publications/${publicationId}/employee-access`)
}

export function saveMcpEmployeeAccess(
  publicationId: number,
  body: { projectId?: number; projectCode: string; displayName?: string; enabled: boolean; writeApprovalRequired?: boolean },
) {
  return controlRequest.put<McpEmployeeAccessView>(`${ROOT}/publications/${publicationId}/employee-access`, body)
}

export function listMcpEmployeeConnections(params?: {
  projectCode?: string
  state?: string
  keyword?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<McpEmployeeConnectionPage>(`${ROOT}/employee-connections`, { params })
}

export function revokeMcpEmployeeConnection(id: number) {
  return controlRequest.post<McpEmployeeConnection>(`${ROOT}/employee-connections/${id}/revoke`)
}

// ===== 写操作审批（员工 AI 工具发起的写操作） =====

export function listMcpWriteApprovals(params?: {
  projectCode?: string
  state?: string
  keyword?: string
  limit?: number
  offset?: number
}) {
  return controlRequest.get<McpWriteApprovalPage>(`${ROOT}/write-approvals`, { params })
}

export function getMcpWriteApproval(id: number) {
  return controlRequest.get<McpWriteApproval>(`${ROOT}/write-approvals/${id}`)
}

export function approveMcpWriteApproval(id: number, comment?: string) {
  // 通过后会立即以员工身份执行一次，留足执行时间。
  return controlRequest.post<McpWriteApproval>(`${ROOT}/write-approvals/${id}/approve`, { comment }, { timeout: 130000 })
}

export function rejectMcpWriteApproval(id: number, comment?: string) {
  return controlRequest.post<McpWriteApproval>(`${ROOT}/write-approvals/${id}/reject`, { comment })
}
