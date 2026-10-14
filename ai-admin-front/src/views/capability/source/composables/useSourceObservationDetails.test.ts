import { computed, defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSourceObservationDetails } from './useSourceObservationDetails'
import type { ProjectToolInfo } from '@/types/scanProject'

const api = vi.hoisted(() => ({ detail: vi.fn() }))
vi.mock('@/api/scanProject', () => ({ getScanProjectTool: api.detail }))
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => vi.clearAllMocks())
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))
function record(id = 1): ProjectToolInfo { return { scanToolId: id, name: `orders:read${id}`, title: '摘要', description: '来源摘要', parameters: [], source: 'scanner', enabled: true } }
function setup() {
  let state!: ReturnType<typeof useSourceObservationDetails>
  const tools = ref([record()])
  const wrapper = mount(defineComponent({ setup() { state = useSourceObservationDetails({ projectId: computed(() => 7), tools }); return () => null } }))
  wrappers.push(wrapper); return { state, tools, wrapper }
}
function deferred() {
  let resolve!: (value: { data: ProjectToolInfo }) => void
  const promise = new Promise<{ data: ProjectToolInfo }>(done => { resolve = done })
  return { promise, resolve }
}

describe('source detail loading and recovery', () => {
  it('deduplicates concurrent reads, merges confirmed details into the selected row, and caches success', async () => {
    const pending = deferred(); api.detail.mockReturnValueOnce(pending.promise)
    const { state, tools } = setup(); const selected = tools.value[0]
    const first = state.ensureToolDetail(selected); const second = state.ensureToolDetail(selected)
    expect(api.detail).toHaveBeenCalledOnce()
    expect(api.detail).toHaveBeenCalledWith(7, 1)
    expect(state.toolDetailLoading[1]).toBe(true)
    pending.resolve({ data: { ...record(), title: '完整来源详情', parameterCount: 2 } })
    expect(await first).toBe(selected); expect(await second).toBe(selected)
    expect(selected.title).toBe('完整来源详情')
    expect(state.toolDetailLoading[1]).toBe(false)
    await state.ensureToolDetail(selected)
    expect(api.detail).toHaveBeenCalledOnce()
  })

  it('rejects mismatched source identity without merging it and permits a fresh retry', async () => {
    api.detail.mockResolvedValueOnce({ data: { ...record(99), title: '其他来源详情' } }).mockResolvedValueOnce({ data: { ...record(), title: '当前来源详情' } })
    const { state, tools } = setup(); const selected = tools.value[0]
    await state.ensureToolDetail(selected)
    expect(tools.value).toHaveLength(1)
    expect(selected.title).toBe('摘要')
    expect(state.toolDetailError[1]).toContain('读取来源详情失败')
    const retry = state.ensureToolDetail(selected)
    expect(state.toolDetailError[1]).toBeUndefined()
    await retry
    expect(api.detail).toHaveBeenCalledTimes(2)
    expect(selected.title).toBe('当前来源详情')
    expect(state.toolDetailLoading[1]).toBe(false)
  })

  it('ignores an old read after cache reset without clearing a newer pending request', async () => {
    const old = deferred(); const current = deferred()
    api.detail.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const { state, tools } = setup(); const selected = tools.value[0]
    const oldRead = state.ensureToolDetail(selected)
    state.resetToolDetailCache()
    const currentRead = state.ensureToolDetail(selected)
    old.resolve({ data: { ...record(), title: '过期详情' } }); await oldRead
    expect(selected.title).toBe('摘要')
    expect(state.toolDetailLoading[1]).toBe(true)
    expect(state.toolDetailError[1]).toBeUndefined()
    const joined = state.ensureToolDetail(selected)
    expect(api.detail).toHaveBeenCalledTimes(2)
    current.resolve({ data: { ...record(), title: '当前详情' } }); await currentRead; await joined
    expect(selected.title).toBe('当前详情')
  })

  it('does not merge late results after the project controller has unmounted', async () => {
    const pending = deferred(); api.detail.mockReturnValueOnce(pending.promise)
    const { state, tools, wrapper } = setup()
    const read = state.ensureToolDetail(tools.value[0]); wrapper.unmount()
    pending.resolve({ data: { ...record(), title: '离页后的详情' } }); await read; await flushPromises()
    expect(tools.value[0].title).toBe('摘要')
    expect(state.toolDetailError[1]).toBeUndefined()
    await state.ensureToolDetail(tools.value[0])
    expect(api.detail).toHaveBeenCalledOnce()
  })
})
