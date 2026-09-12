import { controlRequest } from './request'
import type {
  CapabilitySyncRequest,
  CapabilitySyncResponse,
  CapabilityDiffReviewItem,
  CapabilityChangePage,
  CapabilitySnapshot,
  ProjectInstance,
  RegistryProjectRegisterRequest,
  RegistryProjectResponse,
  RegistryEnrollmentToken,
} from '@/types/registry'

export function issueRegistryEnrollment(projectCode: string) {
  return controlRequest.post<RegistryEnrollmentToken>('/api/platform/registry-enrollments', { projectCode })
}

export function registerRegistryProject(data: RegistryProjectRegisterRequest) {
  return controlRequest.post<RegistryProjectResponse>('/api/registry/projects/register', data)
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

export function diffRegistryCapabilities(projectCode: string, data: CapabilitySyncRequest) {
  return controlRequest.post<CapabilitySyncResponse>(
    `/api/capability-review/projects/${projectCode}/capabilities/diff`,
    data,
  )
}

export function syncRegistryCapabilities(projectCode: string, data: CapabilitySyncRequest) {
  return controlRequest.post<CapabilitySyncResponse>(
    `/api/capability-review/projects/${projectCode}/capabilities/sync`,
    data,
  )
}

export function listCapabilitySnapshots(projectCode: string) {
  return controlRequest.get<CapabilitySnapshot[]>(`/api/capability-review/projects/${projectCode}/snapshots`)
}

export function listCapabilityChanges(projectCode: string, params: {
  state: 'PENDING' | 'PROCESSED'; keyword?: string; current: number; size: number
}) {
  return controlRequest.get<CapabilityChangePage>(
    `/api/capability-review/projects/${encodeURIComponent(projectCode)}/changes`, { params },
  )
}

export function listCapabilityDiffItems(projectCode: string, snapshotId: number) {
  return controlRequest.get<CapabilityDiffReviewItem[]>(
    `/api/capability-review/projects/${projectCode}/snapshots/${snapshotId}/diff-items`,
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
