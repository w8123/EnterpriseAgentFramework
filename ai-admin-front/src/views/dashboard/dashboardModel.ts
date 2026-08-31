import type { ScanProject } from '@/types/scanProject'
import type { RunSummary } from '@/types/runops'
import type { Agent } from '@/types/workflow'
import type {
  DashboardAgentRankingItem,
  DashboardIdentitySummary,
  DashboardProjectSummaryItem,
  DashboardRunOutcomeSummary,
  DashboardTokenDistributionItem,
  DashboardTokenSummary,
  DashboardTrendBucket,
} from '@/types/operationsDashboard'

export const DASHBOARD_SAMPLE_LIMIT = 100

const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED'])
const TOKEN_COLORS = ['#2f91ff', '#8b6cff', '#22c4e8', '#4fcb7a', '#ff8b37', '#f5bd34']

function finiteNonNegative(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : 0
}

function trimmed(value: string | null | undefined) {
  const result = value?.trim()
  return result || undefined
}

function percentage(part: number, total: number) {
  return total > 0 ? Math.round((part / total) * 1000) / 10 : null
}

export function summarizeRunOutcomes(runs: RunSummary[]): DashboardRunOutcomeSummary {
  const completed = runs.filter((run) => run.status === 'COMPLETED').length
  const failed = runs.filter((run) => run.status === 'FAILED').length
  const timedOut = runs.filter((run) => run.status === 'TIMED_OUT').length
  const cancelled = runs.filter((run) => run.status === 'CANCELLED').length
  const running = runs.filter((run) => run.status === 'RUNNING').length
  const suspended = runs.filter((run) => run.status === 'SUSPENDED').length
  const terminal = runs.filter((run) => TERMINAL_STATUSES.has(run.status)).length
  return {
    completed,
    failed,
    timedOut,
    cancelled,
    running,
    suspended,
    terminal,
    technicalCompletionRate: percentage(completed, terminal),
  }
}

export function summarizeIdentities(runs: RunSummary[]): DashboardIdentitySummary {
  const identified = runs
    .map((run) => trimmed(run.userId))
    .filter((value): value is string => Boolean(value))
  return {
    distinctUsers: new Set(identified).size,
    identifiedRuns: identified.length,
    totalRuns: runs.length,
    coverage: percentage(identified.length, runs.length),
  }
}

export function summarizeTokens(runs: RunSummary[]): DashboardTokenSummary {
  // runtime_run.token_cost=0 当前不能区分“真实零消耗”和“未采集”，所以覆盖率只认非零记录。
  const recorded = runs.filter((run) => finiteNonNegative(run.tokenCost) > 0)
  return {
    total: recorded.reduce((sum, run) => sum + finiteNonNegative(run.tokenCost), 0),
    recordedRuns: recorded.length,
    totalRuns: runs.length,
    coverage: percentage(recorded.length, runs.length),
  }
}

function agentKey(run: RunSummary) {
  return trimmed(run.agentId) || trimmed(run.agentKeySlug) || trimmed(run.agentName)
}

function projectKey(run: RunSummary) {
  return trimmed(run.projectCode)
}

export function buildAgentRanking(
  runs: RunSummary[],
  agents: Agent[],
  projects: ScanProject[],
  limit = 5,
): DashboardAgentRankingItem[] {
  const groups = new Map<string, RunSummary[]>()
  runs.forEach((run) => {
    const key = agentKey(run)
    if (!key) return
    groups.set(key, [...(groups.get(key) ?? []), run])
  })

  const projectNames = new Map(
    projects
      .map((project) => [trimmed(project.projectCode), project.name] as const)
      .filter((entry): entry is readonly [string, string] => Boolean(entry[0])),
  )

  return [...groups.entries()]
    .map(([key, samples]) => {
      const first = samples[0]
      const agent = agents.find(
        (candidate) => candidate.id === first.agentId || candidate.keySlug === first.agentKeySlug,
      )
      const outcome = summarizeRunOutcomes(samples)
      const projectCode = trimmed(first.projectCode) || trimmed(agent?.projectCode)
      return {
        key,
        agentId: trimmed(first.agentId) || agent?.id,
        name: trimmed(first.agentName) || agent?.name || trimmed(first.agentKeySlug) || '未命名 Agent',
        projectCode,
        projectName: (projectCode && projectNames.get(projectCode)) || projectCode || '未归属项目',
        enabled: typeof agent?.enabled === 'boolean' ? agent.enabled : null,
        runs: samples.length,
        completed: outcome.completed,
        terminal: outcome.terminal,
        technicalCompletionRate: outcome.technicalCompletionRate,
      }
    })
    .sort((a, b) => b.runs - a.runs || b.completed - a.completed || a.name.localeCompare(b.name, 'zh-CN'))
    .slice(0, Math.max(0, limit))
}

export function buildProjectSummaries(
  projects: ScanProject[],
  agents: Agent[],
  runs: RunSummary[],
  limit = 6,
): DashboardProjectSummaryItem[] {
  const knownProjects = new Map<string, ScanProject>()
  projects.forEach((project) => {
    const key = trimmed(project.projectCode) || `project-id:${project.id}`
    knownProjects.set(key, project)
  })

  runs.forEach((run) => {
    const key = projectKey(run)
    if (key && !knownProjects.has(key)) {
      knownProjects.set(key, {
        id: 0,
        name: key,
        projectCode: key,
        baseUrl: '',
        contextPath: '',
        scanPath: '',
        scanType: 'auto',
        toolCount: 0,
        status: 'created',
      })
    }
  })

  return [...knownProjects.entries()]
    .map(([key, project]) => {
      const projectCode = trimmed(project.projectCode)
      const sampleRuns = runs.filter((run) => projectCode && projectKey(run) === projectCode)
      const outcome = summarizeRunOutcomes(sampleRuns)
      const identities = summarizeIdentities(sampleRuns)
      const tokens = summarizeTokens(sampleRuns)
      return {
        key,
        projectId: project.id > 0 ? project.id : undefined,
        projectCode,
        name: project.name,
        agentCount: agents.filter((agent) => projectCode && trimmed(agent.projectCode) === projectCode).length,
        runs: sampleRuns.length,
        users: identities.distinctUsers,
        identityCoverage: identities.coverage,
        tokens: tokens.total,
        tokenCoverage: tokens.coverage,
        technicalCompletionRate: outcome.technicalCompletionRate,
        sampleRuns,
      }
    })
    .sort((a, b) => b.runs - a.runs || b.agentCount - a.agentCount || a.name.localeCompare(b.name, 'zh-CN'))
    .slice(0, Math.max(0, limit))
}

export function buildTokenDistribution(
  projects: DashboardProjectSummaryItem[],
  runs: RunSummary[] = [],
): DashboardTokenDistributionItem[] {
  const withTokens = projects.filter((project) => project.tokens > 0)
  const attributedTotal = withTokens.reduce((sum, project) => sum + project.tokens, 0)
  const recordedTotal = runs.length ? summarizeTokens(runs).total : attributedTotal
  const unassigned = Math.max(0, recordedTotal - attributedTotal)
  const total = attributedTotal + unassigned
  const items: DashboardTokenDistributionItem[] = withTokens.map((project, index) => ({
    key: project.key,
    name: project.name,
    projectCode: project.projectCode,
    tokens: project.tokens,
    ratio: percentage(project.tokens, total) ?? 0,
    color: TOKEN_COLORS[index % TOKEN_COLORS.length],
  }))
  if (unassigned > 0) {
    items.push({
      key: 'unassigned',
      name: '未归属项目',
      tokens: unassigned,
      ratio: percentage(unassigned, total) ?? 0,
      color: '#71869a',
    })
  }
  return items
}

function startOfBucket(now: Date, days: 1 | 7) {
  const start = new Date(now)
  if (days === 1) {
    start.setMinutes(0, 0, 0)
    start.setHours(start.getHours() - 23)
  } else {
    start.setHours(0, 0, 0, 0)
    start.setDate(start.getDate() - 6)
  }
  return start
}

export function buildTrendBuckets(
  runs: RunSummary[],
  days: 1 | 7,
  now = new Date(),
): DashboardTrendBucket[] {
  // 近 24 小时保留真实小时粒度。此前的 3 小时桶只有 8 个采样点，
  // 会同时压平主图与 KPI sparkline，且无法呈现领导大屏需要的节奏变化。
  const bucketCount = days === 1 ? 24 : 7
  const bucketMs = days === 1 ? 60 * 60 * 1000 : 24 * 60 * 60 * 1000
  const start = startOfBucket(now, days)
  const buckets = Array.from({ length: bucketCount }, (_, index) => {
    const date = new Date(start.getTime() + index * bucketMs)
    return {
      key: date.toISOString(),
      label:
        days === 1
          ? `${String(date.getHours()).padStart(2, '0')}:00`
          : `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`,
      runs: 0,
      tokens: 0,
      users: 0,
      completed: 0,
      terminal: 0,
      userSet: new Set<string>(),
    }
  })

  runs.forEach((run) => {
    if (!run.startedAt) return
    const startedAt = new Date(run.startedAt)
    if (Number.isNaN(startedAt.getTime())) return
    const index = Math.floor((startedAt.getTime() - start.getTime()) / bucketMs)
    if (index < 0 || index >= buckets.length) return
    const bucket = buckets[index]
    bucket.runs += 1
    bucket.tokens += finiteNonNegative(run.tokenCost)
    if (run.status === 'COMPLETED') bucket.completed += 1
    if (TERMINAL_STATUSES.has(run.status)) bucket.terminal += 1
    const userId = trimmed(run.userId)
    if (userId) bucket.userSet.add(userId)
  })

  return buckets.map(({ userSet, ...bucket }) => ({
    ...bucket,
    users: userSet.size,
    technicalCompletionRate: percentage(bucket.completed, bucket.terminal),
  }))
}

export function formatCompactNumber(value: number) {
  if (!Number.isFinite(value)) return '—'
  if (Math.abs(value) >= 1_000_000) {
    return `${(value / 1_000_000).toFixed(value >= 10_000_000 ? 1 : 2).replace(/\.0$/, '')}M`
  }
  if (Math.abs(value) >= 1_000) {
    return `${(value / 1_000).toFixed(value >= 100_000 ? 0 : 1).replace(/\.0$/, '')}K`
  }
  return new Intl.NumberFormat('zh-CN').format(value)
}

export function runStatusLabel(run: RunSummary) {
  if (run.status === 'SUSPENDED') {
    if (run.suspensionReason === 'APPROVAL') return '等待审批'
    if (run.suspensionReason === 'USER_INPUT') return '等待交互'
  }
  const labels: Record<string, string> = {
    RUNNING: '运行中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '超时',
    SUSPENDED: '已暂停',
  }
  return labels[run.status] ?? run.status
}

export function runStatusTone(run: RunSummary) {
  if (run.status === 'COMPLETED') return 'success'
  if (run.status === 'RUNNING') return 'primary'
  if (run.status === 'SUSPENDED') return 'warning'
  if (run.status === 'CANCELLED') return 'muted'
  return 'danger'
}

export function runTargetLabel(run: RunSummary) {
  if (run.runType === 'WORKFLOW') {
    return trimmed(run.workflowName) || trimmed(run.workflowKeySlug) || '未命名 Workflow'
  }
  return trimmed(run.agentName) || trimmed(run.agentKeySlug) || '未命名 Agent'
}
