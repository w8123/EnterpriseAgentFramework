<template>
  <WorkbenchPage class="dashboard-page" density="compact">
    <div ref="dashboardRoot" class="ops-dashboard" :class="{ 'is-fullscreen': isFullscreen }">
      <header class="ops-topbar">
        <div class="ops-heading">
          <h1>智能体运营中心</h1>
          <p>平台级 AI 运营态势与业务价值总览</p>
        </div>

        <div class="ops-topbar__controls">
          <span
            class="ops-scope-chip"
            aria-label="当前数据范围：全部可见项目"
            title="当前数据范围为全部可见项目"
          >全部可见项目</span>
          <span class="ops-date-chip"><i aria-hidden="true" />{{ currentDateLabel }}</span>
          <div class="ops-range" aria-label="统计时间范围">
            <button
              type="button"
              :class="{ 'is-active': rangeDays === 1 }"
              @click="selectRange(1)"
            >近 24 小时</button>
            <button
              type="button"
              :class="{ 'is-active': rangeDays === 7 }"
              @click="selectRange(7)"
            >近 7 天</button>
          </div>
          <span class="ops-refresh-status" :class="{ 'is-paused': !pageVisible }">
            <i aria-hidden="true" />
            {{ pageVisible ? '自动刷新 · 每 30 秒' : '页面不可见 · 已暂停' }}
          </span>
          <button
            type="button"
            class="ops-icon-btn"
            :class="{ 'is-loading': refreshing }"
            :disabled="refreshing"
            aria-label="刷新运营数据"
            title="刷新运营数据"
            @click="refresh"
          >↻</button>
          <span class="ops-last-updated">{{ lastUpdatedLabel }}</span>
          <button
            v-if="!editor.editing.value && !layoutNarrow"
            type="button"
            class="ops-action-btn"
            data-testid="edit-layout"
            @click="editor.beginEdit()"
          ><span aria-hidden="true">✎</span> 编辑布局</button>
          <span
            v-else-if="!editor.editing.value"
            class="ops-editing-badge"
            data-testid="narrow-edit-disabled"
          >内容区小于 1000px，布局编辑不可用</span>
          <span v-else class="ops-editing-badge"><i aria-hidden="true" />布局编辑中</span>
          <button
            type="button"
            class="ops-action-btn is-primary"
            data-testid="toggle-fullscreen"
            @click="toggleFullscreen"
          ><span aria-hidden="true">⇱</span> {{ isFullscreen ? '退出大屏' : '进入大屏' }}</button>
        </div>
      </header>

      <div class="ops-content">
        <div v-if="dataWarning" class="ops-data-warning" role="status">
          <i aria-hidden="true" />
          <span>{{ dataWarning }}</span>
        </div>

        <DashboardLayoutContainer
          :context="widgetContext"
          :layout="editor.layoutForRender.value"
          :editing="editor.editing.value"
          :can-undo="editor.canUndo.value"
          :can-redo="editor.canRedo.value"
          :save-error="editor.saveError.value"
          :layout-source-label="repository.sourceLabel"
          :saved-revision="editor.savedRevision.value"
          @narrow-change="layoutNarrow = $event"
          @begin-edit="editor.beginEdit()"
          @cancel-edit="editor.cancelEdit()"
          @save-edit="editor.saveEdit()"
          @undo="editor.undo()"
          @redo="editor.redo()"
          @tidy="editor.tidyLayout()"
          @reset-default="editor.resetToDefault()"
          @add-widget="editor.addWidgetInstance"
          @remove-widget="editor.removeWidgetInstance"
          @move-widget="editor.moveWidgetInstanceBy"
          @resize-widget="editor.resizeWidgetInstanceBy"
          @commit-rect="editor.commitWidgetRect"
          @update-config="editor.updateWidgetConfig"
          @open-agent="openAgent"
          @open-project="openProject"
          @open-projects="go('/registry/projects')"
          @open-agents="go('/agent')"
          @open-runops="go('/runops')"
          @open-attention="(target) => go(target)"
          @open-run-trace="(traceId) => go(`/runops/${encodeURIComponent(traceId)}`)"
          @retry-runs="loadRuns"
          @retry-all="refresh"
        />
      </div>
    </div>
  </WorkbenchPage>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter, type RouteLocationRaw } from 'vue-router'
import { controlRequest } from '@/api/request'
import { getRecentRunOps } from '@/api/runops'
import { getScanProjects } from '@/api/scanProject'
import { getAgentStatistics, listAgents, listWorkflows } from '@/api/workflow'
import WorkbenchPage from '@/components/common/WorkbenchPage.vue'
import type {
  DashboardAgentRankingItem,
  DashboardAttentionSignal,
  DashboardDomainState,
  DashboardDomainStatus,
  DashboardMetricCardModel,
  DashboardProjectSummaryItem,
  DashboardRangeDays,
  DashboardServiceHealthSignal,
  DashboardWidgetContext,
} from '@/types/operationsDashboard'
import type { ScanProject } from '@/types/scanProject'
import type { RunSummary } from '@/types/runops'
import type { Agent, AgentStatistics, WorkflowWorkingCopy } from '@/types/workflow'
import {
  DASHBOARD_SAMPLE_LIMIT,
  buildAgentRanking,
  buildProjectSummaries,
  buildTokenDistribution,
  buildTrendBuckets,
  formatCompactNumber,
  summarizeIdentities,
  summarizeRunOutcomes,
  summarizeTokens,
} from './dashboardModel'
import { dashboardLayoutLocalRepository } from './dashboardLayoutLocalRepository'
import { defaultConsoleLayout } from './widgetRegistry'
import { useDashboardLayoutEditor } from './useDashboardLayoutEditor'
import DashboardLayoutContainer from './components/DashboardLayoutContainer.vue'

interface InternalServicesHealthResponse {
  services?: Record<string, { status?: string } | undefined>
}

const router = useRouter()
const repository = dashboardLayoutLocalRepository

const projects = ref<DashboardDomainState<ScanProject[]>>({ status: 'loading', data: [] })
const agents = ref<DashboardDomainState<Agent[]>>({ status: 'loading', data: [] })
const workflows = ref<DashboardDomainState<WorkflowWorkingCopy[]>>({ status: 'loading', data: [] })
const agentStats = ref<DashboardDomainState<AgentStatistics | null>>({ status: 'loading', data: null })
const runs = ref<DashboardDomainState<RunSummary[]>>({ status: 'loading', data: [] })
const health = ref<DashboardDomainState<DashboardServiceHealthSignal[]>>({ status: 'loading', data: [] })

const rangeDays = ref<DashboardRangeDays>(1)
const refreshing = ref(false)
const pageVisible = ref(typeof document === 'undefined' ? true : !document.hidden)
const lastUpdatedAt = ref<Date | null>(null)
const dashboardRoot = ref<HTMLElement | null>(null)
const isFullscreen = ref(false)
const layoutNarrow = ref(false)
let runRequestVersion = 0
let autoRefreshTimer: number | undefined

const editor = useDashboardLayoutEditor({ repository, fallback: defaultConsoleLayout })
editor.initialize()

function requireArray<T>(value: unknown, label: string): T[] {
  if (!Array.isArray(value)) throw new Error(`${label}响应不是数组`)
  return value as T[]
}

function requireAgentStatistics(value: unknown): AgentStatistics {
  if (!value || typeof value !== 'object') throw new Error('Agent 统计响应无效')
  const candidate = value as Partial<AgentStatistics>
  const values = [
    candidate.totalAgents,
    candidate.enabledAgents,
    candidate.workflowToolAgents,
    candidate.activeWorkflowTools,
  ]
  if (values.some((item) => typeof item !== 'number' || !Number.isFinite(item) || item < 0)) {
    throw new Error('Agent 统计响应字段无效')
  }
  return candidate as AgentStatistics
}

function requireHealthServices(body: InternalServicesHealthResponse | null | undefined): DashboardServiceHealthSignal[] {
  const source = body?.services
  if (!source || typeof source !== 'object') throw new Error('服务健康响应无效')
  const definitions = [
    ['runtime', 'Runtime'],
    ['capability', 'Capability'],
    ['knowledge', 'Knowledge'],
    ['model', 'Model'],
  ] as const
  return definitions.map(([key, name]) => {
    const status = source[key]?.status
    if (typeof status !== 'string') throw new Error(`${name} 健康状态缺失`)
    return { key, name, status: status.toUpperCase() === 'UP' ? 'online' : 'offline' } as DashboardServiceHealthSignal
  })
}

async function loadProjects() {
  try {
    const { data } = await getScanProjects()
    projects.value = { status: 'ready', data: requireArray<ScanProject>(data, '业务系统') }
  } catch {
    projects.value = { status: 'error', data: [] }
  }
}

async function loadAgents() {
  try {
    const { data } = await listAgents()
    agents.value = { status: 'ready', data: requireArray<Agent>(data, 'Agent') }
  } catch {
    agents.value = { status: 'error', data: [] }
  }
}

async function loadWorkflows() {
  try {
    const { data } = await listWorkflows()
    workflows.value = { status: 'ready', data: requireArray<WorkflowWorkingCopy>(data, 'Workflow') }
  } catch {
    workflows.value = { status: 'error', data: [] }
  }
}

async function loadAgentStats() {
  try {
    const { data } = await getAgentStatistics()
    agentStats.value = { status: 'ready', data: requireAgentStatistics(data) }
  } catch {
    agentStats.value = { status: 'error', data: null }
  }
}

async function loadRuns() {
  const version = ++runRequestVersion
  const days = rangeDays.value
  try {
    const { data } = await getRecentRunOps({ days, limit: DASHBOARD_SAMPLE_LIMIT })
    if (version !== runRequestVersion) return
    runs.value = { status: 'ready', data: requireArray<RunSummary>(data, '运行记录') }
  } catch {
    if (version !== runRequestVersion) return
    runs.value = { status: 'error', data: [] }
  }
}

async function loadHealth() {
  try {
    const { data } = await controlRequest.get<InternalServicesHealthResponse>('/api/internal-services/health')
    health.value = { status: 'ready', data: requireHealthServices(data) }
  } catch {
    health.value = { status: 'error', data: [] }
  }
}

async function refresh() {
  if (refreshing.value) return
  refreshing.value = true
  await Promise.allSettled([
    loadProjects(),
    loadAgents(),
    loadWorkflows(),
    loadAgentStats(),
    loadRuns(),
    loadHealth(),
  ])
  lastUpdatedAt.value = new Date()
  refreshing.value = false
}

async function selectRange(days: DashboardRangeDays) {
  if (rangeDays.value === days) return
  rangeDays.value = days
  runs.value = { status: 'loading', data: [] }
  await loadRuns()
  lastUpdatedAt.value = new Date()
}

function handleVisibilityChange() {
  pageVisible.value = !document.hidden
  if (pageVisible.value) void refresh()
}

function handleFullscreenChange() {
  isFullscreen.value = document.fullscreenElement === dashboardRoot.value
}

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else if (dashboardRoot.value?.requestFullscreen) {
      await dashboardRoot.value.requestFullscreen()
    }
  } catch {
    // 浏览器策略拒绝时保持当前页面状态，不伪装为已经进入大屏。
  }
}

onMounted(() => {
  void refresh()
  autoRefreshTimer = window.setInterval(() => {
    if (!document.hidden) void refresh()
  }, 30_000)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  document.addEventListener('fullscreenchange', handleFullscreenChange)
})

onBeforeUnmount(() => {
  if (autoRefreshTimer != null) window.clearInterval(autoRefreshTimer)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  document.removeEventListener('fullscreenchange', handleFullscreenChange)
})

const rangeLabel = computed(() => (rangeDays.value === 1 ? '近 24 小时' : '近 7 天'))
const runOutcome = computed(() => summarizeRunOutcomes(runs.value.data))
const identitySummary = computed(() => summarizeIdentities(runs.value.data))
const tokenSummary = computed(() => summarizeTokens(runs.value.data))
const trendBuckets = computed(() => buildTrendBuckets(runs.value.data, rangeDays.value))
const agentRanking = computed(() =>
  buildAgentRanking(runs.value.data, agents.value.data, projects.value.data, 10),
)
const allProjectSummaries = computed(() =>
  buildProjectSummaries(projects.value.data, agents.value.data, runs.value.data, Number.MAX_SAFE_INTEGER)
    .map((project) => ({
      ...project,
      runSeries: buildTrendBuckets(project.sampleRuns, rangeDays.value).map((bucket) => bucket.runs),
    })),
)
const projectSummaries = computed(() => allProjectSummaries.value.slice(0, 6))
const rankedProjects = computed(() => allProjectSummaries.value.filter((project) => project.runs > 0).slice(0, 5))
const maxProjectRuns = computed(() => Math.max(1, ...rankedProjects.value.map((project) => project.runs)))
const tokenDistribution = computed(() => buildTokenDistribution(allProjectSummaries.value, runs.value.data))

function combineStatus(...states: DashboardDomainStatus[]): DashboardDomainStatus {
  if (states.includes('error')) return 'error'
  if (states.includes('loading')) return 'loading'
  return 'ready'
}

const projectStatus = computed(() =>
  combineStatus(projects.value.status, agents.value.status, runs.value.status),
)

const activeConfigCount = computed(() =>
  agents.value.data.filter((agent) => agent.activeConfigVersionId != null).length,
)

function percentage(part: number, total: number) {
  return total > 0 ? Math.round((part / total) * 1000) / 10 : null
}

const metricCards = computed<DashboardMetricCardModel[]>(() => {
  const stats = agentStats.value.data
  const outcomes = runOutcome.value
  const identities = identitySummary.value
  const tokens = tokenSummary.value
  return [
    {
      key: 'agent-inventory',
      label: 'Agent 总数',
      value: stats ? formatCompactNumber(stats.totalAgents) : '—',
      detail: stats
        ? `${stats.enabledAgents} 个已启用${agents.value.status === 'ready' ? ` · ${activeConfigCount.value} 个有发布配置` : ''}`
        : '等待 Agent 统计',
      status: agentStats.value.status,
      tone: 'blue',
      icon: 'agent',
      progress: stats && agents.value.status === 'ready'
        ? percentage(activeConfigCount.value, stats.totalAgents)
        : null,
    },
    {
      key: 'enabled-agents',
      label: '已启用 Agent',
      value: stats ? formatCompactNumber(stats.enabledAgents) : '—',
      detail: '这是配置状态，不冒充“正在工作”',
      note: '活跃执行租约尚未接入',
      status: agentStats.value.status,
      tone: 'green',
      icon: 'enabled',
      progress: stats ? percentage(stats.enabledAgents, stats.totalAgents) : null,
    },
    {
      key: 'business-runs',
      label: '运行样本',
      value: formatCompactNumber(runs.value.data.length),
      detail: `${rangeLabel.value} · 最近 ${DASHBOARD_SAMPLE_LIMIT} 条上限`,
      status: runs.value.status,
      tone: 'cyan',
      icon: 'runs',
      series: trendBuckets.value.map((bucket) => bucket.runs),
    },
    {
      key: 'active-runtime-users',
      label: '可识别用户',
      value: identities.identifiedRuns ? formatCompactNumber(identities.distinctUsers) : '—',
      detail: identities.coverage == null
        ? '当前无运行样本'
        : `userId 样本覆盖 ${identities.coverage.toFixed(1)}%`,
      status: runs.value.status,
      tone: 'violet',
      icon: 'users',
      series: trendBuckets.value.map((bucket) => bucket.users),
    },
    {
      key: 'recorded-tokens',
      label: '已记录 Token',
      value: tokens.recordedRuns ? formatCompactNumber(tokens.total) : '—',
      detail: tokens.coverage == null
        ? '当前无运行样本'
        : `非零记录 ${tokens.recordedRuns}/${tokens.totalRuns} 条`,
      status: runs.value.status,
      tone: 'orange',
      icon: 'tokens',
      series: trendBuckets.value.map((bucket) => bucket.tokens),
    },
    {
      key: 'technical-completion-rate',
      label: '技术完成率',
      value: outcomes.technicalCompletionRate == null ? '—' : `${outcomes.technicalCompletionRate.toFixed(1)}%`,
      detail: `${outcomes.completed}/${outcomes.terminal} 个终态样本完成`,
      status: runs.value.status,
      tone: 'green',
      icon: 'success',
      progress: outcomes.technicalCompletionRate,
    },
  ]
})

const attentionItems = computed<DashboardAttentionSignal[]>(() => {
  const outcome = runOutcome.value
  const guardDenied = runs.value.data.reduce((sum, run) => sum + (run.guardDenyCount ?? 0), 0)
  return [
    {
      key: 'failed',
      label: '失败运行',
      value: outcome.failed,
      detail: '当前样本中的 FAILED',
      tone: outcome.failed ? 'risk' : 'ok',
      to: { path: '/runops', query: { status: 'FAILED', days: String(rangeDays.value) } },
    },
    {
      key: 'timed-out',
      label: '超时运行',
      value: outcome.timedOut,
      detail: '当前样本中的 TIMED_OUT',
      tone: outcome.timedOut ? 'risk' : 'ok',
      to: { path: '/runops', query: { status: 'TIMED_OUT', days: String(rangeDays.value) } },
    },
    {
      key: 'suspended',
      label: '等待交互 / 审批',
      value: outcome.suspended,
      detail: '当前样本中的 SUSPENDED',
      tone: outcome.suspended ? 'warning' : 'ok',
      to: { path: '/runops', query: { status: 'SUSPENDED', days: String(rangeDays.value) } },
    },
    {
      key: 'guard',
      label: 'Guard 拒绝',
      value: guardDenied,
      detail: '根运行累计 guardDenyCount',
      tone: guardDenied ? 'warning' : 'ok',
      to: { path: '/runops', query: { days: String(rangeDays.value) } },
    },
  ]
})
const attentionHasSignals = computed(() => attentionItems.value.some((item) => item.value > 0))

const widgetContext = computed<DashboardWidgetContext>(() => ({
  metrics: Object.fromEntries(metricCards.value.map((metric) => [`kpi.${metric.key}`, metric])),
  runsStatus: runs.value.status,
  runsData: runs.value.data,
  agentRanking: agentRanking.value,
  projectStatus: projectStatus.value,
  rankedProjects: rankedProjects.value,
  projectSummaries: projectSummaries.value,
  maxProjectRuns: maxProjectRuns.value,
  tokenDistributionStatus:
    runs.value.status === 'ready' && projects.value.status === 'error' ? 'error' : runs.value.status,
  tokenDistribution: tokenDistribution.value,
  attentionItems: attentionItems.value,
  attentionHasSignals: attentionHasSignals.value,
  trendBuckets: trendBuckets.value,
  rangeLabel: rangeLabel.value,
  health: health.value,
}))

const failedDomains = computed(() => {
  const domains = [
    [projects.value.status, '业务系统'],
    [agents.value.status, 'Agent 列表'],
    [agentStats.value.status, 'Agent 统计'],
    [workflows.value.status, 'Workflow'],
    [runs.value.status, '运行数据'],
    [health.value.status, '服务健康'],
  ] as const
  return domains.filter(([status]) => status === 'error').map(([, label]) => label)
})

const dataWarning = computed(() => {
  const warnings: string[] = []
  if (failedDomains.value.length) warnings.push(`部分数据暂不可用：${failedDomains.value.join('、')}`)
  if (runs.value.status === 'ready' && runs.value.data.length >= DASHBOARD_SAMPLE_LIMIT) {
    warnings.push(`运行记录达到 ${DASHBOARD_SAMPLE_LIMIT} 条接口上限，当前页面仅代表最近样本`)
  }
  if (workflows.value.status === 'ready' && workflows.value.data.length === 0) {
    warnings.push('尚未创建 Workflow')
  }
  return warnings.join('；')
})

const currentDateLabel = computed(() => {
  const date = lastUpdatedAt.value ?? new Date()
  return `今天 · ${String(date.getMonth() + 1).padStart(2, '0')}月${String(date.getDate()).padStart(2, '0')}日`
})

const lastUpdatedLabel = computed(() => {
  if (!lastUpdatedAt.value) return '等待首次刷新'
  return `更新于 ${new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(lastUpdatedAt.value)}`
})

function go(target: RouteLocationRaw) {
  void router.push(target)
}

function openAgent(agent: DashboardAgentRankingItem) {
  if (agent.agentId) {
    go(`/agent/${encodeURIComponent(agent.agentId)}/edit`)
    return
  }
  go({ path: '/runops', query: { agentId: agent.key, days: String(rangeDays.value) } })
}

function openProject(project: DashboardProjectSummaryItem) {
  if (project.projectId) {
    go(`/registry/projects/${project.projectId}`)
    return
  }
  go({ path: '/runops', query: { projectCode: project.projectCode, days: String(rangeDays.value) } })
}
</script>

<style lang="scss" src="./dashboardOperations.scss"></style>
