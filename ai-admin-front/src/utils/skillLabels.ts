import type { AgentSkillSummary } from '@/types/skill'
import type { StatusTone } from '@/components/common/glassWorkbench'

export function skillStatusLabel(status?: string | null) {
  return ({ REVIEW_PENDING: '待评审', APPROVED: '已通过', REJECTED: '已驳回', PUBLISHED: '已发布', DEPRECATED: '已废弃', REVOKED: '已撤销' } as Record<string, string>)[status || ''] || status || '未知'
}

export function skillStatusTone(status?: string | null): StatusTone {
  return ({ REVIEW_PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', PUBLISHED: 'success', DEPRECATED: 'info', REVOKED: 'danger' } as Record<string, StatusTone>)[status || ''] || 'neutral'
}

export function skillScopeLabel(skill: AgentSkillSummary) {
  return skill.visibility === 'PROJECT' && skill.projectCode
    ? `项目 · ${skill.projectCode}`
    : ({ PRIVATE: '私有', PROJECT: '项目', SHARED: '共享', PUBLIC: '公开' } as Record<string, string>)[skill.visibility] || skill.visibility
}

export function skillPublisherTrustLabel(publisher: string) {
  return publisher === 'reachai' ? '内置可信' : '发布者未验证'
}
