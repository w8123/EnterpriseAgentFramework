import type { RunSummary } from '@/types/runops'
import type { RouteLocationRaw } from 'vue-router'

export type DashboardDomainStatus = 'loading' | 'ready' | 'error'
export type DashboardDataDomain = 'projects' | 'agents' | 'workflows' | 'agentStats' | 'runs' | 'health'
export type DashboardMetricTone = 'blue' | 'cyan' | 'violet' | 'green' | 'orange'
export type DashboardMetricIcon = 'agent' | 'enabled' | 'runs' | 'users' | 'tokens' | 'success'
export type DashboardRangeDays = 1 | 7

export interface DashboardDomainState<T> {
  status: DashboardDomainStatus
  data: T
}

export interface DashboardMetricCardModel {
  key: string
  label: string
  value: string
  detail: string
  note?: string
  status: DashboardDomainStatus
  tone: DashboardMetricTone
  icon: DashboardMetricIcon
  /** 仅承载真实分桶序列；没有可靠序列时不渲染装饰性假折线。 */
  series?: number[]
  /** 仅承载可解释的真实比例，例如启用覆盖率或技术完成率。 */
  progress?: number | null
}

export interface DashboardRunOutcomeSummary {
  completed: number
  failed: number
  timedOut: number
  cancelled: number
  running: number
  suspended: number
  terminal: number
  technicalCompletionRate: number | null
}

export interface DashboardIdentitySummary {
  distinctUsers: number
  identifiedRuns: number
  totalRuns: number
  coverage: number | null
}

export interface DashboardTokenSummary {
  total: number
  recordedRuns: number
  totalRuns: number
  coverage: number | null
}

export interface DashboardTrendBucket {
  key: string
  label: string
  runs: number
  tokens: number
  users: number
  completed: number
  terminal: number
  technicalCompletionRate: number | null
}

export interface DashboardAgentRankingItem {
  key: string
  agentId?: string
  name: string
  projectCode?: string
  projectName: string
  enabled: boolean | null
  runs: number
  completed: number
  terminal: number
  technicalCompletionRate: number | null
}

export interface DashboardProjectSummaryItem {
  key: string
  projectId?: number
  projectCode?: string
  name: string
  agentCount: number
  runs: number
  users: number
  identityCoverage: number | null
  tokens: number
  tokenCoverage: number | null
  technicalCompletionRate: number | null
  sampleRuns: RunSummary[]
  /** 当前时间范围内的真实运行分桶，用于项目卡片微趋势。 */
  runSeries?: number[]
}

export interface DashboardTokenDistributionItem {
  key: string
  name: string
  projectCode?: string
  tokens: number
  ratio: number
  color: string
}

// ---------------------------------------------------------------------------
// 可配置看板布局（CONSOLE 模式）
// ---------------------------------------------------------------------------

export type DashboardViewMode = 'CONSOLE'

export type DashboardWidgetCategory =
  | 'KPI'
  | 'RANKING'
  | 'TREND'
  | 'QUALITY'
  | 'PROJECT'
  | 'DISTRIBUTION'
  | 'ACTIVITY'
  | 'SERVICE'
  | 'INSIGHT'
  | 'COST'
  | 'TELEMETRY'

/** 网格矩形，整数坐标，x/y 为 12 列网格中的列、行偏移。 */
export interface DashboardWidgetRect {
  x: number
  y: number
  w: number
  h: number
}

export interface DashboardWidgetGridConstraints {
  minW: number
  minH: number
  maxW: number
  maxH: number
  defaultW: number
  defaultH: number
}

export type DashboardWidgetConfigFieldKey = 'topN' | 'displayCount'

export interface DashboardWidgetConfigField {
  key: DashboardWidgetConfigFieldKey
  label: string
  min: number
  max: number
}

/** 实例级配置；只允许白名单字段，禁止保存组件名、URL、SQL 或权限。 */
export interface DashboardWidgetConfig {
  title?: string
  topN?: number
  displayCount?: number
}

export interface DashboardWidgetAvailability {
  state: 'AVAILABLE' | 'DISABLED'
  /** DISABLED 时必须给出真实原因，不允许伪装成“暂无数据”。 */
  reason?: string
}

/** 代码注册的看板组件定义；组件本身只存在于前端代码，不从服务端反序列化。 */
export interface DashboardWidgetDefinition {
  key: string
  category: DashboardWidgetCategory
  title: string
  description: string
  availability: DashboardWidgetAvailability
  grid: DashboardWidgetGridConstraints
  configFields: DashboardWidgetConfigField[]
  defaultConfig: DashboardWidgetConfig
  /** 必需数据域；403 时由共享宿主展示读取受限，不把未知数据当作零值。 */
  dataDomains?: DashboardDataDomain[]
}

/** 布局中的一个组件实例；同一 Widget 可存在多个实例，instanceId 必须稳定唯一。 */
export interface DashboardWidgetInstance {
  instanceId: string
  widgetKey: string
  rect: DashboardWidgetRect
  config: DashboardWidgetConfig
}

/** 前端布局文档 v1；当前仅保存在浏览器 localStorage，服务端个人布局尚未接入。 */
export interface DashboardLayoutDocument {
  schemaVersion: number
  revision: number
  mode: DashboardViewMode
  columns: number
  items: DashboardWidgetInstance[]
}

export interface DashboardServiceHealthSignal {
  key: string
  name: string
  status: 'online' | 'offline'
}

export interface DashboardAttentionSignal {
  key: string
  label: string
  value: number
  detail: string
  tone: 'risk' | 'warning' | 'ok'
  to: RouteLocationRaw
}

/** Widget 渲染上下文：Dashboard 壳统一加载真实数据后分发给各 Widget 体。 */
export interface DashboardWidgetContext {
  deniedDomains?: Partial<Record<DashboardDataDomain, boolean>>
  metrics: Record<string, DashboardMetricCardModel>
  runsStatus: DashboardDomainStatus
  runsData: RunSummary[]
  agentRanking: DashboardAgentRankingItem[]
  projectStatus: DashboardDomainStatus
  rankedProjects: DashboardProjectSummaryItem[]
  projectSummaries: DashboardProjectSummaryItem[]
  maxProjectRuns: number
  tokenDistributionStatus: DashboardDomainStatus
  tokenDistribution: DashboardTokenDistributionItem[]
  attentionItems: DashboardAttentionSignal[]
  attentionHasSignals: boolean
  trendBuckets: DashboardTrendBucket[]
  rangeLabel: string
  health: DashboardDomainState<DashboardServiceHealthSignal[]>
}
