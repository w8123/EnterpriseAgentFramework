import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SourceOwnerLink from './SourceOwnerLink.vue'

const mocks = vi.hoisted(() => ({ read: vi.fn(), push: vi.fn() }))
vi.mock('@/api/semanticDoc', () => ({ getCapabilityEnrichment: mocks.read }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: { methodKeyword: '订单', apiPage: '2' } }), useRouter: () => ({ push: mocks.push }) }))
vi.mock('@/auth/platformSession', () => ({ platformSessionId: ref('account-a') }))
const views: ReturnType<typeof mount>[] = []
function setup() {
  const view = mount(SourceOwnerLink, { props: { projectId: 7, observationId: 101, canRead: true }, global: { plugins: [ElementPlus] } })
  views.push(view); return view
}
const owner = { projectId: 7, subjectType: 'BUSINESS_METHOD', subjectId: 33, subjectKey: 'orders:read', title: '查询订单' }
beforeEach(() => { vi.clearAllMocks(); mocks.read.mockResolvedValue({ data: owner }) })
afterEach(() => views.splice(0).forEach(view => view.unmount()))
describe('source observation owner navigation', () => {
  it('resolves the owner only on demand and preserves catalog state without guessing the source id', async () => {
    const view = setup()
    expect(mocks.read).not.toHaveBeenCalled()
    await view.get('button').trigger('click'); await flushPromises()
    expect(mocks.read).toHaveBeenCalledWith(7, 'SOURCE_OBSERVATION', 101)
    expect(mocks.push).toHaveBeenCalledWith({ path: '/business-capabilities/java-methods', query: {
      methodKeyword: '订单', apiPage: '2', projectId: '7', subjectType: 'BUSINESS_METHOD', subjectId: '33', subjectKey: 'orders:read',
    } })
  })
  it('does not navigate for unassociated, ambiguous or cross-project results and can retry failures', async () => {
    const view = setup()
    for (const data of [{ ...owner, subjectType: 'SOURCE_OBSERVATION', subjectId: 101 }, { ...owner, projectId: 8 }, { ...owner, subjectId: 0 }]) {
      mocks.read.mockResolvedValueOnce({ data })
      await view.get('button').trigger('click'); await flushPromises()
      expect(mocks.push).not.toHaveBeenCalled()
      expect(view.get('[role="status"]').text()).toContain('未能确认唯一对应能力')
    }
    mocks.read.mockRejectedValueOnce(new Error('offline'))
    await view.get('button').trigger('click'); await flushPromises()
    expect(view.text()).toContain('对应能力读取失败')
    await view.get('button').trigger('click'); await flushPromises()
    expect(mocks.push).toHaveBeenCalledTimes(1)
  })
  it('discards pending resolutions when the source or permission changes', async () => {
    let resolve!: (value: unknown) => void
    mocks.read.mockReturnValueOnce(new Promise(done => { resolve = done }))
    const view = setup(); await view.get('button').trigger('click')
    await view.setProps({ observationId: 102, canRead: false })
    resolve({ data: owner }); await flushPromises()
    expect(mocks.push).not.toHaveBeenCalled()
    expect(view.get('button').attributes('disabled')).toBeDefined()
  })
})
