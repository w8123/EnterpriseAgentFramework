import type { StudioPort, ToolNodeConfig } from '@/types/studio'
import type { HttpApiConnection, HttpApiContract, HttpApiDetail, HttpApiSummary } from '@/types/httpApi'

export interface HttpApiInputTarget {
  key: string
  name: string
  location: 'PATH' | 'QUERY' | 'BODY'
  required: boolean
  type: string
  sensitive?: boolean
}

const SCALAR_TYPES = new Set(['string', 'integer', 'number', 'boolean'])

export function httpApiInputTargets(contract: HttpApiContract | null | undefined): HttpApiInputTarget[] {
  const parameters: HttpApiInputTarget[] = (contract?.parameters || []).filter((parameter) =>
    parameter.location === 'PATH' || parameter.location === 'QUERY').map((parameter) => ({
    key: `${parameter.location === 'PATH' ? 'pathParams' : 'queryParams'}.${parameter.name}`,
    name: parameter.name,
    location: parameter.location as 'PATH' | 'QUERY',
    required: parameter.location === 'PATH' || parameter.required === true,
    type: typeof parameter.schema?.type === 'string' ? parameter.schema.type : 'unknown',
  }))
  const schema = contract?.requestBody?.schema
  if (contract?.identity.method === 'POST' && schema?.type === 'object' && schema.properties
      && typeof schema.properties === 'object' && !Array.isArray(schema.properties)) {
    const required = Array.isArray(schema.required) ? schema.required : []
    for (const [name, raw] of Object.entries(schema.properties as Record<string, unknown>)) {
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) continue
      const field = raw as Record<string, unknown>
      parameters.push({ key: `body.${name}`, name, location: 'BODY', required: required.includes(name),
        type: typeof field.type === 'string' ? field.type : 'unknown',
        sensitive: field.format === 'password' || field.writeOnly === true || /secret|password|token|api.?key|credential/i.test(name) })
    }
  }
  return parameters
}

function postBodyReason(contract: HttpApiContract): string {
  const body = contract.requestBody
  const schema = body?.schema
  const objectKeys = new Set(['type', 'properties', 'required', 'additionalProperties', 'description', 'title'])
  const scalarKeys = new Set(['type', 'enum', 'const', 'minLength', 'maxLength', 'minimum', 'maximum',
    'default', 'description', 'title', 'format', 'nullable', 'readOnly', 'writeOnly'])
  if (!body || body.contentTypes.length !== 1 || body.contentTypes[0].toLowerCase() !== 'application/json'
      || !schema || schema.type !== 'object' || !schema.properties || typeof schema.properties !== 'object'
      || Array.isArray(schema.properties) || Object.keys(schema.properties).length > 64
      || Object.keys(schema).some((key) => !objectKeys.has(key))
      || schema.additionalProperties !== undefined && typeof schema.additionalProperties !== 'boolean') {
    return 'POST 仅支持 application/json 平铺标量对象请求体'
  }
  const fields = schema.properties as Record<string, unknown>
  if (Object.entries(fields).some(([name, raw]) => !name.trim() || name.length > 128 || name.includes('.')
      || !raw || typeof raw !== 'object' || Array.isArray(raw)
      || !SCALAR_TYPES.has(String((raw as Record<string, unknown>).type || ''))
      || (raw as Record<string, unknown>).nullable === true || (raw as Record<string, unknown>).readOnly === true
      || Object.keys(raw).some((key) => !scalarKeys.has(key)))) return 'POST 不支持嵌套、数组或 nullable/readOnly 请求字段'
  if (schema.required !== undefined && (!Array.isArray(schema.required)
      || schema.required.some((name) => typeof name !== 'string' || !Object.prototype.hasOwnProperty.call(fields, name)))) {
    return 'POST 请求体 required 契约无效'
  }
  return ''
}

/** Only schema properties are declarations; examples and observed responses are never field hints. */
export function httpApiOutputFields(contract: HttpApiContract | null | undefined): string[] {
  const response = (contract?.responses || []).find((item) => (/^2\d\d$/.test(item.status) || item.status === 'DEFAULT')
    && item.contentTypes.some((type) => type.toLowerCase().includes('json')))
  const schema = response?.schema
  if (schema?.type !== 'object' || !schema.properties || typeof schema.properties !== 'object'
      || Array.isArray(schema.properties)) return []
  const fields: string[] = []
  const visit = (properties: Record<string, unknown>, prefix = '', depth = 0) => {
    if (depth > 4) return
    for (const [name, raw] of Object.entries(properties)) {
      if (!name || name.includes('.') || !raw || typeof raw !== 'object' || Array.isArray(raw)) continue
      const path = prefix ? `${prefix}.${name}` : name
      fields.push(path)
      const child = raw as Record<string, unknown>
      if (child.type === 'object' && child.properties && typeof child.properties === 'object'
          && !Array.isArray(child.properties)) visit(child.properties as Record<string, unknown>, path, depth + 1)
    }
  }
  visit(schema.properties as Record<string, unknown>)
  return fields
}

export function httpApiOutputPorts(alias: string, contract: HttpApiContract | null | undefined): StudioPort[] {
  const root = alias.trim() || 'tool_output'
  return [
    { id: root, name: root, type: 'any', required: false },
    ...httpApiOutputFields(contract).map((field) => ({
      id: `${root}.${field}`, name: `${root}.${field}`, type: 'any' as const,
      required: false, source: 'API 响应 schema 声明字段',
    })),
  ]
}

export function httpApiReadinessReason(
  summary: HttpApiSummary | null | undefined,
  detail: HttpApiDetail | null | undefined,
  connection: HttpApiConnection | null | undefined,
  scope: { projectId: number | null | undefined; projectCode: string | null | undefined; environment: string | null | undefined },
): string {
  if (!summary || !Number.isInteger(summary.id) || summary.id <= 0 || !summary.qualifiedName) return 'API 稳定标识缺失'
  if (!scope.projectId || !scope.projectCode || !scope.environment) return '当前 Workflow 项目或环境尚未确认'
  if (summary.projectId !== scope.projectId || summary.projectCode !== scope.projectCode
      || summary.environment !== scope.environment) return 'API 不属于当前 Workflow 项目及环境'
  if (!['GET', 'POST'].includes(summary.httpMethod)) return '本批仅支持只读 GET 或明确 WRITE 的 POST JSON API'
  if (!summary.sourceConfirmed || summary.sourceStatus !== 'ACCEPTED'
      || !summary.acceptedContractHash || summary.acceptedContractHash !== summary.candidateContractHash
      || summary.activeSourceCount <= 0) return summary.sourceReason || '来源未确认、冲突或契约发生漂移'
  if (!detail || detail.summary.id !== summary.id || detail.summary.qualifiedName !== summary.qualifiedName
      || detail.summary.sourceSetRevision !== summary.sourceSetRevision
      || detail.summary.acceptedContractHash !== summary.acceptedContractHash
      || detail.summary.candidateContractHash !== summary.candidateContractHash
      || detail.summary.sourceStatus !== 'ACCEPTED' || !detail.summary.sourceConfirmed
      || detail.summary.activeSourceCount <= 0
      || detail.summary.projectId !== summary.projectId
      || detail.summary.projectCode !== summary.projectCode
      || detail.summary.environment !== summary.environment
      || detail.summary.httpMethod !== summary.httpMethod
      || detail.summary.routeTemplate !== summary.routeTemplate) return 'API 详情已变化，请刷新候选'
  const contract = detail.acceptedContract
  if (!contract || contract.identity?.method !== summary.httpMethod
      || contract.identity.routeTemplate !== summary.routeTemplate) return '已接纳契约与目录不一致'
  if (summary.httpMethod === 'GET') {
    if (!['READ_ONLY', 'NONE'].includes(contract.sideEffect)) return '该 API 未被明确声明为只读'
    if (contract.requestBody) return '只读 GET 不支持请求体'
  } else {
    if (summary.sourceKinds?.includes('API_MARKET_OPERATION') || /:market:[a-f0-9]{64}$/.test(summary.qualifiedName)) {
      return '本批不支持市场 WRITE API'
    }
    if (contract.sideEffect !== 'WRITE') return 'POST API 必须由来源明确声明为 WRITE'
    const bodyReason = postBodyReason(contract)
    if (bodyReason) return bodyReason
  }
  if ((contract.parameters || []).some((parameter) =>
    !['PATH', 'QUERY'].includes(parameter.location)
      || !parameter.name || !SCALAR_TYPES.has(String(parameter.schema?.type || '')))) {
    return '本批只支持标量 path/query 参数'
  }
  if (!connection || connection.qualifiedName !== summary.qualifiedName
      || connection.projectId !== summary.projectId || connection.projectCode !== summary.projectCode
      || connection.environment !== summary.environment) return '连接身份不一致，请刷新候选'
  if (connection.status !== 'CONFIGURED' || !connection.revision || connection.blockingReason) {
    return connection.blockingReason || '连接尚未达到可用状态'
  }
  if (summary.sourceKinds?.includes('API_MARKET_OPERATION') || /:market:[a-f0-9]{64}$/.test(summary.qualifiedName)) {
    const proof = connection.verification
    if (proof?.status !== 'VERIFIED' || proof.apiId !== summary.id
        || proof.acceptedContractHash !== summary.acceptedContractHash
        || proof.sourceSetRevision !== summary.sourceSetRevision
        || proof.connectionRevision !== connection.revision
        || proof.credentialRevision !== connection.credentialRevision) {
      return proof?.reason || '当前市场 API 尚未通过真实 Console GET 验证，请在 API 详情完成验证'
    }
  }
  return ''
}

export function applyHttpApiSelection(config: ToolNodeConfig, detail: HttpApiDetail) {
  const targets = httpApiInputTargets(detail.acceptedContract)
  const old = config.httpApiAssetId === detail.summary.id ? config.inputMapping || {} : {}
  const mapping: Record<string, unknown> = {}
  for (const target of targets) mapping[target.key] = Object.prototype.hasOwnProperty.call(old, target.key) ? old[target.key] : ''
  config.ref = detail.summary.qualifiedName
  config.qualifiedName = detail.summary.qualifiedName
  config.projectCode = detail.summary.projectCode
  config.httpApiAssetId = detail.summary.id
  config.assetReference = { kind: 'TOOL', assetType: 'HTTP_API', assetId: detail.summary.id,
    name: detail.summary.qualifiedName, qualifiedName: detail.summary.qualifiedName, projectCode: detail.summary.projectCode }
  config.inputMapping = mapping
  config.argumentSource = 'inputMapping'
  config.credentialRef = ''
  config.mappingNote = ''
  config.runtimeConfigExtras = {}
  config.nestedConfigExtras = {}
  return targets
}
