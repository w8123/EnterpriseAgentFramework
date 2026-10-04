import { effectScope, nextTick, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { ToolInfo } from '@/types/tool'

const api = vi.hoisted(() => ({
  getBusinessMethods: vi.fn(),
  getBusinessMethod: vi.fn(),
}))

vi.mock('@/api/tool', () => api)

import { useWorkflowBusinessMethodPicker } from './useWorkflowBusinessMethodPicker'

function method(name: string, projectId = 7): ToolInfo {
  return {
    assetType: 'BUSINESS_METHOD',
    name,
    title: name,
    description: `${name} description`,
    parameters: [],
    source: 'sdk',
    projectId,
    projectCode: 'orders',
    qualifiedName: `orders:${name}`,
    enabled: true,
    sourceAvailability: 'READY',
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function createPicker() {
  const scope = effectScope()
  const projectId = ref<number | null>(7)
  const projectCode = ref('orders')
  const nodeId = ref('tool-a')
  const requestScopeKey = ref('account-a')
  let picker!: ReturnType<typeof useWorkflowBusinessMethodPicker>
  scope.run(() => {
    picker = useWorkflowBusinessMethodPicker({ projectId, projectCode, nodeId, requestScopeKey })
  })
  return { scope, projectId, projectCode, nodeId, requestScopeKey, picker }
}

describe('useWorkflowBusinessMethodPicker', () => {
  it('uses explicit IME-safe search and project-scoped pagination', async () => {
    api.getBusinessMethods.mockResolvedValue({ data: { records: [method('query')], total: 1 } })
    const state = createPicker()
    state.picker.openDialog()
    await nextTick()
    await Promise.resolve()

    expect(api.getBusinessMethods).toHaveBeenLastCalledWith({
      projectId: 7, enabled: true, current: 1, size: 10, keyword: undefined,
    })
    state.picker.keyword.value = '订单'
    expect(state.picker.submitSearch({ isComposing: true })).toBe(false)
    expect(api.getBusinessMethods).toHaveBeenCalledTimes(1)
    expect(state.picker.submitSearch({ isComposing: false })).toBe(true)
    await Promise.resolve()
    expect(api.getBusinessMethods).toHaveBeenLastCalledWith({
      projectId: 7, enabled: true, current: 1, size: 10, keyword: '订单',
    })
    state.picker.changePage(2)
    await Promise.resolve()
    expect(api.getBusinessMethods).toHaveBeenLastCalledWith({
      projectId: 7, enabled: true, current: 2, size: 10, keyword: '订单',
    })
    state.scope.stop()
  })

  it('ignores late A→B→A list responses and keeps retryable errors in the current dialog generation', async () => {
    const aFirst = deferred<{ data: { records: ToolInfo[]; total: number } }>()
    const b = deferred<{ data: { records: ToolInfo[]; total: number } }>()
    const aLatest = deferred<{ data: { records: ToolInfo[]; total: number } }>()
    api.getBusinessMethods
      .mockReturnValueOnce(aFirst.promise)
      .mockReturnValueOnce(b.promise)
      .mockReturnValueOnce(aLatest.promise)
    const state = createPicker()
    state.picker.openDialog()
    await nextTick()
    state.requestScopeKey.value = 'account-b'
    await nextTick()
    state.requestScopeKey.value = 'account-a'
    await nextTick()

    b.resolve({ data: { records: [method('from-b')], total: 1 } })
    aFirst.resolve({ data: { records: [method('from-first-a')], total: 1 } })
    await Promise.resolve()
    await Promise.resolve()
    expect(state.picker.rows.value).toEqual([])
    expect(state.picker.listStatus.value).toBe('loading')
    aLatest.resolve({ data: { records: [method('from-latest-a')], total: 1 } })
    await Promise.resolve()
    await Promise.resolve()
    expect(state.picker.rows.value.map((item) => item.name)).toEqual(['from-latest-a'])

    api.getBusinessMethods.mockRejectedValueOnce({ response: { status: 403 } })
    state.picker.submitSearch()
    await Promise.resolve()
    await Promise.resolve()
    expect(state.picker.listStatus.value).toBe('error')
    expect(state.picker.listError.value).toContain('无权')
    api.getBusinessMethods.mockResolvedValueOnce({ data: { records: [], total: 0 } })
    state.picker.retryList()
    await Promise.resolve()
    await Promise.resolve()
    expect(state.picker.listStatus.value).toBe('empty')
    state.scope.stop()
  })

  it('does not accept cross-project or stale detail data after a saved reference changes', async () => {
    const oldDetail = deferred<{ data: ToolInfo }>()
    api.getBusinessMethod.mockReturnValueOnce(oldDetail.promise)
    const state = createPicker()
    const pending = state.picker.loadSelectedDetail('old-query')
    state.picker.invalidateDetailState(true)
    oldDetail.resolve({ data: method('old-query') })
    await pending
    expect(state.picker.selectedSummary.value).toBeNull()
    expect(state.picker.detailStatus.value).toBe('idle')
    expect(state.picker.adoptCandidate(method('other-project', 9))).toBe(false)
    expect(state.picker.adoptCandidate(method('current-project'))).toBe(true)
    state.scope.stop()
  })

  it('keeps an adopted business-method detail current after the picker closes', async () => {
    const detail = deferred<{ data: ToolInfo }>()
    api.getBusinessMethods.mockResolvedValue({ data: { records: [], total: 0 } })
    api.getBusinessMethod.mockReturnValueOnce(detail.promise)
    const state = createPicker()
    state.picker.openDialog()
    await nextTick()
    expect(state.picker.adoptCandidate(method('health'))).toBe(true)
    state.picker.closeDialog()
    const pending = state.picker.loadSelectedDetail('health')
    await nextTick()
    detail.resolve({ data: method('health') })
    await pending

    expect(state.picker.selectedSummary.value?.name).toBe('health')
    expect(state.picker.detailStatus.value).toBe('ready')
    state.scope.stop()
  })

  it('keeps a fresh same-reference candidate readable after detail failure and keeps a generic Tool 404 quiet', async () => {
    api.getBusinessMethod.mockReset()
    api.getBusinessMethod
      .mockRejectedValueOnce({ response: { status: 503 } })
      .mockRejectedValueOnce({ response: { status: 404 } })
    const state = createPicker()

    expect(state.picker.adoptCandidate(method('query'))).toBe(true)
    await state.picker.loadSelectedDetail('query')
    expect(state.picker.selectedSummary.value?.name).toBe('query')
    expect(state.picker.detailStatus.value).toBe('error')
    expect(state.picker.detailError.value).toContain('已有引用和映射未被修改')

    await state.picker.loadSelectedDetail('legacy-http-tool')
    expect(state.picker.selectedSummary.value).toBeNull()
    expect(state.picker.detailStatus.value).toBe('idle')
    expect(state.picker.detailError.value).toBe('')
    state.scope.stop()
  })
})
