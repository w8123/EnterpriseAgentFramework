import type { UiRequestV1 } from '../core/conversationTypes'

export type ListCardTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info'

export interface ListCardFieldView {
  key: string
  label: string
  value: string
}

export interface ListCardItemView {
  key: string
  title: string
  subtitle?: string
  status?: {
    label: string
    tone: ListCardTone
  }
  fields: ListCardFieldView[]
}

export interface ListCardPresentation {
  items: ListCardItemView[]
  total: number
  initialVisibleCount: number
  showCount: boolean
  emptyText: string
}

interface ListCardFieldSchema {
  key: string
  label: string
  valueMap: Record<string, string>
  maxItems?: number
  separator: string
  hideWhenEmpty: boolean
}

interface ListCardStatusSchema {
  field: string
  valueMap: Record<string, string>
  toneMap: Record<string, ListCardTone>
}

const BLOCKED_PATH_SEGMENTS = new Set(['__proto__', 'prototype', 'constructor'])
const TONES = new Set<ListCardTone>(['neutral', 'success', 'warning', 'danger', 'info'])

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}

function asText(value: unknown): string | undefined {
  if (value == null) return undefined
  const text = String(value).trim()
  return text || undefined
}

function boundedInteger(value: unknown, fallback: number, min: number, max: number): number {
  const parsed = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(max, Math.max(min, Math.floor(parsed)))
}

function pathSegments(path: string): string[] {
  const normalized = path
    .trim()
    .replace(/^\$\.?/, '')
    .replace(/\[\s*["']?([^\]"']+)["']?\s*\]/g, '.$1')
    .replace(/^\./, '')
  if (!normalized) return []
  const segments = normalized.split('.').filter(Boolean)
  return segments.some((segment) => BLOCKED_PATH_SEGMENTS.has(segment)) ? [] : segments
}

export function readListCardPath(source: unknown, path: string | undefined): unknown {
  if (!path?.trim()) return source
  const segments = pathSegments(path)
  if (!segments.length) return undefined
  let current = source
  for (const segment of segments) {
    if (Array.isArray(current)) {
      const index = Number(segment)
      if (!Number.isInteger(index) || index < 0 || index >= current.length) return undefined
      current = current[index]
      continue
    }
    const record = asRecord(current)
    if (!record || !Object.prototype.hasOwnProperty.call(record, segment)) return undefined
    current = record[segment]
  }
  return current
}

function schemaOf(request: UiRequestV1): Record<string, unknown> {
  const direct = asRecord(request.schema)
  if (direct) return direct
  return asRecord(request.extension?.renderSchema) || {}
}

function resolveItems(data: unknown, schema: Record<string, unknown>): Record<string, unknown>[] {
  const declaredPath = asText(schema.itemsPath ?? schema.dataPath)
  const declared = declaredPath ? readListCardPath(data, declaredPath) : undefined
  const candidate = Array.isArray(declared)
    ? declared
    : Array.isArray(data)
      ? data
      : [
          'records',
          'items',
          'rows',
          'list',
          'data.records',
          'data.items',
          'data.rows',
          'data.list',
          'data',
        ]
          .map((path) => readListCardPath(data, path))
          .find(Array.isArray)

  if (Array.isArray(candidate)) {
    return candidate.map((item) => asRecord(item) || { value: item })
  }
  const record = asRecord(data)
  return record ? [record] : []
}

function resolveTotal(data: unknown, schema: Record<string, unknown>, itemCount: number): number {
  const declaredPath = asText(schema.totalPath ?? schema.totalField)
  const candidates = declaredPath
    ? [readListCardPath(data, declaredPath)]
    : ['total', 'totalCount', 'data.total', 'data.totalCount', 'pagination.total']
        .map((path) => readListCardPath(data, path))
  for (const candidate of candidates) {
    const total = Number(candidate)
    if (Number.isFinite(total) && total >= 0) return Math.floor(total)
  }
  return itemCount
}

function stringMap(value: unknown): Record<string, string> {
  const record = asRecord(value)
  if (!record) return {}
  return Object.fromEntries(
    Object.entries(record)
      .filter(([, item]) => item != null)
      .map(([key, item]) => [key, String(item)]),
  )
}

function toneMap(value: unknown): Record<string, ListCardTone> {
  const values = stringMap(value)
  return Object.fromEntries(
    Object.entries(values)
      .map(([key, tone]) => [key, tone.toLowerCase()])
      .filter((entry): entry is [string, ListCardTone] => TONES.has(entry[1] as ListCardTone)),
  )
}

function resolveFieldSchemas(schema: Record<string, unknown>): ListCardFieldSchema[] {
  if (!Array.isArray(schema.fields)) return []
  return schema.fields.flatMap((item): ListCardFieldSchema[] => {
    if (typeof item === 'string' && item.trim()) {
      return [{
        key: item.trim(),
        label: item.trim(),
        valueMap: {},
        separator: '、',
        hideWhenEmpty: false,
      }]
    }
    const record = asRecord(item)
    const key = asText(record?.key ?? record?.field ?? record?.name)
    if (!record || !key || record.hidden === true) return []
    return [{
      key,
      label: asText(record.label ?? record.title) || key,
      valueMap: stringMap(record.valueMap ?? record.labels),
      maxItems: record.maxItems == null
        ? undefined
        : boundedInteger(record.maxItems, 3, 1, 20),
      separator: asText(record.separator) || '、',
      hideWhenEmpty: record.hideWhenEmpty === true,
    }]
  })
}

function resolveStatusSchema(schema: Record<string, unknown>): ListCardStatusSchema | null {
  const status = asRecord(schema.status) || {}
  const field = asText(status.field ?? schema.statusField ?? schema.badgeField)
  if (!field) return null
  return {
    field,
    valueMap: stringMap(status.valueMap ?? status.labels ?? schema.statusMap),
    toneMap: toneMap(status.toneMap ?? status.tones ?? schema.statusToneMap),
  }
}

function mappingKeys(value: unknown): string[] {
  const key = String(value)
  const normalized = key.trim().toLowerCase()
  const aliases = normalized === '1'
    ? ['true']
    : normalized === '0'
      ? ['false']
      : normalized === 'true'
        ? ['1']
        : normalized === 'false'
          ? ['0']
          : []
  return [...new Set([key, normalized, ...aliases])]
}

function mapValue(value: unknown, mapping: Record<string, string>): string | undefined {
  for (const key of mappingKeys(value)) {
    const mapped = mapping[key]
    if (mapped != null) return mapped
  }
  return undefined
}

function displayValue(value: unknown, field?: ListCardFieldSchema): string {
  if (value == null || value === '') return '-'
  const mapped = mapValue(value, field?.valueMap || {})
  if (mapped != null) return mapped
  if (Array.isArray(value)) {
    const maxItems = field?.maxItems ?? value.length
    const visible = value.slice(0, maxItems).map((item) => displayValue(item))
    const remaining = value.length - visible.length
    return `${visible.join(field?.separator || '、')}${remaining > 0 ? `… +${remaining}` : ''}`
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return String(value)
    }
  }
  return String(value)
}

function fallbackTitleField(row: Record<string, unknown>): string | undefined {
  for (const key of ['title', 'name', 'displayName', 'label']) {
    if (asText(row[key])) return key
  }
  const nameLike = Object.keys(row).find((key) => /(?:name|title|label)$/i.test(key) && asText(row[key]))
  if (nameLike) return nameLike
  return Object.keys(row).find((key) => typeof row[key] === 'string' && asText(row[key]))
}

function defaultFields(
  row: Record<string, unknown>,
  excluded: Set<string>,
): ListCardFieldSchema[] {
  return Object.keys(row)
    .filter((key) => !excluded.has(key))
    .slice(0, 4)
    .map((key) => ({
      key,
      label: key,
      valueMap: {},
      separator: '、',
      hideWhenEmpty: false,
    }))
}

function itemKey(row: Record<string, unknown>, path: string | undefined, index: number): string {
  const value = path ? readListCardPath(row, path) : row.id ?? row.key ?? row.code
  return value == null || value === '' ? `list-item-${index}` : String(value)
}

function statusView(
  row: Record<string, unknown>,
  status: ListCardStatusSchema | null,
): ListCardItemView['status'] {
  if (!status) return undefined
  const value = readListCardPath(row, status.field)
  if (value == null || value === '') return undefined
  return {
    label: mapValue(value, status.valueMap) || displayValue(value),
    tone: mapValue(value, status.toneMap) as ListCardTone | undefined
      ?? 'neutral',
  }
}

export function buildListCardPresentation(request: UiRequestV1): ListCardPresentation {
  const schema = schemaOf(request)
  const rows = resolveItems(request.data, schema)
  const configuredFields = resolveFieldSchemas(schema)
  const status = resolveStatusSchema(schema)
  const declaredTitleField = asText(schema.titleField)
  const subtitleField = asText(schema.subtitleField)
  const itemKeyPath = asText(schema.itemKey ?? schema.rowKey)

  const items = rows.map((row, index): ListCardItemView => {
    const titleField = declaredTitleField || fallbackTitleField(row)
    const excluded = new Set(
      [titleField, subtitleField, status?.field, itemKeyPath].filter((key): key is string => !!key),
    )
    const fields = configuredFields.length ? configuredFields : defaultFields(row, excluded)
    return {
      key: itemKey(row, itemKeyPath, index),
      title: titleField ? displayValue(readListCardPath(row, titleField)) : `#${index + 1}`,
      subtitle: subtitleField ? displayValue(readListCardPath(row, subtitleField)) : undefined,
      status: statusView(row, status),
      fields: fields.flatMap((field): ListCardFieldView[] => {
        const raw = readListCardPath(row, field.key)
        if (field.hideWhenEmpty && (raw == null || raw === '')) return []
        return [{ key: field.key, label: field.label, value: displayValue(raw, field) }]
      }),
    }
  })

  return {
    items,
    total: resolveTotal(request.data, schema, items.length),
    initialVisibleCount: boundedInteger(schema.initialVisibleCount, 5, 1, 50),
    showCount: schema.showCount !== false,
    emptyText: asText(schema.emptyText) || '暂无数据',
  }
}
