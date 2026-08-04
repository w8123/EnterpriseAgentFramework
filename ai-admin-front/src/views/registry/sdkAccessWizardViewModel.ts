import type { ProjectToolInfo } from '@/types/scanProject'

export type AiOnboardingStepStatus =
  | 'TODO'
  | 'RUNNING'
  | 'PASS'
  | 'WARN'
  | 'FAIL'
  | 'SKIPPED'

export function sdkAccessCheckStatusLabel(status: string): string {
  if (status === 'PASS') return '通过'
  if (status === 'WARN') return '需确认'
  if (status === 'PENDING') return '待验证'
  return '失败'
}

export function aiAccessStepStatusLabel(status: AiOnboardingStepStatus): string {
  if (status === 'PASS') return '已报告'
  if (status === 'WARN') return '需确认'
  if (status === 'FAIL') return '报告失败'
  if (status === 'RUNNING') return '进行中'
  if (status === 'SKIPPED') return '已声明跳过'
  return '待处理'
}

export function projectApiToolLabel(tool: ProjectToolInfo): string {
  const method = tool.httpMethod || 'API'
  const path = tool.endpointPath || tool.sourceLocation || tool.name
  return `${tool.title || tool.name} · ${method} ${path}`
}
