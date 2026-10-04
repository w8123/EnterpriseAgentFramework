import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { Ref } from 'vue'
import ElementPlus from 'element-plus'
import HttpApiDetail from './HttpApiDetail.vue'
import BusinessMethodInvocationInputEditor from '@/views/capability/components/BusinessMethodInvocationInputEditor.vue'

const mocks = vi.hoisted(() => ({ detail: vi.fn(), connection: vi.fn(), references: vi.fn(), credentials: vi.fn(),
  invoke: vi.fn(), query: vi.fn(), session: null as Ref<string> | null, user: null as Ref<any> | null,
  route: null as { params: { id: string }; query: object } | null,
  scope: null as { requestKey: Ref<string>; requestParams: Ref<{ projectId: number }> } | null }))
vi.mock('@/api/httpApi', () => ({ getHttpApi: mocks.detail, getHttpApiConnection: mocks.connection, getHttpApiReferences: mocks.references,
  invokeHttpApi: mocks.invoke, getHttpApiInvocation: mocks.query, acceptHttpApi: vi.fn(), saveHttpApiConnection: vi.fn() }))
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
  return { useRoute: () => mocks.route, useRouter: () => ({ push: vi.fn() }) }
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
  stubs: { RouterLink: { props: ['to'], template: '<a><slot /></a>' } } } }) }
async function click(text: string) {
  const button = Array.from(document.querySelectorAll('button')).find(item => item.textContent?.trim() === text)
  if (!button) throw new Error(`Missing action: ${text}`)
  button.click(); await flushPromises()
}
async function input(note = 'isolated-note') {
  await wrapper!.find('input[aria-label="orderId"]').setValue('ORD-3B4')
  const editor = wrapper!.findComponent(BusinessMethodInvocationInputEditor)
  editor.vm.$emit('update:modelValue', { note, apiKey: 'SENSITIVE_BODY_SENTINEL', notify: false })
  editor.vm.$emit('draft-change', { valid: true, revision: 1 }); await flushPromises()
}
async function submitted() { await input(); await click('确认写入试调用'); await click('确认并写入一次') }
beforeEach(() => {
  vi.clearAllMocks(); localStorage.clear(); mocks.session!.value = 'session-a'
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
    expect(editor.props('modelValue').priority).toBeNull()
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
    editor.vm.$emit('update:modelValue', { note: 'one', apiKey: 'test-input', accessCode: privateA, deliveryMark: privateB })
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
    expect(wrapper!.findComponent(BusinessMethodInvocationInputEditor).props('modelValue')).toEqual({})
    expect(document.body.textContent).toContain('缺少此项目的试调用权限')
  })

  it('rejects a queried outcome with another environment and keeps the ID query-only', async () => {
    start(); await flushPromises(); await submitted(); mocks.query.mockResolvedValueOnce({ data: { ...result(), environment: 'production', result: 'FOREIGN_PRIVATE_SENTINEL' } })
    await click('查询原调用'); expect(document.body.textContent).not.toContain('FOREIGN_PRIVATE_SENTINEL')
    expect(document.body.textContent).toContain('调用记录身份无法确认'); expect(document.body.textContent).toContain(firstId)
  })
})
