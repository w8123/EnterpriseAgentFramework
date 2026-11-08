import type {
  CapabilityRetrofitCapabilityState,
  CapabilityRetrofitOverview,
} from '@/api/aiCodingTasks'
import type { ToolInfo } from '@/types/tool'

export const CAPABILITY_RETROFIT_TASK_KIND = 'CAPABILITY_RETROFIT'
export const SELECTION_SCHEMA = 'reachai.capability-retrofit-selection.v1'

export type RetrofitStageKey = 'INVENTORY' | 'SELECT' | 'IMPLEMENT' | 'REVIEW' | 'PUBLISH'

export interface RetrofitStage {
  key: RetrofitStageKey
  title: string
  status: 'done' | 'current' | 'todo'
}

const STAGES: Array<{ key: RetrofitStageKey; title: string }> = [
  { key: 'INVENTORY', title: '盘点' },
  { key: 'SELECT', title: '你来勾选' },
  { key: 'IMPLEMENT', title: '改代码并同步' },
  { key: 'REVIEW', title: '评审接受' },
  { key: 'PUBLISH', title: '发布给员工' },
]

/** Where a retrofit task stands, judged only from platform facts the overview returns. */
export function currentRetrofitStage(overview: CapabilityRetrofitOverview | null): RetrofitStageKey {
  if (!overview?.selection) return 'INVENTORY'
  if (!overview.selection.answered) return 'SELECT'
  if (!overview.report) return 'IMPLEMENT'
  const states = overview.capabilityStates || []
  if (!states.length || states.some((state) => state.state !== 'ACCEPTED')) return 'REVIEW'
  return 'PUBLISH'
}

export function retrofitStages(overview: CapabilityRetrofitOverview | null): RetrofitStage[] {
  const current = currentRetrofitStage(overview)
  const index = STAGES.findIndex((stage) => stage.key === current)
  return STAGES.map((stage, position) => ({
    ...stage,
    status: position < index ? 'done' : position === index ? 'current' : 'todo',
  }))
}

export function selectionAnswer(selected: string[], note = ''): string {
  return JSON.stringify({ schema: SELECTION_SCHEMA, selected, note })
}

export function sideEffectLabel(value?: string | null): string {
  if (value === 'READ') return '只读'
  if (value === 'WRITE') return '会改数据'
  if (value === 'IRREVERSIBLE') return '不可撤回'
  return value || '未知'
}

export function sideEffectTagType(value?: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (value === 'READ') return 'success'
  if (value === 'WRITE') return 'warning'
  if (value === 'IRREVERSIBLE') return 'danger'
  return 'info'
}

export function capabilityStateLabel(state: string): string {
  switch (state) {
    case 'ACCEPTED': return '已接受'
    case 'PENDING_REVIEW': return '待评审'
    case 'NOT_SYNCED': return '还没同步到平台'
    case 'DISABLED': return '已停用'
    default: return '暂时读不到'
  }
}

export function capabilityStateTagType(state: string): 'success' | 'warning' | 'danger' | 'info' {
  if (state === 'ACCEPTED') return 'success'
  if (state === 'PENDING_REVIEW') return 'warning'
  if (state === 'DISABLED') return 'danger'
  return 'info'
}

function tail(value?: string | null): string {
  if (!value) return ''
  const colon = value.lastIndexOf(':')
  return colon >= 0 ? value.slice(colon + 1) : value
}

/** Finds the catalog tool (MCP publication source) for an accepted business method. */
export function matchCatalogTool(
  state: CapabilityRetrofitCapabilityState,
  tools: ToolInfo[],
  projectCode: string,
): ToolInfo | undefined {
  const names = new Set([state.name, state.qualifiedName, state.invocationName, tail(state.qualifiedName)]
    .filter((value): value is string => Boolean(value)))
  return tools.find((tool) => tool.assetType === 'BUSINESS_METHOD'
    && (!tool.projectCode || tool.projectCode === projectCode)
    && (names.has(tool.name) || names.has(tool.qualifiedName || '') || names.has(tail(tool.qualifiedName))))
}

