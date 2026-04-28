import { agentRequest } from './request'
import type {
  SkillAdminTestPendingItem,
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

/** InteractiveFormSkill 挂起后继续（确认卡 / 表单批交等） */
export function testSkillResume(
  name: string,
  body: { interactionId: string; action?: string; values?: Record<string, unknown> },
) {
  return agentRequest.post<SkillTestResult>(`/api/skills/${name}/test/resume`, body)
}

export function getSkillMetrics(name: string, days = 7) {
  return agentRequest.get<SkillMetrics>(`/api/skills/${name}/metrics`, { params: { days } })
}

/** 管理端测试会话下未完成的 Interactive 交互（与「每用户最多 5 条 PENDING」一致） */
export function getAdminTestPendingInteractions() {
  return agentRequest.get<SkillAdminTestPendingItem[]>(`/api/skills/pending-interactions/admin-test`)
}

export function cancelAdminTestPendingInteraction(interactionId: string) {
  return agentRequest.delete(`/api/skills/pending-interactions/admin-test/${interactionId}`)
}

export function cancelAllAdminTestPendingInteractions() {
  return agentRequest.post<{ cancelled: number }>(
    `/api/skills/pending-interactions/admin-test/cancel-all`,
  )
}
