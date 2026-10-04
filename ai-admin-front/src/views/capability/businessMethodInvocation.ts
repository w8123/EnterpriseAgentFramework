import type {
  BusinessMethodInvocationContext,
  BusinessMethodInvocationInputDiagnostic,
  BusinessMethodInvocationOutcome,
  BusinessMethodInvocationStatus,
  BusinessMethodInvocationUnconfirmed,
} from '@/types/businessMethodInvocation'
import type { ToolParameter } from '@/types/tool'
import { logicalToolParameters } from '@/utils/toolParameterTree'

export const INVOCATION_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
export const CONTRACT_HASH_PATTERN = /^[0-9a-f]{64}$/
export const SENSITIVE_VALUE_MASK = '••••••'

export interface InvocationEditorShape {
  parameters: ToolParameter[]
  singleDtoRootName: string | null
}

export interface InvocationStatusPresentation {
  label: string
  tone: 'success' | 'warning' | 'danger' | 'neutral'
  title: string
  description: string
  queryRecommended: boolean
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null
}

function own(object: Record<string, unknown>, key: string) {
  return Object.prototype.hasOwnProperty.call(object, key)
}

function metadata(parameter: ToolParameter): Record<string, unknown> {
  return asRecord(parameter.metadata) || {}
}

function cloneValue<T>(value: T): T {
  if (value === undefined) return value
  try {
    return JSON.parse(JSON.stringify(value)) as T
  } catch {
    return value
  }
}

/**
 * Capability deliberately preserves raw dotted declarations. The trial editor
 * creates an ephemeral tree only for editing; it never changes the accepted
 * declaration or contract hash.
 */
export function logicalInvocationParameters(parameters: readonly ToolParameter[] | null | undefined): ToolParameter[] {
  return logicalToolParameters(parameters)
}

function isObject(parameter: ToolParameter) {
  return ['object', 'map', 'json', 'jsonobject'].includes(normalizeParameterType(parameter))
}

export function isArrayParameter(parameter: ToolParameter) {
  return ['array', 'list', 'set', 'collection'].includes(normalizeParameterType(parameter))
}

export function normalizeParameterType(parameter: ToolParameter): string {
  return String(parameter.type || '').trim().toLowerCase()
}

export function isBooleanParameter(parameter: ToolParameter) {
  return ['boolean', 'bool', 'java.lang.boolean'].includes(normalizeParameterType(parameter))
}

export function isNumericParameter(parameter: ToolParameter) {
  return [
    'integer', 'int', 'long', 'short', 'byte', 'java.lang.integer', 'java.lang.long',
    'number', 'decimal', 'double', 'float', 'bigdecimal', 'java.math.bigdecimal',
  ].includes(normalizeParameterType(parameter))
}

export function isComplexParameter(parameter: ToolParameter) {
  return isObject(parameter) || isArrayParameter(parameter) || Boolean(parameter.children?.length)
}

export function isSensitiveParameter(parameter: ToolParameter) {
  const value = metadata(parameter).sensitive
  return value === true || String(value).toLowerCase() === 'true'
}

export function invocationEditorShape(parameters: readonly ToolParameter[] | null | undefined): InvocationEditorShape {
  const roots = logicalInvocationParameters(parameters)
  const single = roots.length === 1 && isObject(roots[0]) && Boolean(roots[0].children?.length)
  return single
    ? { parameters: roots[0].children || [], singleDtoRootName: roots[0].name }
    : { parameters: roots, singleDtoRootName: null }
}

export function contextIsExecutable(context: BusinessMethodInvocationContext | null | undefined) {
  if (!context) return false
  const hash = String(context.currentContractHash || '')
  return context.executable
    && context.enabled
    && context.credentialAvailable
    && !context.businessIdentityRequired
    && context.sourceAvailability === 'READY'
    && CONTRACT_HASH_PATTERN.test(hash)
    && context.acceptedContractHash === hash
    && context.sourceContractHash === hash
}

export function requiresSideEffectConfirmation(sideEffect: string | null | undefined) {
  return !['NONE', 'READ', 'READ_ONLY'].includes(String(sideEffect || '').trim().toUpperCase())
}

export function sideEffectLabel(sideEffect: string | null | undefined) {
  return ({
    NONE: '无副作用', READ: '只读', READ_ONLY: '只读', IDEMPOTENT_WRITE: '幂等写入',
    WRITE: '写入', IRREVERSIBLE: '不可逆操作',
  } as Record<string, string>)[String(sideEffect || '').trim().toUpperCase()] || '副作用未声明'
}

export function sideEffectTone(sideEffect: string | null | undefined): 'success' | 'warning' | 'danger' | 'neutral' {
  const normalized = String(sideEffect || '').trim().toUpperCase()
  if (['NONE', 'READ', 'READ_ONLY'].includes(normalized)) return 'success'
  if (normalized === 'IRREVERSIBLE') return 'danger'
  return normalized ? 'warning' : 'neutral'
}

function lifecycleIsValid(status: string, stage: string, terminal: unknown) {
  if (typeof terminal !== 'boolean') return false
  switch (status) {
    case 'ACCEPTED': return stage === 'PERSISTED' && !terminal
    case 'DISPATCHING': return stage === 'DISPATCHING' && !terminal
    case 'SUCCEEDED':
    case 'BUSINESS_FAILED': return stage === 'CONFIRMED' && terminal
    case 'NOT_DISPATCHED': return stage === 'NOT_DISPATCHED' && terminal
    case 'UNKNOWN': return stage === 'UNCONFIRMED' && terminal
    default: return false
  }
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** Reject malformed wire values rather than presenting a fabricated lifecycle. */
export function normalizeInvocationOutcome(value: unknown, expectedInvocationId?: string): BusinessMethodInvocationOutcome | null {
  const source = asRecord(value)
  if (!source) return null
  const invocationId = typeof source.invocationId === 'string' ? source.invocationId : ''
  const status = typeof source.status === 'string' ? source.status : ''
  const dispatchStage = typeof source.dispatchStage === 'string' ? source.dispatchStage : ''
  if (
    source.contractVersion !== 1
    || !INVOCATION_ID_PATTERN.test(invocationId)
    || (expectedInvocationId && invocationId !== expectedInvocationId)
    || !lifecycleIsValid(status, dispatchStage, source.terminal)
    || numberOrNull(source.projectId) === null
    || numberOrNull(source.runId) === null
    || typeof source.projectCode !== 'string' || !source.projectCode.trim()
    || typeof source.qualifiedName !== 'string' || !source.qualifiedName.trim()
    || typeof source.traceId !== 'string' || !source.traceId.trim()
    || source.identityMode !== 'PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY'
  ) return null

  return {
    contractVersion: 1,
    invocationId,
    runId: numberOrNull(source.runId),
    traceId: String(source.traceId),
    projectCode: String(source.projectCode),
    projectId: numberOrNull(source.projectId),
    qualifiedName: String(source.qualifiedName),
    identityMode: String(source.identityMode),
    status: status as BusinessMethodInvocationStatus,
    dispatchStage: dispatchStage as BusinessMethodInvocationOutcome['dispatchStage'],
    terminal: source.terminal as boolean,
    code: typeof source.code === 'string' ? source.code : null,
    message: typeof source.message === 'string' ? source.message : null,
    result: source.result,
    resultTruncated: source.resultTruncated === true,
    resultExpiresAtEpochMs: numberOrNull(source.resultExpiresAtEpochMs),
    latencyMs: numberOrNull(source.latencyMs),
  }
}

export function isOutcomeUnconfirmed(value: unknown, expectedInvocationId?: string): value is BusinessMethodInvocationUnconfirmed {
  const source = asRecord(value)
  return Boolean(
    source
    && source.success === false
    && source.code === 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED'
    && typeof source.invocationId === 'string'
    && INVOCATION_ID_PATTERN.test(source.invocationId)
    && (!expectedInvocationId || source.invocationId === expectedInvocationId),
  )
}

export function invocationStatusPresentation(outcome: BusinessMethodInvocationOutcome): InvocationStatusPresentation {
  switch (outcome.status) {
    case 'ACCEPTED': return { label: '已建立，等待派发', tone: 'warning', title: '调用已建立', description: '该调用 ID 已建立，当前尚未确认已向业务系统发出调用。可稍后只读查询。', queryRecommended: true }
    case 'DISPATCHING': return { label: '正在调用', tone: 'warning', title: '调用正在进行', description: '平台正在等待最终结果。请使用同一调用 ID 查询，不要重新提交。', queryRecommended: true }
    case 'SUCCEEDED': return { label: '已成功', tone: 'success', title: '调用已确认成功', description: '业务方法已确认完成。', queryRecommended: false }
    case 'BUSINESS_FAILED': return { label: '业务失败', tone: 'danger', title: '业务系统返回失败', description: '请求已经到达业务执行链路，结果以安全返回内容为准。', queryRecommended: false }
    case 'NOT_DISPATCHED': return { label: '未分派', tone: 'danger', title: '调用未分派', description: '输入或前置校验未通过，业务系统未收到本次调用。', queryRecommended: false }
    case 'UNKNOWN': return { label: '结果未知', tone: 'warning', title: '调用结果未确认', description: '本次可能已经执行，无法确认业务系统是否已完成操作。请查询同一调用 ID，勿重复提交。', queryRecommended: true }
  }
}

function maskedValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(maskedValue)
  const object = asRecord(value)
  if (!object) return value
  return Object.fromEntries(Object.entries(object).map(([key, entry]) => [key, maskedValue(entry)]))
}

function maskByParameters(value: unknown, parameters: readonly ToolParameter[]): unknown {
  const object = asRecord(value)
  if (!object) return value
  const result = { ...object }
  for (const parameter of parameters) {
    if (!parameter?.name || !own(object, parameter.name)) continue
    if (isSensitiveParameter(parameter)) {
      result[parameter.name] = SENSITIVE_VALUE_MASK
      continue
    }
    const raw = object[parameter.name]
    if (Array.isArray(raw) && parameter.children?.length) {
      result[parameter.name] = raw.map((entry) => maskByParameters(entry, parameter.children || []))
    } else if (parameter.children?.length) {
      result[parameter.name] = maskByParameters(raw, parameter.children)
    } else {
      result[parameter.name] = maskedValue(raw)
    }
  }
  return result
}

export function containsSensitiveParameter(parameters: readonly ToolParameter[] | null | undefined) {
  return logicalInvocationParameters(parameters).some(function hasSensitive(parameter) {
    return isSensitiveParameter(parameter) || Boolean(parameter.children?.some(hasSensitive))
  })
}

/** Keeps raw input in memory and presents a separate masked projection when values are hidden. */
export function maskInvocationInput(
  input: Record<string, unknown>,
  parameters: readonly ToolParameter[] | null | undefined,
): Record<string, unknown> {
  const roots = logicalInvocationParameters(parameters)
  const root = roots[0]
  if (roots.length === 1 && root && isObject(root) && root.children?.length) {
    if (own(input, root.name)) return maskByParameters(input, roots) as Record<string, unknown>
    return maskByParameters(input, root.children) as Record<string, unknown>
  }
  return maskByParameters(input, roots) as Record<string, unknown>
}

export function explicitSampleInput(shape: InvocationEditorShape): Record<string, unknown> | null {
  const result: Record<string, unknown> = {}
  for (const parameter of shape.parameters) {
    if (isSensitiveParameter(parameter)) continue
    const source = metadata(parameter)
    const sample = source.example ?? source.examples ?? source.default ?? source.defaultValue
    if (sample !== undefined && sample !== null) result[parameter.name] = cloneValue(sample)
  }
  return Object.keys(result).length ? result : null
}

export function resultInputDiagnostics(result: unknown): BusinessMethodInvocationInputDiagnostic[] {
  const root = asRecord(result)
  const metadataValue = root ? asRecord(root.metadata) : null
  const diagnostics = metadataValue?.inputDiagnostics
  if (!Array.isArray(diagnostics)) return []
  return diagnostics.flatMap((entry) => {
    const item = asRecord(entry)
    const path = typeof item?.path === 'string' ? item.path : ''
    const reason = typeof item?.reason === 'string' ? item.reason : ''
    return path && reason ? [{ path, reason }] : []
  })
}

export function diagnosticReasonLabel(reason: string) {
  return ({
    REQUIRED: '该字段为必填项', UNDECLARED_FIELD: '字段不在当前已接受契约中', MIXED_DTO_BINDING: '单 DTO 不能混用平铺与包装形式',
    TYPE_STRING: '应为文本', TYPE_INTEGER: '应为整数', TYPE_NUMBER: '应为数字', TYPE_BOOLEAN: '应为 true 或 false',
    TYPE_OBJECT: '应为 JSON 对象', TYPE_ARRAY: '应为 JSON 数组', CONST_MISMATCH: '不符合固定值约束',
    ENUM_MISMATCH: '不在允许值范围内', PATTERN_MISMATCH: '不符合格式规则', BELOW_MINIMUM: '小于允许的最小值',
    ABOVE_MAXIMUM: '超过允许的最大值', BELOW_EXCLUSIVE_MINIMUM: '未满足严格最小值',
    ABOVE_EXCLUSIVE_MAXIMUM: '超过严格最大值', MULTIPLE_OF_MISMATCH: '不符合步长约束',
    BELOW_MINLENGTH: '文本长度过短', ABOVE_MAXLENGTH: '文本长度过长', BELOW_MINITEMS: '数组项过少',
    ABOVE_MAXITEMS: '数组项过多', ARRAY_ELEMENT_SHAPE_UNSPECIFIED: '数组元素声明不完整',
    ARRAY_OBJECT_SHAPE_UNSPECIFIED: '数组对象元素声明不完整', UNSUPPORTED_DECLARATION_TYPE: '当前声明类型暂不能验证',
    DECLARATION_SHAPE_UNAVAILABLE: '当前声明结构不可用', INVALID_INPUT: '输入无效',
  } as Record<string, string>)[reason] || '输入未通过安全校验'
}

export function isExpiredResult(outcome: BusinessMethodInvocationOutcome) {
  return outcome.message === '[result-expired]'
}

export function safeJson(value: unknown) {
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return '结果无法按 JSON 展示。'
  }
}
