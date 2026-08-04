import type {
  AiCodingConnectionStatus,
  AiCodingExecutionStatus,
  AiCodingExecutorProvider,
  AiCodingTask,
  AiCodingTaskConnection,
} from '@/types/aiCodingTask'

export type AiCodingTagType =
  | 'primary'
  | 'success'
  | 'warning'
  | 'danger'
  | 'info'

export const AI_CODING_EXECUTOR_OPTIONS: ReadonlyArray<{
  value: AiCodingExecutorProvider
  label: string
}> = [
  { value: 'CODEX', label: 'Codex' },
  { value: 'CURSOR', label: 'Cursor' },
  { value: 'TRAE', label: 'Trae' },
  { value: 'CLAUDE_CODE', label: 'Claude Code' },
]

export const AI_CODING_EXECUTION_STATUS_OPTIONS: ReadonlyArray<{
  value: AiCodingExecutionStatus
  label: string
}> = [
  { value: 'READY', label: '待启动' },
  { value: 'RUNNING', label: '执行中' },
  { value: 'WAITING_USER', label: '待回答' },
  { value: 'RESULT_SUBMITTED', label: 'AI 编程工具已提交' },
  { value: 'RESULT_APPLIED', label: 'AI 编程工具已反馈，待平台验证' },
  { value: 'ACCEPTANCE_READY', label: '待人工验收' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'CANCELLED', label: '已取消' },
]

export function aiCodingProviderLabel(
  provider?: AiCodingExecutorProvider | string,
) {
  if (!provider) return '—'
  return AI_CODING_EXECUTOR_OPTIONS.find(
    (option) => option.value === provider,
  )?.label || provider
}

export function aiCodingExecutionStatusLabel(
  status?: AiCodingExecutionStatus | string,
) {
  if (!status) return '—'
  return AI_CODING_EXECUTION_STATUS_OPTIONS.find(
    (option) => option.value === status,
  )?.label || status
}

export function aiCodingConnectionStatusLabel(
  status?: AiCodingConnectionStatus | string,
) {
  if (!status) return '—'
  return {
    WAITING_CONNECT: '待对接',
    ACTIVE: '对接中',
    TIMED_OUT: '对接超时',
    CLOSED: '对接已关闭',
  }[status] || status
}

export function aiCodingConnectionStatusDescription(
  connection?: Pick<
    AiCodingTaskConnection,
    'status' | 'timeoutReason'
  > | null,
) {
  if (!connection) return '—'
  const label = aiCodingConnectionStatusLabel(connection.status)
  if (connection.status !== 'TIMED_OUT') return label
  const reasons: Record<string, string> = {
    ACTIVATION_EXPIRED: '交接码已过期',
    TOKEN_EXPIRED: '任务凭据已过期',
    LEASE_EXPIRED: '心跳已中断',
  }
  const reason = reasons[connection.timeoutReason || '']
  return reason ? `${label}（${reason}）` : label
}

export function aiCodingConnectionCanRestore(
  connection?: Pick<
    AiCodingTaskConnection,
    'status' | 'timeoutReason' | 'activatedAt'
  > | null,
) {
  return Boolean(
    connection?.activatedAt
      && (
        connection.status === 'ACTIVE'
        || (
          connection.status === 'TIMED_OUT'
          && connection.timeoutReason === 'LEASE_EXPIRED'
        )
      ),
  )
}

export function aiCodingExecutionStatusTagType(
  status?: AiCodingExecutionStatus | string,
): AiCodingTagType {
  if (status === 'FAILED') return 'danger'
  if (status === 'WAITING_USER') return 'warning'
  if (status === 'COMPLETED' || status === 'ACCEPTANCE_READY') return 'success'
  if (status === 'CANCELLED') return 'info'
  return status ? 'primary' : 'info'
}

export function aiCodingTaskStatusTagType(
  task?: Pick<AiCodingTask, 'executionStatus' | 'connection'> | null,
): AiCodingTagType {
  if (!task) return 'info'
  if (task.connection.status === 'TIMED_OUT') return 'warning'
  return aiCodingExecutionStatusTagType(task.executionStatus)
}

export function aiCodingExecutionIsTerminal(
  status?: AiCodingExecutionStatus | string,
) {
  return Boolean(
    status && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status),
  )
}

export function aiCodingExecutionAcceptsClientAccess(
  status?: AiCodingExecutionStatus | string,
) {
  return Boolean(
    status
      && status !== 'ACCEPTANCE_READY'
      && !aiCodingExecutionIsTerminal(status),
  )
}
