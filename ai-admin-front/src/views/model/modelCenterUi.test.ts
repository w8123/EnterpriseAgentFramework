import { describe, expect, it } from 'vitest'
import type { ModelInstance, ModelTemplate } from '@/types/model'
import {
  MODEL_TYPES,
  applyTemplateToDraft,
  buildCreateRequest,
  buildDraftTestRequest,
  buildProviderGroups,
  computeModelCenterMetrics,
  draftFromTemplate,
  draftRuntimeFingerprint,
  extractBaseUrlAuthority,
  filterModelInstances,
  filterProviderGroups,
  filterProviderModels,
  filterTemplates,
  findProtectedOptionKeys,
  findProviderGroup,
  hasBaseUrlAuthorityChanged,
  isMaskedApiKey,
  isSupportedModelType,
  runtimeStatusLabel,
  testStatusLabel,
  validateDraftForSave,
} from './modelCenterUi'

function sampleTemplate(partial?: Partial<ModelTemplate>): ModelTemplate {
  return {
    id: 'tpl-openai-gpt-5-2',
    name: 'OpenAI GPT',
    provider: 'openai',
    modelType: 'LLM',
    modelName: 'gpt-5.2',
    protocol: 'OPENAI_COMPATIBLE',
    connectionDefaults: {
      baseUrl: 'https://api.openai.com/v1',
      chatPath: '/chat/completions',
      authHeader: 'Authorization',
      authPrefix: 'Bearer',
    },
    credentialSchema: [{ key: 'apiKey', label: 'API Key', required: true, secret: true }],
    defaultOptions: { temperature: 0.7 },
    paramsSchema: [],
    iconKey: 'openai',
    enabled: true,
    remark: 'demo',
    ...partial,
  }
}

function sampleInstance(partial?: Partial<ModelInstance>): ModelInstance {
  return {
    id: 'inst-1',
    name: 'Prod GPT',
    provider: 'openai',
    modelType: 'LLM',
    modelName: 'gpt-5.2',
    protocol: 'OPENAI_COMPATIBLE',
    connection: {
      baseUrl: 'https://api.openai.com/v1',
      chatPath: '/chat/completions',
      apiKey: 'sk-li******abcd',
    },
    defaultOptions: { temperature: 0.2 },
    paramsSchema: [],
    status: 'ACTIVE',
    lastTestStatus: 'SUCCESS',
    lastTestAt: '2026-07-17T01:00:00',
    lastTestLatencyMs: 120,
    remark: 'main',
    updatedAt: '2026-07-17T02:00:00',
    ...partial,
  }
}

describe('modelCenterUi V2 contract helpers', () => {
  it('only supports three model types', () => {
    expect(MODEL_TYPES).toEqual(['LLM', 'EMBEDDING', 'RERANKER'])
    expect(isSupportedModelType('LLM')).toBe(true)
    expect(isSupportedModelType('STT')).toBe(false)
    expect(isSupportedModelType('IMAGE')).toBe(false)
  })

  it('maps runtime and test statuses separately', () => {
    expect(runtimeStatusLabel('ACTIVE')).toBe('启用')
    expect(runtimeStatusLabel('DISABLED')).toBe('停用')
    expect(runtimeStatusLabel('ARCHIVED')).toBe('已归档')
    expect(testStatusLabel('SUCCESS')).toBe('测试通过')
    expect(testStatusLabel('FAILED')).toBe('测试失败')
    expect(testStatusLabel('UNKNOWN')).toBe('未测试')
    expect(runtimeStatusLabel('ACTIVE')).not.toBe('测试通过')
  })

  it('defaults list excludes archived and archived filter can include them', () => {
    const list = [
      sampleInstance({ id: 'a', status: 'ACTIVE' }),
      sampleInstance({ id: 'b', status: 'DISABLED', name: 'Disabled' }),
      sampleInstance({ id: 'c', status: 'ARCHIVED', name: 'Archived', lastTestStatus: 'FAILED' }),
    ]
    const current = filterModelInstances(list, { runtimeStatus: 'current' })
    expect(current.map((item) => item.id)).toEqual(['a', 'b'])
    const archived = filterModelInstances(list, { runtimeStatus: 'ARCHIVED' })
    expect(archived.map((item) => item.id)).toEqual(['c'])
  })

  it('computes metrics without counting archived', () => {
    const metrics = computeModelCenterMetrics([
      sampleInstance({ status: 'ACTIVE', lastTestStatus: 'SUCCESS' }),
      sampleInstance({ id: '2', status: 'DISABLED', lastTestStatus: 'FAILED' }),
      sampleInstance({ id: '3', status: 'ARCHIVED', lastTestStatus: 'FAILED' }),
    ])
    expect(metrics).toEqual({
      connected: 2,
      active: 1,
      disabled: 1,
      testFailed: 1,
    })
  })

  it('extracts baseUrl authority/hostname', () => {
    expect(extractBaseUrlAuthority('https://api.openai.com/v1/chat')).toBe('api.openai.com')
    expect(extractBaseUrlAuthority('https://example.com:8443/v2')).toBe('example.com:8443')
  })

  it('detects authority changes', () => {
    expect(
      hasBaseUrlAuthorityChanged('https://api.openai.com/v1', 'https://api.openai.com/v2'),
    ).toBe(false)
    expect(
      hasBaseUrlAuthorityChanged('https://api.openai.com/v1', 'https://api.deepseek.com'),
    ).toBe(true)
  })

  it('treats masked api key as configured marker, not plaintext input', () => {
    expect(isMaskedApiKey('sk-li******abcd')).toBe(true)
    const draft = draftFromTemplate(sampleTemplate())
    expect(draft.connection.apiKey).toBeUndefined()
  })

  it('converts template snapshot into create draft and draft-test payload', () => {
    const template = sampleTemplate({
      defaultOptions: { temperature: 0.5, max_tokens: 1024 },
      connectionDefaults: {
        baseUrl: 'https://api.deepseek.com',
        chatPath: '/chat/completions',
      },
      provider: 'deepseek',
      modelName: 'deepseek-chat',
    })
    const draft = draftFromTemplate(template, 'DeepSeek 接入')
    expect(draft.templateId).toBe(template.id)
    expect(draft.provider).toBe('deepseek')
    expect(draft.modelType).toBe('LLM')
    expect(draft.connection.baseUrl).toBe('https://api.deepseek.com')
    expect(draft.defaultOptions).toEqual({ temperature: 0.5, max_tokens: 1024 })

    const payload = buildDraftTestRequest(draft, 'sk-test-key')
    expect(payload).toMatchObject({
      provider: 'deepseek',
      modelType: 'LLM',
      modelName: 'deepseek-chat',
      protocol: 'OPENAI_COMPATIBLE',
      connection: {
        baseUrl: 'https://api.deepseek.com',
        chatPath: '/chat/completions',
        apiKey: 'sk-test-key',
      },
      defaultOptions: { temperature: 0.5, max_tokens: 1024 },
    })
    expect(payload).not.toHaveProperty('templateId')
  })

  it('filters same-type templates and rejects type change on replace', () => {
    const templates = [
      sampleTemplate({ id: 't1', modelType: 'LLM' }),
      sampleTemplate({ id: 't2', modelType: 'EMBEDDING', name: 'Emb', modelName: 'emb' }),
    ]
    const onlyLlm = filterTemplates(templates, { modelType: 'LLM' })
    expect(onlyLlm.map((item) => item.id)).toEqual(['t1'])

    const draft = draftFromTemplate(sampleTemplate({ modelType: 'LLM' }))
    expect(() =>
      applyTemplateToDraft(
        draft,
        sampleTemplate({ id: 't2', modelType: 'EMBEDDING', name: 'Emb', modelName: 'emb' }),
      ),
    ).toThrow(/相同模型类型/)
  })

  it('validates protected options', () => {
    expect(findProtectedOptionKeys('LLM', { model: 'x', temperature: 0.1 })).toEqual(['model'])
    expect(findProtectedOptionKeys('EMBEDDING', { input: 'x' })).toEqual(['input'])
    expect(findProtectedOptionKeys('RERANKER', { top_n: 3 })).toEqual(['top_n'])
  })

  it('still-save path can skip retest by fingerprint reuse', () => {
    const draft = draftFromTemplate(sampleTemplate())
    const fp1 = draftRuntimeFingerprint(draft, 'sk-1')
    const fp2 = draftRuntimeFingerprint(draft, 'sk-1')
    const fp3 = draftRuntimeFingerprint(
      { ...draft, connection: { ...draft.connection, baseUrl: 'https://api.deepseek.com' } },
      'sk-1',
    )
    expect(fp1).toBe(fp2)
    expect(fp1).not.toBe(fp3)

    // create request itself does not re-test
    const request = buildCreateRequest(draft, 'sk-1')
    expect(request.connection.apiKey).toBe('sk-1')
    expect(request.protocol).toBe('OPENAI_COMPATIBLE')
  })

  it('marks config changes as requiring validation before create', () => {
    const draft = draftFromTemplate(sampleTemplate())
    expect(validateDraftForSave(draft, '', { mode: 'create', credentialSchema: sampleTemplate().credentialSchema })).toBe(
      '请填写 API Key',
    )
    expect(validateDraftForSave(draft, 'sk-ok', { mode: 'create' })).toBeNull()
  })

  it('invalidates successful draft fingerprint when runtime config changes', () => {
    const draft = draftFromTemplate(sampleTemplate())
    const before = draftRuntimeFingerprint(draft, 'sk-ok')
    draft.modelName = 'gpt-4.1-mini'
    const after = draftRuntimeFingerprint(draft, 'sk-ok')
    expect(before).not.toBe(after)
  })
})

describe('provider-first catalog grouping', () => {
  const catalog = [
    sampleTemplate({
      id: 'ds-1',
      name: 'DeepSeek Chat',
      provider: 'deepseek',
      modelName: 'deepseek-chat',
      sortOrder: 10,
      iconKey: 'deepseek',
    }),
    sampleTemplate({
      id: 'ds-2',
      name: 'DeepSeek Reasoner',
      provider: 'deepseek',
      modelName: 'deepseek-reasoner',
      sortOrder: 11,
      iconKey: 'deepseek',
    }),
    sampleTemplate({
      id: 'oa-1',
      name: 'OpenAI GPT',
      provider: 'openai',
      modelName: 'gpt-5.2',
      sortOrder: 20,
      iconKey: 'openai',
    }),
    sampleTemplate({
      id: 'oa-emb',
      name: 'OpenAI Embedding',
      provider: 'openai',
      modelType: 'EMBEDDING',
      modelName: 'text-embedding-3-large',
      sortOrder: 21,
      iconKey: 'openai',
    }),
    sampleTemplate({
      id: 'ds-disabled',
      name: 'DeepSeek Disabled',
      provider: 'deepseek',
      modelName: 'deepseek-disabled',
      sortOrder: 12,
      enabled: false,
    }),
    sampleTemplate({
      id: 'only-emb',
      name: 'Emb Only Vendor',
      provider: 'ollama',
      modelType: 'EMBEDDING',
      modelName: 'nomic-embed',
      sortOrder: 5,
      iconKey: 'ollama',
    }),
  ]

  it('excludes disabled templates from provider groups', () => {
    const groups = buildProviderGroups(catalog)
    const deepseek = findProviderGroup(groups, 'deepseek')
    expect(deepseek?.templates.map((item) => item.id)).toEqual(['ds-1', 'ds-2'])
    expect(deepseek?.modelCount).toBe(2)
  })

  it('locks replace flow to same model type and hides providers without matches', () => {
    const llmGroups = buildProviderGroups(catalog, { modelType: 'LLM' })
    expect(llmGroups.map((group) => group.provider)).toEqual(['deepseek', 'openai'])
    expect(findProviderGroup(llmGroups, 'ollama')).toBeUndefined()

    const deepseek = findProviderGroup(llmGroups, 'deepseek')
    expect(deepseek?.modelCount).toBe(2)

    const openai = findProviderGroup(llmGroups, 'openai')
    expect(openai?.modelCount).toBe(1)
    expect(openai?.templates.every((item) => item.modelType === 'LLM')).toBe(true)
  })

  it('counts provider cards after type filter, not full catalog size', () => {
    const embeddingGroups = buildProviderGroups(catalog, { modelType: 'EMBEDDING' })
    expect(embeddingGroups.map((group) => group.provider).sort()).toEqual(['ollama', 'openai'])
    expect(findProviderGroup(embeddingGroups, 'openai')?.modelCount).toBe(1)
    expect(findProviderGroup(embeddingGroups, 'deepseek')).toBeUndefined()
  })

  it('preserves stable sort by min sortOrder then display name', () => {
    const groups = buildProviderGroups(catalog, { modelType: 'EMBEDDING' })
    expect(groups.map((group) => group.provider)).toEqual(['ollama', 'openai'])
  })

  it('provider search matches vendor name and nested model names', () => {
    const groups = buildProviderGroups(catalog, { modelType: 'LLM' })
    expect(filterProviderGroups(groups, 'DeepSeek').map((group) => group.provider)).toEqual(['deepseek'])
    expect(filterProviderGroups(groups, 'gpt-5.2').map((group) => group.provider)).toEqual(['openai'])
    expect(filterProviderGroups(groups, 'no-such-model')).toEqual([])
  })

  it('model search only filters within current provider templates', () => {
    const deepseek = findProviderGroup(buildProviderGroups(catalog), 'deepseek')
    expect(deepseek).toBeTruthy()
    const matched = filterProviderModels(deepseek!.templates, { keyword: 'reasoner' })
    expect(matched.map((item) => item.id)).toEqual(['ds-2'])
    expect(filterProviderModels(deepseek!.templates, { keyword: 'gpt' })).toEqual([])
  })

  it('onboarding type chip filter can narrow a provider model list', () => {
    const openai = findProviderGroup(buildProviderGroups(catalog), 'openai')
    expect(filterProviderModels(openai!.templates, { modelType: 'EMBEDDING' }).map((item) => item.id)).toEqual([
      'oa-emb',
    ])
  })
})
