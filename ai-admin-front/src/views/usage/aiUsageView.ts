import type { AiUsageApprovalCounts, AiUsageChannel, AiUsagePerson } from '@/types/mcp'

export const channelLabels: Record<AiUsageChannel, string> = {
  EMBED: '业务系统内对话',
  CLAUDE_CODE: 'Claude Code',
  CODEX: 'Codex',
  OTHER: '其他 AI 工具',
}

export const channelHints: Record<AiUsageChannel, string> = {
  EMBED: '在业务系统页面里和 AI 助手对话',
  CLAUDE_CODE: '员工用自己的 Claude Code 操作业务系统',
  CODEX: '员工用自己的 Codex 操作业务系统',
  OTHER: '员工用其他支持 MCP 的 AI 工具',
}

/** EMBED 的「次数」是对话轮次，其余是业务操作调用次数。 */
export function usesUnit(channel: AiUsageChannel): string {
  return channel === 'EMBED' ? '轮对话' : '次调用'
}

export function channelLabel(channel: string): string {
  return channelLabels[channel as AiUsageChannel] ?? channel
}

const numberFormatter = new Intl.NumberFormat('zh-CN')

export function formatCount(value: number | null | undefined): string {
  return numberFormatter.format(value ?? 0)
}

/** 成功率；没有样本时返回 null，页面显示「—」。 */
export function successRate(total: number, failed: number): number | null {
  if (!total) return null
  return Math.round(((total - failed) / total) * 100)
}

export function formatRate(total: number, failed: number): string {
  const rate = successRate(total, failed)
  return rate == null ? '—' : `${rate}%`
}

/** 「通过 2 · 拒绝 1 · 待审批 1」，只列出不为 0 的项。 */
export function approvalSummary(counts: AiUsageApprovalCounts | null | undefined): string {
  if (!counts || !counts.submitted) return '没有写操作'
  const parts: string[] = []
  if (counts.succeeded) parts.push(`通过 ${counts.succeeded}`)
  if (counts.rejected) parts.push(`拒绝 ${counts.rejected}`)
  if (counts.failed) parts.push(`执行失败 ${counts.failed}`)
  if (counts.pending) parts.push(`待审批 ${counts.pending}`)
  if (counts.expired) parts.push(`已过期 ${counts.expired}`)
  return parts.join(' · ')
}

export function personActivity(person: AiUsagePerson): number {
  return person.embedRuns + person.toolCalls
}

/** 按姓名、账号或业务系统筛人（页面内筛选，不再请求后端）。 */
export function filterPeople(people: AiUsagePerson[], keyword: string, channel: string): AiUsagePerson[] {
  const text = keyword.trim().toLowerCase()
  return people.filter((person) => {
    if (channel && !person.channels.includes(channel as AiUsageChannel)) return false
    if (!text) return true
    return [person.name, ...person.accounts, ...person.systems].some((value) =>
      value?.toLowerCase().includes(text),
    )
  })
}

export function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

export const peopleSortOptions = [
  { value: 'activity', label: '使用次数最多' },
  { value: 'recent', label: '最近使用优先' },
  { value: 'failed', label: '失败次数最多' },
  { value: 'pending', label: '待审批最多' },
] as const

export function sortPeople(people: AiUsagePerson[], sort: string): AiUsagePerson[] {
  const lastActive = (person: AiUsagePerson) => {
    const timestamp = new Date(person.lastActiveAt ?? '').getTime()
    return Number.isNaN(timestamp) ? 0 : timestamp
  }
  const score = (person: AiUsagePerson) => {
    if (sort === 'recent') return lastActive(person)
    if (sort === 'failed') return person.toolFailed + person.embedFailed
    if (sort === 'pending') return person.approvals.pending
    return personActivity(person)
  }
  return [...people].sort((a, b) => score(b) - score(a) || lastActive(b) - lastActive(a))
}

export const periodOptions = [
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 90, label: '近 90 天' },
]

export const skillStatusLabels: Record<string, string> = {
  REVIEW_PENDING: '待评审',
  APPROVED: '已通过',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
  DEPRECATED: '已废弃',
  REVOKED: '已撤销',
}
