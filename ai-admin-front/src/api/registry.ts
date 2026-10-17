import { controlRequest } from './request'
import type {
  CapabilityDiffReviewItem,
  CapabilityChangePage,
  ProjectInstance,
  RegistryEnrollmentToken,
} from '@/types/registry'
export function issueRegistryEnrollment(projectCode: string) {
  return controlRequest.post<RegistryEnrollmentToken>('/api/platform/registry-enrollments', { projectCode })
}

export function listRegistryProjectInstances(projectCode: string) {
  return controlRequest.get<ProjectInstance[]>(`/api/registry/projects/${projectCode}/instances`)
}

export function updateRegistryProjectInstanceStatus(projectCode: string, data: { instanceId: string; status: ProjectInstance['status'] }) {
  return controlRequest.post<ProjectInstance>(`/api/registry/projects/${projectCode}/instances/status`, data)
}

export function purgeRegistryProjectOfflineInstances(projectCode: string, minIdleMinutes = 0) {
  return controlRequest.post<{ removed: number }>(
    `/api/registry/projects/${projectCode}/instances/purge-offline`,
    { minIdleMinutes },
  )
}

export function listCapabilityChanges(projectCode: string, params: {
  state: 'PENDING' | 'PROCESSED'; keyword?: string; current: number; size: number
}) {
  return controlRequest.get<CapabilityChangePage>(
    `/api/capability-review/projects/${encodeURIComponent(projectCode)}/changes`, { params },
  )
}

export function reviewCapabilityDiffItem(projectCode: string, diffItemId: number, data: { action: 'APPLY' | 'IGNORE'; note?: string }) {
  return controlRequest.post<CapabilityDiffReviewItem>(
    `/api/capability-review/projects/${projectCode}/diff-items/${diffItemId}/review`,
    data,
  )
}

export function rollbackCapabilityDiffItem(projectCode: string, diffItemId: number, data: { note?: string } = {}) {
  return controlRequest.post<CapabilityDiffReviewItem>(
    `/api/capability-review/projects/${projectCode}/diff-items/${diffItemId}/rollback`,
    { action: 'ROLLBACK', ...data },
  )
}
