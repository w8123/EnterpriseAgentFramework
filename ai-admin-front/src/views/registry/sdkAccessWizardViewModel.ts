import type {
  AiAccessStepStatus,
  ProjectToolInfo,
  SdkAccessCheckStatus,
} from '@/types/scanProject'

export type SdkAccessCheckTagType = 'success' | 'warning' | 'danger' | 'info'

export function sdkAccessCheckStatusLabel(status: SdkAccessCheckStatus): string {
  if (status === 'PASS') return '通过'
  if (status === 'WARN') return '需确认'
  return '失败'
}

export function aiAccessStepStatusLabel(status: AiAccessStepStatus | 'OPEN'): string {
  if (status === 'PASS') return '完成'
  if (status === 'WARN') return '需确认'
  if (status === 'FAIL') return '失败'
  if (status === 'RUNNING') return '进行中'
  if (status === 'SKIPPED') return '跳过'
  return '待处理'
}

export function aiAccessSessionTagType(
  status: AiAccessStepStatus | 'OPEN' | undefined,
): SdkAccessCheckTagType {
  if (status === 'PASS') return 'success'
  if (status === 'WARN' || status === 'RUNNING') return 'warning'
  if (status === 'FAIL') return 'danger'
  return 'info'
}

export function projectApiToolLabel(tool: ProjectToolInfo): string {
  const method = tool.httpMethod || 'API'
  const path = tool.endpointPath || tool.sourceLocation || tool.name
  return `${method} ${path}`
}

export const AI_ACCESS_DISPLAY_STEP_TITLES = [
  '项目识别',
  '后端 Starter',
  '网关路由',
  '业务服务校验',
  '前端 Embed Token',
  '最终自检',
] as const
