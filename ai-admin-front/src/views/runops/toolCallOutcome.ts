import type { RunToolCall } from '@/types/runops'

type ToolObservation = Pick<RunToolCall, 'success' | 'status'>

export function toolCallStatus(tool: ToolObservation): string {
  return tool.status?.trim().toUpperCase() || (tool.success ? 'SUCCESS' : 'FAILED')
}

export function toolCallFailed(tool: ToolObservation): boolean {
  return ['FAILED', 'ERROR', 'CANCELLED', 'TIMED_OUT', 'TIMEOUT'].includes(toolCallStatus(tool))
}

export function toolCallStatusLabel(tool: ToolObservation): string {
  const labels: Record<string, string> = {
    SUCCESS: '成功', BUSINESS_TERMINAL: '业务终止', FAILED: '失败', ERROR: '失败',
    CANCELLED: '已取消', TIMED_OUT: '已超时', TIMEOUT: '已超时',
    WAITING_USER: '等待用户交互', UNKNOWN: '状态未确认',
  }
  return labels[toolCallStatus(tool)] || '状态未确认'
}

export function toolCallStatusTone(tool: ToolObservation): 'success' | 'warning' | 'danger' | 'info' {
  if (toolCallFailed(tool)) return 'danger'
  const status = toolCallStatus(tool)
  if (status === 'SUCCESS') return 'success'
  if (status === 'BUSINESS_TERMINAL') return 'info'
  return 'warning'
}
