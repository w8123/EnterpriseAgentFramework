import type { StatusTone } from '@/components/common/glassWorkbench'
import type { CapabilityDiffReviewItem } from '@/types/registry'
import type { AssetParameter } from '@/types/assetParameter'

interface AssetPresentation {
  name: string
  title?: string | null
  qualifiedName?: string | null
  description?: string | null
  aiDescription?: string | null
  source?: string | null
  httpMethod?: string | null
  baseUrl?: string | null
  contextPath?: string | null
  endpointPath?: string | null
}
import { formatSideEffectLabel } from '@/utils/capabilityLabels'

export interface CapabilityFieldDiff {
  field: string
  oldValue?: unknown
  newValue?: unknown
}

const FIELD_LABELS: Record<string, string> = {
  title: '展示名称',
  description: '能力说明',
  httpMethod: 'HTTP 方法',
  baseUrl: '服务地址',
  contextPath: '上下文路径',
  endpointPath: '接口路径',
  requestBodyType: '请求类型',
  responseType: '响应类型',
  sideEffect: '副作用等级',
  enabled: '可用状态',
  parameters: '输入参数',
  metadata: '声明元数据',
}

export function capabilityDisplayName(tool?: Pick<AssetPresentation, 'name' | 'title'> | null): string {
  return tool?.title?.trim() || tool?.name?.trim() || '未命名资产'
}

export function capabilityStableName(tool?: Pick<AssetPresentation, 'name' | 'qualifiedName'> | null): string {
  return tool?.qualifiedName?.trim() || tool?.name?.trim() || '-'
}

export function capabilityDescription(
  tool?: Pick<AssetPresentation, 'aiDescription' | 'description'> | null,
): string {
  const candidates = [tool?.aiDescription, tool?.description]
    .map(value => String(value || '').trim())
    .filter(Boolean)
  const readable = candidates.find(value => !looksLikeEncodingDamage(value))
  if (readable) return readable
  if (candidates.length) return '来源说明存在编码异常，请从来源项目重新同步'
  return '尚未补充说明'
}

export function capabilityDescriptionHasEncodingIssue(
  tool?: Pick<AssetPresentation, 'aiDescription' | 'description'> | null,
): boolean {
  return [tool?.aiDescription, tool?.description]
    .some(value => looksLikeEncodingDamage(String(value || '').trim()))
}

export function capabilitySourceLabel(source?: string | null, sourceLocation?: string | null): string {
  if (String(sourceLocation || '').trim().toLowerCase().startsWith('sdk:')) return 'SDK 注册'
  const normalized = String(source || '').trim().toLowerCase()
  if (normalized === 'sdk') return 'SDK 注册'
  if (normalized === 'code') return '代码内置'
  if (normalized === 'scanner') return '源码扫描'
  if (normalized === 'manual') return '平台配置'
  return normalized || '来源未知'
}

export function capabilitySideEffectLabel(sideEffect?: string | null): string {
  return formatSideEffectLabel(sideEffect) === '-' ? '未声明' : formatSideEffectLabel(sideEffect)
}

export function capabilitySideEffectTone(sideEffect?: string | null): StatusTone {
  const normalized = String(sideEffect || '').trim().toUpperCase()
  if (normalized === 'NONE' || normalized === 'READ' || normalized === 'READ_ONLY') return 'success'
  if (normalized === 'IRREVERSIBLE') return 'danger'
  if (normalized === 'WRITE' || normalized === 'IDEMPOTENT_WRITE') return 'warning'
  return 'neutral'
}

export function capabilityEndpoint<T extends AssetPresentation>(tool: T): string {
  const path = joinHttpPath(tool.contextPath, tool.endpointPath)
  if (!tool.baseUrl) return path || '-'
  return `${tool.baseUrl.replace(/\/$/, '')}${path ? `/${path.replace(/^\//, '')}` : ''}`
}

export function capabilityParameterCount(parameters?: AssetParameter[] | null): number {
  return (parameters || []).reduce(
    (total, parameter) => total + 1 + capabilityParameterCount(parameter.children),
    0,
  )
}

export function parseCapabilityFieldDiffs(item: CapabilityDiffReviewItem): CapabilityFieldDiff[] {
  try {
    const parsed = JSON.parse(item.fieldDiffJson || '[]')
    if (!Array.isArray(parsed)) return []
    return parsed.filter(diff => diff && typeof diff.field === 'string') as CapabilityFieldDiff[]
  } catch {
    return []
  }
}

export function capabilityFieldLabel(field: string): string {
  return FIELD_LABELS[field] || field
}

export function prettyCapabilityValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '未设置'
  if (typeof value !== 'string') return JSON.stringify(value, null, 2)
  const trimmed = value.trim()
  if (!trimmed) return '未设置'
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2)
  } catch {
    return value
  }
}

function joinHttpPath(contextPath?: string | null, endpointPath?: string | null): string {
  const parts = [contextPath, endpointPath]
    .map(value => String(value || '').trim())
    .filter(Boolean)
    .map(value => value.replace(/^\/+|\/+$/g, ''))
  return parts.length ? `/${parts.join('/')}` : ''
}

function looksLikeEncodingDamage(value: string): boolean {
  if (!value) return false
  const questionMarks = (value.match(/\?/g) || []).length
  const visibleLength = value.replace(/\s/g, '').length
  return questionMarks >= 6 && questionMarks / Math.max(visibleLength, 1) >= 0.3
}
