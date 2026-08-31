import { controlRequest } from './request'
import type {
  AgentSkillAccessCapabilities,
  AgentSkillBundleDiscovery,
  AgentSkillDetail,
  AgentSkillFilePreview,
  AgentSkillImportResult,
  AgentSkillReview,
  AgentSkillSummary,
  AgentSkillVersion,
} from '@/types/skill'

export function listAgentSkills(params?: { search?: string; status?: string }) {
  return controlRequest.get<AgentSkillSummary[]>('/api/skills', { params })
}

export function getAgentSkillAccess() {
  return controlRequest.get<AgentSkillAccessCapabilities>('/api/skills/access')
}

export function discoverAgentSkillBundle(file: File) {
  const form = new FormData()
  form.append('file', file)
  return controlRequest.post<AgentSkillBundleDiscovery>('/api/skills/discoveries', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  })
}

export function listBindableAgentSkillVersions(params?: { search?: string; agentProjectCode?: string }) {
  return controlRequest.get<Array<{ skill: AgentSkillSummary; version: AgentSkillVersion }>>(
    '/api/skills/bindable-versions',
    { params },
  )
}

export function getAgentSkill(skillId: number) {
  return controlRequest.get<AgentSkillDetail>(`/api/skills/${skillId}`)
}

export function getAgentSkillReviews(skillId: number, versionId: number) {
  return controlRequest.get<AgentSkillReview[]>(
    `/api/skills/${skillId}/versions/${versionId}/reviews`,
  )
}

export function getAgentSkillFilePreview(skillId: number, versionId: number, path: string) {
  return controlRequest.get<AgentSkillFilePreview>(
    `/api/skills/${skillId}/versions/${versionId}/files`,
    { params: { path } },
  )
}

export function importAgentSkill(
  file: File,
  metadata: {
    publisher?: string
    version?: string
    displayName?: string
    visibility?: string
    projectCode?: string
    sourceRoot?: string
  },
) {
  const form = new FormData()
  form.append('file', file)
  Object.entries(metadata).forEach(([key, value]) => {
    if (value?.trim()) form.append(key, value.trim())
  })
  return controlRequest.post<AgentSkillImportResult>('/api/skills/imports', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
  })
}

export function reviewAgentSkill(
  skillId: number,
  versionId: number,
  body: { decision: 'APPROVE' | 'REJECT'; comment?: string },
) {
  return controlRequest.post<AgentSkillVersion>(
    `/api/skills/${skillId}/versions/${versionId}/reviews`,
    body,
  )
}

export function publishAgentSkill(skillId: number, versionId: number) {
  return controlRequest.post<AgentSkillVersion>(
    `/api/skills/${skillId}/versions/${versionId}/publish`,
  )
}

export function setDefaultAgentSkillVersion(skillId: number, versionId: number) {
  return controlRequest.post<AgentSkillVersion>(
    `/api/skills/${skillId}/versions/${versionId}/default`,
  )
}

export function deprecateAgentSkill(skillId: number, versionId: number) {
  return controlRequest.post<AgentSkillVersion>(
    `/api/skills/${skillId}/versions/${versionId}/deprecate`,
  )
}

export function revokeAgentSkill(skillId: number, versionId: number) {
  return controlRequest.post<AgentSkillVersion>(
    `/api/skills/${skillId}/versions/${versionId}/revoke`,
  )
}

export function downloadAgentSkillPackage(skillId: number, versionId: number) {
  return controlRequest.get<Blob>(`/api/skills/${skillId}/versions/${versionId}/package`, {
    responseType: 'blob',
  })
}
