import type { AgentSkillBundleCandidate } from '@/types/skill'

export function trustLabel(value?: string | null) {
  return ({
    OFFICIAL: '官方来源',
    VERIFIED: '精选验证',
    COMMUNITY: '社区来源',
    AGGREGATED: '聚合发现',
    TRANSPORT: '传输通道',
  } as Record<string, string>)[value || ''] || value || '来源未知'
}

export function trustTone(value?: string | null): 'neutral' | 'success' | 'warning' | 'danger' | 'info' {
  if (value === 'OFFICIAL' || value === 'VERIFIED') return 'success'
  if (value === 'COMMUNITY' || value === 'AGGREGATED') return 'warning'
  return 'neutral'
}

export function formatInstalls(value?: number | null) {
  const count = Math.max(0, Number(value || 0))
  if (count >= 1_000_000) return `${(count / 1_000_000).toFixed(count >= 10_000_000 ? 0 : 1)}M`
  if (count >= 1_000) return `${(count / 1_000).toFixed(count >= 100_000 ? 0 : 1)}K`
  return String(count)
}

export function normalizeGitHubPublisher(owner?: string | null) {
  const normalized = (owner || '').trim().toLowerCase().replace(/[^a-z0-9._-]+/g, '-')
  if (!normalized || !/^[a-z0-9]/.test(normalized)) return 'community'
  return normalized.slice(0, 64)
}

export function defaultMarketVersion(declaredVersion?: string | null, commitSha?: string | null) {
  if (declaredVersion?.trim()) return declaredVersion.trim()
  return commitSha ? `git-${commitSha.slice(0, 12)}` : '1.0.0'
}

export function selectSuggestedCandidate(
  candidates: AgentSkillBundleCandidate[],
  suggestedSourceRoot?: string | null,
) {
  const selectable = candidates.filter(candidate => candidate.selectable)
  return selectable.find(candidate => candidate.sourceRoot === suggestedSourceRoot)
    || (selectable.length === 1 ? selectable[0] : null)
}
