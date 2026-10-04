import type { ToolParameter } from '@/types/tool'

export type ParameterMetadataItem = { label: string; value: string }

const constraintLabels: Array<[string, string]> = [
  ['const', '固定值'], ['format', '格式'], ['pattern', '格式规则'], ['minimum', '最小值'], ['maximum', '最大值'],
  ['exclusiveMinimum', '严格最小值'], ['exclusiveMaximum', '严格最大值'], ['multipleOf', '步长'],
  ['minLength', '最短长度'], ['maxLength', '最长长度'], ['minItems', '最少项'], ['maxItems', '最多项'],
]

/** Renders only known, source-declared parameter metadata; it never infers contract values. */
export function parameterMetadataItems(metadataValue: ToolParameter['metadata']): ParameterMetadataItem[] {
  const metadata = toRecord(metadataValue)
  if (!metadata) return []
  const sensitive = metadata.sensitive === true || String(metadata.sensitive).toLowerCase() === 'true'
  const items: ParameterMetadataItem[] = []
  if (sensitive) items.push({ label: '敏感字段', value: '示例与默认值不展示' })
  if (!sensitive) {
    addDeclaredItem(items, '示例', metadata.example ?? metadata.examples)
    addDeclaredItem(items, '默认值', metadata.default ?? metadata.defaultValue)
  }
  addDeclaredItem(items, '来源提示', metadata.sourceHint)
  addDeclaredItem(items, '字典', metadata.dictType)
  addDeclaredItem(items, '可选值', metadata.enum)

  const constraints = toRecord(metadata.constraints) || metadata
  for (const [key, label] of constraintLabels) addConstraintItem(items, label, constraints[key])
  return items
}

function addDeclaredItem(items: ParameterMetadataItem[], label: string, value: unknown) {
  const rendered = renderedValue(value)
  if (rendered) items.push({ label, value: rendered })
}

function addConstraintItem(items: ParameterMetadataItem[], label: string, value: unknown) {
  const rendered = renderedValue(value)
  if (rendered) items.push({ label: '约束', value: `${label} ${rendered}` })
}

function toRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
}

function renderedValue(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'string') return value.trim() || null
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) {
    const entries = value.map(renderedValue).filter((entry): entry is string => Boolean(entry))
    return entries.length ? entries.join('、') : null
  }
  return null
}
