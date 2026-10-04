import type {
  DashboardDataDomain,
  DashboardLayoutDocument,
  DashboardWidgetConfig,
  DashboardWidgetDefinition,
  DashboardWidgetInstance,
} from '@/types/operationsDashboard'
import { clampRect, DASHBOARD_GRID_COLUMNS, DASHBOARD_LAYOUT_SCHEMA_VERSION } from './layoutEngine'

export const dashboardDomainLabels: Record<DashboardDataDomain, string> = {
  projects: '业务系统', agents: 'Agent 列表', agentStats: 'Agent 统计',
  workflows: 'Workflow', runs: '运行数据', health: '服务健康',
}

const KPI_GRID = {
  minW: 2,
  minH: 2,
  maxW: 4,
  maxH: 3,
  defaultW: 2,
  defaultH: 2,
} as const

function define(
  definition: Omit<DashboardWidgetDefinition, 'availability' | 'configFields' | 'defaultConfig'> & {
    availability?: DashboardWidgetDefinition['availability']
    configFields?: DashboardWidgetDefinition['configFields']
    defaultConfig?: DashboardWidgetConfig
  },
): DashboardWidgetDefinition {
  return {
    availability: { state: 'AVAILABLE' },
    configFields: [],
    defaultConfig: {},
    ...definition,
  }
}

/**
 * 平台提供的看板组件目录。只在这里集中声明可用性；未接数据的模块必须以
 * DISABLED + 真实原因呈现，不允许用近似指标冒充。
 */
export const dashboardWidgetRegistry: Record<string, DashboardWidgetDefinition> = {
  'kpi.agent-inventory': define({
    key: 'kpi.agent-inventory',
    category: 'KPI',
    title: 'Agent 总数',
    dataDomains: ['agentStats'],
    description: 'Agent 总数、启用数与发布配置覆盖，来自 Agent 统计接口。',
    grid: { ...KPI_GRID },
  }),
  'kpi.enabled-agents': define({
    key: 'kpi.enabled-agents',
    category: 'KPI',
    title: '已启用 Agent',
    dataDomains: ['agentStats'],
    description: '配置态启用数量；启用不等于正在工作。',
    grid: { ...KPI_GRID },
  }),
  'kpi.business-runs': define({
    key: 'kpi.business-runs',
    category: 'KPI',
    title: '运行样本',
    dataDomains: ['runs'],
    description: '当前时间范围内最近返回的运行样本数量。',
    grid: { ...KPI_GRID },
  }),
  'kpi.active-runtime-users': define({
    key: 'kpi.active-runtime-users',
    category: 'KPI',
    title: '可识别用户',
    dataDomains: ['runs'],
    description: '样本中携带 userId 的去重用户数与身份覆盖率。',
    grid: { ...KPI_GRID },
  }),
  'kpi.recorded-tokens': define({
    key: 'kpi.recorded-tokens',
    category: 'KPI',
    title: '已记录 Token',
    dataDomains: ['runs'],
    description: '样本中 token_cost 大于 0 的累计 Token 与采集覆盖率。',
    grid: { ...KPI_GRID },
  }),
  'kpi.technical-completion-rate': define({
    key: 'kpi.technical-completion-rate',
    category: 'KPI',
    title: '技术完成率',
    dataDomains: ['runs'],
    description: '终态样本中 COMPLETED 占比；不是业务成功率。',
    grid: { ...KPI_GRID },
  }),
  'ranking.agent-top': define({
    key: 'ranking.agent-top',
    category: 'RANKING',
    title: '今日 TOP Agent',
    dataDomains: ['runs', 'agents', 'projects'],
    description: '按最近返回运行样本排序的 Agent 排行，支持 TOP N 配置。',
    grid: { minW: 3, minH: 4, maxW: 6, maxH: 10, defaultW: 3, defaultH: 6 },
    configFields: [{ key: 'topN', label: '显示条数（TOP N）', min: 1, max: 10 }],
    defaultConfig: { topN: 5 },
  }),
  'trend.usage': define({
    key: 'trend.usage',
    category: 'TREND',
    title: '今日运行态势',
    dataDomains: ['runs'],
    description: '调用、Token 与可识别用户的分桶趋势，只基于真实样本。',
    grid: { minW: 4, minH: 5, maxW: 12, maxH: 10, defaultW: 6, defaultH: 6 },
  }),
  'ranking.project-top': define({
    key: 'ranking.project-top',
    category: 'RANKING',
    title: '业务系统贡献排行',
    dataDomains: ['projects', 'agents', 'runs'],
    description: '按运行样本数排序的业务系统排行，可钻取到项目详情。',
    grid: { minW: 2, minH: 3, maxW: 6, maxH: 8, defaultW: 3, defaultH: 3 },
  }),
  'quality.attention': define({
    key: 'quality.attention',
    category: 'QUALITY',
    title: '风险与待办',
    dataDomains: ['runs'],
    description: '失败、超时、等待交互审批与 Guard 拒绝的可验证信号。',
    grid: { minW: 2, minH: 3, maxW: 6, maxH: 8, defaultW: 3, defaultH: 3 },
  }),
  'projects.operations-cards': define({
    key: 'projects.operations-cards',
    category: 'PROJECT',
    title: '业务系统运营',
    dataDomains: ['projects', 'agents', 'runs'],
    description: '项目资产与运行样本联合视角的运营卡片，支持显示数量配置。',
    grid: { minW: 4, minH: 4, maxW: 12, maxH: 10, defaultW: 9, defaultH: 4 },
    configFields: [{ key: 'displayCount', label: '显示数量', min: 1, max: 6 }],
    defaultConfig: { displayCount: 4 },
  }),
  'distribution.token-by-project': define({
    key: 'distribution.token-by-project',
    category: 'DISTRIBUTION',
    title: 'Token 使用分布',
    dataDomains: ['projects', 'runs'],
    description: '项目维度的已记录 Token 分布；仅统计大于 0 的记录。',
    grid: { minW: 2, minH: 4, maxW: 6, maxH: 10, defaultW: 3, defaultH: 4 },
  }),
  'activity.runtime-stream': define({
    key: 'activity.runtime-stream',
    category: 'ACTIVITY',
    title: '实时动态',
    dataDomains: ['runs'],
    description: '最近返回的运行样本快照，点击跳转 RunOps 详情。',
    grid: { minW: 4, minH: 1, maxW: 12, maxH: 4, defaultW: 12, defaultH: 1 },
  }),
  'service.data-source-status': define({
    key: 'service.data-source-status',
    category: 'SERVICE',
    title: '服务数据源状态',
    dataDomains: ['health'],
    description: 'Control 聚合的内部服务数据源状态；只表示数据源可达性。',
    grid: { minW: 3, minH: 2, maxW: 12, maxH: 6, defaultW: 4, defaultH: 3 },
  }),
  // 以下目录项诚实展示为不可用；数据链路完成前不得启用。
  'kpi.active-executions': define({
    key: 'kpi.active-executions',
    category: 'KPI',
    title: '正在工作',
    description: '基于活跃执行租约的当前执行数。',
    availability: {
      state: 'DISABLED',
      reason: '活跃执行租约尚未接入；启用状态与残留 RUNNING 状态不能证明 Agent 正在工作。',
    },
    grid: { minW: 2, minH: 2, maxW: 4, maxH: 3, defaultW: 2, defaultH: 2 },
  }),
  'insight.question-clusters-top': define({
    key: 'insight.question-clusters-top',
    category: 'INSIGHT',
    title: '高频问题',
    description: '治理后的问题聚类排行。',
    availability: {
      state: 'DISABLED',
      reason: '缺少治理后的问题聚类数据；会话正文受隐私与留存治理约束，不能直接统计。',
    },
    grid: { minW: 3, minH: 4, maxW: 8, maxH: 10, defaultW: 4, defaultH: 5 },
  }),
  'cost.model-usage': define({
    key: 'cost.model-usage',
    category: 'COST',
    title: 'Token 成本',
    description: '模型调用的货币成本聚合。',
    availability: {
      state: 'DISABLED',
      reason: '缺少调用级成本账本与价格版本，无法计算货币成本。',
    },
    grid: { minW: 3, minH: 4, maxW: 8, maxH: 10, defaultW: 4, defaultH: 5 },
  }),
  'telemetry.resource-utilization': define({
    key: 'telemetry.resource-utilization',
    category: 'TELEMETRY',
    title: '资源利用率',
    description: '项目归属的 CPU / 内存等资源利用率。',
    availability: {
      state: 'DISABLED',
      reason: '缺少项目归属的遥测数据（OTel / Prometheus 未接入），不能冒充服务健康。',
    },
    grid: { minW: 3, minH: 4, maxW: 8, maxH: 10, defaultW: 4, defaultH: 5 },
  }),
}

export function getWidgetDefinition(widgetKey: string): DashboardWidgetDefinition | null {
  return dashboardWidgetRegistry[widgetKey] ?? null
}

export function listWidgetDefinitions(): DashboardWidgetDefinition[] {
  return Object.values(dashboardWidgetRegistry)
}

function instance(widgetKey: string, x: number, y: number, w: number, h: number): DashboardWidgetInstance {
  const definition = dashboardWidgetRegistry[widgetKey]!
  return {
    instanceId: `${widgetKey}#default`,
    widgetKey,
    rect: clampRect({ x, y, w, h }, definition.grid, DASHBOARD_GRID_COLUMNS),
    config: { ...definition.defaultConfig },
  }
}

/** CONSOLE 默认 12 列布局：领导首屏优先，形成 KPI → 态势 → 业务 → 动态的完整闭环。 */
export const defaultConsoleLayout: DashboardLayoutDocument = {
  schemaVersion: DASHBOARD_LAYOUT_SCHEMA_VERSION,
  revision: 0,
  mode: 'CONSOLE',
  columns: DASHBOARD_GRID_COLUMNS,
  items: [
    instance('kpi.agent-inventory', 0, 0, 2, 2),
    instance('kpi.enabled-agents', 2, 0, 2, 2),
    instance('kpi.business-runs', 4, 0, 2, 2),
    instance('kpi.active-runtime-users', 6, 0, 2, 2),
    instance('kpi.recorded-tokens', 8, 0, 2, 2),
    instance('kpi.technical-completion-rate', 10, 0, 2, 2),
    instance('ranking.agent-top', 0, 2, 3, 6),
    instance('trend.usage', 3, 2, 6, 6),
    instance('ranking.project-top', 9, 2, 3, 3),
    instance('quality.attention', 9, 5, 3, 3),
    instance('projects.operations-cards', 0, 8, 9, 4),
    instance('distribution.token-by-project', 9, 8, 3, 4),
    instance('activity.runtime-stream', 0, 12, 12, 1),
  ],
}

/**
 * 早期原型默认布局已经写入部分浏览器。仅当文档仍与旧默认布局完全一致时，
 * 才无损切换到新版领导首屏；用户移动、删除或配置过任一组件后都原样保留。
 */
const LEGACY_DEFAULT_LAYOUT_SIGNATURE = [
  ['kpi.agent-inventory', 0, 0, 2, 2],
  ['kpi.enabled-agents', 2, 0, 2, 2],
  ['kpi.business-runs', 4, 0, 2, 2],
  ['kpi.active-runtime-users', 6, 0, 2, 2],
  ['kpi.recorded-tokens', 8, 0, 2, 2],
  ['kpi.technical-completion-rate', 10, 0, 2, 2],
  ['ranking.agent-top', 0, 2, 4, 8],
  ['trend.usage', 4, 2, 5, 8],
  ['ranking.project-top', 9, 2, 3, 4],
  ['quality.attention', 9, 6, 3, 4],
  ['projects.operations-cards', 0, 10, 9, 7],
  ['distribution.token-by-project', 9, 10, 3, 7],
  ['activity.runtime-stream', 0, 17, 8, 3],
  ['service.data-source-status', 8, 17, 4, 3],
] as const

function matchesLegacyDefault(document: DashboardLayoutDocument) {
  if (document.items.length !== LEGACY_DEFAULT_LAYOUT_SIGNATURE.length) return false
  const byKey = new Map(document.items.map((item) => [item.widgetKey, item]))
  return LEGACY_DEFAULT_LAYOUT_SIGNATURE.every(([widgetKey, x, y, w, h]) => {
    const item = byKey.get(widgetKey)
    if (!item) return false
    const configMatches = !item.config.title
      && (widgetKey !== 'ranking.agent-top' || item.config.topN === 5)
      && (widgetKey !== 'projects.operations-cards' || item.config.displayCount === 6)
      && Object.keys(item.config).every((key) => (
        (widgetKey === 'ranking.agent-top' && key === 'topN')
        || (widgetKey === 'projects.operations-cards' && key === 'displayCount')
      ))
    return configMatches
      && item.rect.x === x
      && item.rect.y === y
      && item.rect.w === w
      && item.rect.h === h
  })
}

export function migrateLegacyDefaultLayout(document: DashboardLayoutDocument): DashboardLayoutDocument {
  if (!matchesLegacyDefault(document)) return document
  return {
    ...defaultConsoleLayout,
    revision: document.revision,
    items: defaultConsoleLayout.items.map((item) => ({
      ...item,
      rect: { ...item.rect },
      config: { ...item.config },
    })),
  }
}

export function resolveWidgetTitle(instanceItem: DashboardWidgetInstance) {
  const custom = instanceItem.config.title?.trim()
  if (custom) return custom
  return getWidgetDefinition(instanceItem.widgetKey)?.title ?? instanceItem.widgetKey
}

function readBoundedConfig(instanceItem: DashboardWidgetInstance, fieldKey: 'topN' | 'displayCount') {
  const definition = getWidgetDefinition(instanceItem.widgetKey)
  const field = definition?.configFields.find((candidate) => candidate.key === fieldKey)
  if (!field) return null
  const value = instanceItem.config[fieldKey]
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    const fallback = definition?.defaultConfig[fieldKey]
    return typeof fallback === 'number' ? fallback : field.min
  }
  return Math.min(field.max, Math.max(field.min, Math.round(value)))
}

export function resolveTopN(instanceItem: DashboardWidgetInstance) {
  return readBoundedConfig(instanceItem, 'topN') ?? 5
}

export function resolveDisplayCount(instanceItem: DashboardWidgetInstance) {
  return readBoundedConfig(instanceItem, 'displayCount') ?? 4
}

/**
 * 把解析后的布局项收口到当前注册表：丢弃未知或 DISABLED 的 widgetKey，
 * 去重 instanceId，并按约束收紧矩形与配置。
 */
export function sanitizeLayoutItems(items: DashboardWidgetInstance[]): DashboardWidgetInstance[] {
  const seen = new Set<string>()
  const result: DashboardWidgetInstance[] = []
  for (const item of items) {
    const definition = getWidgetDefinition(item.widgetKey)
    if (!definition || definition.availability.state !== 'AVAILABLE') continue
    if (seen.has(item.instanceId)) continue
    seen.add(item.instanceId)
    const config: DashboardWidgetConfig = {}
    if (typeof item.config.title === 'string' && item.config.title.trim()) {
      config.title = item.config.title.trim()
    }
    for (const field of definition.configFields) {
      const value = item.config[field.key]
      if (typeof value === 'number' && Number.isFinite(value)) {
        config[field.key] = Math.min(field.max, Math.max(field.min, Math.round(value)))
      }
    }
    result.push({
      instanceId: item.instanceId,
      widgetKey: item.widgetKey,
      rect: clampRect(item.rect, definition.grid, DASHBOARD_GRID_COLUMNS),
      config,
    })
  }
  return result
}
