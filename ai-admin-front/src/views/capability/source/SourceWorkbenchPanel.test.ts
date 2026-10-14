import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SourceWorkbenchPanel from './SourceWorkbenchPanel.vue'
import SourceActions from './components/SourceActions.vue'
import ProjectSourceChanges from '@/views/registry/components/ProjectSourceChanges.vue'
import type { BusinessCapabilityWorkspace } from '../businessCapabilityRoutes'

const api = vi.hoisted(() => ({ project: vi.fn(), tools: vi.fn(), docs: vi.fn(), groups: vi.fn(),
  changes: vi.fn(), models: vi.fn(), semanticStatus: vi.fn(), sensitiveStatus: vi.fn(), owner: vi.fn() }))
vi.mock('@/api/scanProject', async importOriginal => ({ ...await importOriginal<typeof import('@/api/scanProject')>(),
  getScanProjectDetail: api.project, getScanProjectSources: api.tools, getSensitiveDataScanStatus: api.sensitiveStatus }))
vi.mock('@/api/semanticDoc', async importOriginal => ({ ...await importOriginal<typeof import('@/api/semanticDoc')>(),
  listProjectSemanticDocs: api.docs, listScanModules: api.groups, getProjectBatchStatus: api.semanticStatus, getCapabilityEnrichment: api.owner }))
vi.mock('@/api/model', async importOriginal => ({ ...await importOriginal<typeof import('@/api/model')>(), getModelInstances: api.models }))
vi.mock('@/api/registry', async importOriginal => ({ ...await importOriginal<typeof import('@/api/registry')>(), listCapabilityChanges: api.changes }))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue')
  return { platformSessionId: ref('source-reader'), platformSessionUser: ref({ userId: 1, username: 'reader',
    permissionGrants: [
      { permissionCode: 'platform:read', scopeType: 'GLOBAL', scopeValue: '*' },
      { permissionCode: 'platform:write', scopeType: 'GLOBAL', scopeValue: '*' },
    ] }) }
})

function page(records: any[] = []) { return { records, total: records.length, current: 1, size: 20, stats: { total: records.length, removed: 0 }, modules: [] } }
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.resetAllMocks()
  api.project.mockResolvedValue({ data: { id: 7, name: '订单项目', projectCode: 'orders', projectKind: 'REGISTERED' } })
  api.tools.mockResolvedValue({ data: page([{ scanToolId: 31, name: 'orders:read', moduleId: 2, moduleDisplayName: '订单查询' }]) })
  api.docs.mockResolvedValue({ data: [] })
  api.groups.mockResolvedValue({ data: [] })
  api.changes.mockResolvedValue({ data: { records: [], total: 0, pending: 0 } })
  api.models.mockResolvedValue({ data: [] })
  api.semanticStatus.mockResolvedValue({ data: null })
  api.sensitiveStatus.mockResolvedValue({ data: null })
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  window.localStorage.clear()
})
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
async function start(workspace: BusinessCapabilityWorkspace = 'sources', query: Record<string, string> = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/business-capabilities/java-methods/:workspace', component: { template: '<div />' } },
  ] })
  await router.push({ path: `/business-capabilities/java-methods/${workspace}`, query: { projectId: '7', projectCode: 'orders', ...query } })
  const wrapper = mount(SourceWorkbenchPanel, { props: { projectId: 7, workspace }, global: {
    plugins: [router, ElementPlus],
    stubs: { SourceImportDialog: true, SourceGraphPanel: true, SourceObservationsPanel: true,
      SourceSemanticDocsPanel: true, SourceSemanticTasksDrawer: true, SourceSemanticDialogs: true,
      SourceObservationDiffDialog: true },
  } })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('source workbench loading boundary', () => {
  it('keeps whole-project export and counts available when the current subject has no remaining observations', async () => {
    api.owner.mockResolvedValueOnce({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD', subjectId: 11, title: '查询订单', sourceObservations: [] } })
    api.tools.mockResolvedValueOnce({ data: { ...page([{ scanToolId: 31, name: '其他来源' }]), total: 500, stats: { total: 500, removed: 0 } } })
    const wrapper = await start('sources', { subjectType: 'BUSINESS_METHOD', subjectId: '11' })
    expect(api.tools).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('当前没有可核对的来源记录')
    expect(wrapper.getComponent(SourceActions).props('sourceCount')).toBe(500)
    expect(wrapper.findComponent({ name: 'SourceObservationsPanel' }).exists()).toBe(false)
  })
  it('waits for a typed subject source association and requests only those observations once', async () => {
    const owner = deferred<any>()
    api.owner.mockReturnValueOnce(owner.promise)
    const wrapper = await start('sources', { subjectType: 'BUSINESS_METHOD', subjectId: '11' })
    expect(api.owner).toHaveBeenCalledWith(7, 'BUSINESS_METHOD', 11)
    expect(api.tools).not.toHaveBeenCalled()
    expect(api.changes).toHaveBeenCalledOnce()
    owner.resolve({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD', subjectId: 11, title: '查询订单', sourceObservations: [{ id: 31 }, { id: 32 }] } })
    await flushPromises()
    expect(api.tools).toHaveBeenCalledOnce()
    expect(api.tools.mock.lastCall).toEqual([7, expect.objectContaining({ observationIds: [31, 32], defer: false })])
    expect(wrapper.getComponent({ name: 'SourceObservationsPanel' }).props('tools')).toHaveLength(1)
  })
  it('rereads only the requested source page for pagination and sends search/module filters to the server', async () => {
    api.tools.mockImplementation(async (_project: number, query: any) => ({ data: { ...page([{ scanToolId: query.current * 20, name: '来源' }]),
      current: query.current, total: 105, stats: { total: 500, removed: 40 }, modules: [{ moduleId: 2, moduleDisplayName: '订单', count: 500 }] } }))
    const wrapper = await start()
    const records = wrapper.getComponent({ name: 'SourceObservationsPanel' })
    expect(records.props('total')).toBe(105); expect(records.props('projectTotal')).toBe(500)
    expect(wrapper.getComponent(SourceActions).props('sourceCount')).toBe(500)
    records.vm.$emit('changePage', 3); await flushPromises()
    expect(api.tools).toHaveBeenCalledTimes(2)
    expect(api.tools.mock.lastCall).toEqual([7, expect.objectContaining({ current: 3, size: 20 })])
    expect(records.props('current')).toBe(3)
    for (const request of [api.project, api.docs, api.changes, api.semanticStatus, api.sensitiveStatus]) expect(request).toHaveBeenCalledOnce()
    records.vm.$emit('search', '客户'); await flushPromises()
    expect(api.tools).toHaveBeenCalledTimes(3)
    expect(api.tools.mock.lastCall).toEqual([7, expect.objectContaining({ current: 1, keyword: '客户' })])
    records.vm.$emit('filterModule', '2'); await flushPromises()
    expect(api.tools).toHaveBeenCalledTimes(4)
    expect(api.tools.mock.lastCall).toEqual([7, expect.objectContaining({ current: 1, moduleId: 2, keyword: '客户' })])
    expect(api.docs).toHaveBeenCalledOnce(); expect(api.changes).toHaveBeenCalledOnce()
  })
  it('reads changes once on first mount and once more for each completed manual refresh', async () => {
    const wrapper = await start()
    expect(wrapper.findComponent(ProjectSourceChanges).exists()).toBe(true)
    expect(api.changes).toHaveBeenCalledOnce()
    for (const expected of [2, 3]) {
      wrapper.getComponent(SourceActions).vm.$emit('primaryAction', 'refresh')
      await flushPromises()
      expect(api.changes).toHaveBeenCalledTimes(expected)
      expect(api.project).toHaveBeenCalledTimes(expected)
    }
  })

  it('shows project changes while source records and semantic documents are still pending', async () => {
    const tools = deferred<any>(), docs = deferred<any>()
    api.tools.mockReturnValueOnce(tools.promise)
    api.docs.mockReturnValueOnce(docs.promise)
    const wrapper = await start()
    expect(wrapper.findComponent(ProjectSourceChanges).exists()).toBe(true)
    expect(api.changes).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('暂无待处理变化')
    tools.resolve({ data: page([{ scanToolId: 31, name: 'orders:read' }]) })
    await flushPromises()
    expect(api.changes).toHaveBeenCalledOnce()
    expect(wrapper.getComponent(SourceActions).props('loading')).toBe(false)
    expect(wrapper.getComponent({ name: 'SourceObservationsPanel' }).props('semanticLoading')).toBe(true)
    docs.resolve({ data: [] })
    await flushPromises()
    expect(api.changes).toHaveBeenCalledOnce()
    expect(wrapper.getComponent({ name: 'SourceObservationsPanel' }).props('semanticLoading')).toBe(false)
  })

  it('does not load unused groups or model options at startup and loads models when the generation drawer opens', async () => {
    const wrapper = await start()
    expect(api.tools).toHaveBeenCalledOnce()
    expect(api.docs).toHaveBeenCalledOnce()
    expect(api.groups).not.toHaveBeenCalled()
    expect(api.models).not.toHaveBeenCalled()
    expect(api.semanticStatus).toHaveBeenCalledOnce()
    expect(api.sensitiveStatus).toHaveBeenCalledOnce()
    wrapper.getComponent(SourceActions).vm.$emit('openModelGeneratePanel')
    await flushPromises()
    expect(api.models).toHaveBeenCalledOnce()
  })

  it('keeps graph startup independent of source and semantic requests and loads them after switching to sources', async () => {
    const wrapper = await start('graph')
    expect(api.project).toHaveBeenCalledOnce()
    for (const request of [api.tools, api.docs, api.groups, api.models, api.semanticStatus, api.sensitiveStatus, api.changes]) {
      expect(request).not.toHaveBeenCalled()
    }
    await wrapper.setProps({ workspace: 'sources' })
    await flushPromises()
    expect(wrapper.findComponent(ProjectSourceChanges).exists()).toBe(true)
    expect(api.tools).toHaveBeenCalledOnce()
    expect(api.docs).toHaveBeenCalledOnce()
    expect(api.changes).toHaveBeenCalledOnce()
    expect(api.semanticStatus).toHaveBeenCalledOnce()
    expect(api.sensitiveStatus).toHaveBeenCalledOnce()
    expect(api.groups).not.toHaveBeenCalled()
    expect(api.models).not.toHaveBeenCalled()
  })

  it('loads the semantic workspace without fetching source records or a change review', async () => {
    const wrapper = await start('semantics')
    expect(wrapper.findComponent({ name: 'SourceSemanticDocsPanel' }).exists()).toBe(true)
    expect(api.project).toHaveBeenCalledOnce()
    expect(api.docs).toHaveBeenCalledOnce()
    expect(api.semanticStatus).toHaveBeenCalledOnce()
    expect(api.sensitiveStatus).toHaveBeenCalledOnce()
    for (const request of [api.tools, api.changes, api.groups, api.models]) expect(request).not.toHaveBeenCalled()
  })

  it('does not refresh the old change review when leaving sources for graph', async () => {
    const wrapper = await start()
    expect(api.changes).toHaveBeenCalledOnce()
    await wrapper.setProps({ workspace: 'graph' })
    await flushPromises()
    expect(wrapper.findComponent(ProjectSourceChanges).exists()).toBe(false)
    for (const request of [api.tools, api.docs, api.semanticStatus, api.sensitiveStatus, api.changes]) {
      expect(request).toHaveBeenCalledOnce()
    }
  })

  it('keeps records and changes usable while marking failed semantic evidence separately', async () => {
    api.docs.mockRejectedValueOnce(new Error('Semantic read unavailable'))
    const wrapper = await start()
    expect(api.changes).toHaveBeenCalledOnce()
    expect(wrapper.getComponent(SourceActions).props('loading')).toBe(false)
    const records = wrapper.getComponent({ name: 'SourceObservationsPanel' })
    expect(records.props('tools')).toHaveLength(1)
    expect(records.props('semanticLoading')).toBe(false)
    expect(records.props('semanticError')).toContain('读取失败')
  })

  it('keeps changes available after a source read fails and does not present the failed collection as an empty success', async () => {
    api.tools.mockRejectedValueOnce(new Error('Source read unavailable'))
    const wrapper = await start()
    expect(api.changes).toHaveBeenCalledOnce()
    expect(wrapper.findComponent(ProjectSourceChanges).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'SourceObservationsPanel' }).exists()).toBe(false)
    expect(wrapper.getComponent(SourceActions).props('loading')).toBe(false)
    expect(wrapper.getComponent(SourceActions).props('sourceCount')).toBe(0)
    expect(wrapper.text()).toContain('项目来源读取失败')
  })
})
