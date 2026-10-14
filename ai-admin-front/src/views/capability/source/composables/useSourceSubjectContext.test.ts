import { computed, defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useSourceSubjectContext } from './useSourceSubjectContext'
const api = vi.hoisted(() => ({ read: vi.fn() }))
vi.mock('@/api/semanticDoc', () => ({ getCapabilityEnrichment: api.read }))
const views: ReturnType<typeof mount>[] = []
afterEach(() => { views.splice(0).forEach(view => view.unmount()); vi.resetAllMocks() })
function setup() {
  const subjectId = ref<unknown>('33'), account = ref('one'), canRead = ref(true)
  let state!: ReturnType<typeof useSourceSubjectContext>
  const view = mount(defineComponent({ setup() {
    state = useSourceSubjectContext({ projectId: computed(() => 7), workspace: () => 'sources',
      query: () => ({ projectId: '7', subjectType: 'HTTP_API', subjectId: subjectId.value as string }),
      canRead: () => canRead.value, contextKey: () => account.value })
    return () => null
  } }))
  views.push(view); return { state, subjectId, account, canRead }
}
describe('formal asset source evidence context', () => {
  it('uses exact owner source observations even without generated documentation', async () => {
    api.read.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', subjectId: 33, title: '订单查询', semanticDoc: null,
      sourceObservations: [{ id: 201 }, { id: 202 }] } })
    const { state } = setup(); await flushPromises()
    expect(api.read).toHaveBeenCalledWith(7, 'HTTP_API', 33)
    expect(state.observationIds.value).toEqual([201, 202])
    expect(state.title.value).toBe('订单查询')
  })
  it('does not substitute all sources for failed, foreign or malformed evidence', async () => {
    api.read.mockResolvedValue({ data: { projectId: 8, subjectType: 'HTTP_API', subjectId: 33, sourceObservations: [{ id: 201 }] } })
    const { state, subjectId } = setup(); await flushPromises()
    expect(state.error.value).toContain('读取失败')
    expect(state.observationIds.value).toEqual([])
    subjectId.value = 'not-an-id'; await flushPromises()
    expect(state.error.value).toContain('身份不完整')
    expect(state.observationIds.value).toEqual([])
  })
  it('discards a pending owner response on permission change', async () => {
    let resolve!: (value: unknown) => void
    api.read.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const { state, canRead } = setup()
    canRead.value = false; await flushPromises()
    resolve({ data: { projectId: 7, subjectType: 'HTTP_API', subjectId: 33, sourceObservations: [{ id: 201 }] } }); await flushPromises()
    expect(state.observationIds.value).toEqual([])
    expect(state.error.value).toContain('无权')
  })
  it('keeps the current account evidence when an old account read arrives later', async () => {
    let resolve!: (value: unknown) => void
    api.read.mockReturnValueOnce(new Promise(done => { resolve = done }))
      .mockResolvedValueOnce({ data: { projectId: 7, subjectType: 'HTTP_API', subjectId: 33, sourceObservations: [{ id: 302 }] } })
    const { state, account } = setup()
    account.value = 'two'; await flushPromises()
    resolve({ data: { projectId: 7, subjectType: 'HTTP_API', subjectId: 33, sourceObservations: [{ id: 201 }] } }); await flushPromises()
    expect(state.observationIds.value).toEqual([302])
  })
})
