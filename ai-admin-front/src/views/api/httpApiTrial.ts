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
  const required = Array.isArray(schema?.required) ? schema.required : []
  return Object.entries((schema?.properties || {}) as Record<string, Record<string, unknown>>).map(([name, value]) => ({
    name, type: String(value.type || ''), required: required.includes(name),
    description: typeof value.description === 'string' ? value.description : '', location: 'body',
    metadata: { sensitive: value.format === 'password' || value.writeOnly === true
      || /password|passwd|secret|token|credential|authorization|cookie|private[-_]?key|api[-_]?key/i.test(name) },
  }))
}

/** Mirror the existing bounded scalar contract; server validation remains authoritative. */
export function validateHttpApiBody(detail: HttpApiDetail | null, input: Record<string, unknown>): string {
  const schema = detail?.acceptedContract?.requestBody?.schema
  if (!schema) return '当前请求体契约不可读取。'
  const properties = (schema.properties || {}) as Record<string, Record<string, unknown>>
  if (Object.keys(input).some(name => !Object.prototype.hasOwnProperty.call(properties, name))) return '请求体含未声明字段，请删除后确认。'
  for (const [name, field] of Object.entries(properties)) {
    const value = input[name]
    if (!Object.prototype.hasOwnProperty.call(input, name)) {
      if (Array.isArray(schema.required) && schema.required.includes(name)) return `请填写必填请求体字段：${name}`
      continue
    }
    if (value == null) return `请求体字段不能为 null：${name}；如不填写，请移除字段。`
    if ((field.type === 'string' && typeof value !== 'string')
      || (field.type === 'boolean' && typeof value !== 'boolean')
      || (['integer', 'number'].includes(String(field.type)) && (typeof value !== 'number'
        || !Number.isFinite(value) || (field.type === 'integer' && !Number.isSafeInteger(value))))) return `请求体字段类型错误：${name}`
    if (typeof value === 'string' && ((typeof field.minLength === 'number' && value.length < field.minLength)
      || (typeof field.maxLength === 'number' && value.length > field.maxLength)
      || value.length > 2048 || /[\x00-\x1f\x7f]/.test(value))) return `请求体字段长度或字符不符合约束：${name}`
    if (typeof value === 'number' && ((typeof field.minimum === 'number' && value < field.minimum)
      || (typeof field.maximum === 'number' && value > field.maximum))) return `请求体字段超出数值范围：${name}`
    if ((Object.prototype.hasOwnProperty.call(field, 'const') && field.const !== value)
      || (Array.isArray(field.enum) && !field.enum.includes(value))) return `请求体字段不在允许值中：${name}`
  }
  return ''
}
