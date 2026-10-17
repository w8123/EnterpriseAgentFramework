import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { Ref } from 'vue'
import ElementPlus from 'element-plus'
import HttpApiDetail from './HttpApiDetail.vue'

const mocks = vi.hoisted(() => ({ detail: vi.fn(), connection: vi.fn(), references: vi.fn(), credentials: vi.fn(),
  save: vi.fn(), saveDefault: vi.fn(), removeOverride: vi.fn(), invoke: vi.fn(),
  user: null as Ref<any> | null }))
vi.mock('@/api/httpApi', () => ({ getHttpApi: mocks.detail, getHttpApiConnection: mocks.connection,
  getHttpApiReferences: mocks.references, invokeHttpApi: mocks.invoke, getHttpApiInvocation: vi.fn(), acceptHttpApi: vi.fn(),
  saveHttpApiConnection: mocks.save, saveHttpApiProjectDefaultConnection: mocks.saveDefault,
  removeHttpApiConnectionOverride: mocks.removeOverride }))
vi.mock('@/api/workflowCredential', () => ({ listWorkflowCredentials: mocks.credentials }))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue'); mocks.user = ref({ userId: 7, permissionGrants: [] })
  return { platformSessionId: ref('session-a'), platformSessionUser: mocks.user }
})
vi.mock('@/composables/usePageProjectScope', async () => {
  const { ref } = await import('vue')
  return { usePageProjectScope: () => ({ requestKey: ref('orders:41'), requestParams: ref({ projectId: 41 }),
    canLoadData: ref(true), status: ref('ready'), feedbackMessage: ref(''), recoveryAction: ref('') }) }
})
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue'); const route = reactive({ params: { id: '1' }, query: {} })
  return { useRoute: () => route, useRouter: () => ({ push: vi.fn() }), onBeforeRouteLeave: () => undefined }
})

const hash = 'a'.repeat(64), source = 'b'.repeat(64)
const grants = ['platform:read', 'platform:write', 'capability:invoke']
  .map(permissionCode => ({ permissionCode, scopeType: 'PROJECT', scopeValue: 'orders' }))
function owner() {
  const contract = { identity: { method: 'GET', routeTemplate: '/orders' }, sideEffect: 'READ_ONLY',
    authentication: { state: 'NONE', schemes: [], requiredHeaderNames: [] }, parameters: [], requestBody: null,
    responses: [{ status: '200', schema: { type: 'object' }, contentTypes: ['application/json'] }] }
  return { summary: { id: 1, qualifiedName: 'orders.dev.GET./orders', projectId: 41, projectCode: 'orders', environment: 'dev',
    httpMethod: 'GET', routeTemplate: '/orders', sourceStatus: 'ACCEPTED', sourceConfirmed: true,
    candidateContractHash: hash, acceptedContractHash: hash, sourceSetRevision: source }, contract, acceptedContract: contract, sources: [] }
}
function projectDefault(revision: number | null, origin: string | null) {
  return { projectId: 41, projectCode: 'orders', environment: 'dev', origin, authMode: origin ? 'NONE' : null,
    credentialRef: null, credentialName: null, revision, status: origin ? 'CONFIGURED' : 'UNCONFIGURED',
    blockingReason: origin ? null : '尚未保存本项目默认连接', configurationAllowed: true }
}
function inheritedView(revision = 3) {
  return { qualifiedName: 'orders.dev.GET./orders', projectId: 41, projectCode: 'orders', environment: 'dev',
    origin: 'http://gateway.test', authMode: 'NONE', credentialRef: null, credentialName: null, credentialRevision: null,
    revision: revision * 2, status: 'CONFIGURED', blockingReason: null, previewUrl: 'http://gateway.test/orders',
    configurationAllowed: true, connectionSource: 'PROJECT_DEFAULT', inherited: true, overrideActive: false,
    overrideRevision: null, projectDefault: projectDefault(revision, 'http://gateway.test') }
}
function overrideView() {
  return { ...inheritedView(), origin: 'http://special.test', previewUrl: 'http://special.test/orders', revision: 5,
    connectionSource: 'OVERRIDE', inherited: false, overrideActive: true, overrideRevision: 2 }
}
let wrapper: ReturnType<typeof mount> | null = null
function start() { wrapper = mount(HttpApiDetail, { attachTo: document.body, global: { plugins: [ElementPlus],
  stubs: { CapabilityEnrichmentPanel: true, RouterLink: { props: ['to'], template: '<a><slot /></a>' } } } }) }
function button(text: string) {
  return Array.from(document.querySelectorAll('button')).find(item => item.textContent?.trim() === text) as HTMLButtonElement | undefined
}
async function click(text: string) {
  const target = button(text)
  if (!target) throw new Error(`Missing action: ${text}`)
  target.click(); await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks(); localStorage.clear()
  mocks.user!.value = { userId: 7, permissionGrants: grants.map(item => ({ ...item })) }
  mocks.detail.mockResolvedValue({ data: owner() })
  mocks.credentials.mockResolvedValue({ data: [] })
  mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE', references: [] } })
})
afterEach(() => { wrapper?.unmount(); wrapper = null; document.body.innerHTML = '' })

describe('HTTP API project default connection (D27)', () => {
  it('shows the inherited project default and saves it with the project default revision', async () => {
    mocks.connection.mockResolvedValue({ data: inheritedView(3) })
    mocks.saveDefault.mockResolvedValue({ data: inheritedView(4) })
    start(); await flushPromises()
    const text = document.body.textContent || ''
    expect(text).toContain('项目默认连接（本项目所有 API 共用）')
    expect(text).toContain('继承项目默认连接')
    expect(text).toContain('本 API 覆盖连接（可选）')
    expect(button('移除覆盖，改用项目默认')).toBeUndefined()
    await wrapper!.find('input[aria-label="项目默认服务地址"]').setValue('http://moved.test:8080')
    await click('保存项目默认连接')
    expect(mocks.saveDefault).toHaveBeenCalledWith(1, { origin: 'http://moved.test:8080', authMode: 'NONE',
      credentialRef: null, expectedRevision: 3 })
    expect(document.body.textContent).toContain('项目默认修订 4')
  })

  it('creates the first project default without a revision and rejects a path in the origin', async () => {
    mocks.connection.mockResolvedValue({ data: { ...inheritedView(), origin: null, authMode: null, revision: null,
      status: 'UNCONFIGURED', connectionSource: null, inherited: false, previewUrl: null, projectDefault: projectDefault(null, null) } })
    mocks.saveDefault.mockResolvedValue({ data: inheritedView(1) })
    start(); await flushPromises()
    expect(document.body.textContent).toContain('未配置')
    expect(document.body.textContent).toContain('未覆盖')
    await wrapper!.find('input[aria-label="项目默认服务地址"]').setValue('http://gateway.test/api')
    const select = wrapper!.findAllComponents({ name: 'ElSelect' }).find(item => item.attributes('aria-label') === '项目默认认证方式'
      || item.props('placeholder') === '明确选择认证方式')!
    select.vm.$emit('update:modelValue', 'NONE'); await flushPromises()
    await click('保存项目默认连接')
    expect(mocks.saveDefault).not.toHaveBeenCalled()
    await wrapper!.find('input[aria-label="项目默认服务地址"]').setValue('http://gateway.test')
    await click('保存项目默认连接')
    expect(mocks.saveDefault).toHaveBeenCalledWith(1, { origin: 'http://gateway.test', authMode: 'NONE',
      credentialRef: null, expectedRevision: null })
  })

  it('saves an override with the override revision and removes it back to inheriting', async () => {
    mocks.connection.mockResolvedValue({ data: inheritedView() })
    mocks.save.mockResolvedValue({ data: { ...overrideView(), overrideRevision: 1, revision: 3 } })
    start(); await flushPromises()
    await wrapper!.find('input[placeholder="https://orders.example.com:8443"]').setValue('http://special.test')
    await click('保存覆盖连接')
    expect(mocks.save).toHaveBeenCalledWith(1, { origin: 'http://special.test', authMode: 'NONE', credentialRef: null,
      expectedRevision: null })
    expect(document.body.textContent).toContain('覆盖生效中')
    mocks.removeOverride.mockResolvedValue({ data: { ...inheritedView(), overrideRevision: 2 } })
    await click('移除覆盖，改用项目默认')
    expect(mocks.removeOverride).toHaveBeenCalledWith(1, 1)
    expect(document.body.textContent).toContain('未覆盖')
    expect(button('移除覆盖，改用项目默认')).toBeUndefined()
  })

  it('pins a trial call to the effective revision of the inherited connection', async () => {
    mocks.connection.mockResolvedValue({ data: inheritedView(3) })
    mocks.invoke.mockResolvedValue({ data: { contractVersion: 1, invocationId: 'x', targetType: 'HTTP_API',
      qualifiedName: 'orders.dev.GET./orders', projectId: 41, projectCode: 'orders', environment: 'dev', status: 'SUCCEEDED', terminal: true } })
    vi.stubGlobal('crypto', { randomUUID: () => '123e4567-e89b-42d3-a456-426614174000' })
    start(); await flushPromises()
    const call = Array.from(document.querySelectorAll('button')).find(item => item.textContent?.includes('试调用')
      && !item.disabled && item.closest('form'))
    expect(call).toBeDefined()
    call!.click(); await flushPromises()
    expect(mocks.invoke.mock.calls[0]?.[1]).toMatchObject({ connectionRevision: 6 })
    vi.unstubAllGlobals()
  })
})
