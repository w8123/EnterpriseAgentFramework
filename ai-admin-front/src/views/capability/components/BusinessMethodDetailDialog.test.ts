import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { businessMethodFixture } from '@/test/fixtures/businessMethod'
import BusinessMethodDetailDialog from './BusinessMethodDetailDialog.vue'
import { parseProjectScopeQuery } from '@/utils/projectScope'

const mocks = vi.hoisted(() => ({ get: vi.fn(), references: vi.fn(), push: vi.fn(), copy: vi.fn(), route: { query: {} as Record<string, string> } }))
vi.mock('@/api/businessMethod', () => ({ getBusinessMethod: mocks.get, getBusinessMethodReferences: mocks.references }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }), useRoute: () => mocks.route }))
vi.mock('@/utils/aiCodingClipboard', () => ({ copyAiCodingText: mocks.copy }))
const tool = businessMethodFixture({ name: 'orders_read', title: '查询订单', qualifiedName: 'orders:read', projectCode: 'orders', description: '按订单标识查询', source: 'sdk', enabled: true, sourceAvailability: 'SOURCE_UNKNOWN', parameters: [{ name: 'id', type: 'string', description: '订单标识', required: true }] })
let wrapper: ReturnType<typeof mount>
function start(props: Record<string, unknown> = {}) { wrapper = mount(BusinessMethodDetailDialog, { props: { modelValue: true, method: tool, ...props }, attachTo: document.body, global: { plugins: [ElementPlus], stubs: { CapabilityEnrichmentPanel: true } } }) }
function button(label: string) { return Array.from(document.querySelectorAll('button')).find(b => b.textContent?.trim() === label) as HTMLButtonElement }
function referencesTab() { (document.querySelector('#tab-references') as HTMLElement).click() }
  beforeEach(() => { vi.clearAllMocks(); mocks.route.query = {}; mocks.get.mockResolvedValue({ data: tool }); mocks.copy.mockResolvedValue({ copied: true }); mocks.push.mockResolvedValue(undefined) })
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

describe('business method inspector', () => {
  it('uses the already owner-verified deep-link detail and still rereads on an explicit refresh', async () => {
    start({ freshDetail: true }); await flushPromises()
    expect(mocks.get).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('查询订单')
    ;(document.querySelector('button[aria-label="重新读取业务方法状态"]') as HTMLElement).click(); await flushPromises()
    expect(mocks.get).toHaveBeenCalledOnce()
  })
  it('keeps source project navigation behind unsaved-input protection after removing Workflow handoff', async () => {
    start(); await flushPromises()
    expect(button('用于 Workflow')).toBeUndefined()
    wrapper.findComponent({ name: 'BusinessMethodInvocationPanel' }).vm.$emit('dirty-change', true)
    button('查看来源项目').click(); await flushPromises()
    expect(mocks.push).not.toHaveBeenCalled()
    button('继续编辑').click(); await flushPromises()
    expect(mocks.push).not.toHaveBeenCalled()
    button('查看来源项目').click(); await flushPromises()
    button('放弃输入').click(); await flushPromises()
    expect(mocks.push).toHaveBeenCalledWith(expect.objectContaining({ name: 'RegistryProjectDetail', params: { projectCode: 'orders' } }))
  })
  it('keeps the contract first and loads supplementary explanations only when requested', async () => {
    start(); await flushPromises()
    expect(document.querySelector('#tab-contract')?.getAttribute('aria-selected')).toBe('true')
    expect(document.querySelector('#pane-contract')?.textContent).toContain('输入参数')
    expect(wrapper.findComponent({ name: 'CapabilityEnrichmentPanel' }).exists()).toBe(false)

    ;(document.querySelector('#tab-description') as HTMLElement).click(); await flushPromises()
    const explanation = wrapper.findComponent({ name: 'CapabilityEnrichmentPanel' })
    expect(explanation.exists()).toBe(true)
    expect(explanation.attributes()).toMatchObject({ projectid: String(tool.projectId), subjecttype: 'BUSINESS_METHOD',
      subjectid: String(tool.assetId), subjectkey: tool.qualifiedName })
    expect(document.querySelector('#tab-description')?.getAttribute('aria-selected')).toBe('true')

    ;(document.querySelector('#tab-contract') as HTMLElement).click(); await flushPromises()
    expect(document.querySelector('#tab-contract')?.getAttribute('aria-selected')).toBe('true')
    expect(document.querySelector('#pane-contract')?.textContent).toContain('复制输入契约')
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })
  it('locks the detail close and tab change while the explanation is saving', async () => {
    start(); await flushPromises()
    ;(document.querySelector('#tab-description') as HTMLElement).click(); await flushPromises()
    wrapper.findComponent({ name: 'CapabilityEnrichmentPanel' }).vm.$emit('busy-change', true); await flushPromises()
    ;(document.querySelector('#tab-contract') as HTMLElement).click(); await flushPromises()
    expect(document.querySelector('#tab-description')?.getAttribute('aria-selected')).toBe('true')
    wrapper.findAllComponents({ name: 'AppDialog' })[0].vm.$emit('update:modelValue', false)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.findComponent({ name: 'CapabilityEnrichmentPanel' }).vm.$emit('busy-change', false); await flushPromises()
    ;(document.querySelector('#tab-contract') as HTMLElement).click(); await flushPromises()
    expect(document.querySelector('#tab-contract')?.getAttribute('aria-selected')).toBe('true')
  })
  it('does not reload when the parent replaces the same capability with refreshed data', async () => {
    start(); await flushPromises()
    await wrapper.setProps({ method: { ...tool, title: '更新后的展示名' } }); await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })
  it('re-fetches business method details through the dedicated endpoint and invalidates an old account context response', async () => {
    let resolveOld!: (value: unknown) => void
    mocks.get.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const refreshed = { ...tool, title: '新账号的订单查询' }
    mocks.get.mockResolvedValueOnce({ data: refreshed })

    start({ contextKey: 'account-a|project:7' })
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledWith('orders_read')

    await wrapper.setProps({ contextKey: 'account-b|project:7' })
    await flushPromises()
    resolveOld({ data: tool })
    await flushPromises()

    expect(document.body.textContent).toContain('新账号的订单查询')
    expect(document.body.textContent).not.toContain('查询订单')
  })
  it('provides the trial workspace for a Java method whose transport is HTTP', async () => {
    mocks.get.mockResolvedValue({ data: { ...tool, httpMethod: 'POST', endpointPath: '/reachai/capabilities/read/invoke' } })
    start(); await flushPromises()
    expect(document.querySelector('#tab-invocation')).not.toBeNull()
    expect(document.body.textContent).toContain('业务方法目录')
    expect(document.body.textContent).not.toContain('能力目录')
  })
  it('explains the real blocker, copies the current contract and navigates with capability context', async () => {
    start(); await flushPromises()
    expect(document.body.textContent).toContain('当前调用会被拦截')
    expect(document.body.textContent).not.toContain('前往 Workflow 编排')
    button('复制输入契约').click(); await flushPromises()
    expect(JSON.parse(mocks.copy.mock.calls[0][0]).businessMethod).toBe('orders:read')
    button('检查当前业务方法变化').click(); await flushPromises()
    expect(mocks.push).toHaveBeenCalledWith({ name: 'RegistryProjectDetail', params: { projectCode: 'orders' }, query: {
      projectId: String(tool.projectId), projectCode: 'orders', subjectType: 'BUSINESS_METHOD', subjectId: String(tool.assetId), subjectKey: 'orders:read',
      section: 'source-changes', changeSearch: 'orders:read',
    } })
  })
  it.each(['检查当前业务方法变化', '查看来源项目'])('preserves catalog query when opening %s and replaces stale owner context', async label => {
    mocks.route.query = { scope: 'all', projectId: '99', projectCode: 'stale', methodKeyword: '订单', methodPage: '4', methodGroupId: '3',
      apiKeyword: '支付', subjectType: 'HTTP_API', subjectId: '12', subjectKey: 'stale-key' }
    start(); await flushPromises(); button(label).click(); await flushPromises()
    const target = mocks.push.mock.calls[0][0]
    expect(target.query).toMatchObject({ methodKeyword: '订单', methodPage: '4', methodGroupId: '3', apiKeyword: '支付',
      projectId: String(tool.projectId), projectCode: 'orders', subjectType: 'BUSINESS_METHOD', subjectId: String(tool.assetId), subjectKey: 'orders:read' })
    expect(parseProjectScopeQuery(target.query)).toEqual({ kind: 'project', projectId: tool.projectId, projectCode: 'orders' })
  })
  it('loads references lazily and links to the actual workflow instead of the list', async () => {
    mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE', references: [{ kind: 'WORKFLOW', id: 'w1', name: '订单处理', stage: 'DRAFT', nodeId: 'lookup' }] } })
    start(); await flushPromises(); expect(mocks.references).not.toHaveBeenCalled()
    referencesTab(); await flushPromises()
    expect(mocks.references).toHaveBeenCalledWith('orders', 'orders_read')
    expect(document.body.textContent).toContain('订单处理')
    button('查看').click(); await flushPromises()
    expect(mocks.push).toHaveBeenCalledWith({ name: 'WorkflowStudio', params: { workflowId: 'w1' }, query: { nodeId: 'lookup' } })
  })
  it('does not present incomplete or failed reference queries as unused', async () => {
    mocks.references.mockResolvedValue({ data: { runtimeEvidence: 'UNKNOWN', publicationEvidence: 'COMPLETE', references: [] } })
    start(); await flushPromises(); referencesTab(); await flushPromises()
    expect(document.body.textContent).toContain('部分使用位置尚未确认')
    expect(document.body.textContent).not.toContain('还没有被引用')
    mocks.references.mockRejectedValue(new Error('offline')); button('刷新').click(); await flushPromises()
    expect(document.body.textContent).toContain('使用位置查询失败')
    expect(document.body.textContent).not.toContain('还没有被引用')
  })
  it('discards late detail and reference responses after selecting another capability', async () => {
    let resolveOld!: (value: unknown) => void
    mocks.get.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    start(); await flushPromises()
    const next = { ...tool, name: 'orders_write', title: '更新订单', qualifiedName: 'orders:write' }
    mocks.get.mockResolvedValue({ data: next }); await wrapper.setProps({ method: next }); await flushPromises()
    resolveOld({ data: tool }); await flushPromises()
    expect(document.body.textContent).toContain('更新订单'); expect(document.body.textContent).not.toContain('查询订单')
    let resolveReferences!: (value: unknown) => void
    mocks.references.mockImplementationOnce(() => new Promise(resolve => { resolveReferences = resolve }))
    referencesTab(); await flushPromises(); await wrapper.setProps({ modelValue: false }); await flushPromises()
    mocks.get.mockResolvedValue({ data: tool }); await wrapper.setProps({ modelValue: true, method: tool }); await flushPromises()
    resolveReferences({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE', references: [{ name: '旧请求引用', id: 'old', kind: 'WORKFLOW' }] } }); await flushPromises()
    expect(document.body.textContent).not.toContain('旧请求引用')
  })
  it('shows a retryable detail failure instead of treating the cached row as fresh', async () => {
    mocks.get.mockRejectedValueOnce({ response: { status: 404 } }); start(); await flushPromises()
    expect(document.body.textContent).toContain('业务方法详情未能加载')
    expect(button('复制输入契约')).toBeUndefined()
    button('重新加载').click(); await flushPromises()
    expect(document.body.textContent).toContain('输入参数')
  })
})
