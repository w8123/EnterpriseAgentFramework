import type { ApiMarketVerificationStatus } from '@/types/apiMarket'
import type { StatusTone } from '@/components/common/glassWorkbench'

export const apiMarketCategoryNames: Record<string, string> = {
  AI: 'AI 与智能服务',
  BUSINESS: '商业服务',
  DATA: '公共数据',
  DEVELOPER: '开发者工具',
  FINANCE: '金融',
  GEO: '地图与地理',
  MEDIA: '媒体内容',
  SCIENCE: '科学',
  SOCIAL: '社交',
  TEXT: '文本与翻译',
  WEATHER: '天气',
  OTHER: '其他',
}

export function formatApiMarketCategory(value?: string | null) {
  return apiMarketCategoryNames[value || ''] || value || '其他'
}

export function formatApiMarketAuth(value?: string | null) {
  const labels: Record<string, string> = {
    NONE: '无需认证',
    API_KEY_HEADER: 'API Key · Header',
    API_KEY_QUERY: 'API Key · Query',
    BEARER: 'Bearer Token',
    BASIC: 'Basic Auth',
    OAUTH2: 'OAuth 2.0',
    CUSTOM_HEADERS: '自定义 Header',
  }
  return labels[(value || '').toUpperCase()] || value || '认证未知'
}

export function formatApiMarketPricing(value?: string | null) {
  const labels: Record<string, string> = {
    FREE: '无需付费',
    FREE_TIER: '有免费额度',
    PAID: '付费服务',
    UNKNOWN: '价格待确认',
  }
  return labels[(value || '').toUpperCase()] || value || '价格待确认'
}

export function formatApiMarketVerification(value?: string | null) {
  const labels: Record<string, string> = {
    VERIFIED: '已验证',
    UNVERIFIED: '待验证',
    STALE: '验证已过期',
    FAILED: '验证失败',
  }
  return labels[(value || '').toUpperCase()] || value || '待验证'
}

export function apiMarketVerificationTone(value?: ApiMarketVerificationStatus | null): StatusTone {
  if (value === 'VERIFIED') return 'success'
  if (value === 'FAILED') return 'danger'
  if (value === 'STALE') return 'warning'
  return 'neutral'
}

export function formatApiMarketSpec(value?: string | null) {
  const labels: Record<string, string> = {
    VALID: 'OpenAPI 可用',
    CHANGED: 'Spec 有变更',
    INVALID: 'Spec 无效',
    NONE: '暂无 Spec',
  }
  return labels[(value || '').toUpperCase()] || value || '暂无 Spec'
}

export function formatApiMarketSideEffect(value?: string | null) {
  const labels: Record<string, string> = {
    READ_ONLY: '只读',
    IDEMPOTENT_WRITE: '幂等写入',
    WRITE: '可能写入',
    IRREVERSIBLE: '不可逆操作',
  }
  return labels[(value || '').toUpperCase()] || value || '风险待确认'
}

export function apiMarketSideEffectTone(value?: string | null): StatusTone {
  if (value === 'READ_ONLY') return 'success'
  if (value === 'IRREVERSIBLE') return 'danger'
  if (value === 'WRITE' || value === 'IDEMPOTENT_WRITE') return 'warning'
  return 'neutral'
}

export function formatApiMarketIntegrationStatus(value?: string | null) {
  const labels: Record<string, string> = {
    CONFIGURING: '待配置',
    READY: '来源已选择',
    BROKEN: '异常',
    DISABLED: '已停用',
  }
  return labels[(value || '').toUpperCase()] || value || '未知'
}

export function apiMarketIntegrationTone(value?: string | null): StatusTone {
  if (value === 'READY') return 'neutral'
  if (value === 'BROKEN') return 'danger'
  if (value === 'CONFIGURING') return 'warning'
  return 'neutral'
}
