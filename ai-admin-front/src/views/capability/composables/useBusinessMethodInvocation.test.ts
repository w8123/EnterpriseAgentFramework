import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import {
  getBusinessMethodInvocation,
  getBusinessMethodInvocationContext,
  invokeBusinessMethod,
} from '@/api/businessMethodInvocation'
import { useBusinessMethodInvocation } from './useBusinessMethodInvocation'

vi.mock('@/api/businessMethodInvocation', () => ({
  getBusinessMethodInvocation: vi.fn(),
  getBusinessMethodInvocationContext: vi.fn(),
  invokeBusinessMethod: vi.fn(),
}))

const hash = 'a'.repeat(64)
const invocationId = '123e4567-e89b-42d3-a456-426614174000'
function context(projectCode = 'orders') {
  return {
    contractVersion: 1, name: 'orders.lookup', assetType: 'BUSINESS_METHOD', projectId: 8, projectCode,
    qualifiedName: 'orders.lookup', currentContractHash: hash, executionRevision: 'd'.repeat(64), acceptedContractHash: hash, sourceContractHash: hash,
    sourceAvailability: 'READY', enabled: true, credentialAvailable: true, businessIdentityRequired: false,
    executable: true, parameters: [], sideEffect: 'READ',
  }
}
function outcome(id = invocationId) {
  return {
    contractVersion: 1, invocationId: id, runId: 7, traceId: 'trace-7', projectId: 8, projectCode: 'orders',
    qualifiedName: 'orders.lookup', identityMode: 'PROJECT_CREDENTIAL_NO_BUSINESS_IDENTITY',
    status: 'SUCCEEDED', dispatchStage: 'CONFIRMED', terminal: true,
  }
}

describe('useBusinessMethodInvocation', () => {
  const method = ref('orders.lookup')
  const scope = ref('account-a|project:8')
  const actor = ref<number | null>(42)
  const active = ref(true)
  const authorized = ref(true)
  let api!: ReturnType<typeof useBusinessMethodInvocation>
  let wrapper!: ReturnType<typeof mount>

  function mountHarness(referenceContextKey?: string) {
    const Harness = defineComponent({
      setup() {
        api = useBusinessMethodInvocation({ methodName: method, contextKey: scope, referenceContextKey, actorId: actor, active, authorized })
        return () => h('div')
      },
    })
    wrapper = mount(Harness)
  }

  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    method.value = 'orders.lookup'; scope.value = 'account-a|project:8'; actor.value = 42; active.value = true; authorized.value = true
    vi.stubGlobal('crypto', { randomUUID: () => invocationId })
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: context() } as any)
    mountHarness()
  })
  afterEach(() => {
    wrapper?.unmount()
    vi.unstubAllGlobals()
  })

  it('uses one fixed POST id, then only queries after an unconfirmed response', async () => {
    await flushPromises()
    api.input.value = { secret: 'must-not-persist', count: 0, enabled: false }
    let settle!: (value: unknown) => void
    vi.mocked(invokeBusinessMethod).mockImplementationOnce(() => new Promise(resolve => { settle = resolve }) as any)
    const first = api.submit(false, api.input.value)
    const second = api.submit(false, api.input.value)
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)
    expect(invokeBusinessMethod).toHaveBeenCalledWith('orders.lookup', expect.objectContaining({ invocationId, input: expect.objectContaining({ count: 0, enabled: false }) }))
    settle({ data: { success: false, code: 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED', invocationId } })
    await Promise.all([first, second])
    expect(api.clientState.value).toBe('OUTCOME_UNCONFIRMED')
    const stored = Object.keys(localStorage)
      .map((key) => localStorage.getItem(key) || '')
      .join('\n')
    expect(stored).not.toContain('must-not-persist')
    vi.mocked(getBusinessMethodInvocation).mockResolvedValue({ data: outcome() } as any)
    await api.query()
    expect(getBusinessMethodInvocation).toHaveBeenCalledWith(invocationId)
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)
  })

  it('does not resend after a 404 read and discards a late response from an old scope', async () => {
    await flushPromises()
    api.input.value = { id: 'x' }
    vi.mocked(invokeBusinessMethod).mockResolvedValue({ data: { success: false, code: 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED', invocationId } } as any)
    await api.submit(false, api.input.value)
    vi.mocked(getBusinessMethodInvocation).mockRejectedValue({ response: { status: 404 } })
    await api.query()
    expect(api.clientState.value).toBe('QUERY_NOT_FOUND')
    expect(invokeBusinessMethod).toHaveBeenCalledTimes(1)

    let resolveOld!: (value: unknown) => void
    vi.mocked(getBusinessMethodInvocationContext).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }) as any)
    const oldRequest = api.loadContext()
    scope.value = 'account-b|project:8'
    await nextTick()
    await flushPromises()
    resolveOld({ data: context('old-project') })
    await oldRequest
    await flushPromises()
    expect(api.context.value?.projectCode).toBe('orders')
    expect(api.input.value).toEqual({})
  })

  it('performs one read-only recovery query for the newest owner-scoped reference after reopening', async () => {
    wrapper.unmount()
    const recoveredId = '223e4567-e89b-42d3-a456-426614174000'
    const key = `reachai.businessMethodInvocation.refs.v1.${encodeURIComponent('actor:42|account-a|project:8|method:orders.lookup')}`
    localStorage.setItem(key, JSON.stringify([{ invocationId: recoveredId, createdAt: Date.now() }]))
    vi.clearAllMocks()
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: context() } as any)
    vi.mocked(getBusinessMethodInvocation).mockResolvedValue({ data: outcome(recoveredId) } as any)

    mountHarness()
    await flushPromises()

    expect(getBusinessMethodInvocation).toHaveBeenCalledTimes(1)
    expect(getBusinessMethodInvocation).toHaveBeenCalledWith(recoveredId)
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
  })

  it('recovers the same id after reload resets the request generation, while persisting only id and time', async () => {
    wrapper.unmount()
    scope.value = 'session-a|2|project:8:orders|ready'
    mountHarness('project:8:orders')
    await flushPromises()
    vi.mocked(invokeBusinessMethod).mockResolvedValue({ data: { success: false,
      code: 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED', invocationId } } as any)
    await api.submit(false, { note: 'private-input-not-for-storage' })
    wrapper.unmount()
    scope.value = 'session-a|1|project:8:orders|ready'
    vi.clearAllMocks()
    vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: context() } as any)
    vi.mocked(getBusinessMethodInvocation).mockResolvedValue({ data: outcome() } as any)
    mountHarness('project:8:orders')
    await flushPromises()

    expect(getBusinessMethodInvocation).toHaveBeenCalledExactlyOnceWith(invocationId)
    expect(invokeBusinessMethod).not.toHaveBeenCalled()
    expect(api.currentInvocationId.value).toBe(invocationId)
    const refs = Object.keys(localStorage).filter(key => key.startsWith('reachai.businessMethodInvocation.refs.v1'))
    expect(refs).toHaveLength(1)
    const stored = JSON.parse(localStorage.getItem(refs[0]!)!)
    expect(Object.keys(stored[0]).sort()).toEqual(['createdAt', 'invocationId'])
    expect(JSON.stringify(stored)).not.toContain('private-input-not-for-storage')
  })

  it('does not recover a stable reference for another actor, project or method', async () => {
    wrapper.unmount()
    const key = `reachai.businessMethodInvocation.refs.v1.${encodeURIComponent('actor:42|project:8:orders|method:orders.lookup')}`
    localStorage.setItem(key, JSON.stringify([{ invocationId, createdAt: Date.now() }]))
    for (const other of ['actor', 'project', 'method']) {
      actor.value = other === 'actor' ? 43 : 42
      method.value = other === 'method' ? 'orders.other' : 'orders.lookup'
      vi.clearAllMocks()
      vi.mocked(getBusinessMethodInvocationContext).mockResolvedValue({ data: { ...context(), name: method.value } } as any)
      mountHarness(other === 'project' ? 'project:9:other' : 'project:8:orders')
      await flushPromises()
      expect(api.recentReferences.value).toEqual([])
      expect(getBusinessMethodInvocation).not.toHaveBeenCalled()
      expect(invokeBusinessMethod).not.toHaveBeenCalled()
      wrapper.unmount()
    }
  })

  it('keeps a pending POST owned by its attempt when a call-condition refresh completes', async () => {
    await flushPromises()
    api.input.value = { id: 'submit-snapshot' }
    let resolvePost!: (value: unknown) => void
    let resolveRefresh!: (value: unknown) => void
    vi.mocked(invokeBusinessMethod).mockImplementationOnce(() => new Promise(resolve => { resolvePost = resolve }) as any)
    vi.mocked(getBusinessMethodInvocationContext).mockImplementationOnce(() => new Promise(resolve => { resolveRefresh = resolve }) as any)

    const posting = api.submit(false, api.input.value)
    const refreshing = api.loadContext()
    expect(api.isSubmitting.value).toBe(true)

    resolvePost({ data: outcome() })
    await posting
    expect(api.isSubmitting.value).toBe(false)
    expect(api.currentInvocationId.value).toBe(invocationId)
    expect(api.outcome.value?.status).toBe('SUCCEEDED')

    resolveRefresh({ data: context() })
    await refreshing
    expect(api.currentInvocationId.value).toBe(invocationId)
    expect(api.outcome.value?.status).toBe('SUCCEEDED')
  })

  it('drops a late read-only response after an explicit new attempt and never lets it replace the new UUID', async () => {
    await flushPromises()
    const firstId = '123e4567-e89b-42d3-a456-426614174000'
    const secondId = '223e4567-e89b-42d3-a456-426614174000'
    const ids = [firstId, secondId]
    vi.stubGlobal('crypto', { randomUUID: () => ids.shift() || '' })
    api.input.value = { id: 'old' }
    vi.mocked(invokeBusinessMethod)
      .mockResolvedValueOnce({ data: { success: false, code: 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED', invocationId: firstId } } as any)
      .mockResolvedValueOnce({ data: outcome(secondId) } as any)
    await api.submit(false, api.input.value)

    let resolveOldQuery!: (value: unknown) => void
    vi.mocked(getBusinessMethodInvocation).mockImplementationOnce(() => new Promise(resolve => { resolveOldQuery = resolve }) as any)
    const oldQuery = api.query()
    expect(api.beginNewAttempt()).toBe(true)
    api.input.value = { id: 'new' }
    await api.submit(false, api.input.value)

    resolveOldQuery({ data: outcome(firstId) })
    await oldQuery
    expect(api.currentInvocationId.value).toBe(secondId)
    expect(api.outcome.value?.invocationId).toBe(secondId)
    expect(vi.mocked(invokeBusinessMethod).mock.calls.map(([, request]) => (request as any).invocationId)).toEqual([firstId, secondId])
  })

  it('clears sensitive state and discards late query data immediately when authorization is revoked', async () => {
    await flushPromises()
    api.input.value = { secret: 'never-visible-after-revoke' }
    vi.mocked(invokeBusinessMethod).mockResolvedValue({ data: outcome() } as any)
    await api.submit(false, api.input.value)
    let resolveQuery!: (value: unknown) => void
    vi.mocked(getBusinessMethodInvocation).mockImplementationOnce(() => new Promise(resolve => { resolveQuery = resolve }) as any)
    const pendingQuery = api.query()

    authorized.value = false
    await nextTick()
    expect(api.input.value).toEqual({})
    expect(api.context.value).toBeNull()
    expect(api.outcome.value).toBeNull()
    expect(api.currentInvocationId.value).toBe('')
    expect(api.recentReferences.value).toEqual([])

    resolveQuery({ data: outcome() })
    await pendingQuery
    expect(api.input.value).toEqual({})
    expect(api.outcome.value).toBeNull()
    expect(api.currentInvocationId.value).toBe('')
  })

  it('keeps an authorized attempt when only the visible tab is hidden', async () => {
    await flushPromises()
    api.input.value = { id: 'keep-when-tab-hides' }
    vi.mocked(invokeBusinessMethod).mockResolvedValue({ data: outcome() } as any)
    await api.submit(false, api.input.value)

    active.value = false
    await nextTick()

    expect(api.input.value).toEqual({ id: 'keep-when-tab-hides' })
    expect(api.context.value?.qualifiedName).toBe('orders.lookup')
    expect(api.outcome.value?.invocationId).toBe(invocationId)
    expect(api.currentInvocationId.value).toBe(invocationId)
  })

  it('describes a forbidden read as an unavailable query without rewriting the prior business-execution fact', async () => {
    await flushPromises()
    api.input.value = { id: 'forbidden-query' }
    vi.mocked(invokeBusinessMethod).mockResolvedValue({ data: { success: false, code: 'CONSOLE_CAPABILITY_OUTCOME_UNCONFIRMED', invocationId } } as any)
    await api.submit(false, api.input.value)
    vi.mocked(getBusinessMethodInvocation).mockRejectedValue({ response: { status: 403 } })

    await api.query()
    expect(api.actionError.value).toContain('无权读取这条调用记录')
    expect(api.actionError.value).toContain('无法据此确认前次调用是否已经执行')
    expect(api.actionError.value).not.toContain('未发起业务调用')
    expect(api.currentInvocationId.value).toBe(invocationId)
  })
})
