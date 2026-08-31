import type { TagProps } from 'element-plus'

export type A2aTone = TagProps['type']

const labels: Record<string, string> = {
  INBOUND: '入站',
  OUTBOUND: '出站',
  BIDIRECTIONAL: '双向',
  DEVELOPMENT: '开发',
  TEST: '测试',
  STAGING: '预发布',
  PRODUCTION: '生产',
  DRAFT: '草稿',
  VALIDATING: '校验中',
  READY: '预检通过',
  PUBLISHED: '已发布',
  SUSPENDED: '已暂停',
  ARCHIVED: '已归档',
  DISCOVERED: '已发现',
  VERIFYING: '验证中',
  REVIEW_REQUIRED: '待审核',
  HEALTHY: '健康',
  DEGRADED: '降级',
  UNREACHABLE: '不可达',
  UNKNOWN: '未知',
  PENDING: '待审核',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  SUPERSEDED: '已替代',
  ACTIVE: '有效',
  DISABLED: '已停用',
  QUARANTINED: '已隔离',
  GRACE: '轮换宽限期',
  REVOKED: '已吊销',
  EXPIRED: '已过期',
  UNTRUSTED: '不可信',
  KNOWN: '已知',
  TRUSTED: '可信',
  PRIVILEGED: '高信任',
  TASK_STATE_SUBMITTED: '已受理',
  TASK_STATE_WORKING: '执行中',
  TASK_STATE_INPUT_REQUIRED: '等待输入',
  TASK_STATE_AUTH_REQUIRED: '等待授权',
  TASK_STATE_COMPLETED: '已完成',
  TASK_STATE_FAILED: '失败',
  TASK_STATE_CANCELED: '已取消',
  TASK_STATE_REJECTED: '已拒绝',
  API_KEY: 'API Key',
  BEARER: 'Bearer Token',
  LOCAL_AGENT: '本地 Agent',
  REMOTE_AGENT: '远程 Agent',
  REMOTE_CLIENT: '远程客户端',
  HMAC: 'HMAC',
  OAUTH2_CLIENT_CREDENTIALS: 'OAuth2 Client Credentials',
  MTLS: 'mTLS',
  ANONYMOUS: '匿名',
  PASSED: '通过',
  FAILED: '失败',
  NOT_RUN: '未执行',
  NOT_CONFIGURED: '未配置',
}

export function a2aLabel(value?: string | null): string {
  if (!value) return '-'
  return labels[value] ?? value
}

export function a2aTone(value?: string | null): A2aTone {
  if (!value) return 'info'
  if (['PUBLISHED', 'ACTIVE', 'TRUSTED', 'PASSED', 'APPROVED', 'HEALTHY', 'TASK_STATE_COMPLETED'].includes(value)) {
    return 'success'
  }
  if (
    [
      'SUSPENDED',
      'VALIDATING',
      'GRACE',
      'KNOWN',
      'TASK_STATE_INPUT_REQUIRED',
      'TASK_STATE_AUTH_REQUIRED',
      'TASK_STATE_WORKING',
      'VERIFYING',
      'REVIEW_REQUIRED',
      'PENDING',
      'DEGRADED',
    ].includes(value)
  ) {
    return 'warning'
  }
  if (
    [
      'FAILED',
      'REVOKED',
      'EXPIRED',
      'QUARANTINED',
      'UNTRUSTED',
      'TASK_STATE_FAILED',
      'TASK_STATE_REJECTED',
    ].includes(value)
  ) {
    return 'danger'
  }
  return 'info'
}

export function a2aTaskTerminal(state?: string | null): boolean {
  return [
    'TASK_STATE_COMPLETED',
    'TASK_STATE_FAILED',
    'TASK_STATE_CANCELED',
    'TASK_STATE_REJECTED',
  ].includes(state ?? '')
}

export function a2aFormatDate(value?: string | null): string {
  if (!value) return '-'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString('zh-CN', { hour12: false })
}

export function a2aFormatBytes(value?: number | null): string {
  if (value === null || value === undefined) return '-'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / (1024 * 1024)).toFixed(1)} MB`
}

export interface A2aTaskCenterDeepLink {
  taskId: string
  direction: 'INBOUND' | 'OUTBOUND'
}

function firstQueryValue(value: unknown): string {
  const candidate = Array.isArray(value) ? value[0] : value
  return typeof candidate === 'string' ? candidate.trim() : ''
}

/**
 * Parses the stable Task Center deep-link contract. Invalid or incomplete query
 * parameters fail closed so the page never guesses which direction owns a task.
 */
export function a2aTaskCenterDeepLink(query: Record<string, unknown>): A2aTaskCenterDeepLink | null {
  const taskId = firstQueryValue(query.taskId)
  const direction = firstQueryValue(query.direction).toUpperCase()
  if (!taskId || (direction !== 'INBOUND' && direction !== 'OUTBOUND')) return null
  return { taskId, direction }
}
