import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useCatalogClassification } from './useCatalogClassification'
const mocks = vi.hoisted(() => ({ read: vi.fn() }))
vi.mock('@/api/semanticDoc', () => ({ getCatalogClassificationIndex: mocks.read }))
const payload = (projectId = 7) => ({ data: { projectId, subjectType: 'BUSINESS_METHOD', groups: [], memberships: [], ungroupedCount: 0 } })
beforeEach(() => { vi.clearAllMocks(); mocks.read.mockResolvedValue(payload()) })
describe('catalog classification scope and retry', () => {
  it('waits for confirmed scope and rejects a late response after project change', async () => {
    const project = ref<number | null>(7), ready = ref(false), session = ref('a')
    let resolveOld!: (response: ReturnType<typeof payload>) => void
    mocks.read.mockImplementation((id: number) => id === 7 ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve(payload(id)))
    let model!: ReturnType<typeof useCatalogClassification>
    const wrapper = mount(defineComponent({ setup() { model = useCatalogClassification(project, ready, 'BUSINESS_METHOD', session); return () => null } }))
    expect(mocks.read).not.toHaveBeenCalled()
    ready.value = true; await flushPromises()
    project.value = 8; await flushPromises()
    resolveOld(payload(7)); await flushPromises()
    expect(model.index.value?.projectId).toBe(8)
    wrapper.unmount()
  })
  it('clears previous account data synchronously and offers retry without a false empty classification', async () => {
    const project = ref<number | null>(7), ready = ref(true), session = ref('a')
    let model!: ReturnType<typeof useCatalogClassification>
    const wrapper = mount(defineComponent({ setup() { model = useCatalogClassification(project, ready, 'BUSINESS_METHOD', session); return () => null } }))
    await flushPromises(); expect(model.index.value).not.toBeNull()
    mocks.read.mockRejectedValueOnce(new Error('unavailable'))
    session.value = 'b'; expect(model.index.value).toBeNull()
    await flushPromises(); expect(model.error.value).toContain('业务分类读取失败')
    await model.refresh(); expect(model.index.value?.projectId).toBe(7)
    project.value = null; expect(model.index.value).toBeNull(); await flushPromises()
    expect(mocks.read).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })
})
