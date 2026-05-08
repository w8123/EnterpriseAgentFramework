import { agentRequest } from './request'
import type {
  CapabilitySyncRequest,
  CapabilitySyncResponse,
  CapabilityDiffReviewItem,
  CapabilitySnapshot,
  ProjectInstance,
  RegistryProjectRegisterRequest,
  RegistryProjectResponse,
} from '@/types/registry'

export function registerRegistryProject(data: RegistryProjectRegisterRequest) {
  return agentRequest.post<RegistryProjectResponse>('/api/registry/projects/register', data)
}

export function listRegistryProjectInstances(projectCode: string) {
  return agentRequest.get<ProjectInstance[]>(`/api/registry/projects/${projectCode}/instances`)
}

export function diffRegistryCapabilities(projectCode: string, data: CapabilitySyncRequest) {
  return agentRequest.post<CapabilitySyncResponse>(
    `/api/registry/projects/${projectCode}/capabilities/diff`,
    data,
  )
}

export function syncRegistryCapabilities(projectCode: string, data: CapabilitySyncRequest) {
  return agentRequest.post<CapabilitySyncResponse>(
    `/api/registry/projects/${projectCode}/capabilities/sync`,
    data,
  )
}

export function applyRegistryCapabilities(projectCode: string, data: CapabilitySyncRequest) {
  return agentRequest.post<CapabilitySyncResponse>(
    `/api/registry/projects/${projectCode}/capabilities/apply`,
    data,
  )
}

export function listCapabilitySnapshots(projectCode: string) {
  return agentRequest.get<CapabilitySnapshot[]>(`/api/registry/projects/${projectCode}/capability-snapshots`)
}

export function listCapabilityDiffItems(snapshotId: number) {
  return agentRequest.get<CapabilityDiffReviewItem[]>(`/api/registry/capability-snapshots/${snapshotId}/diff-items`)
}

export function reviewCapabilityDiffItem(diffItemId: number, data: { action: 'APPLY' | 'IGNORE'; operator?: string; note?: string }) {
  return agentRequest.post<CapabilityDiffReviewItem>(`/api/registry/capability-diff-items/${diffItemId}/review`, data)
}
