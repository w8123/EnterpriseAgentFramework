/**
 * 手工添加 HTTP API：表单模型、curl 解析、JSON 示例推断结构、Swagger 2.0 转 OpenAPI 3。
 * 只保留接口结构；Token、Cookie、密码等取值一律丢弃，鉴权由项目「接口鉴权」统一配置。
 */

export type ManualHttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
export type ManualParameterType = 'string' | 'integer' | 'number' | 'boolean'
export type ManualAuthMode = 'UNKNOWN' | 'NONE' | 'BEARER' | 'API_KEY_HEADER'
export type ManualSideEffect = 'READ_ONLY' | 'IDEMPOTENT_WRITE' | 'WRITE' | 'IRREVERSIBLE'

export interface ManualParameterDraft {
  name: string
  location: 'PATH' | 'QUERY'
  type: ManualParameterType
  required: boolean
  description: string
}

export interface ManualHttpApiDraft {
  httpMethod: ManualHttpMethod
  path: string
  sideEffect: ManualSideEffect
  parameters: ManualParameterDraft[]
  /** JSON 示例或 JSON Schema 文本；GET 时忽略 */
  bodyText: string
  bodyContentType: 'application/json' | 'text/plain'
  responseType: 'object' | 'array'
  authMode: ManualAuthMode
  authHeaderName: string
  origin: 'form' | 'curl'
}

export interface ManualHttpApiPayload {
  httpMethod: ManualHttpMethod
  path: string
  sideEffect: ManualSideEffect
  origin: 'form' | 'curl'
  parameters: Array<{ name: string; location: 'PATH' | 'QUERY'; type: ManualParameterType; required: boolean; description?: string }>
  requestBody: { contentType: string; required: boolean; schema: JsonSchema } | null
  responseType: 'object' | 'array'
  authentication: { mode: ManualAuthMode; headerName?: string }
}

export type JsonSchema = { [key: string]: unknown }

export const MANUAL_METHODS: ManualHttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']

export function emptyManualDraft(): ManualHttpApiDraft {
  return { httpMethod: 'GET', path: '', sideEffect: 'READ_ONLY', parameters: [], bodyText: '',
    bodyContentType: 'application/json', responseType: 'object', authMode: 'UNKNOWN', authHeaderName: '', origin: 'form' }
}

export function defaultSideEffect(method: ManualHttpMethod): ManualSideEffect {
  return method === 'GET' ? 'READ_ONLY' : 'WRITE'
}

const ROUTE_PARAMETER = /\{([A-Za-z][A-Za-z0-9_.-]*)\}/g

/** 路径里的 {占位符} 自动变成路径参数，已删掉的占位符同步移除。 */
export function syncPathParameters(path: string, parameters: ManualParameterDraft[]): ManualParameterDraft[] {
  const names = Array.from(path.matchAll(ROUTE_PARAMETER), match => match[1])
  const query = parameters.filter(item => item.location === 'QUERY')
  const existing = new Map(parameters.filter(item => item.location === 'PATH').map(item => [item.name, item]))
  const pathParameters = names.filter((name, index) => names.indexOf(name) === index).map(name => existing.get(name)
    || { name, location: 'PATH' as const, type: 'string' as const, required: true, description: '' })
  return [...pathParameters, ...query]
}

/** 去掉域名和项目 context-path，只留 / 开头的接口路径。 */
export function normalizeApiPath(raw: string, contextPath?: string | null): string {
  let value = raw.trim()
  const absolute = value.match(/^https?:\/\/[^/?#]+(.*)$/i)
  if (absolute) value = absolute[1] || '/'
  value = value.split(/[?#]/)[0]
  if (!value.startsWith('/')) value = `/${value}`
  value = value.replace(/\/+/g, '/')
  const context = (contextPath || '').trim().replace(/\/+$/, '')
  if (context && context !== '/' && (value === context || value.startsWith(`${context}/`))) {
    value = value.slice(context.length) || '/'
  }
  if (value.length > 1 && value.endsWith('/')) value = value.slice(0, -1)
  // 把 Spring/Express 风格的 :id 写法转成 {id}
  return value.replace(/\/:([A-Za-z][A-Za-z0-9_]*)/g, '/{$1}')
}

export function inferScalarType(value: string): ManualParameterType {
  if (/^-?\d{1,15}$/.test(value)) return 'integer'
  if (/^-?\d+\.\d+$/.test(value)) return 'number'
  if (value === 'true' || value === 'false') return 'boolean'
  return 'string'
}

const SECRET_NAME = /(pass(word)?|pwd|token|secret|api[-_]?key|access[-_]?key|credential|session|cookie|signature|auth)/i

const SCHEMA_KEYS = new Set(['type', 'properties', 'items', 'required', 'description', 'title', 'format', 'enum',
  'nullable', 'minimum', 'maximum', 'minLength', 'maxLength', 'minItems', 'maxItems', 'additionalProperties', 'default'])

/** 由 JSON 示例推断 JSON Schema；如果文本本身就是 Schema（含 type 或 properties），直接使用。 */
export function schemaFromJsonText(text: string): JsonSchema {
  const parsed = JSON.parse(text) as unknown
  if (isPlainObject(parsed) && typeof parsed.type === 'string'
    && ['object', 'array', 'string', 'integer', 'number', 'boolean'].includes(parsed.type)
    && Object.keys(parsed).every(key => SCHEMA_KEYS.has(key))) {
    return parsed
  }
  return inferSchema(parsed, 0)
}

export function inferSchema(value: unknown, depth = 0): JsonSchema {
  if (depth > 8) return {}
  if (Array.isArray(value)) {
    const first = value.find(item => item !== null && item !== undefined)
    return { type: 'array', items: first === undefined ? { type: 'string' } : inferSchema(first, depth + 1) }
  }
  if (isPlainObject(value)) {
    const properties: Record<string, JsonSchema> = {}
    for (const [key, item] of Object.entries(value).slice(0, 200)) {
      if (!key.trim() || key.length > 128) continue
      properties[key] = item === null ? { type: 'string', nullable: true } : inferSchema(item, depth + 1)
    }
    return { type: 'object', properties }
  }
  if (typeof value === 'number') return { type: Number.isInteger(value) ? 'integer' : 'number' }
  if (typeof value === 'boolean') return { type: 'boolean' }
  return { type: 'string' }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 按 shell 规则切分 curl 命令（支持单双引号、反斜杠续行、$'...'）。 */
export function tokenizeShell(input: string): string[] {
  const text = input.replace(/\\\r?\n/g, ' ').replace(/\^\r?\n/g, ' ')
  const tokens: string[] = []
  let current = ''
  let quote: '"' | "'" | null = null
  let started = false
  for (let index = 0; index < text.length; index++) {
    const char = text[index]
    if (quote) {
      if (char === quote) { quote = null; continue }
      if (char === '\\' && quote === '"' && index + 1 < text.length && '"\\$`'.includes(text[index + 1])) {
        current += text[++index]; continue
      }
      current += char
      continue
    }
    if (char === '$' && text[index + 1] === "'") { quote = "'"; started = true; index++; continue }
    if (char === '"' || char === "'") { quote = char; started = true; continue }
    if (char === '\\' && index + 1 < text.length) { current += text[++index]; started = true; continue }
    if (/\s/.test(char)) {
      if (started) { tokens.push(current); current = ''; started = false }
      continue
    }
    current += char
    started = true
  }
  if (quote) throw new Error('curl 命令里的引号没有闭合')
  if (started) tokens.push(current)
  return tokens
}

export interface CurlParseResult {
  draft: ManualHttpApiDraft
  /** 被丢弃的敏感信息提示（只说名字，不含取值） */
  droppedSecrets: string[]
  /** curl 里的服务地址，用来提醒是否和项目地址一致 */
  origin: string | null
}

const DATA_FLAGS = new Set(['-d', '--data', '--data-raw', '--data-binary', '--data-ascii', '--data-urlencode', '--json'])
const VALUE_FLAGS = new Set(['-H', '--header', '-X', '--request', '-u', '--user', '-b', '--cookie', '-A', '--user-agent',
  '-e', '--referer', '-o', '--output', '-m', '--max-time', '--connect-timeout', '-x', '--proxy', '--url', '-F', '--form',
  '-w', '--write-out', '-T', '--upload-file', '--retry', '-c', '--cookie-jar', '--cacert', '--cert', '--key', '-E', '-K', '--config'])

/** 把浏览器「复制为 cURL」的内容解析成表单草稿。 */
export function parseCurl(input: string, contextPath?: string | null): CurlParseResult {
  const tokens = tokenizeShell(input.trim())
  if (!tokens.length || tokens[0].toLowerCase() !== 'curl') throw new Error('请粘贴以 curl 开头的命令')
  let method: string | null = null
  let url: string | null = null
  const headers: Array<[string, string]> = []
  const data: string[] = []
  let forceGet = false
  let jsonFlag = false
  const dropped = new Set<string>()
  for (let index = 1; index < tokens.length; index++) {
    const token = tokens[index]
    const take = () => { if (index + 1 >= tokens.length) throw new Error(`${token} 后面缺少取值`); return tokens[++index] }
    if (token === '-X' || token === '--request') method = take().toUpperCase()
    else if (token.startsWith('-X') && token.length > 2) method = token.slice(2).toUpperCase()
    else if (token === '-H' || token === '--header') {
      const raw = take(); const split = raw.indexOf(':')
      if (split > 0) headers.push([raw.slice(0, split).trim(), raw.slice(split + 1).trim()])
    } else if (DATA_FLAGS.has(token)) { if (token === '--json') jsonFlag = true; data.push(take()) }
    else if (token.startsWith('--data') && token.includes('=')) data.push(token.slice(token.indexOf('=') + 1))
    else if (token === '-G' || token === '--get') forceGet = true
    else if (token === '-u' || token === '--user') { take(); dropped.add('账号密码（-u）') }
    else if (token === '-b' || token === '--cookie') { take(); dropped.add('Cookie') }
    else if (token === '-F' || token === '--form') throw new Error('暂不支持 multipart 表单（-F）接口，请改用手动填写')
    else if (token === '--url') url = take()
    else if (VALUE_FLAGS.has(token)) take()
    else if (token.startsWith('-')) continue
    else if (!url) url = token
  }
  if (!url) throw new Error('curl 命令里没有找到请求地址')
  let parsed: URL
  try { parsed = new URL(/^https?:\/\//i.test(url) ? url : `http://placeholder.invalid${url.startsWith('/') ? '' : '/'}${url}`) }
  catch { throw new Error('请求地址无法解析') }
  const resolvedMethod = (method || (data.length && !forceGet ? 'POST' : 'GET')) as ManualHttpMethod
  if (!MANUAL_METHODS.includes(resolvedMethod)) throw new Error(`暂不支持 ${resolvedMethod} 请求`)

  const draft = emptyManualDraft()
  draft.origin = 'curl'
  draft.httpMethod = resolvedMethod
  draft.sideEffect = defaultSideEffect(resolvedMethod)
  draft.path = normalizeApiPath(decodeURIComponent(parsed.pathname), contextPath)
  const query: ManualParameterDraft[] = []
  const addQuery = (name: string, value: string) => {
    if (!name || query.some(item => item.name === name)) return
    if (SECRET_NAME.test(name)) dropped.add(`查询参数 ${name} 的取值`)
    query.push({ name, location: 'QUERY', type: SECRET_NAME.test(name) ? 'string' : inferScalarType(value), required: false, description: '' })
  }
  parsed.searchParams.forEach((value, name) => addQuery(name, value))

  let contentType = ''
  for (const [name, value] of headers) {
    const lower = name.toLowerCase()
    if (lower === 'content-type') contentType = value.toLowerCase()
    else if (lower === 'authorization') {
      if (/^bearer\s+/i.test(value)) { draft.authMode = 'BEARER'; dropped.add('Bearer 令牌') }
      else dropped.add('Authorization 请求头')
    } else if (lower === 'cookie') dropped.add('Cookie')
    else if (/(api[-_]?key|access[-_]?key|token|secret)/i.test(lower) && draft.authMode !== 'BEARER') {
      draft.authMode = 'API_KEY_HEADER'; draft.authHeaderName = name; dropped.add(`${name} 请求头`)
    }
  }

  if (data.length) {
    const body = data.join('&')
    if (forceGet || resolvedMethod === 'GET') {
      new URLSearchParams(body).forEach((value, name) => addQuery(name, value))
    } else if (jsonFlag || contentType.includes('json') || /^\s*[[{]/.test(body)) {
      try { draft.bodyText = JSON.stringify(inferSchema(JSON.parse(body)), null, 2) }
      catch { throw new Error('请求体不是合法 JSON，请检查后再试') }
      draft.bodyContentType = 'application/json'
    } else if (contentType.includes('x-www-form-urlencoded') || (!contentType && body.includes('='))) {
      throw new Error('暂不支持表单编码（x-www-form-urlencoded）的请求体，请改用 JSON 或手动填写')
    } else {
      draft.bodyText = JSON.stringify({ type: 'string' }, null, 2)
      draft.bodyContentType = 'text/plain'
    }
  }
  draft.parameters = syncPathParameters(draft.path, query)
  return { draft, droppedSecrets: Array.from(dropped), origin: /^https?:\/\//i.test(url) ? parsed.origin : null }
}

/** 表单草稿 → 提交给后端的结构；校验失败抛出中文错误。 */
export function buildManualPayload(draft: ManualHttpApiDraft, contextPath?: string | null): ManualHttpApiPayload {
  if (!MANUAL_METHODS.includes(draft.httpMethod)) throw new Error('请选择请求方法')
  if (!draft.path.trim()) throw new Error('请填写 API 路径')
  const path = normalizeApiPath(draft.path, contextPath)
  const parameters = syncPathParameters(path, draft.parameters)
  const names = new Set<string>()
  for (const item of parameters) {
    const name = item.name.trim()
    if (!name) throw new Error('参数名称不能为空')
    const key = `${item.location}:${name}`
    if (names.has(key)) throw new Error(`参数 ${name} 重复`)
    names.add(key)
  }
  let requestBody: ManualHttpApiPayload['requestBody'] = null
  if (draft.httpMethod !== 'GET' && draft.bodyText.trim()) {
    let schema: JsonSchema
    if (draft.bodyContentType === 'text/plain') schema = { type: 'string' }
    else {
      try { schema = schemaFromJsonText(draft.bodyText) }
      catch { throw new Error('请求体需要是 JSON 示例或 JSON Schema') }
    }
    requestBody = { contentType: draft.bodyContentType, required: true, schema }
  }
  if (draft.authMode === 'API_KEY_HEADER' && !/^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,128}$/.test(draft.authHeaderName.trim())) {
    throw new Error('请填写 API Key 所在的请求头名称')
  }
  return {
    httpMethod: draft.httpMethod,
    path,
    sideEffect: draft.sideEffect,
    origin: draft.origin,
    parameters: parameters.map(item => ({ name: item.name.trim(), location: item.location, type: item.type,
      required: item.location === 'PATH' ? true : item.required, ...(item.description.trim() ? { description: item.description.trim() } : {}) })),
    requestBody,
    responseType: draft.responseType,
    authentication: draft.authMode === 'API_KEY_HEADER'
      ? { mode: draft.authMode, headerName: draft.authHeaderName.trim() } : { mode: draft.authMode },
  }
}

/* ---------------- Swagger 2.0 → OpenAPI 3（常见写法） ---------------- */

type JsonObject = Record<string, unknown>

function rewriteRefs(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(rewriteRefs)
  if (!isPlainObject(value)) return value
  const result: JsonObject = {}
  for (const [key, item] of Object.entries(value)) {
    if (key === '$ref' && typeof item === 'string') result[key] = item.replace(/^#\/definitions\//, '#/components/schemas/')
    else result[key] = rewriteRefs(item)
  }
  return result
}

const PARAMETER_SCHEMA_KEYS = ['type', 'format', 'items', 'enum', 'default', 'minimum', 'maximum', 'minLength', 'maxLength', 'pattern']

export function isSwagger2(document: unknown): boolean {
  return isPlainObject(document) && typeof document.swagger === 'string' && document.swagger.startsWith('2')
}

/** 把 Swagger 2.0 文档转换成 OpenAPI 3.0；表单参数（formData）接口会被跳过并计数。 */
export function convertSwagger2(document: JsonObject): { openapi: JsonObject; skipped: number } {
  const doc = rewriteRefs(document) as JsonObject
  const globalConsumes = Array.isArray(doc.consumes) ? doc.consumes as string[] : ['application/json']
  const globalProduces = Array.isArray(doc.produces) ? doc.produces as string[] : ['application/json']
  const basePath = typeof doc.basePath === 'string' && doc.basePath !== '/' ? doc.basePath.replace(/\/+$/, '') : ''
  const sharedParameters = isPlainObject(doc.parameters) ? doc.parameters as Record<string, JsonObject> : {}
  let skipped = 0
  const paths: JsonObject = {}
  for (const [path, rawItem] of Object.entries(isPlainObject(doc.paths) ? doc.paths : {})) {
    if (!isPlainObject(rawItem)) continue
    const item: JsonObject = {}
    const pathLevel = Array.isArray(rawItem.parameters) ? rawItem.parameters as JsonObject[] : []
    for (const [method, rawOperation] of Object.entries(rawItem)) {
      if (!['get', 'post', 'put', 'patch', 'delete', 'head', 'options'].includes(method) || !isPlainObject(rawOperation)) continue
      const operation: JsonObject = { ...rawOperation }
      const resolved = [...pathLevel, ...(Array.isArray(rawOperation.parameters) ? rawOperation.parameters as JsonObject[] : [])]
        .map(parameter => typeof parameter.$ref === 'string' && parameter.$ref.startsWith('#/parameters/')
          ? sharedParameters[parameter.$ref.slice('#/parameters/'.length)] || parameter : parameter)
      if (resolved.some(parameter => parameter.in === 'formData')) { skipped++; continue }
      const consumes = Array.isArray(rawOperation.consumes) ? rawOperation.consumes as string[] : globalConsumes
      const produces = Array.isArray(rawOperation.produces) ? rawOperation.produces as string[] : globalProduces
      const parameters: JsonObject[] = []
      for (const parameter of resolved) {
        if (parameter.in === 'body') {
          operation.requestBody = { required: parameter.required === true,
            content: Object.fromEntries((consumes.length ? consumes : ['application/json']).map(media => [media, { schema: parameter.schema || {} }])),
            ...(parameter.description ? { description: parameter.description } : {}) }
          continue
        }
        const schema: JsonObject = {}
        for (const key of PARAMETER_SCHEMA_KEYS) if (parameter[key] !== undefined) schema[key] = parameter[key]
        const next: JsonObject = { name: parameter.name, in: parameter.in, required: parameter.in === 'path' ? true : parameter.required === true, schema }
        if (parameter.description) next.description = parameter.description
        parameters.push(next)
      }
      delete operation.consumes; delete operation.produces
      operation.parameters = parameters
      const responses: JsonObject = {}
      for (const [status, rawResponse] of Object.entries(isPlainObject(rawOperation.responses) ? rawOperation.responses : {})) {
        if (!isPlainObject(rawResponse)) continue
        const response: JsonObject = { description: rawResponse.description || '' }
        if (rawResponse.schema) response.content = Object.fromEntries((produces.length ? produces : ['application/json'])
          .map(media => [media, { schema: rawResponse.schema }]))
        responses[status] = response
      }
      operation.responses = Object.keys(responses).length ? responses : { 200: { description: 'OK' } }
      item[method] = operation
    }
    if (Object.keys(item).length) paths[`${basePath}${path}`] = item
  }
  const securitySchemes: JsonObject = {}
  for (const [name, raw] of Object.entries(isPlainObject(doc.securityDefinitions) ? doc.securityDefinitions : {})) {
    if (!isPlainObject(raw)) continue
    if (raw.type === 'apiKey') securitySchemes[name] = { type: 'apiKey', name: raw.name, in: raw.in }
    else if (raw.type === 'basic') securitySchemes[name] = { type: 'http', scheme: 'basic' }
    else if (raw.type === 'oauth2') {
      const flow = raw.flow === 'accessCode' ? 'authorizationCode' : raw.flow === 'application' ? 'clientCredentials' : String(raw.flow || 'implicit')
      securitySchemes[name] = { type: 'oauth2', flows: { [flow]: { ...(raw.authorizationUrl ? { authorizationUrl: raw.authorizationUrl } : {}),
        ...(raw.tokenUrl ? { tokenUrl: raw.tokenUrl } : {}), scopes: raw.scopes || {} } } }
    }
  }
  const info = isPlainObject(doc.info) ? doc.info : {}
  const openapi: JsonObject = {
    openapi: '3.0.3',
    info: { title: info.title || 'API', version: info.version || '1.0.0', ...(info.description ? { description: info.description } : {}) },
    paths,
    components: { schemas: isPlainObject(doc.definitions) ? doc.definitions : {}, ...(Object.keys(securitySchemes).length ? { securitySchemes } : {}) },
  }
  if (Array.isArray(doc.security)) openapi.security = doc.security
  if (Array.isArray(doc.tags)) openapi.tags = doc.tags
  return { openapi, skipped }
}

/** 准备上传的 OpenAPI 文档：JSON 里的 Swagger 2.0 自动转换；YAML 原样上传（需为 OpenAPI 3）。 */
export function prepareOpenApiDocument(fileName: string, content: string): { specFile: string; specContent: string; note: string } {
  const text = content.trim()
  if (!text) throw new Error('文档内容为空')
  if (text.startsWith('{')) {
    let parsed: unknown
    try { parsed = JSON.parse(text) } catch { throw new Error('JSON 文档格式有误') }
    if (isSwagger2(parsed)) {
      const { openapi, skipped } = convertSwagger2(parsed as JsonObject)
      return { specFile: fileName.replace(/\.(json|ya?ml)$/i, '') + '.openapi3.json', specContent: JSON.stringify(openapi),
        note: `已把 Swagger 2.0 转成 OpenAPI 3.0${skipped ? `，跳过 ${skipped} 个表单上传接口` : ''}` }
    }
    if (!isPlainObject(parsed) || typeof parsed.openapi !== 'string' || !parsed.openapi.startsWith('3')) {
      throw new Error('这不是 OpenAPI 3 或 Swagger 2.0 文档')
    }
    return { specFile: fileName, specContent: text, note: '' }
  }
  if (/^\s*swagger\s*:\s*['"]?2/m.test(text)) {
    throw new Error('YAML 格式的 Swagger 2.0 暂不支持，请导出 JSON 格式（一般在 /v2/api-docs）后再上传')
  }
  if (!/^\s*openapi\s*:\s*['"]?3/m.test(text)) throw new Error('这不是 OpenAPI 3 或 Swagger 2.0 文档')
  return { specFile: fileName, specContent: text, note: '' }
}

/** 给员工复制到自己智能体里的提示词。 */
export function agentScanPrompt(project: { id: number; name: string; projectCode?: string | null }): string {
  return [
    `请扫描当前打开的项目代码，找出所有对外提供的 HTTP 接口（例如 Spring 的 @RestController / @GetMapping / @PostMapping，或其他框架的路由）。`,
    `然后用 ReachAI 的 MCP 工具 reachai_http_apis_upload，把它们上传到 ReachAI 项目「${project.name}」（项目编号 ${project.id}${project.projectCode ? `，项目编码 ${project.projectCode}` : ''}）。`,
    `要求：`,
    `1. path 只写接口路径，路径参数写成 {id}；不要带域名，也不要带 server.servlet.context-path。`,
    `2. 写清 GET/POST 等方法、路径参数和查询参数的类型；有 @RequestBody 的，把请求体字段整理成 JSON Schema 放进 requestBody.schema。`,
    `3. 按 Controller 或模块填 businessGroups，按是否修改数据填 sideEffect（查询用 READ_ONLY，新增/修改用 WRITE，删除用 IRREVERSIBLE）。`,
    `4. 只上传接口结构，不要上传任何 Token、密码、Cookie 或真实数据。`,
    `5. 一次最多 200 个，超过就分批；上传完告诉我成功和失败各多少，失败的原因是什么。`,
  ].join('\n')
}
