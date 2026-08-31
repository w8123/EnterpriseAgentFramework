import type { PlatformAuthAuditEventView, PlatformPermissionView } from '@/api/platformAuth'

export interface PlatformPermissionGroup {
  resourceType: string
  permissions: PlatformPermissionView[]
}

export function passwordPolicyIssues(password: string): string[] {
  const issues: string[] = []
  if (password.length < 12 || password.length > 128) issues.push('长度为 12–128 个字符')
  const classes = [/[a-z]/.test(password), /[A-Z]/.test(password), /\d/.test(password), /[^A-Za-z0-9]/.test(password)]
    .filter(Boolean).length
  if (classes < 3) issues.push('至少包含大写、小写、数字、符号中的三类')
  return issues
}

export function groupPlatformPermissions(
  permissions: readonly PlatformPermissionView[],
): PlatformPermissionGroup[] {
  const groups = new Map<string, PlatformPermissionView[]>()
  for (const permission of permissions) {
    const key = permission.resourceType?.trim() || 'OTHER'
    const bucket = groups.get(key) ?? []
    bucket.push(permission)
    groups.set(key, bucket)
  }
  return [...groups.entries()]
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([resourceType, items]) => ({
      resourceType,
      permissions: [...items].sort((left, right) => (
        left.permissionCode.localeCompare(right.permissionCode)
      )),
    }))
}

const AUDIT_LABELS: Record<string, string> = {
  PLATFORM_ACCOUNT_CREATED: '创建平台账号',
  PLATFORM_ACCOUNT_UPDATED: '更新平台账号',
  PLATFORM_ACCOUNT_PASSWORD_RESET: '重置账号密码',
  PLATFORM_ACCOUNT_SESSIONS_REVOKED: '撤销账号会话',
  PLATFORM_USER_ROLE_GRANTS_REPLACED: '调整角色授权',
  PLATFORM_CUSTOM_ROLE_CREATED: '创建自定义角色',
  PLATFORM_CUSTOM_ROLE_UPDATED: '更新自定义角色',
  PLATFORM_AUTH_PROVIDER_SAVED: '更新认证源',
}

export function platformAuditEventLabel(eventType?: string | null): string {
  if (!eventType) return '-'
  return AUDIT_LABELS[eventType] ?? eventType
}

const SENSITIVE_DETAIL_KEY = /(password|secret|token|credential|configjson)/i

export function safeAuditDetailEntries(
  event: Pick<PlatformAuthAuditEventView, 'detailsJson'>,
): Array<[string, string]> {
  if (!event.detailsJson) return []
  try {
    const parsed = JSON.parse(event.detailsJson) as Record<string, unknown>
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') return []
    return Object.entries(parsed)
      .filter(([key]) => !SENSITIVE_DETAIL_KEY.test(key))
      .map(([key, value]) => [key, value == null ? '-' : String(value)])
  } catch {
    return []
  }
}
