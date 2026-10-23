/** 调试页执行阶段的展示格式化。 */
export type StepState = 'complete' | 'active' | 'error' | 'pending'
export type ExecutionPhase = 'idle' | 'running' | 'success' | 'error' | 'stopped'
export type RawResponseMode = 'show' | 'mismatch' | 'empty'

export function formatStepDetail(detail: unknown) {
  if (typeof detail === 'string') return detail
  try {
    return JSON.stringify(detail)
  } catch {
    return String(detail ?? '')
  }
}

export function formatStepName(name: string, index: number) {
  const normalized = name.toLowerCase()
  if (normalized.includes('config')) return '读取已发布配置'
  if (normalized === 'workflow' || normalized.includes('workflow')) return '调用 Workflow'
  if (normalized.includes('plan') || normalized.includes('replan')) return '规划执行路径'
  if (normalized.includes('answer') || normalized.includes('final') || normalized.includes('respond')) return '生成最终回答'
  if (normalized.includes('policy')) return '策略检查'
  if (normalized.includes('agent') || normalized.includes('resolve')) return '识别 Agent 范围'
  return name || `步骤 ${index + 1}`
}

export function formatOptionalCount(value: number | null): string {
  return value === null ? '—' : String(value)
}
