import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
import {
  getBusinessMethodInvocation,
  getBusinessMethodInvocationContext,
  invokeBusinessMethod,
} from '@/api/businessMethodInvocation'
import BusinessMethodInvocationPanel from './BusinessMethodInvocationPanel.vue'

const session = vi.hoisted(() => ({ user: null as any, push: vi.fn(), copy: vi.fn() }))
vi.mock('@/api/businessMethodInvocation', () => ({
  getBusinessMethodInvocation: vi.fn(),
  getBusinessMethodInvocationContext: vi.fn(),
  invokeBusinessMethod: vi.fn(),
}))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue')
  session.user = ref(null)
  return { platformSessionUser: session.user }
})
vi.mock('vue-router', () => ({ useRouter: () => ({ push: session.push }) }))
vi.mock('@/utils/aiCodingClipboard', () => ({ copyAiCodingText: session.copy }))

const hash = 'a'.repeat(64)
const invocationId = '123e4567-e89b-42d3-a456-426614174000'
function grants(includeInvoke = true) {
  return [
    { permissionCode: 'platform:read', scopeType: 'PROJECT', scopeValue: 'orders' },
    ...(includeInvoke ? [{ permissionCode: 'capability:invoke', scopeType: 'PROJECT', scopeValue: 'orders' }] : []),
    { permissionCode: 'runops:read', scopeType: 'PROJECT', scopeValue: 'orders' },
  ]
}
function context(sideEffect = 'READ') {
  return {
    contractVersion: 1, name: 'orders.lookup', assetType: 'BUSINESS_METHOD', projectId: 8, projectCode: 'orders',
    qualifiedName: 'orders.lookup', currentContractHash: hash, executionRevision: 'd'.repeat(64), acceptedContractHash: hash, sourceContractHash: hash,
    sourceAvailability: 'READY', enabled: true, credentialAvailable: true, businessIdentityRequired: false,
    executable: true, parameters: [{ name: 'id', type: 'string', description: '订单标识', required: true }],
    sideEffect, targetDescription: '订单项目实例', timeoutMs: 12000,
  }
}
function outcome(overrides: Record<string, unknown> = {}) {
  return {
    contractVersion: 1, invocationId, runId: 7, traceId: 'trace-7', projectId: 8, projectCode: 'orders',
    qualifiedName: 'orders.lookup', identityMode: 'PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY',
    status: 'SUCCEEDED', dispatchStage: 'CONFIRMED', terminal: true, result: { accepted: true }, ...overrides,
  }
}

describe('BusinessMethodInvocationPanel', () => {
  let wrapper: ReturnType<typeof mount>
  function start(props: Record<string, unknown> = {}) {
    wrapper = mount(BusinessMethodInvocationPanel, {
      props: { methodName: 'orders.lookup', projectCode: 'orders', contextKey: 'account-a|project:8', active: true, ...props },
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
    })
  }
  function button(label: string) {
    return Array.from(document.querySelectorAll('button')).find((item) => item.textContent?.trim() === label) as HTMLButtonElement
  }
  function valueInput() {
    return wrapper.find('input[type="text"], input[type="password"]')
  }
  function fieldItem(name: string) {
    const item = wrapper.findAll('.el-form-item').find((candidate) => candidate.text().includes(name))
    if (!item) throw new Error(`missing field ${name}`)
    return item
  }
  function renderedValues() {
    return [
      document.body.textContent || '',
      ...Array.from(document.querySelectorAll('input, textarea')).map((item) => (item as HTMLInputElement | HTMLTextAreaElement).value),
    ].join('\n')
  }

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    vi.stubGlobal('crypto', { randomUUID: () => invocationId })
    session.user.value = { userId: 42, username: 'tester', permissionGrants: grants() }
    session.copy.mockResolvedValue({ copied: true })
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: context() } as any)
    vi.mocked(invokeBusinessMethod).mockResolvedValue({ data: outcome() } as any)
  })
  afterEach(() => {
    wrapper?.unmount()
    document.body.innerHTML = ''
    vi.unstubAllGlobals()
  })

  it('submits the normal project-scoped read path once and exposes only the safe result', async () => {
    start(); await flushPromises()
    expect(document.body.textContent).toContain('使用项目接入凭据调用；不携带业务用户身份，业务系统仍会校验')
    await valueInput().setValue('A-0')
    button('开始试调用').click(); await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledWith('orders.lookup', expect.objectContaining({
      invocationId, confirmedSideEffect: false, input: { id: 'A-0' },
    }))
    expect(document.body.textContent).toContain('调用已确认成功')
    expect(document.body.textContent).toContain('安全返回结果')
    const dirtyEvents = wrapper.emitted('dirty-change') || []
    expect(dirtyEvents[dirtyEvents.length - 1]).toEqual([false])
    expect(document.body.textContent).toContain('运行 ID')
    button('复制 Run ID').click(); await flushPromises()
    expect(session.copy).toHaveBeenCalledWith('7')
    button('查看 RunOps 追踪').click(); await flushPromises()
    expect(session.push).toHaveBeenCalledWith({ name: 'RunOpsDetail', params: { traceId: 'trace-7' } })
  })

  it('requires an explicit second confirmation for writes and does not duplicate a pending submit', async () => {
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: context('WRITE') } as any)
    let settle!: (value: unknown) => void
    vi.mocked(invokeBusinessMethod).mockImplementationOnce(() => new Promise(resolve => { settle = resolve }) as any)
    start(); await flushPromises(); await valueInput().setValue('A-1')
    button('开始试调用').click(); await flushPromises()
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('确认写入试调用')
    await valueInput().setValue('A-1-updated')
    await flushPromises()
    expect(document.body.textContent).not.toContain('确认写入试调用')
    button('开始试调用').click(); await flushPromises()
    button('确认并调用').click()
    button('确认并调用').click()
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)
    settle({ data: outcome() })
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledWith('orders.lookup', expect.objectContaining({ confirmedSideEffect: true }))
  })

  it('cancels a write with zero POSTs and invalidates its confirmation after a contract refresh', async () => {
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: context('WRITE') } as any)
    start(); await flushPromises(); await valueInput().setValue('ORD-2E')
    button('开始试调用').click(); await flushPromises()
    expect(document.activeElement).toBe(button('取消'))
    button('取消').click(); await flushPromises()
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
    expect(localStorage.length).toBe(0)
    expect((valueInput().element as HTMLInputElement).value).toBe('ORD-2E')
    button('开始试调用').click(); await flushPromises()
    const nextHash = 'b'.repeat(64)
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: {
      ...context('WRITE'), currentContractHash: nextHash, acceptedContractHash: nextHash, sourceContractHash: nextHash,
    } } as any)
    button('刷新调用条件').click(); await flushPromises()
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
    button('开始试调用').click(); await flushPromises()
    button('确认并调用').click(); await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledExactlyOnceWith('orders.lookup', expect.objectContaining({
      expectedContractHash: nextHash, expectedExecutionRevision: 'd'.repeat(64), input: { id: 'ORD-2E' }, confirmedSideEffect: true,
    }))
  })

  it('invalidates write confirmation when execution binding changes with the same contract', async () => {
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: context('WRITE') } as any)
    start(); await flushPromises(); await valueInput().setValue('ORD-BINDING')
    button('开始试调用').click(); await flushPromises()
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(true)
    const executionRevision = 'e'.repeat(64)
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: {
      ...context('WRITE'), executionRevision,
    } } as any)
    button('刷新调用条件').click(); await flushPromises()
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false)
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
    button('开始试调用').click(); await flushPromises()
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
    button('确认并调用').click(); await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledExactlyOnceWith('orders.lookup', expect.objectContaining({
      expectedContractHash: hash, expectedExecutionRevision: executionRevision,
      input: { id: 'ORD-BINDING' }, confirmedSideEffect: true,
    }))
  })

  it('requires a new id and a fresh write confirmation after UNKNOWN; query failures never resend', async () => {
    const nextId = '223e4567-e89b-42d3-a456-426614174000'
    const ids = [invocationId, nextId]
    vi.stubGlobal('crypto', { randomUUID: () => ids.shift() || '' })
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: context('WRITE') } as any)
    vi.mocked(invokeBusinessMethod)
      .mockResolvedValueOnce({ data: outcome({ status: 'UNKNOWN', dispatchStage: 'UNCONFIRMED', result: null }) } as any)
      .mockResolvedValueOnce({ data: outcome({ invocationId: nextId }) } as any)
    vi.mocked(getBusinessMethodInvocation).mockRejectedValue({ response: { status: 503 } })
    start(); await flushPromises(); await valueInput().setValue('ORD-2E')
    button('开始试调用').click(); await flushPromises(); button('确认并调用').click(); await flushPromises()
    button('查询当前调用').click(); await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)
    expect(getBusinessMethodInvocation).toHaveBeenCalledWith(invocationId)
    expect(wrapper.text()).toContain('可能已经执行')
    button('新建一次试调用').click(); await flushPromises()
    expect(wrapper.text()).toContain('新的写入试调用仍需单独确认')
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)
    button('开始试调用').click(); await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)
    button('确认并调用').click(); await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenLastCalledWith('orders.lookup', expect.objectContaining({
      invocationId: nextId, confirmedSideEffect: true, input: { id: 'ORD-2E' },
    }))
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(2)
  })

  it('keeps the same id after an unconfirmed POST, supports read-only query, and never auto-resends after 404', async () => {
    vi.mocked(invokeBusinessMethod).mockResolvedValue({ data: {
      success: false, code: 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED', invocationId,
    } } as any)
    vi.mocked(getBusinessMethodInvocation).mockRejectedValue({ response: { status: 404 } })
    start(); await flushPromises(); await valueInput().setValue('A-2')
    button('开始试调用').click(); await flushPromises()
    expect(document.body.textContent).toContain('本次调用结果尚未确认')
    expect(document.body.textContent).toContain('本次可能已经执行')
    button('查询当前调用').click(); await flushPromises()
    expect(document.body.textContent).toContain('尚未找到该调用记录')
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)
    const dirtyEvents = wrapper.emitted('dirty-change') || []
    expect(dirtyEvents[dirtyEvents.length - 1]).toEqual([false])
  })

  it('makes truncation and expired result explicit instead of rendering a false empty success', async () => {
    vi.mocked(invokeBusinessMethod).mockResolvedValue({ data: outcome({
      result: null, resultTruncated: true, message: '[result-expired]',
    }) } as any)
    start(); await flushPromises(); await valueInput().setValue('A-3')
    button('开始试调用').click(); await flushPromises()
    expect(document.body.textContent).toContain('返回结果已截断')
    expect(document.body.textContent).toContain('返回结果已过期')
    expect(document.body.textContent).not.toContain('安全返回结果')
  })

  it('makes NOT_DISPATCHED explicit and allows a separately initiated new attempt', async () => {
    vi.mocked(invokeBusinessMethod).mockResolvedValue({ data: outcome({
      status: 'NOT_DISPATCHED', dispatchStage: 'NOT_DISPATCHED', code: 'INPUT_INVALID', result: { reason: 'REQUIRED' },
    }) } as any)
    start(); await flushPromises(); await valueInput().setValue('A-4')
    button('开始试调用').click(); await flushPromises()
    expect(document.body.textContent).toContain('调用未分派')
    button('新建一次试调用').click(); await flushPromises()
    expect(document.body.textContent).toContain('只有明确提交后才会生成新的调用 ID')
  })

  it('does not load a context or offer a submit when the project invocation grant is absent', async () => {
    session.user.value = { userId: 42, username: 'tester', permissionGrants: grants(false) }
    start(); await flushPromises()
    expect(getBusinessMethodInvocationContext).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('当前账号不能试调用该业务方法')
    expect(button('开始试调用')).toBeUndefined()
  })

  it('keeps an invalid visible JSON draft from submitting the last valid value', async () => {
    start(); await flushPromises()
    await valueInput().setValue('A-valid')
    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await wrapper.find('textarea').setValue('{"id":')
    await flushPromises()

    button('开始试调用').click()
    await wrapper.find('textarea').trigger('keydown', { key: 'Enter', ctrlKey: true })
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(document.body.textContent).toContain('请输入合法的 JSON 对象')
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
  })

  it('invalidates a write confirmation for any visible invalid field edit', async () => {
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: {
      ...context('WRITE'),
      parameters: [{ name: 'count', type: 'integer', description: '数量', required: true }],
    } } as any)
    start(); await flushPromises()
    await valueInput().setValue('7')
    button('开始试调用').click()
    await flushPromises()
    expect(document.body.textContent).toContain('确认写入试调用')

    await valueInput().setValue('not-a-number')
    await flushPromises()

    expect(document.body.textContent).not.toContain('确认写入试调用')
    expect((valueInput().element as HTMLInputElement).value).toBe('not-a-number')
    expect(document.body.textContent).toContain('请输入有限整数')
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
  })

  it('requires an explicit new attempt before a result-unknown call can send another UUID', async () => {
    const firstId = '123e4567-e89b-42d3-a456-426614174000'
    const secondId = '223e4567-e89b-42d3-a456-426614174000'
    const ids = [firstId, secondId]
    vi.stubGlobal('crypto', { randomUUID: () => ids.shift() || '' })
    vi.mocked(invokeBusinessMethod)
      .mockResolvedValueOnce({ data: { success: false, code: 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED', invocationId: firstId } } as any)
      .mockResolvedValueOnce({ data: outcome({ invocationId: secondId }) } as any)
    start(); await flushPromises()
    await valueInput().setValue('A-unknown')
    button('开始试调用').click()
    await flushPromises()

    button('开始试调用').click()
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)

    button('新建一次试调用').click()
    await flushPromises()
    button('开始试调用').click()
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(2)
    expect(vi.mocked(invokeBusinessMethod).mock.calls.map(([, request]) => (request as any).invocationId)).toEqual([firstId, secondId])
  })

  it('preserves a wrapped DTO, null, false, zero, empty strings and undeclared keys when a real form edit follows JSON input', async () => {
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: {
      ...context(),
      parameters: [{
        name: 'request', type: 'object', description: '请求对象', required: true, children: [
          { name: 'id', type: 'string', description: '', required: true },
          { name: 'nullable', type: 'string', description: '', required: false },
          { name: 'empty', type: 'string', description: '', required: false },
          { name: 'enabled', type: 'boolean', description: '', required: false },
          { name: 'count', type: 'integer', description: '', required: false },
        ],
      }],
    } } as any)
    start(); await flushPromises()
    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await wrapper.find('textarea').setValue('{"request":{"id":"before","nullable":null,"empty":"","enabled":false,"count":0},"extra":{"keep":true}}')
    await flushPromises()
    await wrapper.findAll('input[type="radio"]')[0].setValue()
    await valueInput().setValue('after')
    button('开始试调用').click()
    await flushPromises()

    expect(invokeBusinessMethod).toHaveBeenCalledWith('orders.lookup', expect.objectContaining({
      input: { request: { id: 'after', nullable: null, empty: '', enabled: false, count: 0 }, extra: { keep: true } },
    }))
  })

  it.each([
    ['ordinary parameters', 'direct'],
    ['a single DTO in flat form', 'flat'],
    ['a single DTO in wrapped form', 'wrapped'],
  ] as const)('keeps an explicitly cancelled optional field out of the JSON preview and POST for %s', async (_label, shape) => {
    const fields = { id: 'order-1', optional: 'remove-me', nullable: null, enabled: false, count: 0 }
    const input = shape === 'wrapped'
      ? { request: fields, extra: { keep: true } }
      : { ...fields, extra: { keep: true } }
    const expected = shape === 'wrapped'
      ? { request: { id: 'order-1', nullable: null, enabled: false, count: 0 }, extra: { keep: true } }
      : { id: 'order-1', nullable: null, enabled: false, count: 0, extra: { keep: true } }
    const parameterFields = [
      { name: 'id', type: 'string', description: '', required: true },
      { name: 'optional', type: 'string', description: '', required: false },
      { name: 'nullable', type: 'string', description: '', required: false },
      { name: 'enabled', type: 'boolean', description: '', required: false },
      { name: 'count', type: 'integer', description: '', required: false },
    ]
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: {
      ...context(),
      parameters: shape === 'direct'
        ? parameterFields
        : [{ name: 'request', type: 'object', description: '请求对象', required: true, children: parameterFields }],
    } } as any)
    start(); await flushPromises()

    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await wrapper.find('textarea').setValue(JSON.stringify(input))
    await flushPromises()
    await wrapper.findAll('input[type="radio"]')[0].setValue()
    await flushPromises()
    await fieldItem('optional').find('input[type="checkbox"]').setValue(false)
    await flushPromises()

    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await flushPromises()
    expect(JSON.parse((wrapper.find('textarea').element as HTMLTextAreaElement).value)).toEqual(expected)
    expect(JSON.parse((wrapper.find('textarea').element as HTMLTextAreaElement).value)).not.toHaveProperty('optional')
    expect(JSON.stringify(JSON.parse((wrapper.find('textarea').element as HTMLTextAreaElement).value))).not.toContain('remove-me')

    button('开始试调用').click()
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenLastCalledWith('orders.lookup', expect.objectContaining({ input: expected }))
  })

  it('keeps an invalid scalar form draft in place, blocks the mode switch, and only submits its corrected value', async () => {
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: {
      ...context('WRITE'),
      parameters: [{ name: 'count', type: 'integer', description: '数量', required: true }],
    } } as any)
    start(); await flushPromises()
    await valueInput().setValue('7')
    button('开始试调用').click()
    await flushPromises()
    expect(document.body.textContent).toContain('确认写入试调用')

    await valueInput().setValue('not-a-number')
    await flushPromises()
    expect(document.body.textContent).toContain('请输入有限整数')
    expect(document.body.textContent).not.toContain('确认写入试调用')

    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await flushPromises()
    expect((wrapper.findAll('input[type="radio"]')[0].element as HTMLInputElement).checked).toBe(true)
    expect((valueInput().element as HTMLInputElement).value).toBe('not-a-number')
    expect(document.body.textContent).toContain('当前表单有未修正的输入')

    button('开始试调用').click()
    await valueInput().trigger('keydown', { key: 'Enter', ctrlKey: true })
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(invokeBusinessMethod).not.toHaveBeenCalled()

    await valueInput().setValue('8')
    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await flushPromises()
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toContain('"count": 8')
    button('开始试调用').click()
    await flushPromises()
    button('确认并调用').click()
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)
    expect(invokeBusinessMethod).toHaveBeenCalledWith('orders.lookup', expect.objectContaining({ input: { count: 8 } }))
  })

  it('keeps an invalid complex form draft in form mode and never restores its last valid JSON for submission', async () => {
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: {
      ...context(),
      parameters: [{ name: 'filter', type: 'object', description: '筛选条件', required: true }],
    } } as any)
    start(); await flushPromises()
    const complexInput = wrapper.find('.input-form textarea')
    await complexInput.setValue('{"page":1}')
    await complexInput.setValue('{"page":')
    await flushPromises()
    expect(document.body.textContent).toContain('请输入合法 JSON 对象')

    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await flushPromises()
    expect((wrapper.findAll('input[type="radio"]')[0].element as HTMLInputElement).checked).toBe(true)
    expect((wrapper.find('.input-form textarea').element as HTMLTextAreaElement).value).toBe('{"page":')
    expect(document.body.textContent).toContain('当前表单有未修正的输入')

    button('开始试调用').click()
    await wrapper.find('.input-form textarea').trigger('keydown', { key: 'Enter', ctrlKey: true })
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(invokeBusinessMethod).not.toHaveBeenCalled()

    await wrapper.find('.input-form textarea').setValue('{"page":2}')
    button('开始试调用').click()
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledWith('orders.lookup', expect.objectContaining({ input: { filter: { page: 2 } } }))
  })

  it.each([
    ['flat', {
      token: 'FLAT_DIRECT_SECRET_SENTINEL',
      profile: { secret: 'FLAT_NESTED_SECRET_SENTINEL' },
      records: [{ pin: 'FLAT_ARRAY_SECRET_SENTINEL' }],
    }],
    ['wrapped', {
      request: {
        token: 'WRAPPED_DIRECT_SECRET_SENTINEL',
        profile: { secret: 'WRAPPED_NESTED_SECRET_SENTINEL' },
        records: [{ pin: 'WRAPPED_ARRAY_SECRET_SENTINEL' }],
      },
    }],
  ] as const)('masks direct, nested and array sensitive DTO values after %s JSON input and can re-hide an invalid draft', async (_shape, input) => {
    const sentinels = JSON.stringify(input).match(/[A-Z_]+_SECRET_SENTINEL/g) || []
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: {
      ...context(),
      parameters: [{ name: 'request', type: 'object', description: '敏感请求', required: true, children: [
        { name: 'token', type: 'string', description: '', required: true, metadata: { sensitive: true } },
        { name: 'profile', type: 'object', description: '', required: true, children: [
          { name: 'secret', type: 'string', description: '', required: true, metadata: { sensitive: true } },
        ] },
        { name: 'records', type: 'array', description: '', required: true, children: [
          { name: 'pin', type: 'string', description: '', required: true, metadata: { sensitive: true } },
        ] },
      ] }],
    } } as any)
    start(); await flushPromises()

    await wrapper.findAll('input[type="radio"]')[1].setValue()
    button('显示敏感值并编辑').click()
    await flushPromises()
    await wrapper.find('textarea').setValue(JSON.stringify(input))
    await flushPromises()
    button('重新隐藏敏感值').click()
    await flushPromises()
    expect(renderedValues()).toContain('••••••')
    sentinels.forEach((value) => expect(renderedValues()).not.toContain(value))

    await wrapper.findAll('input[type="radio"]')[0].setValue()
    await flushPromises()
    sentinels.forEach((value) => expect(renderedValues()).not.toContain(value))
    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await flushPromises()
    sentinels.forEach((value) => expect(renderedValues()).not.toContain(value))

    button('显示敏感值并编辑').click()
    await flushPromises()
    const invalidDraft = `${JSON.stringify(input).slice(0, -1)}`
    await wrapper.find('textarea').setValue(invalidDraft)
    await flushPromises()
    expect(document.body.textContent).toContain('请输入合法的 JSON 对象')
    expect(button('重新隐藏敏感值').disabled).toBe(false)
    button('重新隐藏敏感值').click()
    await flushPromises()
    sentinels.forEach((value) => expect(renderedValues()).not.toContain(value))
    button('显示敏感值并编辑').click()
    await flushPromises()
    expect((wrapper.find('textarea').element as HTMLTextAreaElement).value).toBe(invalidDraft)
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
  })

  it.each([
    ['已建立等待派发', { status: 'ACCEPTED', dispatchStage: 'PERSISTED', terminal: false }],
    ['正在调用', { status: 'DISPATCHING', dispatchStage: 'DISPATCHING', terminal: false }],
    ['结果未知', { status: 'UNKNOWN', dispatchStage: 'UNCONFIRMED', terminal: true }],
    ['无效响应', null],
  ] as const)('does not let ordinary controls create a second POST while %s is still being viewed', async (_label, lifecycle) => {
    const firstId = '123e4567-e89b-42d3-a456-426614174000'
    const secondId = '223e4567-e89b-42d3-a456-426614174000'
    const ids = [firstId, secondId]
    vi.stubGlobal('crypto', { randomUUID: () => ids.shift() || '' })
    const firstResponse = lifecycle
      ? { data: outcome({ invocationId: firstId, ...lifecycle }) }
      : { data: { invocationId: firstId, contractVersion: 1 } }
    vi.mocked(invokeBusinessMethod)
      .mockResolvedValueOnce(firstResponse as any)
      .mockResolvedValueOnce({ data: outcome({ invocationId: secondId }) } as any)
    start(); await flushPromises()
    await valueInput().setValue('locked')
    button('开始试调用').click()
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)
    expect(button('开始试调用').disabled).toBe(true)

    await valueInput().trigger('keydown', { key: 'Enter' })
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)

    button('新建一次试调用').click()
    await flushPromises()
    button('开始试调用').click()
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(2)
    expect(vi.mocked(invokeBusinessMethod).mock.calls.map(([, request]) => (request as any).invocationId)).toEqual([firstId, secondId])
  })

  it('disables conflicting refresh and historic read controls while the POST is pending', async () => {
    let settle!: (value: unknown) => void
    vi.mocked(invokeBusinessMethod).mockImplementationOnce(() => new Promise(resolve => { settle = resolve }) as any)
    start(); await flushPromises()
    await valueInput().setValue('pending')
    button('开始试调用').click()
    await flushPromises()

    expect(button('刷新调用条件').disabled).toBe(true)
    expect(Array.from(document.querySelectorAll('button')).filter((item) => item.textContent?.trim() === '查询').every((item) => item.disabled)).toBe(true)

    settle({ data: outcome() })
    await flushPromises()
    expect(button('刷新调用条件').disabled).toBe(false)
  })

  it('clears an open panel and rejects a late POST result when the current project invoke grant is removed', async () => {
    let settle!: (value: unknown) => void
    vi.mocked(invokeBusinessMethod).mockImplementationOnce(() => new Promise(resolve => { settle = resolve }) as any)
    start(); await flushPromises()
    await valueInput().setValue('clear-on-revoke')
    button('开始试调用').click()
    await flushPromises()
    expect(document.body.textContent).toContain('调用 ID')

    session.user.value = { userId: 42, username: 'tester', permissionGrants: [{ permissionCode: 'platform:read', scopeType: 'PROJECT', scopeValue: 'orders' }] }
    await nextTick()
    await flushPromises()
    expect(document.body.textContent).toContain('当前账号不能试调用该业务方法')
    expect(document.body.textContent).not.toContain('clear-on-revoke')
    expect(button('查询当前调用')).toBeUndefined()

    settle({ data: outcome() })
    await flushPromises()
    expect(document.body.textContent).toContain('当前账号不能试调用该业务方法')
    expect(document.body.textContent).not.toContain('调用已确认成功')
  })

  it('does not expose a sensitive complex value in the form after explicit JSON editing', async () => {
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: {
      ...context(),
      parameters: [{ name: 'filter', type: 'object', description: '筛选条件', required: true, metadata: { sensitive: true } }],
    } } as any)
    start(); await flushPromises()
    await wrapper.findAll('input[type="radio"]')[1].setValue()
    button('显示敏感值并编辑').click()
    await nextTick()
    await wrapper.find('textarea').setValue('{"filter":{"token":"FORM_SECRET_SENTINEL"}}')
    await nextTick()
    await flushPromises()
    await wrapper.findAll('input[type="radio"]')[0].setValue()
    await flushPromises()

    expect(Array.from(document.querySelectorAll('textarea')).map((item) => item.value).join('\n')).not.toContain('FORM_SECRET_SENTINEL')
    expect(document.body.textContent).not.toContain('FORM_SECRET_SENTINEL')
    expect(document.body.textContent).toContain('显示并编辑')

    button('显示并编辑').click()
    await flushPromises()
    expect(wrapper.find('textarea').element.value).toContain('FORM_SECRET_SENTINEL')
    button('隐藏').click()
    await flushPromises()
    expect(Array.from(document.querySelectorAll('textarea')).map((item) => item.value).join('\n')).not.toContain('FORM_SECRET_SENTINEL')
    button('开始试调用').click()
    await flushPromises()
    expect(invokeBusinessMethod).toHaveBeenCalledWith('orders.lookup', expect.objectContaining({
      input: { filter: { token: 'FORM_SECRET_SENTINEL' } },
    }))
  })
})
