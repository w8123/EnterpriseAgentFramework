import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { ToolInfo } from '@/types/tool'
import CapabilityDetailDialog from './CapabilityDetailDialog.vue'

const mocks = vi.hoisted(() => ({ get: vi.fn(), businessGet: vi.fn(), references: vi.fn(), push: vi.fn(), copy: vi.fn() }))
vi.mock('@/api/tool', () => ({ getTool: mocks.get, getBusinessMethod: mocks.businessGet, getCapabilityReferences: mocks.references }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@/utils/aiCodingClipboard', () => ({ copyAiCodingText: mocks.copy }))
const tool: ToolInfo = { name: 'orders_read', title: '查询订单', qualifiedName: 'orders:read', projectCode: 'orders', description: '按订单标识查询', source: 'sdk', enabled: true, sourceAvailability: 'SOURCE_UNKNOWN', parameters: [{ name: 'id', type: 'string', description: '订单标识', required: true }] }
let wrapper: ReturnType<typeof mount>
function start(props: Record<string, unknown> = {}) { wrapper = mount(CapabilityDetailDialog, { props: { modelValue: true, capability: tool, ...props }, attachTo: document.body, global: { plugins: [ElementPlus] } }) }
function button(label: string) { return Array.from(document.querySelectorAll('button')).find(b => b.textContent?.trim() === label) as HTMLButtonElement }
function referencesTab() { (document.querySelector('#tab-references') as HTMLElement).click() }
  beforeEach(() => { vi.clearAllMocks(); mocks.get.mockResolvedValue({ data: tool }); mocks.businessGet.mockResolvedValue({ data: tool }); mocks.copy.mockResolvedValue({ copied: true }); mocks.push.mockResolvedValue(undefined) })
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = '' })

describe('capability inspector', () => {
  it('does not reload when the parent replaces the same capability with refreshed data', async () => {
    start(); await flushPromises()
    await wrapper.setProps({ capability: { ...tool, title: '更新后的展示名' } }); await flushPromises()
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })
  it('re-fetches business method details through the dedicated endpoint and invalidates an old account context response', async () => {
    let resolveOld!: (value: unknown) => void
    mocks.businessGet.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const refreshed = { ...tool, title: '新账号的订单查询' }
    mocks.businessGet.mockResolvedValueOnce({ data: refreshed })

    start({ catalogKind: 'business-method', contextKey: 'account-a|project:7' })
    await flushPromises()
    expect(mocks.businessGet).toHaveBeenCalledWith('orders_read')
    expect(mocks.get).not.toHaveBeenCalled()

    await wrapper.setProps({ contextKey: 'account-b|project:7' })
    await flushPromises()
    resolveOld({ data: tool })
    await flushPromises()

    expect(document.body.textContent).toContain('新账号的订单查询')
    expect(document.body.textContent).not.toContain('查询订单')
  })
  it('shows the trial-call workspace only for the business-method detail, never for the mixed legacy detail', async () => {
    start(); await flushPromises()
    expect(document.querySelector('#tab-invocation')).toBeNull()
    wrapper.unmount(); document.body.innerHTML = ''
    start({ catalogKind: 'business-method', contextKey: 'account-a|project:7' }); await flushPromises()
    expect(document.querySelector('#tab-invocation')).not.toBeNull()
  })
  it('explains the real blocker, copies the current contract and navigates with capability context', async () => {
    start(); await flushPromises()
    expect(document.body.textContent).toContain('当前调用会被拦截')
    expect(document.body.textContent).not.toContain('前往 Workflow 编排')
    button('复制输入契约').click(); await flushPromises()
    expect(JSON.parse(mocks.copy.mock.calls[0][0]).capability).toBe('orders:read')
    button('检查当前能力变化').click(); await flushPromises()
    expect(mocks.push).toHaveBeenCalledWith({ name: 'CapabilityReview', query: { projectCode: 'orders', changeSearch: 'orders:read' } })
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
    mocks.get.mockResolvedValue({ data: next }); await wrapper.setProps({ capability: next }); await flushPromises()
    resolveOld({ data: tool }); await flushPromises()
    expect(document.body.textContent).toContain('更新订单'); expect(document.body.textContent).not.toContain('查询订单')
    let resolveReferences!: (value: unknown) => void
    mocks.references.mockImplementationOnce(() => new Promise(resolve => { resolveReferences = resolve }))
    referencesTab(); await flushPromises(); await wrapper.setProps({ modelValue: false }); await flushPromises()
    mocks.get.mockResolvedValue({ data: tool }); await wrapper.setProps({ modelValue: true, capability: tool }); await flushPromises()
    resolveReferences({ data: { runtimeEvidence: 'COMPLETE', publicationEvidence: 'COMPLETE', references: [{ name: '旧请求引用', id: 'old', kind: 'WORKFLOW' }] } }); await flushPromises()
    expect(document.body.textContent).not.toContain('旧请求引用')
  })
  it('shows a retryable detail failure instead of treating the cached row as fresh', async () => {
    mocks.get.mockRejectedValueOnce({ response: { status: 404 } }); start(); await flushPromises()
    expect(document.body.textContent).toContain('能力详情未能加载')
    expect(button('复制输入契约')).toBeUndefined()
    button('重新加载').click(); await flushPromises()
    expect(document.body.textContent).toContain('输入参数')
  })
})
