import { controlRequest } from './request'
import type { WorkflowCredential, WorkflowCredentialPayload } from '@/types/workflowCredential'

export function listWorkflowCredentials(params?: { projectId?: number | null; projectCode?: string | null }) {
  return controlRequest.get<WorkflowCredential[]>('/api/workflows/credentials', { params })
}

export function createWorkflowCredential(data: WorkflowCredentialPayload) {
  return controlRequest.post<WorkflowCredential>('/api/workflows/credentials', data)
}

/** 更新凭据（换令牌、改密码、改成登录换令牌）。已发布的流程钉住了凭据版本，更新后要重新发布。 */
export function updateWorkflowCredential(id: number, data: WorkflowCredentialPayload) {
  return controlRequest.put<WorkflowCredential>(`/api/workflows/credentials/${id}`, data)
}

export interface HttpApiFlowCredentialRefresh {
  refreshed: number
  alreadyCurrent: number
  writesNeedRepublish: number
  otherNeedRepublish: number
}

/** 凭据更新后，让本项目已发布的只读流程直接用上新凭据；写入流程仍需重新发布。 */
export function refreshHttpApiFlowCredential(data: { projectId: number; projectCode: string }) {
  return controlRequest.post<HttpApiFlowCredentialRefresh>('/api/workflows/http-api-flows/credential-refresh', data, {
    errorFeedback: 'local',
  })
}
