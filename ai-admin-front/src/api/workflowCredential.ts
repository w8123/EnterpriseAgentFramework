import { controlRequest } from './request'
import type { WorkflowCredential, WorkflowCredentialPayload } from '@/types/workflowCredential'

export function listWorkflowCredentials(params?: { projectId?: number | null; projectCode?: string | null }) {
  return controlRequest.get<WorkflowCredential[]>('/api/workflows/credentials', { params })
}

export function createWorkflowCredential(data: WorkflowCredentialPayload) {
  return controlRequest.post<WorkflowCredential>('/api/workflows/credentials', data)
}

export function updateWorkflowCredential(id: number, data: WorkflowCredentialPayload) {
  return controlRequest.put<WorkflowCredential>(`/api/workflows/credentials/${id}`, data)
}

export function deleteWorkflowCredential(id: number) {
  return controlRequest.delete(`/api/workflows/credentials/${id}`)
}
