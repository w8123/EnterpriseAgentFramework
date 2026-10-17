import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import WorkflowStudioReadOnlyTrialDrawer from './WorkflowStudioReadOnlyTrialDrawer.vue'

const api = vi.hoisted(() => ({ run: vi.fn() }))
vi.mock('@/api/workflow', () => ({ runWorkflowReadOnlyTrial: api.run }))
let wrapper: VueWrapper | null = null
const graph = JSON.stringify({ schemaVersion: 2,
  inputSchema: { type: 'object', properties: { orderNo: { type: 'string' } }, required: ['orderNo'] },
  nodes: [
    { id: 'input', type: 'USER_INPUT', config: {} },
    { id: 'method', type: 'TOOL', name: '查询订单', ref: { kind: 'TOOL', qualifiedName: 'orders:normalize' }, config: {} },
    { id: 'api', type: 'TOOL', name: '查询库存', ref: { kind: 'TOOL', qualifiedName: 'http-api:orders:dev:stock' }, config: { httpApiAssetId: 7 } },
    { id: 'answer', type: 'ANSWER', config: {} },
  ], edges: [], entryNodeId: 'input', exitNodeIds: ['answer'] })
async function drawer(props: Record<string, unknown> = {}) {
  wrapper = mount(WorkflowStudioReadOnlyTrialDrawer, { attachTo: document.body, props: {
    open: true, dirty: false, authorized: true, sessionScope: 'actor-a',
    studio: { workflowId: 'wf-method', projectId: 41, projectCode: 'orders', status: 'DRAFT', revision: 'saved-r1', graphSpecJson: graph } as any,
    ...props,
  }, global: { plugins: [ElementPlus], stubs: {
    AppDrawer: { template: '<section><slot /><footer><slot name="footer" /></footer></section>' },
  } } })
  await flushPromises(); return wrapper
}
describe('whole-draft read-only trial drawer', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.run.mockResolvedValue({ data: { success: false, status: 'FAILED', runId: 'r', traceId: 'trace-1', workflowId: 'wf-method',
      revision: 'saved-r1', answer: null, variables: {}, elapsedMs: 12, errorCode: 'HTTP_API_TRIAL_DISPATCH_FAILED',
      failedNodeId: 'api', failedNodeName: '查询库存', errorMessage: '「查询库存」调用失败',
      targets: [], steps: [
        { index: 0, nodeId: 'method', nodeType: 'TOOL', nodeName: '查询订单', status: 'SUCCESS', elapsedMs: 4,
          output: { orderNo: 'N-1' }, errorCode: null, errorMessage: null },
        { index: 1, nodeId: 'api', nodeType: 'TOOL', nodeName: '查询库存', status: 'ERROR', elapsedMs: 8,
          output: null, errorCode: 'HTTP_API_TRIAL_DISPATCH_FAILED', errorMessage: '「查询库存」调用失败' },
      ] } })
  })
  afterEach(() => { wrapper?.unmount(); wrapper = null; document.body.innerHTML = '' })

  it('lists the real targets, submits one native form and renders per-node results', async () => {
    const view = await drawer()
    expect(view.text()).toContain('查询订单'); expect(view.text()).toContain('http-api:orders:dev:stock')
    expect(view.findAll('form')).toHaveLength(1)
    await view.get('#trial-input-orderNo').setValue('A-1024')
    const submit = new Event('submit', { cancelable: true, bubbles: true })
    view.get('form').element.dispatchEvent(submit); await flushPromises()
    expect(submit.defaultPrevented).toBe(true)
    expect(api.run).toHaveBeenCalledExactlyOnceWith({ workflowId: 'wf-method', expectedRevision: 'saved-r1', inputParams: { orderNo: 'A-1024' } })
    const steps = view.findAll('.trial-step')
    expect(steps).toHaveLength(2)
    expect(steps[0].text()).toContain('成功 · 4 ms')
    expect(steps[1].classes()).toContain('is-failed')
    expect(steps[1].text()).toContain('「查询库存」调用失败')
    await steps[1].get('.trial-node-link').trigger('click')
    expect(view.emitted('focus-node')?.[0]).toEqual(['api'])
  })

  it('IME Enter is prevented and never dispatches', async () => {
    const view = await drawer(); await view.get('#trial-input-orderNo').setValue('A-1024')
    const enter = new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, cancelable: true, bubbles: true })
    view.get('#trial-input-orderNo').element.dispatchEvent(enter); await flushPromises()
    expect(enter.defaultPrevented).toBe(true); expect(api.run).not.toHaveBeenCalled()
  })

  it('empty required input focuses the invalid field without dispatch', async () => {
    const view = await drawer()
    view.get('form').element.dispatchEvent(new Event('submit', { cancelable: true, bubbles: true })); await flushPromises()
    expect(api.run).not.toHaveBeenCalled(); expect(view.text()).toContain('请填写 orderNo')
    expect(document.activeElement?.id).toBe('trial-input-orderNo')
  })

  it('offers save when the draft has unsaved edits', async () => {
    const view = await drawer({ dirty: true })
    expect(view.text()).toContain('有未保存编辑')
    const save = view.findAll('button').find((button) => button.text() === '保存草稿')!
    await save.trigger('click')
    expect(view.emitted('save')).toHaveLength(1)
  })
})
