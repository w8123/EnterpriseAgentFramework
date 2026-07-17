import type {
  ModelConnectionConfig,
  ModelCredentialSchemaField,
  ModelInstance,
  ModelInstanceCreateRequest,
  ModelInstanceDraftTestRequest,
  ModelInstanceFromTemplateRequest,
  ModelInstanceStatus,
  ModelInstanceUpdateRequest,
  ModelTemplate,
  ModelTestStatus,
  ModelType,
} from '@/types/model'
import type { StatusTone } from '@/components/common/glassWorkbench'

export const MODEL_TYPES: ModelType[] = ['LLM', 'EMBEDDING', 'RERANKER']

export const DEFAULT_PATHS: Record<ModelType, { key: keyof ModelConnectionConfig; path: string }> = {
  LLM: { key: 'chatPath', path: '/chat/completions' },
  EMBEDDING: { key: 'embeddingPath', path: '/embeddings' },
  RERANKER: { key: 'rerankPath', path: '/rerank' },
}

const CONNECTION_KEYS = [
  'baseUrl',
  'chatPath',
  'embeddingPath',
  'rerankPath',
  'apiKey',
  'authHeader',
  'authPrefix',
] as const

const PROTECTED_BY_TYPE: Record<ModelType, string[]> = {
  LLM: ['model', 'messages', 'stream', 'stream_options', 'tools', 'tool_choice'],
  EMBEDDING: ['model', 'input'],
  RERANKER: ['model', 'query', 'documents', 'top_n'],
}

const CONNECTION_PROTECTED = [
  'baseUrl',
  'chatPath',
  'embeddingPath',
  'rerankPath',
  'apiKey',
  'authHeader',
  'authPrefix',
  'path',
  'token',
  'secret',
  'password',
]

export const PROVIDER_DISPLAY_NAMES: Record<string, string> = {
  openai: 'OpenAI',
  tongyi: '通义千问',
  anthropic: 'Anthropic',
  gemini: 'Gemini',
  deepseek: 'DeepSeek',
  kimi: 'Kimi',
  moonshot: 'Kimi',
  siliconflow: '硅基流动',
  'azure-openai': 'Azure OpenAI',
  'tencent-hunyuan': '腾讯混元',
  qianfan: '百度千帆',
  volcengine: '火山方舟',
  ollama: 'Ollama',
  vllm: 'vLLM',
}

export const PROVIDER_ICON_KEYS = [
  'openai',
  'tongyi',
  'anthropic',
  'gemini',
  'deepseek',
  'kimi',
  'siliconflow',
  'azure-openai',
  'tencent-hunyuan',
  'qianfan',
  'volcengine',
  'ollama',
  'vllm',
] as const

export type RuntimeStatusFilter = 'current' | 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
export type TestStatusFilter = '' | ModelTestStatus

export interface ModelCenterMetrics {
  connected: number
  active: number
  disabled: number
  testFailed: number
}

export interface ModelInstanceDraft {
  name: string
  provider: string
  modelType: ModelType
  modelName: string
  protocol: 'OPENAI_COMPATIBLE'
  connection: ModelConnectionConfig
  defaultOptions: Record<string, unknown>
  paramsSchema: unknown
  remark: string
  status: 'ACTIVE' | 'DISABLED'
  templateId?: string | null
  apiKeyConfigured: boolean
  originalBaseUrl?: string
}

export interface FriendlyOptionFields {
  temperature?: number
  maxTokens?: number
  thinkingType?: 'enabled' | 'disabled'
  reasoningEffort?: 'high' | 'max'
  advancedJson: string
}

export function isSupportedModelType(value: unknown): value is ModelType {
  return typeof value === 'string' && (MODEL_TYPES as string[]).includes(value)
}

export function modelTypeLabel(type: ModelType | string): string {
  switch (type) {
    case 'LLM':
      return '大语言模型'
    case 'EMBEDDING':
      return '向量模型'
    case 'RERANKER':
      return '重排模型'
    default:
      return String(type || '未知类型')
  }
}

export function providerDisplayName(provider: string | null | undefined): string {
  const key = String(provider || '').trim().toLowerCase()
  if (!key) return '自定义'
  return PROVIDER_DISPLAY_NAMES[key] || provider || '自定义'
}

export function resolveProviderIconKey(providerOrIconKey: string | null | undefined): string | null {
  const key = String(providerOrIconKey || '').trim().toLowerCase()
  if (!key) return null
  if ((PROVIDER_ICON_KEYS as readonly string[]).includes(key)) return key
  if (key === 'moonshot' || key === 'dashscope') {
    return key === 'moonshot' ? 'kimi' : 'tongyi'
  }
  return null
}

export function runtimeStatusLabel(status: ModelInstanceStatus | string): string {
  switch (status) {
    case 'ACTIVE':
      return '启用'
    case 'DISABLED':
      return '停用'
    case 'ARCHIVED':
      return '已归档'
    default:
      return String(status || '未知')
  }
}

export function runtimeStatusTone(status: ModelInstanceStatus | string): StatusTone {
  switch (status) {
    case 'ACTIVE':
      return 'success'
    case 'DISABLED':
      return 'warning'
    case 'ARCHIVED':
      return 'neutral'
    default:
      return 'info'
  }
}

export function testStatusLabel(status: ModelTestStatus | string | null | undefined): string {
  switch (status) {
    case 'SUCCESS':
      return '测试通过'
    case 'FAILED':
      return '测试失败'
    case 'UNKNOWN':
    default:
      return '未测试'
  }
}

export function testStatusTone(status: ModelTestStatus | string | null | undefined): StatusTone {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'UNKNOWN':
    default:
      return 'info'
  }
}

export function unwrapApiData<T>(data: T | { data?: T } | undefined | null): T | undefined {
  if (data == null) return undefined
  if (typeof data === 'object' && data !== null && 'data' in data) {
    return (data as { data?: T }).data
  }
  return data as T
}

/** Axios interceptor usually unwraps ApiResult.data; keep a safe reader for both shapes. */
export function readModelApiPayload<T>(payload: unknown): T {
  const unwrapped = unwrapApiData<T>(payload as T | { data?: T } | null | undefined)
  return (unwrapped !== undefined ? unwrapped : payload) as T
}

export function normalizeListPayload<T>(payload: unknown): T[] {
  if (Array.isArray(payload)) return payload as T[]
  if (payload !== null && typeof payload === 'object' && 'data' in payload) {
    const nested = (payload as { data?: unknown }).data
    return Array.isArray(nested) ? (nested as T[]) : []
  }
  return []
}

export function computeModelCenterMetrics(instances: ModelInstance[]): ModelCenterMetrics {
  let connected = 0
  let active = 0
  let disabled = 0
  let testFailed = 0
  for (const item of instances) {
    if (item.status === 'ARCHIVED') continue
    connected += 1
    if (item.status === 'ACTIVE') active += 1
    if (item.status === 'DISABLED') disabled += 1
    if (item.lastTestStatus === 'FAILED') testFailed += 1
  }
  return { connected, active, disabled, testFailed }
}

export function filterModelInstances(
  instances: ModelInstance[],
  options: {
    keyword?: string
    provider?: string
    modelType?: ModelType | ''
    runtimeStatus?: RuntimeStatusFilter
    testStatus?: TestStatusFilter
  },
): ModelInstance[] {
  const keyword = String(options.keyword || '').trim().toLowerCase()
  const provider = String(options.provider || '').trim().toLowerCase()
  const modelType = options.modelType || ''
  const runtimeStatus = options.runtimeStatus || 'current'
  const testStatus = options.testStatus || ''

  return instances.filter((item) => {
    if (runtimeStatus === 'current') {
      if (item.status === 'ARCHIVED') return false
    } else if (item.status !== runtimeStatus) {
      return false
    }

    if (modelType && item.modelType !== modelType) return false
    if (provider && String(item.provider || '').toLowerCase() !== provider) return false
    if (testStatus && (item.lastTestStatus || 'UNKNOWN') !== testStatus) return false

    if (!keyword) return true
    const haystack = [item.name, item.provider, item.modelName, item.remark]
      .map((part) => String(part || '').toLowerCase())
      .join(' ')
    return haystack.includes(keyword)
  })
}

export function extractBaseUrlAuthority(baseUrl: string | null | undefined): string {
  const raw = String(baseUrl || '').trim()
  if (!raw) return '-'
  try {
    const url = new URL(raw)
    return url.host || raw
  } catch {
    const withoutProtocol = raw.replace(/^[a-z]+:\/\//i, '')
    const authority = withoutProtocol.split('/')[0] || raw
    return authority || '-'
  }
}

export function getBaseUrlAuthorityKey(baseUrl: string | null | undefined): string | null {
  const raw = String(baseUrl || '').trim()
  if (!raw) return null
  try {
    const url = new URL(raw)
    const protocol = url.protocol.toLowerCase()
    const host = url.hostname.toLowerCase()
    const port = url.port || (protocol === 'https:' ? '443' : protocol === 'http:' ? '80' : '')
    return `${protocol}//${host}:${port}`
  } catch {
    return raw.toLowerCase()
  }
}

export function hasBaseUrlAuthorityChanged(
  previous: string | null | undefined,
  next: string | null | undefined,
): boolean {
  const left = getBaseUrlAuthorityKey(previous)
  const right = getBaseUrlAuthorityKey(next)
  if (!left || !right) return Boolean(left || right) && left !== right
  return left !== right
}

export function isMaskedApiKey(value: unknown): boolean {
  return typeof value === 'string' && value.includes('******')
}

export function hasConfiguredApiKey(connection: ModelConnectionConfig | null | undefined): boolean {
  const apiKey = connection?.apiKey
  if (typeof apiKey !== 'string') return false
  const trimmed = apiKey.trim()
  return trimmed.length > 0
}

export function parseCredentialSchema(value: unknown): ModelCredentialSchemaField[] {
  if (!Array.isArray(value)) return []
  return value
    .filter((item): item is Record<string, unknown> => item != null && typeof item === 'object')
    .map((item) => ({
      key: String(item.key || ''),
      label: String(item.label || item.key || '凭证'),
      required: Boolean(item.required),
      secret: Boolean(item.secret),
    }))
    .filter((item) => item.key)
}

export function normalizeConnection(input: ModelConnectionConfig | Record<string, unknown> | null | undefined): ModelConnectionConfig {
  const source = (input || {}) as Record<string, unknown>
  const result: ModelConnectionConfig = {}
  for (const key of CONNECTION_KEYS) {
    const value = source[key]
    if (typeof value === 'string') {
      const trimmed = value.trim()
      if (trimmed) result[key] = trimmed
    }
  }
  return result
}

export function defaultPathForType(modelType: ModelType): string {
  return DEFAULT_PATHS[modelType].path
}

export function pathKeyForType(modelType: ModelType): keyof ModelConnectionConfig {
  return DEFAULT_PATHS[modelType].key
}

export function applyPathForType(connection: ModelConnectionConfig, modelType: ModelType, path?: string): ModelConnectionConfig {
  const next = normalizeConnection(connection)
  const key = pathKeyForType(modelType)
  next[key] = (path || defaultPathForType(modelType)).trim() || defaultPathForType(modelType)
  return next
}

export function findProtectedOptionKeys(
  modelType: ModelType,
  options: Record<string, unknown> | null | undefined,
): string[] {
  if (!options || typeof options !== 'object') return []
  const protectedKeys = new Set([...CONNECTION_PROTECTED, ...PROTECTED_BY_TYPE[modelType]])
  return Object.keys(options).filter((key) => protectedKeys.has(key))
}

export function assertNoProtectedOptions(modelType: ModelType, options: Record<string, unknown> | null | undefined): void {
  const hits = findProtectedOptionKeys(modelType, options)
  if (hits.length) {
    throw new Error(`defaultOptions 包含受保护字段：${hits.join(', ')}`)
  }
}

export function createEmptyDraft(partial?: Partial<ModelInstanceDraft>): ModelInstanceDraft {
  const modelType = partial?.modelType || 'LLM'
  const { connection: partialConnection, ...restPartial } = partial || {}
  const connection = applyPathForType(
    {
      baseUrl: '',
      authHeader: 'Authorization',
      authPrefix: 'Bearer',
      ...partialConnection,
    },
    modelType,
    partialConnection?.[pathKeyForType(modelType)] as string | undefined,
  )
  return {
    name: '',
    provider: '',
    modelName: '',
    protocol: 'OPENAI_COMPATIBLE',
    defaultOptions: {},
    paramsSchema: [],
    remark: '',
    status: 'ACTIVE',
    templateId: null,
    apiKeyConfigured: false,
    originalBaseUrl: undefined,
    ...restPartial,
    modelType,
    connection,
  }
}

export function draftFromTemplate(template: ModelTemplate, nameHint?: string): ModelInstanceDraft {
  const modelType = isSupportedModelType(template.modelType) ? template.modelType : 'LLM'
  const connection = applyPathForType(
    normalizeConnection(template.connectionDefaults),
    modelType,
    (template.connectionDefaults || {})[pathKeyForType(modelType)] as string | undefined,
  )
  delete connection.apiKey
  return createEmptyDraft({
    name: nameHint || `${template.name} 接入`,
    provider: template.provider,
    modelType,
    modelName: template.modelName,
    protocol: 'OPENAI_COMPATIBLE',
    connection,
    defaultOptions: { ...(template.defaultOptions || {}) },
    paramsSchema: template.paramsSchema ?? [],
    remark: '',
    status: 'ACTIVE',
    templateId: template.id,
    apiKeyConfigured: false,
  })
}

export function draftFromCustomOpenAi(modelType: ModelType = 'LLM'): ModelInstanceDraft {
  return createEmptyDraft({
    name: '',
    provider: 'custom',
    modelType,
    modelName: '',
    connection: applyPathForType(
      {
        baseUrl: '',
        authHeader: 'Authorization',
        authPrefix: 'Bearer',
      },
      modelType,
    ),
    templateId: null,
  })
}

export function draftFromInstance(instance: ModelInstance): ModelInstanceDraft {
  const modelType = isSupportedModelType(instance.modelType) ? instance.modelType : 'LLM'
  const connection = normalizeConnection(instance.connection)
  const apiKeyConfigured = hasConfiguredApiKey(connection)
  if (isMaskedApiKey(connection.apiKey) || apiKeyConfigured) {
    delete connection.apiKey
  }
  return createEmptyDraft({
    name: instance.name,
    provider: instance.provider,
    modelType,
    modelName: instance.modelName,
    protocol: 'OPENAI_COMPATIBLE',
    connection,
    defaultOptions: { ...(instance.defaultOptions || {}) },
    paramsSchema: instance.paramsSchema ?? [],
    remark: instance.remark || '',
    status: instance.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE',
    templateId: null,
    apiKeyConfigured,
    originalBaseUrl: instance.connection?.baseUrl,
  })
}

export function applyTemplateToDraft(draft: ModelInstanceDraft, template: ModelTemplate): ModelInstanceDraft {
  if (template.modelType !== draft.modelType) {
    throw new Error('只能更换相同模型类型的上游模型')
  }
  const connection = applyPathForType(
    normalizeConnection(template.connectionDefaults),
    draft.modelType,
    (template.connectionDefaults || {})[pathKeyForType(draft.modelType)] as string | undefined,
  )
  delete connection.apiKey
  const authorityChanged = hasBaseUrlAuthorityChanged(draft.originalBaseUrl || draft.connection.baseUrl, connection.baseUrl)
  return {
    ...draft,
    provider: template.provider,
    modelName: template.modelName,
    protocol: 'OPENAI_COMPATIBLE',
    connection: {
      ...connection,
      authHeader: connection.authHeader || draft.connection.authHeader || 'Authorization',
      authPrefix: connection.authPrefix || draft.connection.authPrefix || 'Bearer',
    },
    defaultOptions: { ...(template.defaultOptions || {}) },
    paramsSchema: template.paramsSchema ?? [],
    templateId: null,
    apiKeyConfigured: authorityChanged ? false : draft.apiKeyConfigured,
  }
}

export function applyCustomUpstreamToDraft(
  draft: ModelInstanceDraft,
  custom: { provider: string; modelName: string; baseUrl: string; path?: string },
): ModelInstanceDraft {
  const connection = applyPathForType(
    {
      ...draft.connection,
      baseUrl: custom.baseUrl,
      apiKey: undefined,
    },
    draft.modelType,
    custom.path,
  )
  const authorityChanged = hasBaseUrlAuthorityChanged(draft.originalBaseUrl || draft.connection.baseUrl, connection.baseUrl)
  return {
    ...draft,
    provider: custom.provider.trim(),
    modelName: custom.modelName.trim(),
    connection,
    templateId: null,
    apiKeyConfigured: authorityChanged ? false : draft.apiKeyConfigured,
  }
}

export function splitFriendlyOptions(
  modelType: ModelType,
  options: Record<string, unknown> | null | undefined,
  provider?: string,
  modelName?: string,
): FriendlyOptionFields {
  const source = { ...(options || {}) }
  const result: FriendlyOptionFields = { advancedJson: '{}' }
  if (modelType === 'LLM') {
    if (typeof source.temperature === 'number') {
      result.temperature = source.temperature
      delete source.temperature
    }
    if (typeof source.max_tokens === 'number') {
      result.maxTokens = source.max_tokens
      delete source.max_tokens
    }
    const showThinking = shouldShowDeepSeekThinking(provider, modelName)
    if (showThinking) {
      const thinking = source.thinking
      if (thinking && typeof thinking === 'object' && thinking !== null && 'type' in thinking) {
        const type = String((thinking as { type?: unknown }).type || '')
        if (type === 'enabled' || type === 'disabled') {
          result.thinkingType = type
          delete source.thinking
        }
      }
      if (source.reasoning_effort === 'high' || source.reasoning_effort === 'max') {
        result.reasoningEffort = source.reasoning_effort
        delete source.reasoning_effort
      }
    }
  }
  result.advancedJson = JSON.stringify(source, null, 2)
  return result
}

export function mergeFriendlyOptions(
  modelType: ModelType,
  fields: FriendlyOptionFields,
  provider?: string,
  modelName?: string,
): Record<string, unknown> {
  let advanced: Record<string, unknown> = {}
  const text = String(fields.advancedJson || '').trim() || '{}'
  try {
    const parsed = JSON.parse(text) as unknown
    if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('高级模型参数必须是 JSON 对象')
    }
    advanced = { ...(parsed as Record<string, unknown>) }
  } catch (error) {
    throw new Error(error instanceof Error ? error.message : '高级模型参数 JSON 非法')
  }

  if (modelType === 'LLM') {
    if (typeof fields.temperature === 'number') advanced.temperature = fields.temperature
    if (typeof fields.maxTokens === 'number') advanced.max_tokens = fields.maxTokens
    if (shouldShowDeepSeekThinking(provider, modelName)) {
      if (fields.thinkingType) advanced.thinking = { type: fields.thinkingType }
      if (fields.reasoningEffort) advanced.reasoning_effort = fields.reasoningEffort
    }
  }

  assertNoProtectedOptions(modelType, advanced)
  return advanced
}

export function shouldShowDeepSeekThinking(provider?: string, modelName?: string): boolean {
  const p = String(provider || '').toLowerCase()
  const m = String(modelName || '').toLowerCase()
  return p === 'deepseek' || m.includes('deepseek')
}

export function buildConnectionForSubmit(
  draft: ModelInstanceDraft,
  apiKeyInput: string,
  options?: { omitEmptyApiKey?: boolean },
): ModelConnectionConfig {
  const connection = normalizeConnection(draft.connection)
  const pathKey = pathKeyForType(draft.modelType)
  if (!connection[pathKey]) {
    connection[pathKey] = defaultPathForType(draft.modelType)
  }
  const key = apiKeyInput.trim()
  if (key) {
    connection.apiKey = key
  } else if (options?.omitEmptyApiKey !== false) {
    delete connection.apiKey
  }
  return connection
}

export function buildCreateRequest(draft: ModelInstanceDraft, apiKeyInput: string): ModelInstanceCreateRequest {
  const connection = buildConnectionForSubmit(draft, apiKeyInput, { omitEmptyApiKey: false })
  if (!connection.apiKey && isApiKeyRequired(draft)) {
    // keep empty; caller validates
  }
  assertNoProtectedOptions(draft.modelType, draft.defaultOptions)
  return {
    name: draft.name.trim(),
    provider: draft.provider.trim(),
    modelType: draft.modelType,
    modelName: draft.modelName.trim(),
    protocol: 'OPENAI_COMPATIBLE',
    connection,
    defaultOptions: draft.defaultOptions,
    paramsSchema: draft.paramsSchema,
    status: draft.status,
    remark: draft.remark?.trim() || null,
  }
}

export function buildFromTemplateRequest(draft: ModelInstanceDraft, apiKeyInput: string): ModelInstanceFromTemplateRequest {
  const connection = buildConnectionForSubmit(draft, apiKeyInput, { omitEmptyApiKey: false })
  assertNoProtectedOptions(draft.modelType, draft.defaultOptions)
  return {
    name: draft.name.trim(),
    modelName: draft.modelName.trim(),
    connection,
    defaultOptions: draft.defaultOptions,
    status: draft.status,
    remark: draft.remark?.trim() || null,
  }
}

export function buildUpdateRequest(draft: ModelInstanceDraft, apiKeyInput: string): ModelInstanceUpdateRequest {
  const connection = buildConnectionForSubmit(draft, apiKeyInput, { omitEmptyApiKey: true })
  assertNoProtectedOptions(draft.modelType, draft.defaultOptions)
  return {
    name: draft.name.trim(),
    provider: draft.provider.trim(),
    modelName: draft.modelName.trim(),
    connection,
    defaultOptions: draft.defaultOptions,
    paramsSchema: draft.paramsSchema,
    remark: draft.remark?.trim() || null,
  }
}

export function buildStatusUpdateRequest(instance: ModelInstance, status: 'ACTIVE' | 'DISABLED'): ModelInstanceUpdateRequest {
  return {
    name: instance.name,
    status,
  }
}

export function buildDraftTestRequest(
  draft: ModelInstanceDraft,
  apiKeyInput: string,
  options?: { instanceId?: string },
): ModelInstanceDraftTestRequest {
  const connection = buildConnectionForSubmit(draft, apiKeyInput, { omitEmptyApiKey: true })
  assertNoProtectedOptions(draft.modelType, draft.defaultOptions)
  return {
    id: options?.instanceId,
    name: draft.name.trim() || undefined,
    provider: draft.provider.trim(),
    modelType: draft.modelType,
    modelName: draft.modelName.trim(),
    protocol: 'OPENAI_COMPATIBLE',
    connection,
    defaultOptions: draft.defaultOptions,
  }
}

export function isApiKeyRequired(draft: ModelInstanceDraft, credentialSchema?: unknown): boolean {
  if (draft.templateId) {
    const fields = parseCredentialSchema(credentialSchema)
    const apiKeyField = fields.find((field) => field.key === 'apiKey')
    if (apiKeyField) return Boolean(apiKeyField.required)
  }
  // custom create always requires api key unless omitted intentionally; ollama may be empty
  const provider = draft.provider.toLowerCase()
  if (provider === 'ollama' || provider === 'vllm') return false
  return true
}

export function validateDraftForSave(
  draft: ModelInstanceDraft,
  apiKeyInput: string,
  options?: { mode: 'create' | 'update'; credentialSchema?: unknown },
): string | null {
  if (!draft.name.trim()) return '请填写接入名称'
  if (!draft.provider.trim()) return '请填写供应商'
  if (!isSupportedModelType(draft.modelType)) return '模型类型无效'
  if (!draft.modelName.trim()) return '请填写上游 modelName'
  if (!draft.connection.baseUrl?.trim()) return '请填写 Base URL'
  const pathKey = pathKeyForType(draft.modelType)
  if (!String(draft.connection[pathKey] || '').trim()) return `请填写 ${String(pathKey)}`

  const mode = options?.mode || 'create'
  const key = apiKeyInput.trim()
  const authorityChanged = hasBaseUrlAuthorityChanged(draft.originalBaseUrl, draft.connection.baseUrl)
  if (mode === 'create') {
    if (isApiKeyRequired(draft, options?.credentialSchema) && !key) {
      return '请填写 API Key'
    }
  } else {
    if (authorityChanged && draft.apiKeyConfigured && !key) {
      return '服务地址已变化，请重新填写 API Key'
    }
    if (authorityChanged && !draft.apiKeyConfigured && isApiKeyRequired(draft, options?.credentialSchema) && !key) {
      return '服务地址已变化，请填写 API Key'
    }
  }

  try {
    assertNoProtectedOptions(draft.modelType, draft.defaultOptions)
  } catch (error) {
    return error instanceof Error ? error.message : '默认参数包含受保护字段'
  }
  return null
}

export function draftRuntimeFingerprint(draft: ModelInstanceDraft, apiKeyInput: string): string {
  return JSON.stringify({
    provider: draft.provider.trim(),
    modelType: draft.modelType,
    modelName: draft.modelName.trim(),
    connection: buildConnectionForSubmit(draft, apiKeyInput, { omitEmptyApiKey: true }),
    defaultOptions: draft.defaultOptions,
  })
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

export function formatLatency(ms: number | null | undefined): string {
  if (ms == null || Number.isNaN(Number(ms))) return '-'
  return `${ms} ms`
}

export function uniqueProviders(instances: Array<{ provider?: string | null }>): string[] {
  const set = new Set<string>()
  for (const item of instances) {
    const provider = String(item.provider || '').trim()
    if (provider) set.add(provider)
  }
  return Array.from(set).sort((a, b) => a.localeCompare(b))
}

export function filterTemplates(
  templates: ModelTemplate[],
  options: { keyword?: string; provider?: string; modelType?: ModelType | ''; onlyEnabled?: boolean },
): ModelTemplate[] {
  const keyword = String(options.keyword || '').trim().toLowerCase()
  const provider = String(options.provider || '').trim().toLowerCase()
  const modelType = options.modelType || ''
  return templates.filter((item) => {
    if (options.onlyEnabled !== false && item.enabled === false) return false
    if (modelType && item.modelType !== modelType) return false
    if (provider && String(item.provider || '').toLowerCase() !== provider) return false
    if (!keyword) return true
    const haystack = [item.name, item.provider, item.modelName, item.remark]
      .map((part) => String(part || '').toLowerCase())
      .join(' ')
    return haystack.includes(keyword)
  })
}

export interface ModelProviderGroup {
  provider: string
  displayName: string
  iconKey: string | null
  templates: ModelTemplate[]
  modelCount: number
  sortOrder: number
}

/** Group enabled templates by provider, preserving API order within each group. */
export function buildProviderGroups(
  templates: ModelTemplate[],
  options?: { modelType?: ModelType | ''; onlyEnabled?: boolean },
): ModelProviderGroup[] {
  const filtered = filterTemplates(templates, {
    modelType: options?.modelType || '',
    onlyEnabled: options?.onlyEnabled !== false,
  })
  const map = new Map<string, ModelTemplate[]>()
  for (const tpl of filtered) {
    const key = String(tpl.provider || '').trim()
    if (!key) continue
    const list = map.get(key)
    if (list) list.push(tpl)
    else map.set(key, [tpl])
  }

  const groups: ModelProviderGroup[] = []
  for (const [provider, list] of map) {
    let sortOrder = Number.MAX_SAFE_INTEGER
    for (const item of list) {
      if (typeof item.sortOrder === 'number') {
        sortOrder = Math.min(sortOrder, item.sortOrder)
      }
    }
    groups.push({
      provider,
      displayName: providerDisplayName(provider),
      iconKey: resolveProviderIconKey(list[0]?.iconKey || provider),
      templates: list,
      modelCount: list.length,
      sortOrder: sortOrder === Number.MAX_SAFE_INTEGER ? 9999 : sortOrder,
    })
  }

  groups.sort((a, b) => {
    if (a.sortOrder !== b.sortOrder) return a.sortOrder - b.sortOrder
    return a.displayName.localeCompare(b.displayName, 'zh-CN')
  })
  return groups
}

/** Provider search: match display name, provider id, or any model under the group. */
export function filterProviderGroups(groups: ModelProviderGroup[], keyword?: string): ModelProviderGroup[] {
  const query = String(keyword || '').trim().toLowerCase()
  if (!query) return groups
  return groups.filter((group) => {
    if (group.displayName.toLowerCase().includes(query)) return true
    if (group.provider.toLowerCase().includes(query)) return true
    return group.templates.some((item) => {
      const haystack = [item.name, item.modelName, item.remark]
        .map((part) => String(part || '').toLowerCase())
        .join(' ')
      return haystack.includes(query)
    })
  })
}

/** Search/filter models within a single provider group. */
export function filterProviderModels(
  templates: ModelTemplate[],
  options?: { keyword?: string; modelType?: ModelType | '' },
): ModelTemplate[] {
  return filterTemplates(templates, {
    keyword: options?.keyword,
    modelType: options?.modelType || '',
    onlyEnabled: true,
  })
}

export function findProviderGroup(
  groups: ModelProviderGroup[],
  provider: string | null | undefined,
): ModelProviderGroup | undefined {
  const key = String(provider || '').trim().toLowerCase()
  if (!key) return undefined
  return groups.find((group) => group.provider.toLowerCase() === key)
}

export const EMBEDDING_REPLACE_WARNING =
  '更换向量模型可能导致现有知识库或业务索引中的历史向量维度、分布不兼容。本版本不会自动重建索引，请在更换后重新测试并按实际业务重建相关向量数据。'

export const ARCHIVE_CONFIRM_MESSAGE =
  '归档后不能编辑、测试、启用或恢复。归档不会改写 Agent、Workflow、Knowledge 等已有 modelInstanceId；若仍有业务引用，后续运行时调用会失败。当前版本不会自动检查引用关系。确定归档吗？'
