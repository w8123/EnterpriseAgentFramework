import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CapabilityGraphCanvas from './CapabilityGraphCanvas.vue'
import type { ApiGraphNode } from '@/api/apiGraph'

const api = vi.hoisted(() => ({ snapshot: vi.fn(), candidates: vi.fn(), save: vi.fn() }))
vi.mock('@/api/apiGraph', async original => ({ ...await original<typeof import('@/api/apiGraph')>(),
  getApiGraphSnapshot: api.snapshot, listApiGraphCandidates: api.candidates, saveApiGraphLayout: api.save }))
const nodes: ApiGraphNode[] = Array.from({ length: 60 }, (_, index) => ({ id: index + 1, projectId: 7, kind: 'API',
  label: `查询${index + 1}`, refId: index + 1, parentId: null, typeName: null,
  propsJson: JSON.stringify({ httpMethod: 'GET', endpointPath: '/orders', subjectType: 'HTTP_API', subjectId: index + 1 }) }))
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => { vi.resetAllMocks(); api.snapshot.mockResolvedValue({ data: { nodes, edges: [], layouts: [] } }); api.candidates.mockResolvedValue({ data: [] }); api.save.mockResolvedValue({ data: null }) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })
async function setup(realDrawer = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:rest(.*)', component: { template: '<div />' } }] })
  await router.push('/business-capabilities/http-apis/graph?projectId=7')
  const view = mount(CapabilityGraphCanvas, { attachTo: document.body, props: { projectId: 7, canWrite: true }, global: {
    plugins: [router, ElementPlus], stubs: { CapabilityGraphToolbar: true, AppDrawer: !realDrawer, AppDialog: true } } })
  wrappers.push(view); await flushPromises()
  const scroller = view.get('.graph-container')
  Object.defineProperties(scroller.element, { clientWidth: { configurable: true, value: 500 }, clientHeight: { configurable: true, value: 420 } })
  await scroller.trigger('scroll'); await flushPromises()
  return { view, scroller }
}
describe('graph candidate reads and viewport interaction', () => {
  it('keeps the formal graph usable while optional candidates load or fail and exposes an independent retry', async () => {
    let rejectCandidates!: (cause: unknown) => void
    api.candidates.mockReturnValueOnce(new Promise((_resolve, reject) => { rejectCandidates = reject }))
    const { view } = await setup()
    const toolbar = view.getComponent({ name: 'CapabilityGraphToolbar' })
    await view.get('[data-graph-node-id="1"]').trigger('click')
    toolbar.vm.$emit('update:showCandidates', true); await flushPromises()
    expect(view.text()).toContain('正在读取候选关系')
    expect(toolbar.props('loading')).toBe(false)
    expect(view.get('[data-graph-node-id="1"]').classes()).toContain('active')
    rejectCandidates(new Error('unavailable')); await flushPromises()
    expect(view.text()).toContain('正式关系仍可查看')
    expect(toolbar.props('loading')).toBe(false)
    expect(view.findAll('.api-card').length).toBeGreaterThan(0)
    expect(view.get('[data-graph-node-id="1"]').classes()).toContain('active')
    const retry = view.findAll('button').find(button => button.text() === '重读候选关系')!
    await retry.trigger('click'); await flushPromises()
    expect(api.candidates).toHaveBeenCalledTimes(2); expect(api.snapshot).toHaveBeenCalledOnce()
    expect(view.text()).not.toContain('候选关系读取失败')
    expect(api.save).not.toHaveBeenCalled()
  })
  it('opens node details outside the glass graph ancestor and closes without clearing the selected node', async () => {
    const { view } = await setup(true)
    await view.get('[data-graph-node-id="1"]').trigger('click'); await flushPromises()
    const drawer = document.body.querySelector('.el-drawer')
    expect(drawer).not.toBeNull()
    expect(drawer?.closest('.api-graph-canvas')).toBeNull()
    ;(drawer?.querySelector('.el-drawer__close-btn') as HTMLButtonElement).click(); await flushPromises()
    expect(view.get('[data-graph-node-id="1"]').classes()).toContain('active')
    expect(api.save).not.toHaveBeenCalled()
  })
  it('toggles candidates without rereading the confirmed snapshot', async () => {
    const { view } = await setup()
    expect(api.snapshot).toHaveBeenCalledOnce()
    const toolbar = view.getComponent({ name: 'CapabilityGraphToolbar' })
    toolbar.vm.$emit('update:showCandidates', true); await flushPromises()
    expect(api.candidates).toHaveBeenCalledOnce(); expect(api.snapshot).toHaveBeenCalledOnce()
    toolbar.vm.$emit('update:showCandidates', false); await flushPromises()
    expect(api.candidates).toHaveBeenCalledOnce(); expect(api.snapshot).toHaveBeenCalledOnce()
  })
  it('culls distant cards while retaining selected cards and lets Tab reach an offscreen card', async () => {
    const { view, scroller } = await setup()
    expect(view.findAll('.api-card').length).toBeLessThan(60)
    expect(view.find('[data-graph-node-id="2"]').exists()).toBe(false)
    const first = view.get('[data-graph-node-id="1"]')
    ;(first.element as HTMLElement).focus()
    await first.trigger('keydown', { key: 'Tab' }); await flushPromises()
    expect(view.find('[data-graph-node-id="2"]').exists()).toBe(true)
    expect((document.activeElement as HTMLElement).dataset.graphNodeId).toBe('2')
    await first.trigger('click'); await flushPromises()
    ;(scroller.element as HTMLElement).scrollLeft = 3000
    await scroller.trigger('scroll'); await flushPromises()
    expect(view.get('[data-graph-node-id="1"]').classes()).toContain('active')
    expect(api.snapshot).toHaveBeenCalledOnce()
    expect(api.save).not.toHaveBeenCalled()
  })
  it('retains a dragged offscreen card until pointer release and persists the actual manual position once', async () => {
    const { view, scroller } = await setup()
    const first = view.get('[data-graph-node-id="1"]')
    await first.trigger('pointerdown', { button: 0, clientX: 330, clientY: 140 })
    window.dispatchEvent(new PointerEvent('pointermove', { clientX: 430, clientY: 240 }))
    ;(scroller.element as HTMLElement).scrollLeft = 3000
    await scroller.trigger('scroll'); await flushPromises()
    expect(view.get('[data-graph-node-id="1"]').classes()).toContain('dragging')
    expect(api.save).not.toHaveBeenCalled()
    window.dispatchEvent(new PointerEvent('pointerup')); await flushPromises()
    expect(api.save).toHaveBeenCalledOnce()
    expect(api.save.mock.calls[0][0]).toBe(7)
    expect(api.snapshot).toHaveBeenCalledOnce()
  })
})
