import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import WorkflowStudioReadOnlyTrialDrawer from './WorkflowStudioReadOnlyTrialDrawer.vue'

const api = vi.hoisted(() => ({ owner: vi.fn(), run: vi.fn() }))
vi.mock('@/api/businessMethodInvocation', () => ({ getBusinessMethodInvocationContext: api.owner }))
vi.mock('@/api/workflow', () => ({ runWorkflowReadOnlyTrial: api.run }))
vi.mock('@/api/httpApi', () => ({ getHttpApi: vi.fn(), getHttpApiConnection: vi.fn() }))
let wrapper: VueWrapper | null = null
const graph = JSON.stringify({ nodes: [
  { id: 'method', type: 'TOOL', ref: { kind: 'TOOL', qualifiedName: 'orders:normalize' }, config: { inputMapping: { orderNo: 'params.orderNo' } } },
  { id: 'variable', type: 'VARIABLE_ASSIGN', config: { assignments: { normalized: 'nodeOutput.method.data' } } },
], edges: [{ from: 'method', to: 'variable' }], entryNodeId: 'method', exitNodeIds: ['variable'] })
async function drawer() {
  wrapper = mount(WorkflowStudioReadOnlyTrialDrawer, { attachTo: document.body, props: {
    open: true, dirty: false, authorized: true, sessionScope: 'actor-a',
    studio: { workflowId: 'wf-method', projectId: 41, projectCode: 'orders', status: 'DRAFT', revision: 'saved-r1', graphSpecJson: graph } as any,
  }, global: { plugins: [ElementPlus], stubs: {
    AppDrawer: { template: '<section><slot /><footer><slot name="footer" /></footer></section>' },
  } } })
  await flushPromises(); return wrapper
}
describe('read-only trial native form and IME behavior', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    api.owner.mockResolvedValue({ data: { contractVersion: 1, name: 'orders_normalize', qualifiedName: 'orders:normalize',
      sourceQualifiedName: 'orders:normalize', assetType: 'BUSINESS_METHOD', projectId: 41, projectCode: 'orders',
      enabled: true, sourceAvailability: 'READY', currentContractHash: 'a'.repeat(64), acceptedContractHash: 'a'.repeat(64),
      sourceContractHash: 'a'.repeat(64), sideEffect: 'READ_ONLY', credentialAvailable: true,
      businessIdentityRequired: false, executable: true, responseType: 'String',
      parameters: [{ name: 'orderNo', type: 'String', required: true }],
    } })
    api.run.mockResolvedValue({ data: { success: true, assetType: 'BUSINESS_METHOD', status: 'SUCCESS',
      qualifiedName: 'orders:normalize', revision: 'saved-r1', elapsedMs: 1, variables: {}, methodOutput: { data: 'N-A' } } })
  })
  afterEach(() => { wrapper?.unmount(); wrapper = null; document.body.innerHTML = '' })
  it('has one native form, prevents navigation and submits one explicit keyboard operation', async () => {
    const view = await drawer()
    expect(view.findAll('form')).toHaveLength(1)
    await view.get('input').setValue('A-1024')
    const submit = new Event('submit', { cancelable: true, bubbles: true })
    view.get('form').element.dispatchEvent(submit); await flushPromises()
    expect(submit.defaultPrevented).toBe(true)
    expect(api.run).toHaveBeenCalledExactlyOnceWith({ workflowId: 'wf-method', expectedRevision: 'saved-r1', inputParams: { orderNo: 'A-1024' } })
  })
  it('IME Enter is prevented and never dispatches a method', async () => {
    const view = await drawer(); await view.get('input').setValue('A-1024')
    const enter = new KeyboardEvent('keydown', { key: 'Enter', isComposing: true, cancelable: true, bubbles: true })
    view.get('input').element.dispatchEvent(enter); await flushPromises()
    expect(enter.defaultPrevented).toBe(true); expect(api.run).not.toHaveBeenCalled()
  })
  it('empty required input focuses the invalid field without dispatch', async () => {
    const view = await drawer()
    const submit = new Event('submit', { cancelable: true, bubbles: true })
    view.get('form').element.dispatchEvent(submit); await flushPromises()
    expect(api.run).not.toHaveBeenCalled(); expect(view.text()).toContain('请填写 orderNo')
    expect(document.activeElement?.id).toBe('trial-input-orderNo')
  })
})
