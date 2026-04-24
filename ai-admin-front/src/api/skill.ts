import { agentRequest } from './request'
import type {
  SkillInfo,
  SkillListQuery,
  SkillPageResult,
  SkillMetrics,
  SkillTestResult,
  SkillUpsertRequest,
} from '@/types/skill'

export function getSkills(params?: SkillListQuery) {
  return agentRequest.get<SkillPageResult>('/api/skills', { params })
}

export function getSkillDetail(name: string) {
  return agentRequest.get<SkillInfo>(`/api/skills/${name}`)
}

export function createSkill(data: SkillUpsertRequest) {
  return agentRequest.post<SkillInfo>('/api/skills', data)
}

export function updateSkill(name: string, data: SkillUpsertRequest) {
  return agentRequest.put<SkillInfo>(`/api/skills/${name}`, data)
}

export function deleteSkill(name: string) {
  return agentRequest.delete(`/api/skills/${name}`)
}

export function toggleSkill(name: string, enabled: boolean) {
  return agentRequest.put<SkillInfo>(`/api/skills/${name}/toggle`, { enabled })
}

export function testSkill(name: string, args: Record<string, unknown>) {
  return agentRequest.post<SkillTestResult>(`/api/skills/${name}/test`, { args })
}

export function getSkillMetrics(name: string, days = 7) {
  return agentRequest.get<SkillMetrics>(`/api/skills/${name}/metrics`, { params: { days } })
}
