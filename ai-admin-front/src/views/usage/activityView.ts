import type { ActivityInsight, ActivityInsightStatus } from '@/types/mcp'
import type { ModelInstance } from '@/types/model'

export type ActivityView = 'operations' | 'keywords' | 'problems' | 'people' | 'insights'

export const activityViews: { value: ActivityView; label: string }[] = [
  { value: 'operations', label: '高频操作' },
  { value: 'keywords', label: '热门搜索' },
  { value: 'problems', label: '常见问题' },
  { value: 'people', label: '员工' },
  { value: 'insights', label: 'AI 归纳' },
]

export const insightStatusLabels: Record<ActivityInsightStatus, string> = {
  SUGGESTED: '待定',
  RECOMMENDED: '已推荐给员工',
  DISMISSED: '不推荐',
}

export function failureRate(total: number, failures: number): string {
  if (!total) return '—'
  const rate = (failures / total) * 100
  if (failures > 0 && rate < 0.1) return '<0.1%'
  return `${rate >= 10 || rate === 0 ? Math.round(rate) : rate.toFixed(1)}%`
}

/** 有接口说明就用说明，否则把「POST /erp/customer/create」显示成路径。 */
export function operationLabel(row: { operationName: string | null; operationKey: string }): string {
  return row.operationName?.trim() || row.operationKey
}

export function insightEvidence(item: ActivityInsight): string {
  const e = item.evidence ?? {}
  const n = (key: string) => Number(e[key] ?? 0)
  if (item.kind === 'FLOW') return `${n('people')} 人做过 ${n('sessions')} 次`
  const parts = [`失败 ${n('failed')} / ${n('total')} 次（${e.failRate ?? 0}%）`, `${n('people')} 人遇到`]
  if (n('retriedThenSucceeded')) parts.push(`${n('retriedThenSucceeded')} 次重试后成功`)
  if (n('gaveUp')) parts.push(`${n('gaveUp')} 次没再重试`)
  return parts.join(' · ')
}

/** 测试默认用 DeepSeek Flash 一类便宜的模型。 */
export function pickDefaultModel(models: ModelInstance[]): ModelInstance | undefined {
  return (
    models.find((model) => /flash/i.test(`${model.name} ${model.modelName}`)) ??
    models.find((model) => /deepseek/i.test(`${model.name} ${model.modelName}`)) ??
    models[0]
  )
}
