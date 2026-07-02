import { controlRequest } from './request'
import type {
  CapabilitySyncRequest,
  CapabilitySyncResponse,
  CapabilityDiffReviewItem,
  CapabilitySnapshot,
  ProjectInstance,
  RegistryProjectRegisterRequest,
  RegistryProjectResponse,
  RuntimeGovernancePolicyUpdateRequest,
} from '@/types/registry'

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

export function updateRegistryProjectInstanceGovernancePolicy(
  projectCode: string,
  data: RuntimeGovernancePolicyUpdateRequest,
) {
  return controlRequest.post<ProjectInstance>(`/api/registry/projects/${projectCode}/instances/governance-policy`, data)
}

export function diffRegistryCapabilities(projectCode: string, data: CapabilitySyncRequest) {
  return controlRequest.post<CapabilitySyncResponse>(
    `/api/registry/projects/${projectCode}/capabilities/diff`,
    data,
  )
}

export function syncRegistryCapabilities(projectCode: string, data: CapabilitySyncRequest) {
  return controlRequest.post<CapabilitySyncResponse>(
    `/api/registry/projects/${projectCode}/capabilities/sync`,
    data,
  )
}

export function applyRegistryCapabilities(projectCode: string, data: CapabilitySyncRequest) {
  return controlRequest.post<CapabilitySyncResponse>(
    `/api/registry/projects/${projectCode}/capabilities/apply`,
    data,
  )
}

export function listCapabilitySnapshots(projectCode: string) {
  return controlRequest.get<CapabilitySnapshot[]>(`/api/registry/projects/${projectCode}/capability-snapshots`)
}

export function listCapabilityDiffItems(snapshotId: number) {
  return controlRequest.get<CapabilityDiffReviewItem[]>(`/api/registry/capability-snapshots/${snapshotId}/diff-items`)
}

export function reviewCapabilityDiffItem(diffItemId: number, data: { action: 'APPLY' | 'IGNORE'; operator?: string; note?: string }) {
  return controlRequest.post<CapabilityDiffReviewItem>(`/api/registry/capability-diff-items/${diffItemId}/review`, data)
}
