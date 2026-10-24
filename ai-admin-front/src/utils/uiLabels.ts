type TagType = 'success' | 'info' | 'warning' | 'danger'

/** 平台通用启用/停用类状态（展示文案；value 仍为后端枚举） */
const COMMON_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '已启用',
  INACTIVE: '未启用',
  DISABLED: '已停用',
  DELETED: '已删除',
}

/** 内置认证源默认展示名称（按编码；与种子数据一致） */
const AUTH_PROVIDER_DEFAULT_NAMES: Record<string, string> = {
  LOCAL: '本地开发登录',
  HEADER: '受信任网关请求头',
  OIDC: '企业 OIDC 登录',
  SAML: '企业 SAML 登录',
}

/** 认证源类型（编码保留英文，附带中文说明） */
const AUTH_PROVIDER_TYPE_LABELS: Record<string, string> = {
  LOCAL: '本地登录',
  HEADER: '网关请求头',
  OIDC: 'OIDC 单点登录',
  SAML: 'SAML 单点登录',
}

/** 平台内置角色（按 role_code；与种子数据一致） */
const PLATFORM_ROLE_LABELS: Record<string, string> = {
  PLATFORM_ADMIN: '平台管理员',
  DEVELOPER: '开发人员',
  OPERATOR: '运营人员',
  MANAGER: '管理人员',
}

/** 平台用户常见英文显示名 → 中文 */
const PLATFORM_USER_DISPLAY_NAME_LABELS: Record<string, string> = {
  'Platform Admin': '平台管理员',
}

export function formatCommonStatusLabel(status?: string | null): string {
  if (status == null || status === '') return '-'
  return COMMON_STATUS_LABELS[status] ?? status
}

export function commonStatusTagType(status?: string | null): TagType {
  if (status === 'ACTIVE') return 'success'
  if (status === 'DISABLED') return 'warning'
  if (status === 'DELETED') return 'danger'
  if (status === 'INACTIVE') return 'info'
  return 'info'
}

export const COMMON_STATUS_SELECT_OPTIONS = [
  { value: 'ACTIVE', label: '已启用' },
  { value: 'INACTIVE', label: '未启用' },
] as const

export const BUSINESS_USER_STATUS_SELECT_OPTIONS = [
  { value: 'ACTIVE', label: '已启用' },
  { value: 'DISABLED', label: '已停用' },
  { value: 'DELETED', label: '已删除' },
] as const

export function formatAuthProviderTypeLabel(type?: string | null): string {
  if (type == null || type === '') return '-'
  return AUTH_PROVIDER_TYPE_LABELS[type] ?? type
}

/** 认证源名称：内置编码优先中文默认名，其余用库中名称 */
export function formatAuthProviderName(providerCode?: string | null, providerName?: string | null): string {
  if (providerCode && AUTH_PROVIDER_DEFAULT_NAMES[providerCode]) {
    return AUTH_PROVIDER_DEFAULT_NAMES[providerCode]
  }
  return providerName?.trim() || '-'
}

export const AUTH_PROVIDER_TYPE_SELECT_OPTIONS = [
  { value: 'LOCAL', label: '本地登录 (LOCAL)' },
  { value: 'HEADER', label: '网关请求头 (HEADER)' },
  { value: 'OIDC', label: 'OIDC 单点登录' },
  { value: 'SAML', label: 'SAML 单点登录' },
] as const

/** 平台角色展示名（内置编码优先中文） */
export function formatPlatformRoleLabel(roleCode?: string | null, roleName?: string | null): string {
  if (roleCode && PLATFORM_ROLE_LABELS[roleCode]) {
    return PLATFORM_ROLE_LABELS[roleCode]
  }
  if (roleName && PLATFORM_USER_DISPLAY_NAME_LABELS[roleName]) {
    return PLATFORM_USER_DISPLAY_NAME_LABELS[roleName]
  }
  return roleName?.trim() || roleCode || '-'
}
