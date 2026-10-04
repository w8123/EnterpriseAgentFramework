import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import WorkflowVersions from './WorkflowVersions.vue'

const mocks = vi.hoisted(() => ({ query: {} as Record<string, string>, versions: vi.fn(), workflow: vi.fn() }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { workflowId: 'orders-wf' }, query: mocks.query }), useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/auth/platformSession', async () => { const { ref } = await import('vue'); return { platformSessionUser: ref({ permissionGrants: [] }) } })
vi.mock('@/api/workflow', () => ({ getWorkflow: mocks.workflow, listWorkflowVersions: mocks.versions,
  publishWorkflowVersion: vi.fn(), rollbackWorkflowVersion: vi.fn(), validateWorkflowVersion: vi.fn() }))
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  mocks.query = { versionId: '9', nodeId: 'api-node' }
  mocks.workflow.mockResolvedValue({ data: { id: 'orders-wf', name: '订单流程', projectCode: 'orders' } })
  mocks.versions.mockResolvedValue({ data: [{ id: 9, workflowId: 'orders-wf', version: 'v1.0.0', status: 'RETIRED',
    graphSpecSnapshotJson: JSON.stringify({ nodes: [{ id: 'api-node', type: 'HTTP_REQUEST', name: '订单查询', ref: { contractHash: 'old-pin' } }] }) },
    { id: 10, workflowId: 'orders-wf', version: 'v1.0.1', status: 'ACTIVE' }] })
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })
async function start() { wrapper = mount(WorkflowVersions, { global: { plugins: [ElementPlus], stubs: { RouterLink: true } } }); await flushPromises() }
describe('version reference navigation', () => {
  it('locates the exact historical version and immutable node instead of redirecting to active', async () => {
    await start(); const location = wrapper.find('.reference-location')
    expect(location.text()).toContain('v1.0.0'); expect(location.text()).toContain('历史版本')
    expect(location.text()).toContain('api-node'); expect(location.text()).toContain('old-pin')
    expect(location.text()).not.toContain('v1.0.1')
  })
  it('does not silently fall back when the referenced version is absent', async () => {
    mocks.query.versionId = '19'; await start()
    expect(wrapper.find('.reference-location').text()).toContain('未查到指定引用版本')
    expect(wrapper.find('.reference-location').text()).not.toContain('v1.0.1')
  })
  it('reads the real legacy envelope whose graphSpec is a JSON string', async () => {
    const historical = (await mocks.versions()).data[0]
    mocks.versions.mockResolvedValue({ data: [{ ...historical, graphSpecSnapshotJson: undefined,
      snapshotJson: JSON.stringify({ graphSpec: historical.graphSpecSnapshotJson }) }] })
    await start()
    expect(wrapper.find('.reference-location').text()).toContain('old-pin')
  })
  it('explains unreadable node evidence without changing the selected version', async () => {
    mocks.query.nodeId = 'missing-node'; await start()
    expect(wrapper.find('.reference-location').text()).toContain('未能在此版本读取节点 missing-node')
    expect(wrapper.find('.reference-location').text()).toContain('v1.0.0')
  })
})
