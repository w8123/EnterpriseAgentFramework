import type { ModelInstance } from '@/types/model'

export function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

export function highlightSdkCode(value: string) {
  const escaped = escapeHtml(value)
  const tokenPattern = /(\/\/[^\n]*|'[^']*'|\b(?:async|await|return)\b|\b(?:pageBridge|registerAction|queryTeams)\b|\b(?:pageKey|actionKey|title|inputSchema|type|properties|teamName|description|sampleArgs|handler)\b|[{}()[\],:])/g
  return escaped.replace(tokenPattern, (token) => {
    if (token.startsWith('//')) return `<span class="code-comment">${token}</span>`
    if (token.startsWith("'")) return `<span class="code-string">${token}</span>`
    if (/^(async|await|return)$/.test(token)) return `<span class="code-keyword">${token}</span>`
    if (/^(pageBridge|registerAction|queryTeams)$/.test(token)) return `<span class="code-function">${token}</span>`
    if (/^(pageKey|actionKey|title|inputSchema|type|properties|teamName|description|sampleArgs|handler)$/.test(token)) {
      return `<span class="code-property">${token}</span>`
    }
    return `<span class="code-punctuation">${token}</span>`
  })
}

export function workflowKeyPart(value: unknown, fallback: string) {
  const normalized = String(value || '')
    .trim()
    .replace(/\./g, '-')
    .replace(/[^A-Za-z0-9_-]+/g, '-')
    .replace(/[-_]{2,}/g, '-')
    .replace(/^[^A-Za-z0-9]+/, '')
    .replace(/[^A-Za-z0-9]+$/, '')
    .toLowerCase()
  return (normalized || fallback).slice(0, 48)
}

export function parseJsonObject(text: string, label: string) {
  const parsed = JSON.parse(text || '{}')
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`${label} 必须是 JSON 对象`)
  }
  return parsed as Record<string, unknown>
}

export function safeJson(text?: string) {
  if (!text) return {}
  try {
    return JSON.parse(text)
  } catch {
    return {}
  }
}

export function normalizeModelInstanceList(payload: unknown): ModelInstance[] {
  if (Array.isArray(payload)) {
    return payload as ModelInstance[]
  }
  if (payload !== null && typeof payload === 'object' && 'data' in payload) {
    const wrapped = (payload as { data?: unknown }).data
    return Array.isArray(wrapped) ? (wrapped as ModelInstance[]) : []
  }
  return []
}

export function isActiveModelInstance(item: ModelInstance) {
  return String(item.status ?? '').toUpperCase() === 'ACTIVE'
}

export function modelOptionLabel(model: ModelInstance) {
  const modelName = model.modelName || model.id
  return `${model.name || model.id} · ${model.provider || '未知厂商'} · ${modelName}`
}

export function pageAssistantToolUrl(url?: string | null) {
  const value = url?.trim()
  if (!value) return undefined
  return value
}

export function stepStatusTagType(status: string) {
  if (status === 'PASS') return 'success'
  if (status === 'WARN' || status === 'SKIPPED') return 'warning'
  if (status === 'FAIL') return 'danger'
  if (status === 'RUNNING') return 'primary'
  return 'info'
}

export function pageAccessStateLabel(state?: string | null) {
  const value = String(state || '').toUpperCase()
  if (value === 'WAITING_TARGET') return '待确认'
  if (value === 'COMPLETED') return '已完成'
  if (value === 'BLOCKED') return '阻塞'
  return '接入中'
}

export function pageAccessStateTagType(state?: string | null) {
  const value = String(state || '').toUpperCase()
  if (value === 'COMPLETED') return 'success'
  if (value === 'BLOCKED') return 'danger'
  if (value === 'WAITING_TARGET') return 'warning'
  return 'primary'
}

export function formatValidationOverallStatus(summary: Record<string, unknown>) {
  const status = String(summary.overallStatus || '').trim()
  const errors = Array.isArray(summary.errors) ? summary.errors.filter(Boolean) : []
  const warnings = Array.isArray(summary.warnings) ? summary.warnings.filter(Boolean) : []
  if (!status && !errors.length && !warnings.length) return ''
  const parts = [status || 'UNKNOWN']
  if (errors.length) parts.push(`${errors.length} errors`)
  if (warnings.length) parts.push(`${warnings.length} warnings`)
  return parts.join(' · ')
}
