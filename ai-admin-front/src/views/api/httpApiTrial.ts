import type { HttpApiConnection, HttpApiDetail, HttpApiInvocationOutcome, HttpApiParameter } from '@/types/httpApi'
import type { ToolParameter } from '@/types/tool'

export function buildHttpApiTrialParameters(parameters: HttpApiParameter[], draft: Record<string, string>) {
  const pathParams: Record<string, unknown> = {}
  const queryParams: Record<string, unknown> = {}
  const errors: Record<string, string> = {}
  for (const parameter of parameters) {
    const key = `${parameter.location}:${parameter.name}`
    const raw = draft[key]?.trim() || ''
    if (!raw) {
      if (parameter.required || parameter.location === 'PATH') errors[key] = '请输入必填参数'
      continue
    }
    let value: unknown = raw
    const type = parameter.schema.type
    if (type === 'integer' || type === 'number') {
      if (!Number.isFinite(Number(raw)) || (type === 'integer' && !Number.isSafeInteger(Number(raw)))) {
        errors[key] = type === 'integer' ? '请输入整数' : '请输入有效数字'
        continue
      }
      value = Number(raw)
    } else if (type === 'boolean') value = raw === 'true'
    if (parameter.location === 'PATH') pathParams[parameter.name] = value
    else if (parameter.location === 'QUERY') queryParams[parameter.name] = value
  }
  return { pathParams, queryParams, errors }
}

export function isCurrentHttpApiEvidence(detail: HttpApiDetail | null,
  connection: HttpApiConnection | null, outcome: HttpApiInvocationOutcome | null,
  sourceAccepted: boolean) {
  return !!outcome && !!detail && !!connection && sourceAccepted && connection.status === 'CONFIGURED'
    && outcome.expectedContractHash === detail.summary.acceptedContractHash
    && outcome.sourceSetRevision === detail.summary.sourceSetRevision
    && outcome.connectionRevision === connection.revision
    && outcome.credentialRevision === connection.credentialRevision
}

export function canStartNewHttpApiAttempt(outcome: HttpApiInvocationOutcome | null) {
  return !outcome || outcome.terminal
}

export function httpApiTrialFailure(error: unknown, operation: 'invoke' | 'query'): string {
  const status = (error as { response?: { status?: number } } | null)?.response?.status
  if (status === 403) return operation === 'invoke'
    ? '请求被调用权限或 API ACL 拒绝；原调用 ID 已保留，请核对权限，勿直接重发。'
    : '无权读取此调用记录；原调用 ID 已保留，不会重新派发。'
  return operation === 'invoke'
    ? '调用结果未确认；请查询同一调用 ID，勿重新提交'
    : '暂未读到此调用记录；无法据此判断请求未发出，请稍后用同一 ID 查询'
}

export function httpApiBodyParameters(detail: HttpApiDetail | null): ToolParameter[] {
  const schema = detail?.acceptedContract?.requestBody?.schema
  if (!schema) return []
  const field = (name: string, value: Record<string, unknown>, required: boolean): ToolParameter => ({
    name, type: String(value.type || (value.properties ? 'object' : '')), required,
    description: typeof value.description === 'string' ? value.description : '', location: 'body',
    metadata: { sensitive: value.format === 'password' || value.writeOnly === true
      || /password|passwd|secret|token|credential|authorization|cookie|private[-_]?key|api[-_]?key/i.test(name) },
    ...(value.properties || value.items ? { children: fields((value.type === 'array' ? value.items : value) as Record<string, unknown>) } : {}),
  })
  const fields = (node: Record<string, unknown>): ToolParameter[] => {
    const required = Array.isArray(node?.required) ? node.required : []
    return Object.entries((node?.properties || {}) as Record<string, Record<string, unknown>>)
      .map(([name, value]) => field(name, value, required.includes(name)))
  }
  return schema.type && schema.type !== 'object'
    ? [field('$body', schema, detail?.acceptedContract?.requestBody?.required === true)] : fields(schema)
}

export function httpApiSideEffectState(detail: HttpApiDetail | null): 'read' | 'write' | 'blocked' {
  const effect = detail?.acceptedContract?.sideEffect
  if (effect === 'NONE' || effect === 'READ_ONLY') return 'read'
  return ['WRITE', 'IDEMPOTENT_WRITE', 'IRREVERSIBLE'].includes(String(effect)) ? 'write' : 'blocked'
}

/** Preview validation only; accepted-schema support and dispatch authorization remain server-owned. */
export function validateHttpApiBody(detail: HttpApiDetail | null, input: unknown): string {
  const body = detail?.acceptedContract?.requestBody
  if (input == null) return body?.required ? '请填写必填 JSON 请求体。' : ''
  if (!body?.schema) return '当前请求体契约不可读取。'
  let encoded: string
  try { encoded = JSON.stringify(input) } catch { return '请输入合法的 JSON 请求体。' }
  if (!encoded || new TextEncoder().encode(encoded).length > 256 * 1024) return '请求体超过 256 KiB 上限。'
  let nodes = 0
  const same = (left: unknown, right: unknown): boolean => {
    if (left === right) return true
    if (Array.isArray(left) && Array.isArray(right)) return left.length === right.length && left.every((value, index) => same(value, right[index]))
    if (left && right && typeof left === 'object' && typeof right === 'object') {
      const a = left as Record<string, unknown>, b = right as Record<string, unknown>
      return Object.keys(a).length === Object.keys(b).length && Object.keys(a).every(key => Object.prototype.hasOwnProperty.call(b, key) && same(a[key], b[key]))
    }
    return false
  }
  const validate = (schema: Record<string, unknown>, value: unknown, path: string, depth: number): string => {
    if (++nodes > 4096 || depth > 6) return '请求体超过层级或节点上限。'
    if (value === null) return schema.nullable === true || schema.type === 'null' ? '' : `请求体字段不能为 null：${path}；如不填写，请移除字段。`
    const type = schema.type || (schema.properties ? 'object' : undefined)
    if ((type === 'object' && (typeof value !== 'object' || Array.isArray(value)))
      || (type === 'array' && !Array.isArray(value)) || (type === 'string' && typeof value !== 'string')
      || (type === 'boolean' && typeof value !== 'boolean')
      || (['integer', 'number'].includes(String(type)) && (typeof value !== 'number' || !Number.isFinite(value)
        || (type === 'integer' && !Number.isSafeInteger(value))))) return `请求体字段类型错误：${path}`
    if ((Object.prototype.hasOwnProperty.call(schema, 'const') && !same(schema.const, value))
      || (Array.isArray(schema.enum) && !schema.enum.some(item => same(item, value)))) return `请求体字段不在允许值中：${path}`
    if (typeof value === 'string' && (value.length > 32768 || (typeof schema.minLength === 'number' && value.length < schema.minLength)
      || (typeof schema.maxLength === 'number' && value.length > schema.maxLength))) return `请求体字段长度不符合约束：${path}`
    if (typeof value === 'number' && (!Number.isFinite(value) || (typeof schema.minimum === 'number' && value < schema.minimum)
      || (typeof schema.maximum === 'number' && value > schema.maximum))) return `请求体字段超出数值范围：${path}`
    if (Array.isArray(value)) {
      if (value.length > 1024 || (typeof schema.minItems === 'number' && value.length < schema.minItems)
        || (typeof schema.maxItems === 'number' && value.length > schema.maxItems)) return `请求体数组数量不符合约束：${path}`
      if (schema.uniqueItems === true && value.some((entry, index) => value.slice(0, index).some(previous => same(previous, entry)))) return `请求体数组含重复项：${path}`
      for (let index = 0; index < value.length; index++) {
        const error = validate((schema.items || { additionalProperties: true }) as Record<string, unknown>, value[index], `${path}[${index}]`, depth + 1)
        if (error) return error
      }
    } else if (value && typeof value === 'object') {
      const object = value as Record<string, unknown>, keys = Object.keys(object)
      if (keys.length > 64 || (typeof schema.minProperties === 'number' && keys.length < schema.minProperties)
        || (typeof schema.maxProperties === 'number' && keys.length > schema.maxProperties)) return `请求体对象字段数量不符合约束：${path}`
      const properties = (schema.properties || {}) as Record<string, Record<string, unknown>>
      for (const name of Array.isArray(schema.required) ? schema.required : []) {
        if (typeof name === 'string' && !Object.prototype.hasOwnProperty.call(object, name)) return `请填写必填请求体字段：${path}.${name}`
      }
      for (const name of keys) {
        const declared = Object.prototype.hasOwnProperty.call(properties, name)
        if (!declared && schema.additionalProperties !== true && (!schema.additionalProperties || typeof schema.additionalProperties !== 'object')) return `请求体含未声明字段：${path}.${name}，请删除后确认。`
        const field = declared ? properties[name]! : typeof schema.additionalProperties === 'object' ? schema.additionalProperties as Record<string, unknown> : { additionalProperties: true }
        const error = validate(field, object[name], `${path}.${name}`, depth + 1)
        if (error) return error
      }
    } else if (!['string', 'number', 'boolean'].includes(typeof value)) return `请输入合法的 JSON 请求体：${path}`
    return ''
  }
  return validate(body.schema, input, '$', 0)
}
