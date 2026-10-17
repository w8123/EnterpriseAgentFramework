import { controlRequest } from './request'
import type { WorkflowCredential, WorkflowCredentialPayload } from '@/types/workflowCredential'

export function listWorkflowCredentials(params?: { projectId?: number | null; projectCode?: string | null }) {
  return controlRequest.get<WorkflowCredential[]>('/api/workflows/credentials', { params })
}

export function createWorkflowCredential(data: WorkflowCredentialPayload) {
  return controlRequest.post<WorkflowCredential>('/api/workflows/credentials', data)
}
