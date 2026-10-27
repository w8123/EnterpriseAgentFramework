import type { WorkflowCanvasSource } from '@/types/agent'
import type {
  DocumentExtractNodeConfig,
  InteractionNodeConfig,
  IntentClassifierNodeConfig,
  KnowledgeWriteNodeConfig,
  StudioFieldSchema,
  VariableAggregateNodeConfig,
} from '@/types/studio'

export function isSdkDefinition(def: WorkflowCanvasSource) {
  const sdkGraph = def.extra?.sdkGraph
  return !!sdkGraph && typeof sdkGraph === 'object'
    && (sdkGraph as Record<string, unknown>).source === 'SDK'
}

export function stringValue(value: unknown) {
  return value == null ? '' : String(value)
}

export function numberValue(value: unknown, fallback: number) {
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

export function arrayValue(value: unknown) {
  return Array.isArray(value) ? value.map((item) => String(item)).filter(Boolean) : []
}

export function objectRecordValue(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return value as Record<string, unknown>
}

export function firstRecordValue(...values: unknown[]): Record<string, unknown> {
  for (const value of values) {
    if (value !== null && value !== undefined) return objectRecordValue(value)
  }
  return {}
}

export function firstNonEmptyText(...values: unknown[]) {
  for (const value of values) {
    const text = stringValue(value).trim()
    if (text) return text
  }
  return ''
}

export function configuredReferenceValue(value: unknown): string {
  const reference = objectRecordValue(value)
  if (Object.keys(reference).length) {
    return firstNonEmptyText(
      reference.qualifiedName,
      reference.name,
      configuredReferenceValue(reference.ref),
      reference.toolName,
    )
  }
  return stringValue(value).trim()
}

export function stringRecord(value: unknown): Record<string, string> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const out: Record<string, string> = {}
  for (const [key, raw] of Object.entries(value)) out[key] = String(raw)
  return out
}

/** Preserve native JSON types for VARIABLE_ASSIGN save/reopen. */
export function jsonValueRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  const out: Record<string, unknown> = {}
  for (const [key, raw] of Object.entries(value)) {
    if (!key) continue
    out[key] = raw
  }
  return out
}

export function schemaValue(value: unknown): StudioFieldSchema[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => {
      const name = stringValue(item.name || item.key)
      return {
        ...item,
        name,
        key: stringValue(item.key || item.name) || name,
        type: fieldTypeValue(item.type),
        required: item.required === true,
        description: stringValue(item.description || item.label),
        defaultValue: stringValue(item.defaultValue),
        source: stringValue(item.source),
        targetPath: stringValue(item.targetPath),
        component: stringValue(item.component),
        datasource: stringValue(item.datasource),
        slotFilling: slotFillingValue(item.slotFilling),
      } as StudioFieldSchema
    })
}

function slotFillingValue(value: unknown): StudioFieldSchema['slotFilling'] {
  const raw = objectRecordValue(value)
  if (!Object.keys(raw).length) return undefined
  const strategies = arrayValue(raw.strategies)
    .map((item) => item.toUpperCase())
    .filter((item) => ['USER_INPUT', 'RULE', 'LLM', 'DICTIONARY'].includes(item)) as NonNullable<StudioFieldSchema['slotFilling']>['strategies']
  const policy = stringValue(raw.confirmPolicy).toUpperCase()
  return {
    enabled: raw.enabled === true,
    strategies: normalizeSlotStrategies(strategies, raw.enabled === true),
    confirmPolicy: ['NEVER', 'ALWAYS'].includes(policy) ? policy as 'NEVER' | 'ALWAYS' : 'LOW_CONFIDENCE',
    confidenceThreshold: numberValue(raw.confidenceThreshold, 0.85),
    llmPrompt: stringValue(raw.llmPrompt),
    modelInstanceId: stringValue(raw.modelInstanceId),
    patterns: arrayValue(raw.patterns),
    dictionaryValues: arrayValue(raw.dictionaryValues),
  }
}

function normalizeSlotStrategies(
  strategies: NonNullable<StudioFieldSchema['slotFilling']>['strategies'],
  enabled: boolean,
): NonNullable<StudioFieldSchema['slotFilling']>['strategies'] {
  const extractionStrategies = strategies.filter((item) => item !== 'USER_INPUT')
  if (enabled && extractionStrategies.length === 0) {
    return ['LLM']
  }
  return extractionStrategies.length ? extractionStrategies : ['LLM']
}

export function fieldTypeValue(value: unknown): StudioFieldSchema['type'] {
  const raw = stringValue(value).toLowerCase()
  return ['number', 'integer', 'boolean', 'object', 'array', 'file'].includes(raw)
    ? raw as StudioFieldSchema['type']
    : 'string'
}

export function interactionTypeValue(value: unknown): InteractionNodeConfig['interactionType'] {
  const raw = stringValue(value).toUpperCase()
  if (['PRESENT_OUTPUT', 'USER_CHOICE', 'CONFIRM_ACTION', 'REVIEW_EDIT'].includes(raw)) {
    return raw as InteractionNodeConfig['interactionType']
  }
  return 'COLLECT_INPUT'
}

export function interactionComponentValue(value: unknown): InteractionNodeConfig['component'] {
  const raw = stringValue(value).toUpperCase()
  if (['DETAIL', 'TABLE', 'CARD', 'REPORT', 'CUSTOM'].includes(raw)) {
    return raw as InteractionNodeConfig['component']
  }
  return 'FORM'
}

export function interactionBindingValue(value: unknown): InteractionNodeConfig['binding'] {
  const raw = objectRecordValue(value)
  const sourceKind = stringValue(raw.sourceKind).toUpperCase()
  if (sourceKind && sourceKind !== 'NONE' && sourceKind !== 'TOOL') {
    throw new Error('不支持的交互来源类型。API 调用请使用 API 节点，交互节点消费其输出。')
  }
  const normalizedSourceKind = sourceKind === 'TOOL' ? 'TOOL' : 'NONE'
  return {
    sourceKind: normalizedSourceKind,
    assetType: raw.assetType === 'BUSINESS_METHOD' ? 'BUSINESS_METHOD' : undefined,
    assetId: raw.assetId == null ? undefined : numberValue(raw.assetId, 0),
    ref: stringValue(raw.ref),
    qualifiedName: stringValue(raw.qualifiedName) || null,
    projectCode: stringValue(raw.projectCode) || null,
    projectId: raw.projectId == null ? null : numberValue(raw.projectId, 0),
    apiNodeId: raw.apiNodeId == null ? null : numberValue(raw.apiNodeId, 0),
    apiMethod: stringValue(raw.apiMethod) || null,
    apiPath: stringValue(raw.apiPath) || null,
    generatedFrom: stringValue(raw.generatedFrom),
    autoCreateCallNode: raw.autoCreateCallNode === true,
    autoCreateDisplayNode: raw.autoCreateDisplayNode === true || (raw.autoCreateDisplayNode == null && raw.autoCreateCallNode === true),
    callNodeId: stringValue(raw.callNodeId),
    displayNodeId: stringValue(raw.displayNodeId),
  }
}

export function classifierStrategyValue(value: unknown): IntentClassifierNodeConfig['strategy'] {
  const strategy = stringValue(value).toUpperCase()
  if (strategy === 'LLM' || strategy === 'HYBRID') return strategy
  return 'KEYWORD'
}

export function classifierClassesValue(value: unknown): IntentClassifierNodeConfig['classes'] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => ({
      id: stringValue(item.id),
      label: stringValue(item.label),
      description: stringValue(item.description),
      keywords: Array.isArray(item.keywords) ? item.keywords.map((keyword) => stringValue(keyword)).filter(Boolean) : [],
    }))
    .filter((item) => !!item.id)
}

export function aggregateItemsValue(value: unknown): VariableAggregateNodeConfig['items'] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is Record<string, unknown> => !!item && typeof item === 'object')
    .map((item) => ({ name: stringValue(item.name), source: stringValue(item.source) }))
    .filter((item) => !!item.name && !!item.source)
}

export function aggregateModeValue(value: unknown): VariableAggregateNodeConfig['mode'] {
  const mode = stringValue(value)
  if (mode === 'array' || mode === 'text') return mode
  return 'object'
}

export function documentFormatValue(value: unknown): DocumentExtractNodeConfig['format'] {
  const format = stringValue(value)
  if (format === 'markdown' || format === 'json') return format
  return 'text'
}

export function knowledgeWriteModeValue(value: unknown): KnowledgeWriteNodeConfig['mode'] {
  return stringValue(value) === 'publish' ? 'publish' : 'draft'
}

export function normalizeParams(value?: Record<string, unknown>) {
  return value || {}
}
