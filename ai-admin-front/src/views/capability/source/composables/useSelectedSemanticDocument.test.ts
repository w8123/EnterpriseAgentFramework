import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSelectedSemanticDocument } from './useSelectedSemanticDocument'
import type { SemanticDoc, SemanticDocIndex } from '@/types/semanticDoc'

const read = vi.hoisted(() => vi.fn())
vi.mock('@/api/semanticDoc', () => ({ getSubjectSemanticDoc: read }))
const index = (subjectId = 11): SemanticDocIndex => ({ id: subjectId, projectId: 7, subjectType: 'HTTP_API', subjectId,
  subjectKey: `orders:${subjectId}`, title: '订单说明', updatedAt: 'revision-1', status: 'generated', promptVersion: null, modelName: null, tokenUsage: 0 })
const body = (subjectId = 11, contentMd = '完整正文'): SemanticDoc => ({ ...index(subjectId), contentMd })
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.resetAllMocks() })
function deferred() { let resolve!: (value: any) => void, reject!: (value: any) => void
  const promise = new Promise<any>((done, fail) => { resolve = done; reject = fail }); return { promise, resolve, reject } }
function setup(initial: SemanticDocIndex | null = index(), busy = false) {
  const metadata = ref(initial), indexLoading = ref(busy), projectId = ref(7)
  let state!: ReturnType<typeof useSelectedSemanticDocument>
  const wrapper = mount(defineComponent({ setup() { state = useSelectedSemanticDocument(() => projectId.value, () => metadata.value, () => indexLoading.value); return () => null } }))
  wrappers.push(wrapper); return { state, metadata, indexLoading, projectId, wrapper }
}
describe('selected semantic body ownership and lifecycle', () => {
  it('reads only the selected body after the index is available and keeps absent documents distinct', async () => {
    read.mockResolvedValue({ data: body() })
    const { state, metadata, indexLoading } = setup(index(), true)
    expect(read).not.toHaveBeenCalled()
    indexLoading.value = false; await flushPromises()
    expect(read).toHaveBeenCalledOnce(); expect(read).toHaveBeenCalledWith(7, 'HTTP_API', 11)
    expect(state.document.value?.contentMd).toBe('完整正文')
    metadata.value = null; await flushPromises()
    expect(read).toHaveBeenCalledOnce(); expect(state.document.value).toBeNull(); expect(state.error.value).toBe('')
  })
  it.each(['resolve', 'reject'] as const)('ignores a previous selection late %s and retains the current body', async outcome => {
    const old = deferred(); read.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ data: body(12, '当前正文') })
    const { state, metadata } = setup()
    metadata.value = index(12); await flushPromises()
    old[outcome](outcome === 'resolve' ? { data: body(11, '旧正文') } : new Error('Old failure')); await flushPromises()
    expect(state.document.value?.contentMd).toBe('当前正文'); expect(state.error.value).toBe(''); expect(state.loading.value).toBe(false)
  })
  it('invalidates the body during index refresh and rereads the selected revision only once afterwards', async () => {
    const old = deferred(); read.mockReturnValueOnce(old.promise).mockResolvedValueOnce({ data: body(11, '刷新后正文') })
    const { state, metadata, indexLoading } = setup()
    indexLoading.value = true; metadata.value = null; await flushPromises()
    old.resolve({ data: body(11, '旧正文') }); await flushPromises()
    expect(state.document.value).toBeNull()
    metadata.value = { ...index(), updatedAt: 'revision-2' }; indexLoading.value = false; await flushPromises()
    expect(read).toHaveBeenCalledTimes(2); expect(state.document.value?.contentMd).toBe('刷新后正文')
  })
  it('rejects a missing or foreign owner body without presenting a successful blank and supports retry', async () => {
    read.mockResolvedValueOnce({ data: { ...body(), projectId: 8 } }).mockResolvedValueOnce({ data: null }).mockResolvedValueOnce({ data: body() })
    const { state } = setup(); await flushPromises()
    expect(state.error.value).toContain('读取失败'); expect(state.document.value).toBeNull()
    await state.refresh(); expect(state.error.value).toContain('读取失败')
    await state.refresh(); expect(state.error.value).toBe(''); expect(state.document.value?.contentMd).toBe('完整正文')
  })
  it('does not adopt a late body after the reader is unmounted', async () => {
    const pending = deferred(); read.mockReturnValue(pending.promise)
    const { state, wrapper } = setup(); wrapper.unmount()
    pending.resolve({ data: body() }); await flushPromises()
    expect(state.document.value).toBeNull()
  })
})
