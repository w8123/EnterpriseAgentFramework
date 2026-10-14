import { computed, defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSourceWorkbenchData } from './useSourceWorkbenchData'

const api = vi.hoisted(() => ({ project: vi.fn(), tools: vi.fn() }))
vi.mock('@/api/scanProject', () => ({ getScanProjectDetail: api.project, getScanProjectSources: api.tools }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))
function page(records: any[] = []) { return { records, total: records.length, current: 1, size: 20, stats: { total: records.length, removed: 0 }, modules: [] } }
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.clearAllMocks() })
function deferred<T>() { let resolve!: (value: T) => void, reject!: (value: unknown) => void; const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail }); return { promise, resolve, reject } }
function start(loadSemanticAssets = vi.fn().mockResolvedValue(undefined)) {
  let data!: ReturnType<typeof useSourceWorkbenchData>
  const sideEffects = { loadSemanticAssets, applyProjectLoaded: vi.fn(), onToolsLoaded: vi.fn(), clearSemanticAssets: vi.fn() }
  const wrapper = mount(defineComponent({ setup() {
    data = useSourceWorkbenchData({ projectId: computed(() => 7), sideEffects: () => sideEffects })
    return () => ''
  } }))
  wrappers.push(wrapper)
  return { data, sideEffects, wrapper }
}
describe('shared project source controller', () => {
  it('keeps global source counts independent from the current page and ignores a late failed page read', async () => {
    const old = deferred<any>()
    api.tools.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ data: { ...page([{ scanToolId: 41 }]),
      current: 3, total: 42, stats: { total: 500, removed: 50 }, modules: [{ moduleId: 2, moduleDisplayName: '订单', count: 500 }] } })
    const { data } = start()
    const pending = data.refreshSources(); await data.refreshSources()
    old.reject(new Error('Old page unavailable')); await pending
    expect(data.tools.value[0].scanToolId).toBe(41)
    expect(data.sourceCurrent.value).toBe(3); expect(data.sourceTotal.value).toBe(42)
    expect(data.sourceStats.value).toEqual({ total: 500, removed: 50 })
    expect(data.error.value).toBe(''); expect(data.loading.value).toBe(false)
  })
  it('keeps the latest refresh when earlier project/source responses arrive late', async () => {
    const oldProject = deferred<any>(), oldTools = deferred<any>()
    api.project.mockReturnValueOnce(oldProject.promise).mockResolvedValueOnce({ data: { id: 7, name: '当前' } })
    api.tools.mockReturnValueOnce(oldTools.promise).mockResolvedValueOnce({ data: page([{ scanToolId: 22 }]) })
    const { data } = start()
    const old = data.refreshAll(); await data.refreshAll()
    oldProject.resolve({ data: { id: 7, name: '旧响应' } }); oldTools.resolve({ data: page([{ scanToolId: 11 }]) }); await old
    expect(data.project.value?.name).toBe('当前')
    expect(data.tools.value[0].scanToolId).toBe(22)
  })
  it('retains discovered sources while reporting incomplete semantic evidence', async () => {
    api.project.mockResolvedValue({ data: { id: 7, name: '订单' } }); api.tools.mockResolvedValue({ data: page([{ scanToolId: 22 }]) })
    const { data, sideEffects } = start(vi.fn().mockRejectedValue(new Error('Unavailable')))
    await data.refreshAll()
    expect(data.project.value?.id).toBe(7)
    expect(data.tools.value).toHaveLength(1)
    expect(data.partialError.value).toContain('业务说明读取失败')
    expect(data.error.value).toBe('')
    expect(sideEffects.clearSemanticAssets).toHaveBeenCalledTimes(2)
  })
  it('rejects a wrong owner and discards detached responses after scope removal', async () => {
    api.project.mockResolvedValueOnce({ data: { id: 8 } }); api.tools.mockResolvedValue({ data: page() })
    const { data, wrapper } = start()
    await data.refreshAll(); expect(data.project.value).toBeNull(); expect(data.error.value).toContain('项目接入信息读取失败')
    const pending = deferred<any>(); api.project.mockReturnValueOnce(pending.promise)
    const refresh = data.refreshAll(); wrapper.unmount(); pending.resolve({ data: { id: 7, name: '旧账号' } }); await refresh; await flushPromises()
    expect(data.project.value).toBeNull()
  })
  it('publishes project and source data before slow business descriptions complete', async () => {
    const semantics = deferred<void>()
    api.project.mockResolvedValue({ data: { id: 7, name: '当前项目' } })
    api.tools.mockResolvedValue({ data: page([{ scanToolId: 22 }]) })
    const { data } = start(vi.fn().mockReturnValue(semantics.promise))
    const refresh = data.refreshAll()
    await flushPromises()
    expect(data.project.value?.id).toBe(7)
    expect(data.tools.value).toHaveLength(1)
    expect(data.loading.value).toBe(false)
    expect(data.semanticLoading.value).toBe(true)
    semantics.resolve(); await refresh
    expect(data.semanticLoading.value).toBe(false)
  })
  it('keeps project access available when source evidence fails', async () => {
    api.project.mockResolvedValue({ data: { id: 7 } })
    api.tools.mockRejectedValue(new Error('Unavailable'))
    const { data } = start()
    await data.refreshAll()
    expect(data.project.value?.id).toBe(7)
    expect(data.tools.value).toEqual([])
    expect(data.error.value).toContain('项目来源读取失败')
  })
})
