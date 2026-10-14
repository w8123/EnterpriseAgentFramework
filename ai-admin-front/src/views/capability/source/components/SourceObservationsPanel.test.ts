import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElPagination } from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SourceObservationsPanel from './SourceObservationsPanel.vue'
import SourceObservationDetailDrawer from './SourceObservationDetailDrawer.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import { renderSafeMarkdown } from '@/utils/safeMarkdown'
import { parameterRows } from '../sourceParameterRows'
import type { ProjectToolInfo } from '@/types/scanProject'
import type { SemanticDoc } from '@/types/semanticDoc'

const api = vi.hoisted(() => ({ body: vi.fn() }))
vi.mock('@/api/semanticDoc', () => ({ getSubjectSemanticDoc: api.body }))
const longDocument = `仅在详情阅读的完整业务说明。${'这是关于接口返回结构和业务使用条件的长篇解释。'.repeat(40)}`
function record(id = 1, overrides: Partial<ProjectToolInfo> = {}): ProjectToolInfo {
  return { scanToolId: id, projectId: 7, moduleId: 1, name: `orders:read${id}`, title: `查询订单${id}`,
    description: '按订单号查询', httpMethod: 'GET', endpointPath: '/orders', sourceLocation: 'OrderController.java',
    source: 'scanner', enabled: true, parameters: [], ...overrides }
}
const document: SemanticDoc = { id: 31, projectId: 7, subjectType: 'SOURCE_OBSERVATION', subjectId: 1,
  subjectKey: 'orders:read1', title: '订单说明', contentMd: longDocument, promptVersion: null,
  modelName: null, tokenUsage: 0, status: 'generated' }
const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); globalThis.document.body.innerHTML = '' })
function start(records = [record()], extra: Record<string, unknown> = {}, slots: Record<string, string> = {}) {
  api.body.mockImplementation(async (projectId: number, type: string, id: number) => ({ data: (Object.values(wrapper.props('toolDocMap')) as SemanticDoc[]).find(doc => doc.projectId === projectId && doc.subjectType === type && doc.subjectId === id) || null }))
  const wrapper = mount(SourceObservationsPanel, { attachTo: globalThis.document.body, props: {
    projectId: 7, interfaceCollapseActive: ['m-1'], loading: false, tools: records,
    stageAdvice: { title: '等待来源', description: '', primaryLabel: '同步来源', primaryAction: 'importApi' },
    visibleToolModuleGroups: [{ key: 'm-1', moduleId: 1, label: '订单', tools: records }], total: records.length, projectTotal: records.length, current: 1, pageSize: 20, modules: [],
    toolDocMap: { 1: document }, sensitiveScanStarting: false, sensitiveTaskPolling: false, sensitiveStatusError: '',
    exportScanToolsExcelLoading: false, toolDetailLoading: {}, toolDetailError: {}, rescanSourceLoading: {},
    parameterRows, renderMd: renderSafeMarkdown, scanToolRowClassName: () => '',
    toolParameterCount: row => row.parameterCount ?? row.parameters?.length ?? 0,
    toolLinkLabel: () => '历史投影差异', toolLinkTagType: () => 'warning', ...extra,
  }, slots, global: { plugins: [ElementPlus], stubs: { SourceOwnerLink: true } } })
  wrappers.push(wrapper); return wrapper
}
function button(label: string): HTMLButtonElement {
  const target = Array.from(globalThis.document.querySelectorAll('button')).find(item => item.textContent?.trim() === label || item.getAttribute('aria-label') === label)
  expect(target, `button ${label}`).toBeDefined()
  return target!
}
async function open(wrapper: ReturnType<typeof mount>) {
  await flushPromises()
  await wrapper.get('.source-record-name').trigger('click'); await flushPromises()
  return wrapper.getComponent(SourceObservationDetailDrawer)
}

describe('source record list and detail workflow', () => {
  it('distinguishes pending and failed descriptions from missing documents in the list and drawer', async () => {
    const view = start([record()], { toolDocMap: {}, semanticLoading: true })
    expect(view.text()).toContain('说明读取中')
    await open(view)
    expect(globalThis.document.body.textContent).toContain('正在读取业务说明')
    expect(globalThis.document.body.textContent).not.toContain('还没有补充 AI 语义文档')
    expect(button('生成 AI 文档').disabled).toBe(true)
    await view.setProps({ semanticLoading: false, semanticError: '业务说明读取失败' })
    expect(view.text()).toContain('说明读取失败')
    expect(globalThis.document.body.textContent).toContain('业务说明读取失败')
    expect(button('生成 AI 文档').disabled).toBe(true)
    await view.setProps({ semanticError: '', toolDocMap: { 1: document } })
    expect(view.text()).toContain('AI 已生成')
    await flushPromises()
    expect(globalThis.document.body.textContent).toContain(longDocument)
    expect(button('重新生成 AI').disabled).toBe(false)
  })
  it('opens only the specifically resolved source and removes its drawer when the context changes', async () => {
    const view = start([record(1), record(2)], { focusObservationId: 2 })
    await flushPromises()
    expect(view.getComponent(SourceObservationDetailDrawer).props('row')?.scanToolId).toBe(2)
    expect(view.getComponent(SourceObservationDetailDrawer).props('modelValue')).toBe(true)
    expect(view.emitted('toolExpandChange')![0][0]).toMatchObject({ scanToolId: 2 })
    await view.setProps({ focusObservationId: 999 }); await flushPromises()
    expect(view.getComponent(SourceObservationDetailDrawer).props('modelValue')).toBe(false)
  })
  it('dispatches server search without locally hiding records or changing the complete-export action', async () => {
    const view = start([record(), record(2)], { projectTotal: 500, total: 500 })
    await flushPromises()
    const search = view.get('input[aria-label="搜索来源记录"]')
    await search.setValue('客户')
    expect(view.findAll('.source-record-name')).toHaveLength(2)
    await new Promise(resolve => setTimeout(resolve, 320))
    expect(view.emitted('search')).toEqual([['客户']])
    expect(view.getComponent(ElPagination).props('total')).toBe(500)
    button('导出 EXCEL').click(); await flushPromises()
    expect(view.emitted('exportExcel')).toEqual([[]])
    const filtered = [record(300, { title: '客户详情' })]
    await view.setProps({ tools: filtered, keyword: '客户', total: 1, visibleToolModuleGroups: [{ key: 'm-1', moduleId: 1, label: '订单', tools: filtered }] })
    expect(view.findAll('.source-record-name')).toHaveLength(1)
    expect(view.text()).toContain('1 / 500 条')
    button('清除来源搜索').click(); await flushPromises()
    expect(view.emitted('search')![1]).toEqual([''])
    expect(globalThis.document.activeElement).toBe(search.element)
  })
  it('does not submit Chinese composition until its final text and keeps previous results visible', async () => {
    const view = start([record(), record(2)])
    await flushPromises()
    const search = view.get('input[aria-label="搜索来源记录"]')
    await search.trigger('compositionstart')
    ;(search.element as HTMLInputElement).value = '客户'
    await search.trigger('input'); await new Promise(resolve => setTimeout(resolve, 320))
    expect(view.emitted('search')).toBeUndefined()
    await search.trigger('compositionend'); await new Promise(resolve => setTimeout(resolve, 320))
    expect(view.emitted('search')).toEqual([['客户']])
    expect(view.findAll('.source-record-name')).toHaveLength(2)
  })
  it('distinguishes filtered empty results from a project without sources and keeps global module counts', async () => {
    const view = start([], { projectTotal: 800, total: 0, keyword: '不存在', modules: [{ moduleId: 2, moduleDisplayName: '客户', count: 790 }] })
    await flushPromises()
    expect(view.text()).toContain('没有匹配的来源记录')
    expect(view.text()).not.toContain('等待来源')
    button('导出 EXCEL').click(); await flushPromises()
    expect(view.emitted('exportExcel')).toEqual([[]])
    expect(view.findComponent({ name: 'ElOption' }).props('label')).toBe('客户 (790)')
    view.getComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', '2')
    expect(view.emitted('filterModule')).toEqual([['2']])
  })
  it('renders only the supplied server page and emits one pagination action while retaining accessible collapse', async () => {
    const records = Array.from({ length: 20 }, (_, index) => record(index + 21))
    const view = start(records, { total: 105, projectTotal: 105, current: 2 })
    await flushPromises()
    expect(view.findAll('.source-record-name')).toHaveLength(20)
    expect(view.get('.source-record-name').text()).toBe('查询订单21')
    const pagination = view.getComponent(ElPagination)
    expect(pagination.props('currentPage')).toBe(2)
    pagination.vm.$emit('current-change', 3)
    expect(view.emitted('changePage')).toEqual([[3]])
    const toggle = view.get('button[aria-label="折叠订单来源记录"]')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    await toggle.trigger('click')
    expect(view.findAll('.source-record-name')).toHaveLength(0)
    await view.get('button[aria-label="展开订单来源记录"]').trigger('click')
    expect(view.findAll('.source-record-name')).toHaveLength(20)
  })

  it('uses the supplied toolbar and status while preserving sensitive-task recovery', async () => {
    const view = start([record()], { sensitiveStatusError: '扫描状态读取失败' }, {
      toolbar: '<button>集中来源操作</button>', status: '<p>当前无需确认变化</p>',
    }); await flushPromises()
    expect(view.text()).toContain('集中来源操作')
    expect(view.text()).toContain('当前无需确认变化')
    expect(view.text()).not.toContain('导出 EXCEL')
    button('重试扫描状态').click(); await flushPromises()
    expect(view.emitted('retrySensitiveStatus')).toEqual([[]])
    await open(view)
    expect(view.emitted('toolExpandChange')).toHaveLength(1)
  })

  it('opens the same record from its name and View, with the full AI document only in the drawer', async () => {
    const view = start(); await flushPromises()
    expect(view.text()).toContain('AI 已生成')
    expect(view.text()).not.toContain('仅在详情阅读的完整业务说明')
    const drawer = await open(view)
    const selected = view.emitted('toolExpandChange')![0][0]
    expect(drawer.props('row')).toBe(selected)
    expect(globalThis.document.body.textContent).toContain(longDocument)
    button('关闭').click(); await flushPromises()
    expect(drawer.getComponent(AppDrawer).props('modelValue')).toBe(false)
    const viewAction = view.findAll('button').find(item => item.text() === '核对来源')!
    await viewAction.trigger('click'); await flushPromises()
    expect(drawer.props('row')).toBe(selected)
    expect(view.emitted('toolExpandChange')![1]).toEqual([selected, true])
  })

  it('preserves source refresh, AI generation, editing and diff actions on the selected record', async () => {
    const view = start([record(1, { toolSyncDiffFields: ['parameters'], sensitiveData: { types: ['PHONE'], summary: '包含电话', scannedAt: '2026-10-09' } })])
    const drawer = await open(view)
    const selected = drawer.props('row')
    for (const [label, event] of [['扫描更新', 'rescanFromSource'], ['重新生成 AI', 'regenerateTool'], ['查看差异', 'openDiff']] as const) {
      button(label).click(); await flushPromises()
      expect(view.emitted(event)![0][0]).toBe(selected)
    }
    button('编辑 AI 文档').click(); await flushPromises()
    expect(view.emitted('openEditDoc')![0][0]).toMatchObject({ contentMd: longDocument, subjectId: 1 })
    expect(globalThis.document.body.textContent).toContain('包含电话')
    expect(globalThis.document.body.textContent).toContain('历史投影差异')
  })

  it('retains source detail reading while disabling writes for a read-only project', async () => {
    const view = start([record()], { canWrite: false })
    await open(view)
    expect(globalThis.document.body.textContent).toContain(longDocument)
    for (const label of ['重新生成 AI', '编辑 AI 文档', '扫描更新']) {
      expect(button(label).disabled).toBe(true)
      button(label).click()
    }
    await flushPromises()
    expect(view.emitted('regenerateTool')).toBeUndefined()
    expect(view.emitted('openEditDoc')).toBeUndefined()
    expect(view.emitted('rescanFromSource')).toBeUndefined()
  })

  it('shares generation busy state with the linked formal owner without confusing subject types', async () => {
    const owner = { ...document, subjectType: 'HTTP_API' as const, subjectId: 4, sourceObservationIds: [1] }
    const view = start([record()], { toolDocMap: { 1: owner }, generatingKeys: ['HTTP_API:4'] })
    await open(view)
    expect(button('重新生成 AI').disabled).toBe(true)
    expect(button('编辑 AI 文档').disabled).toBe(true)
    button('重新生成 AI').click(); await flushPromises()
    expect(view.emitted('regenerateTool')).toBeUndefined()
    await view.setProps({ generatingKeys: ['GROUP:4'] })
    expect(button('重新生成 AI').disabled).toBe(false)
    expect(button('编辑 AI 文档').disabled).toBe(false)
  })

  it('shows loading and failed detail reads without inventing empty parameters and allows inline retry', async () => {
    const view = start([record()], { toolDetailLoading: { 1: true } })
    await open(view)
    expect(globalThis.document.querySelector('.source-observation-detail')?.getAttribute('aria-busy')).toBe('true')
    expect(globalThis.document.body.textContent).not.toContain('没有声明参数')
    expect(globalThis.document.body.textContent).not.toContain('参数定义')
    expect(button('扫描更新').disabled).toBe(true)
    await view.setProps({ toolDetailLoading: {}, toolDetailError: { 1: '读取来源详情失败，请重新加载。' } }); await flushPromises()
    expect(globalThis.document.body.textContent).toContain('读取来源详情失败')
    expect(globalThis.document.body.textContent).not.toContain('没有声明参数')
    button('重新加载详情').click(); await flushPromises()
    expect(view.emitted('toolExpandChange')).toHaveLength(2)
    expect(button('扫描更新').disabled).toBe(true)
    await view.setProps({ toolDetailError: {} }); await flushPromises()
    expect(globalThis.document.body.textContent).toContain('没有声明参数')
    expect(button('扫描更新').disabled).toBe(false)
  })

  it.each([{ sourceLocation: 'SDK:orders' }, { removedFromSource: true }])('disables source refresh for %j and preserves AI generation without a document', async condition => {
    const view = start([record(1, condition)], { toolDocMap: {} }); await open(view)
    expect(button('扫描更新').disabled).toBe(true)
    button('扫描更新').click()
    expect(view.emitted('rescanFromSource')).toBeUndefined()
    expect(button('编辑 AI 文档').disabled).toBe(true)
    button('编辑 AI 文档').click()
    expect(view.emitted('openEditDoc')).toBeUndefined()
    button('生成 AI 文档').click(); await flushPromises()
    expect(view.emitted('regenerateTool')).toHaveLength(1)
  })

  it('closes and clears the detail when a refresh removes the selected record', async () => {
    const view = start(); const drawer = await open(view)
    await view.setProps({ tools: [], visibleToolModuleGroups: [] }); await flushPromises()
    expect(drawer.props('row')).toBeNull()
    expect(drawer.getComponent(AppDrawer).props('modelValue')).toBe(false)
    expect(globalThis.document.querySelector('.source-observation-detail')).toBeNull()
  })

})
