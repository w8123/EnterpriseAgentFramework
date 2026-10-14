import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import CapabilityParamHints from './CapabilityParamHints.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()) })
const hint = { targetPath: 'input.orderId', targetField: 'orderId', targetApi: 'orders:read', sourcePath: 'output.id',
  sourceField: 'id', sourceApi: 'orders:create', confidence: 1 }
function setup(graphState: 'CURRENT' | 'STALE' | 'UNACCEPTED' | 'NOT_BUILT' = 'CURRENT', hints: typeof hint[] | undefined = [hint]) {
  const wrapper = mount(CapabilityParamHints, { props: { projectId: 7, subjectType: 'BUSINESS_METHOD', subjectId: 11,
    graphState, contractHash: 'current-contract', hints }, global: { plugins: [ElementPlus] } })
  wrappers.push(wrapper); return wrapper
}
describe('confirmed parameter hints in the owner enrichment response', () => {
  it('displays confirmed hints supplied by the owning enrichment without additional reads', async () => {
    const wrapper = setup(); await flushPromises()
    expect(wrapper.text()).toContain('来自 orders:create 的 id')
    expect(wrapper.text()).toContain('不会自动修改 Workflow')
    expect(wrapper.text()).toContain('不代表已被流程使用')
    expect(wrapper.text()).toContain('实际引用请查看“使用位置”')
    expect(wrapper.findAll('button')).toHaveLength(0)
  })
  it('distinguishes stale, unreadable and a known empty current contract and delegates retry to the owner', async () => {
    const wrapper = setup('STALE', []); await flushPromises()
    expect(wrapper.text()).toContain('图谱或已确认关系基于此前契约')
    expect(wrapper.text()).toContain('请先核对图谱修订')
    expect(wrapper.text()).not.toContain('尚无可用的已确认参数来源')
    await wrapper.setProps({ graphState: 'CURRENT', hints: undefined })
    expect(wrapper.text()).toContain('读取失败')
    expect(wrapper.text()).not.toContain('尚无可用的已确认参数来源')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toEqual([[]])
    await wrapper.setProps({ hints: [] })
    expect(wrapper.text()).toContain('尚无可用的已确认参数来源')
  })
  it.each(['UNACCEPTED', 'NOT_BUILT'] as const)('keeps %s unavailable even when a hint array is supplied', async state => {
    const wrapper = setup(state); await flushPromises()
    expect(wrapper.text()).not.toContain('orders:create')
    expect(wrapper.text()).not.toContain('尚无可用的已确认参数来源')
  })
  it('drops previous-owner hints when the owner response is invalidated', async () => {
    const wrapper = setup(); await wrapper.setProps({ subjectId: 12, hints: undefined })
    expect(wrapper.text()).not.toContain('orders:create')
    expect(wrapper.text()).toContain('读取失败')
    await wrapper.setProps({ hints: [{ ...hint, sourceApi: 'customers:read' }] })
    expect(wrapper.text()).toContain('customers:read')
    expect(wrapper.text()).not.toContain('orders:create')
  })
})
