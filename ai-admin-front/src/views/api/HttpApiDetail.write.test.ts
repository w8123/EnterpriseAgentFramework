import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { Ref } from 'vue'
import ElementPlus from 'element-plus'
import HttpApiDetail from './HttpApiDetail.vue'
import BusinessMethodInvocationInputEditor from '@/views/capability/components/BusinessMethodInvocationInputEditor.vue'

const mocks = vi.hoisted(() => ({ detail: vi.fn(), connection: vi.fn(), references: vi.fn(), credentials: vi.fn(), save: vi.fn(),
  invoke: vi.fn(), query: vi.fn(), push: vi.fn(), leaveGuard: null as null | (() => boolean | Promise<boolean>), session: null as Ref<string> | null, user: null as Ref<any> | null,
  route: null as { params: { id: string }; query: object } | null,
  scope: null as { requestKey: Ref<string>; requestParams: Ref<{ projectId: number }> } | null }))
vi.mock('@/api/httpApi', () => ({ getHttpApi: mocks.detail, getHttpApiConnection: mocks.connection, getHttpApiReferences: mocks.references,
  invokeHttpApi: mocks.invoke, getHttpApiInvocation: mocks.query, acceptHttpApi: vi.fn(), saveHttpApiConnection: mocks.save }))
vi.mock('@/api/workflowCredential', () => ({ listWorkflowCredentials: mocks.credentials }))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue'); mocks.session = ref('session-a'); mocks.user = ref({ userId: 7, permissionGrants: [] })
  return { platformSessionId: mocks.session, platformSessionUser: mocks.user }
})
vi.mock('@/composables/usePageProjectScope', async () => {
  const { ref } = await import('vue')
  mocks.scope = { requestKey: ref('orders:41'), requestParams: ref({ projectId: 41 }) }
  return { usePageProjectScope: () => ({ ...mocks.scope, canLoadData: ref(true), status: ref('ready'), feedbackMessage: ref(''), recoveryAction: ref('') }) }
})
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue'); mocks.route = reactive({ params: { id: '1' }, query: {} })
  return { useRoute: () => mocks.route, useRouter: () => ({ push: mocks.push }), onBeforeRouteLeave: (guard: () => boolean | Promise<boolean>) => { mocks.leaveGuard = guard } }
})

const hash = 'a'.repeat(64), source = 'b'.repeat(64), credentialRevision = 'c'.repeat(64)
const firstId = '123e4567-e89b-42d3-a456-426614174000', nextId = '123e4567-e89b-42d3-a456-426614174001'
const qualifiedName = 'orders.dev.POST./orders/{orderId}/notes'
const grants = ['platform:read', 'platform:write', 'capability:invoke'].map(permissionCode => ({ permissionCode, scopeType: 'PROJECT', scopeValue: 'orders' }))
function owner(environment = 'dev', projectCode = 'orders', id = 1) {
  const contract = { identity: { method: 'POST', routeTemplate: '/orders/{orderId}/notes' }, sideEffect: 'WRITE',
    authentication: { state: 'NONE', schemes: [], requiredHeaderNames: [] },
    parameters: [{ name: 'orderId', location: 'PATH', required: true, schema: { type: 'string' }, contentTypes: [] }],
    requestBody: { required: true, contentTypes: ['application/json'], schema: { type: 'object', required: ['note', 'apiKey'], properties: {
      note: { type: 'string', minLength: 1 }, apiKey: { type: 'string' }, notify: { type: 'boolean' }, priority: { type: 'integer' },
    } } }, responses: [] }
  return { summary: { id, qualifiedName, projectId: 41, projectCode, environment, httpMethod: 'POST',
    routeTemplate: '/orders/{orderId}/notes', sourceStatus: 'ACCEPTED', sourceConfirmed: true,
    candidateContractHash: hash, acceptedContractHash: hash, sourceSetRevision: source }, contract, acceptedContract: contract, sources: [] }
}
function result(id = firstId, status = 'UNKNOWN') { return { contractVersion: 1, invocationId: id, targetType: 'HTTP_API', qualifiedName,
  projectId: 41, projectCode: 'orders', environment: 'dev', runId: 7, traceId: 'trace-7', status, dispatchStage: 'UNCONFIRMED', terminal: true } }
let wrapper: ReturnType<typeof mount> | null = null
function start() { wrapper = mount(HttpApiDetail, { attachTo: document.body, global: { plugins: [ElementPlus],
  stubs: { CapabilityEnrichmentPanel: true, RouterLink: { props: ['to'], template: '<a><slot /></a>' } } } }) }
async function click(text: string) {
  const button = Array.from(document.querySelectorAll('button')).find(item => item.textContent?.trim() === text)
  if (!button) throw new Error(`Missing action: ${text}`)
  button.click(); await flushPromises()
}
async function input(note = 'isolated-note') {
  await wrapper!.find('input[aria-label="orderId"]').setValue('ORD-3B4')
  const editor = wrapper!.findComponent(BusinessMethodInvocationInputEditor)
  editor.vm.$emit('http-body-change', { note, apiKey: 'SENSITIVE_BODY_SENTINEL', notify: false })
  editor.vm.$emit('draft-change', { valid: true, revision: 1 }); await flushPromises()
}
async function submitted() { await input(); await click('确认写入试调用'); await click('确认并写入一次') }
beforeEach(() => {
  vi.clearAllMocks(); localStorage.clear(); mocks.session!.value = 'session-a'
  mocks.save.mockReset()
  mocks.user!.value = { userId: 7, permissionGrants: grants.map(item => ({ ...item })) }
  mocks.route!.params.id = '1'; mocks.scope!.requestKey.value = 'orders:41'; mocks.scope!.requestParams.value = { projectId: 41 }
  mocks.detail.mockResolvedValue({ data: owner() })
  mocks.connection.mockResolvedValue({ data: { status: 'CONFIGURED', revision: 1, credentialRevision, origin: 'http://127.0.0.1:1', authMode: 'NONE' } })
  mocks.credentials.mockResolvedValue({ data: [] }); mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE', references: [] } })
  mocks.invoke.mockResolvedValue({ data: result() }); mocks.query.mockResolvedValue({ data: result() })
  let count = 0; vi.stubGlobal('crypto', { randomUUID: () => count++ === 0 ? firstId : nextId })
})
afterEach(() => { wrapper?.unmount(); wrapper = null; document.body.innerHTML = ''; vi.unstubAllGlobals() })

describe('write HTTP API confirmation and query-only recovery', () => {
  it('allows correcting an owner-approved blocked connection without enabling invocation before save', async () => {
    mocks.connection.mockResolvedValue({ data: { status: 'BLOCKED', configurationAllowed: true,
      revision: 4, credentialRevision, origin: 'http://old.invalid', authMode: 'NONE',
      blockingReason: '服务地址当前不可用，请检查连接配置' } })
    const { saveHttpApiConnection } = await import('@/api/httpApi')
    vi.mocked(saveHttpApiConnection).mockResolvedValueOnce({ data: { status: 'CONFIGURED', configurationAllowed: true,
      revision: 5, credentialRevision, origin: 'http://127.0.0.1:8080', authMode: 'NONE' } } as never)
    start(); await flushPromises()
    expect(Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === '确认写入试调用')?.disabled).toBe(true)
    const save = Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === '保存连接')!
    expect(save.disabled).toBe(false)
    await wrapper!.find('input[placeholder="https://orders.example.com:8443"]').setValue('http://127.0.0.1:8080')
    await click('保存连接')
    expect(saveHttpApiConnection).toHaveBeenCalledWith(1, { origin: 'http://127.0.0.1:8080', authMode: 'NONE', credentialRef: null, expectedRevision: 4 })
  })
  it('keeps explicit owner refusal and unconfirmed sources blocked even through form submission', async () => {
    const { saveHttpApiConnection } = await import('@/api/httpApi')
    mocks.connection.mockResolvedValue({ data: { status: 'CONFIGURED', configurationAllowed: false,
      revision: 1, origin: 'http://127.0.0.1:8080', authMode: 'NONE' } })
    start(); await flushPromises()
    const save = () => Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === '保存连接')!
    expect(save().disabled).toBe(true)
    await wrapper!.findAll('form')[1]!.trigger('submit'); await flushPromises()
    expect(saveHttpApiConnection).not.toHaveBeenCalled()
    mocks.connection.mockResolvedValue({ data: { status: 'UNKNOWN', revision: 1,
      origin: 'http://127.0.0.1:8080', authMode: 'NONE' } })
    await click('刷新当前状态')
    expect(save().disabled).toBe(true)
    const unconfirmed = owner(); unconfirmed.summary.sourceConfirmed = false
    mocks.detail.mockResolvedValue({ data: unconfirmed })
    mocks.connection.mockResolvedValue({ data: { status: 'BLOCKED', configurationAllowed: true,
      revision: 1, origin: 'http://127.0.0.1:8080', authMode: 'NONE' } })
    await click('刷新当前状态')
    expect(save().disabled).toBe(true)
    await wrapper!.findAll('form')[1]!.trigger('submit'); await flushPromises()
    expect(saveHttpApiConnection).not.toHaveBeenCalled()
  })
  it('replaces an inactive credential only after the current project list is loaded', async () => {
    const declaration = owner(); declaration.acceptedContract.authentication.state = 'UNKNOWN'
    mocks.detail.mockResolvedValue({ data: declaration })
    mocks.connection.mockResolvedValue({ data: { status: 'BLOCKED', configurationAllowed: true, revision: 4,
      origin: 'http://127.0.0.1:8080', authMode: 'BEARER', credentialRef: 'disabled', credentialRevision: null } })
    mocks.credentials.mockResolvedValue({ data: [{ id: 8, credentialRef: 'active', name: 'Active fixture', type: 'BEARER',
      scope: 'PROJECT', status: 'ACTIVE', projectId: 41, projectCode: 'orders' }] })
    const { saveHttpApiConnection } = await import('@/api/httpApi')
    vi.mocked(saveHttpApiConnection).mockResolvedValueOnce({ data: { status: 'CONFIGURED', configurationAllowed: true,
      revision: 5, credentialRevision: 'd'.repeat(64), origin: 'http://127.0.0.1:8080', authMode: 'BEARER', credentialRef: 'active' } } as never)
    start(); await flushPromises()
    const save = () => Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === '保存连接')!
    expect(save().disabled).toBe(true)
    await wrapper!.findAll('[role="tab"]').find(tab => tab.text() === '接入信息')!.trigger('click'); await flushPromises()
    const selector = wrapper!.findAllComponents({ name: 'ElSelect' }).find(component => component.props('placeholder') === '选择当前项目的活动凭据')!
    selector.vm.$emit('update:modelValue', 'active'); await flushPromises()
    await click('保存连接')
    expect(saveHttpApiConnection).toHaveBeenCalledWith(1, { origin: 'http://127.0.0.1:8080', authMode: 'BEARER', credentialRef: 'active', expectedRevision: 4 })
    await input(); await click('确认写入试调用'); await click('确认并写入一次')
    expect(mocks.invoke.mock.calls[0][1]).toMatchObject({ connectionRevision: 5, expectedCredentialRevision: 'd'.repeat(64) })
  })
  it('invalidates an open write confirmation after a repaired connection revision is saved', async () => {
    const { saveHttpApiConnection } = await import('@/api/httpApi')
    vi.mocked(saveHttpApiConnection).mockResolvedValueOnce({ data: { status: 'CONFIGURED', configurationAllowed: true,
      revision: 2, credentialRevision: 'd'.repeat(64), origin: 'http://127.0.0.1:8080', authMode: 'NONE' } } as never)
    start(); await flushPromises(); await input(); await click('确认写入试调用')
    await wrapper!.find('input[placeholder="https://orders.example.com:8443"]').setValue('http://127.0.0.1:8080')
    await click('保存连接')
    expect(wrapper!.findAllComponents({ name: 'AppDialog' })[0].props('modelValue')).toBe(false)
    expect(mocks.invoke).not.toHaveBeenCalled()
    await click('确认写入试调用'); await click('确认并写入一次')
    expect(mocks.invoke.mock.calls[0][1]).toMatchObject({ connectionRevision: 2, expectedCredentialRevision: 'd'.repeat(64) })
  })
  it.each(['IDEMPOTENT_WRITE', 'IRREVERSIBLE'])('requires explicit confirmation for %s operations even without a JSON body', async effect => {
    const declaration = owner()
    Object.assign(declaration.acceptedContract, { sideEffect: effect, requestBody: null,
      identity: { method: 'DELETE', routeTemplate: '/orders/{orderId}/notes' } })
    Object.assign(declaration.summary, { httpMethod: 'DELETE' })
    mocks.detail.mockResolvedValue({ data: declaration }); start(); await flushPromises()
    await wrapper!.find('input[aria-label="orderId"]').setValue('ORD-3B4')
    await click('确认写入试调用')
    expect(mocks.invoke).not.toHaveBeenCalled()
    await click('确认并写入一次')
    expect(mocks.invoke).toHaveBeenCalledTimes(1)
    expect(mocks.invoke.mock.calls[0][1].confirmedSideEffect).toBe(true)
    expect(mocks.invoke.mock.calls[0][1].body).toBeUndefined()
  })

  it('sends a declared root-array body as the array after confirmation and rejects malformed drafts', async () => {
    const declaration = owner()
    Object.assign(declaration.acceptedContract, { requestBody: { required: true, contentTypes: ['application/json'],
      schema: { type: 'array', items: { type: 'object', required: ['moduleId'], properties: {
        moduleId: { type: 'string' }, directors: { type: 'array', items: { type: 'string' } },
      } } } } })
    mocks.detail.mockResolvedValue({ data: declaration }); start(); await flushPromises()
    await wrapper!.find('input[aria-label="orderId"]').setValue('ORD-3B4')
    const editor = wrapper!.findComponent(BusinessMethodInvocationInputEditor)
    if (editor.find('input[value="json"]').exists()) await editor.find('input[value="json"]').setValue()
    await editor.find('textarea').setValue('[{"moduleId":"LM-test",')
    await click('确认写入试调用'); expect(mocks.invoke).not.toHaveBeenCalled()
    expect(editor.find('textarea').element.value).toBe('[{"moduleId":"LM-test",')
    await editor.find('textarea').setValue('[{"moduleId":"LM-test","directors":[]}]')
    await click('确认写入试调用'); expect(mocks.invoke).not.toHaveBeenCalled()
    await click('确认并写入一次')
    expect(mocks.invoke.mock.calls[0][1].body).toEqual([{ moduleId: 'LM-test', directors: [] }])
  })

  it('submits a reviewed read-only POST query body without a write confirmation', async () => {
    const declaration = owner()
    Object.assign(declaration.acceptedContract, { sideEffect: 'READ_ONLY' })
    mocks.detail.mockResolvedValue({ data: declaration }); start(); await flushPromises(); await input()
    await click('试调用')
    expect(mocks.invoke).toHaveBeenCalledTimes(1)
    expect(mocks.invoke.mock.calls[0][1].confirmedSideEffect).toBe(false)
    expect(mocks.invoke.mock.calls[0][1].body).toEqual({ note: 'isolated-note', apiKey: 'SENSITIVE_BODY_SENTINEL', notify: false })
  })

  it('keeps a text/plain request body as the logical string without wrapping or trimming it', async () => {
    const declaration = owner()
    Object.assign(declaration.acceptedContract, { requestBody: { required: true, contentTypes: ['text/plain'], schema: { type: 'string' } } })
    mocks.detail.mockResolvedValue({ data: declaration }); start(); await flushPromises()
    await wrapper!.find('input[aria-label="orderId"]').setValue('ORD-3B4')
    const editor = wrapper!.findComponent(BusinessMethodInvocationInputEditor)
    await editor.find('textarea').setValue(JSON.stringify('  exact text\n'))
    await click('确认写入试调用'); await click('确认并写入一次')
    expect(mocks.invoke.mock.calls[0][1].body).toBe('  exact text\n')
  })

  it('blocks an unknown operation effect', async () => {
    const declaration = owner()
    Object.assign(declaration.acceptedContract, { sideEffect: 'UNKNOWN' })
    mocks.detail.mockResolvedValue({ data: declaration }); start(); await flushPromises()
    expect(Array.from(document.querySelectorAll('button')).find(item => item.textContent?.trim() === '试调用')?.disabled).toBe(true)
    expect(mocks.invoke).not.toHaveBeenCalled()
  })

  it('keeps multipart blocked instead of inferring JSON from the editable body', async () => {
    const declaration = owner()
    declaration.acceptedContract.requestBody.contentTypes = ['multipart/form-data']
    mocks.detail.mockResolvedValue({ data: declaration })
    mocks.connection.mockResolvedValue({ data: { status: 'BLOCKED', blockingReason: '暂不支持 multipart 请求体', revision: null } })
    start(); await flushPromises(); await input(); await click('确认写入试调用')
    expect(document.body.textContent).toContain('暂不支持 multipart 请求体')
    expect(mocks.invoke).not.toHaveBeenCalled()
  })
  it('protects unsent Console input when returning to the catalog or leaving through navigation', async () => {
    start(); await flushPromises(); await input()
    expect(document.body.textContent).not.toContain('用于 Workflow')
    wrapper!.findComponent({ name: 'PageHeader' }).vm.$emit('back')
    await flushPromises(); expect(mocks.push).not.toHaveBeenCalled()
    await click('继续编辑'); expect(mocks.push).not.toHaveBeenCalled()
    expect(wrapper!.find<HTMLInputElement>('input[aria-label="orderId"]').element.value).toBe('ORD-3B4')
    const leaving = mocks.leaveGuard!()
    await flushPromises(); await click('继续编辑'); expect(await leaving).toBe(false)
    wrapper!.findComponent({ name: 'PageHeader' }).vm.$emit('back')
    await flushPromises(); await click('放弃输入并离开')
    expect(mocks.push).toHaveBeenCalledWith({ path: '/business-capabilities/http-apis', query: {} })
    expect(mocks.invoke).not.toHaveBeenCalled()
  })
  it('locks navigation and section changes while the business explanation is being saved', async () => {
    start(); await flushPromises()
    await wrapper!.find('#tab-description').trigger('click'); await flushPromises()
    wrapper!.findComponent({ name: 'CapabilityEnrichmentPanel' }).vm.$emit('busy-change', true); await flushPromises()
    expect(mocks.leaveGuard!()).toBe(false)
    await wrapper!.find('#tab-contract').trigger('click'); await flushPromises()
    expect(wrapper!.find('#tab-description').attributes('aria-selected')).toBe('true')
    wrapper!.findComponent({ name: 'CapabilityEnrichmentPanel' }).vm.$emit('busy-change', false); await flushPromises()
    expect(mocks.leaveGuard!()).toBe(true)
  })
  it('blocks connection saves for unsupported contracts while allowing ordinary unconfigured connections', async () => {
    mocks.connection.mockResolvedValue({ data: { status: 'BLOCKED', revision: null, origin: null, authMode: null,
      blockingReason: '首批仅支持 JSON 对象响应' } })
    start(); await flushPromises()
    const save = () => Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === '保存连接')!
    expect(save().disabled).toBe(true)
    expect(document.body.textContent).toContain('首批仅支持 JSON 对象响应')
    save().click(); await flushPromises()
    const { saveHttpApiConnection } = await import('@/api/httpApi')
    expect(saveHttpApiConnection).not.toHaveBeenCalled()
    mocks.connection.mockResolvedValue({ data: { status: 'UNCONFIGURED', revision: null, origin: null, authMode: null } })
    await click('刷新当前状态')
    expect(save().disabled).toBe(false)
  })
  it('explains missing JSON media declarations on accepted object responses without enabling a call', async () => {
    const declaration = owner()
    Object.assign(declaration.acceptedContract, { responses: [{ status: '200', schema: { type: 'object' }, contentTypes: [] }] })
    mocks.detail.mockResolvedValue({ data: declaration })
    mocks.connection.mockResolvedValue({ data: { status: 'BLOCKED', blockingReason: '首批仅支持 JSON 对象响应' } })
    start(); await flushPromises()
    expect(document.body.textContent).toContain('produces=application/json')
    expect(document.body.textContent).toContain('重新同步并接纳契约')
    const save = Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === '保存连接')!
    expect(save.disabled).toBe(true)
    expect(mocks.invoke).not.toHaveBeenCalled()
  })
  it('retains the server blocker for declared JSON arrays instead of suggesting a media change', async () => {
    const declaration = owner()
    Object.assign(declaration.acceptedContract, { responses: [{ status: '200', schema: { type: 'array' }, contentTypes: ['application/json'] }] })
    mocks.detail.mockResolvedValue({ data: declaration })
    mocks.connection.mockResolvedValue({ data: { status: 'BLOCKED', blockingReason: '首批仅支持 JSON 对象响应' } })
    start(); await flushPromises()
    expect(document.body.textContent).toContain('首批仅支持 JSON 对象响应')
    expect(document.body.textContent).not.toContain('produces=application/json')
    expect(mocks.invoke).not.toHaveBeenCalled()
  })
  it('blocks explicit optional null before opening a write confirmation or submitting', async () => {
    start(); await flushPromises(); await input()
    const editor = wrapper!.findComponent(BusinessMethodInvocationInputEditor)
    await editor.find('input[value="json"]').setValue()
    await click('显示敏感值并编辑')
    await editor.find('textarea').setValue(JSON.stringify({ note: 'one', apiKey: 'test-input', priority: null }))
    await flushPromises()
    await click('确认写入试调用')
    expect(wrapper!.findAllComponents({ name: 'AppDialog' })[0].props('modelValue')).toBe(false)
    expect(wrapper!.find('.api-warning[role="alert"]').exists()).toBe(true)
    expect(wrapper!.find('.api-warning[role="alert"]').text()).toContain('null')
    expect(mocks.invoke).not.toHaveBeenCalled(); expect(localStorage.length).toBe(0)
    expect(JSON.parse((editor.find('textarea').element as HTMLTextAreaElement).value).priority).toBeNull()
  })

  it('masks ordinary-named source-declared sensitive fields in the real shared editor and JSON preview', async () => {
    const declaration = owner()
    Object.assign(declaration.acceptedContract.requestBody.schema.properties, {
      accessCode: { type: 'string', format: 'password' }, deliveryMark: { type: 'string', writeOnly: true },
    })
    mocks.detail.mockResolvedValue({ data: declaration }); start(); await flushPromises()
    const editor = wrapper!.findComponent(BusinessMethodInvocationInputEditor)
    const privateA = Math.random().toString(36) + Math.random().toString(36)
    const privateB = Math.random().toString(36) + Math.random().toString(36)
    editor.vm.$emit('http-body-change', { note: 'one', apiKey: 'test-input', accessCode: privateA, deliveryMark: privateB })
    editor.vm.$emit('draft-change', { valid: true, revision: 2 }); await flushPromises()
    expect(editor.find('button[aria-label="显示并编辑 accessCode 的敏感值"]').exists()).toBe(true)
    expect(editor.find('button[aria-label="显示并编辑 deliveryMark 的敏感值"]').exists()).toBe(true)
    expect(document.body.textContent!.includes(privateA) || document.body.textContent!.includes(privateB)).toBe(false)
    editor.find('button[aria-label="显示并编辑 accessCode 的敏感值"]').element.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises(); expect(editor.find('input[type="password"]').exists()).toBe(true)
    await editor.find('button[aria-label="隐藏 accessCode 的敏感值"]').trigger('click')
    await editor.find('input[value="json"]').setValue()
    expect(editor.text().includes('敏感字段已隐藏')).toBe(true)
    expect(document.body.textContent!.includes(privateA) || document.body.textContent!.includes(privateB)).toBe(false)
    expect((editor.find('textarea').element as HTMLTextAreaElement).disabled).toBe(true)
    expect((editor.find('textarea').element as HTMLTextAreaElement).value.includes(privateA)
      || (editor.find('textarea').element as HTMLTextAreaElement).value.includes(privateB)).toBe(false)
    expect(mocks.invoke).not.toHaveBeenCalled()
  })

  it('cancels with zero POST and binds one confirmation to exact body, owner and connection facts', async () => {
    start(); await flushPromises(); await input(); await click('确认写入试调用')
    expect(mocks.invoke).not.toHaveBeenCalled(); expect(document.body.textContent).toContain('不是模拟或只读验证')
    await click('取消'); expect(mocks.invoke).not.toHaveBeenCalled(); expect(localStorage.length).toBe(0)
    await click('确认写入试调用'); await click('确认并写入一次')
    expect(mocks.invoke).toHaveBeenCalledTimes(1)
    const payload = mocks.invoke.mock.calls[0][1]
    expect(payload.confirmedSideEffect).toBe(true); expect(payload.expectedSourceSetRevision).toBe(source)
    expect(payload.expectedCredentialRevision).toBe(credentialRevision); expect(payload.body.notify).toBe(false)
    expect(payload.body.note).toBe('isolated-note'); expect(payload).not.toHaveProperty('url')
    const stored = localStorage.getItem(localStorage.key(0)!)!
    expect(stored).not.toContain('SENSITIVE_BODY_SENTINEL'); expect(stored).not.toContain('isolated-note')
    expect(document.body.textContent).not.toContain('SENSITIVE_BODY_SENTINEL')
    expect(Object.keys(JSON.parse(stored)[0]).sort()).toEqual(['createdAt', 'invocationId'])
  })

  it('invalidates confirmation on body or path changes and rereading, without sending a stale snapshot', async () => {
    start(); await flushPromises(); await input(); await click('确认写入试调用')
    await input('changed-note'); expect(mocks.invoke).not.toHaveBeenCalled()
    expect(wrapper!.findAllComponents({ name: 'AppDialog' })[0].props('modelValue')).toBe(false)
    await click('确认写入试调用'); await wrapper!.find('input[aria-label="orderId"]').setValue('another')
    expect(wrapper!.findAllComponents({ name: 'AppDialog' })[0].props('modelValue')).toBe(false)
    await click('确认写入试调用'); await click('刷新当前状态')
    expect(mocks.invoke).not.toHaveBeenCalled(); expect(wrapper!.findAllComponents({ name: 'AppDialog' })[0].props('modelValue')).toBe(false)
  })

  it('does not double submit and holds the same ID through public loss and query failure', async () => {
    let reject!: (reason: unknown) => void
    mocks.invoke.mockImplementationOnce(() => new Promise((_resolve, no) => { reject = no }))
    start(); await flushPromises(); await input(); await click('确认写入试调用'); await click('确认并写入一次')
    expect(mocks.invoke).toHaveBeenCalledTimes(1); expect(document.body.textContent).toContain(firstId)
    reject(new Error('public response lost')); await flushPromises()
    mocks.query.mockRejectedValueOnce(new Error('query offline')); await click('查询原调用')
    expect(document.body.textContent).toContain('无法据此判断请求未发出'); expect(document.body.textContent).toContain(firstId)
    await click('查询原调用'); expect(mocks.invoke).toHaveBeenCalledTimes(1); expect(mocks.query.mock.calls.every(call => call[0] === firstId)).toBe(true)
  })

  it('reopens after UNKNOWN with read-only query and uses a new ID only after warning and new confirmation', async () => {
    start(); await flushPromises(); await submitted(); wrapper!.unmount(); wrapper = null
    start(); await flushPromises(); expect(mocks.invoke).toHaveBeenCalledTimes(1)
    expect(mocks.query).toHaveBeenCalledWith(firstId, 'orders'); expect(document.body.textContent).toContain('本次可能已经执行')
    await click('发起新调用'); expect(document.body.textContent).toContain('不是恢复查询')
    await click('继续查询原调用'); expect(document.body.textContent).toContain(firstId)
    await click('发起新调用'); await click('使用新 ID 准备新调用')
    expect(mocks.invoke).toHaveBeenCalledTimes(1); await input('new-note'); await click('确认写入试调用')
    expect(mocks.invoke).toHaveBeenCalledTimes(1); await click('确认并写入一次')
    expect(mocks.invoke).toHaveBeenCalledTimes(2); expect(mocks.invoke.mock.calls[1][1].invocationId).toBe(nextId)
  })

  it('does not restore another account, project, environment or API handle', async () => {
    start(); await flushPromises(); await submitted(); wrapper!.unmount(); wrapper = null
    for (const change of ['account', 'project', 'environment', 'api']) {
      mocks.user!.value = { userId: change === 'account' ? 8 : 7, permissionGrants: grants }
      mocks.detail.mockResolvedValue({ data: owner(change === 'environment' ? 'test' : 'dev', change === 'project' ? 'other' : 'orders', change === 'api' ? 2 : 1) })
      mocks.route!.params.id = change === 'api' ? '2' : '1'
      mocks.query.mockClear(); start(); await flushPromises()
      expect(mocks.query).not.toHaveBeenCalled(); expect(document.body.textContent).not.toContain(firstId)
      wrapper!.unmount(); wrapper = null
    }
  })

  it('discards a late POST after a changed account and clears input/results immediately on revoked permission', async () => {
    let settle!: (value: unknown) => void
    mocks.invoke.mockImplementationOnce(() => new Promise(resolve => { settle = resolve }))
    start(); await flushPromises(); await submitted()
    mocks.user!.value = { userId: 8, permissionGrants: grants }; await flushPromises()
    settle({ data: { ...result(firstId, 'SUCCEEDED'), result: 'LATE_PRIVATE_SENTINEL' } }); await flushPromises()
    expect(document.body.textContent).not.toContain('LATE_PRIVATE_SENTINEL'); expect(document.body.textContent).not.toContain(firstId)
    await input(); mocks.user!.value.permissionGrants = []; await flushPromises()
    expect(wrapper!.findComponent(BusinessMethodInvocationInputEditor).props('modelValue')).toBeUndefined()
    expect(document.body.textContent).toContain('缺少此项目的试调用权限')
  })

  it('rejects a queried outcome with another environment and keeps the ID query-only', async () => {
    start(); await flushPromises(); await submitted(); mocks.query.mockResolvedValueOnce({ data: { ...result(), environment: 'production', result: 'FOREIGN_PRIVATE_SENTINEL' } })
    await click('查询原调用'); expect(document.body.textContent).not.toContain('FOREIGN_PRIVATE_SENTINEL')
    expect(document.body.textContent).toContain('调用记录身份无法确认'); expect(document.body.textContent).toContain(firstId)
  })
})
