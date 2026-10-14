import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import BusinessCapabilitySourceWorkbench from './BusinessCapabilitySourceWorkbench.vue'
import { providePageProjectScope } from '@/composables/usePageProjectScope'
import { useProjectStore } from '@/store/project'
import { markPlatformSessionAnonymous, markPlatformSessionAuthenticated, acknowledgePlatformExplorationNotice } from '@/auth/platformSession'
import { PLATFORM_PERMISSION_READ } from '@/auth/platformAccess'

const api = vi.hoisted(() => ({ projects: vi.fn(), project: vi.fn(), page: vi.fn(), docs: vi.fn(), status: vi.fn(), sensitive: vi.fn() }))
vi.mock('@/api/scanProject', async original => ({ ...await original<typeof import('@/api/scanProject')>(), getScanProjects: api.projects,
  getScanProjectDetail: api.project, getScanProjectSources: api.page, getSensitiveDataScanStatus: api.sensitive }))
vi.mock('@/api/semanticDoc', async original => ({ ...await original<typeof import('@/api/semanticDoc')>(), listProjectSemanticDocs: api.docs, getProjectBatchStatus: api.status }))
let wrapper: ReturnType<typeof mount> | null = null
afterEach(() => { wrapper?.unmount(); wrapper = null; markPlatformSessionAnonymous(false); localStorage.clear(); sessionStorage.clear(); vi.clearAllMocks() })
async function setupScope(workspace = 'sources') {
  markPlatformSessionAnonymous(false); localStorage.clear(); sessionStorage.clear()
  const pinia = createPinia(); setActivePinia(pinia)
  markPlatformSessionAuthenticated({ sessionId: 'source-scope-fixture', expiresAt: '2030-01-01T00:00:00.000Z', principal: {
    userId: 7, username: 'source-fixture', permissionGrants: [{ permissionCode: PLATFORM_PERMISSION_READ, scopeType: 'PROJECT', scopeValue: 'orders' }] } })
  acknowledgePlatformExplorationNotice()
  const project = { id: 7, projectCode: 'orders', projectKind: 'REGISTERED', name: '订单', baseUrl: '', contextPath: '', scanPath: '', scanType: 'auto', toolCount: 105, status: 'created' }
  api.projects.mockResolvedValue({ data: [project] }); api.project.mockResolvedValue({ data: project })
  api.page.mockImplementation(async (_owner: number, query: any) => ({ data: { records: [{ projectId: 7, scanToolId: query.current * 20, name: '订单来源' }],
    total: 105, current: query.current, size: 20, stats: { total: 105, removed: 0 }, modules: [] } }))
  api.docs.mockResolvedValue({ data: [] }); api.status.mockResolvedValue({ data: null }); api.sensitive.mockResolvedValue({ data: null })
  await useProjectStore().fetchProjects()
  const path = '/business-capabilities/java-methods/:workspace'
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path, component: BusinessCapabilitySourceWorkbench,
    meta: { projectScope: { permission: PLATFORM_PERMISSION_READ, resourceLabel: '业务能力', requiresProjectCode: false } } }] })
  await router.push({ path: `/business-capabilities/java-methods/${workspace}`, query: { projectId: '7', projectCode: 'orders', sourcePage: '2' } })
  const Host = defineComponent({ setup() { providePageProjectScope(); return () => h(RouterView) } })
  wrapper = mount(Host, { global: { plugins: [pinia, router, ElementPlus], stubs: {
    SourceImportDialog: true, SourceGraphPanel: true, SourceObservationsPanel: true, SourceActions: true, ProjectSourceChanges: true,
    SourceSemanticDocsPanel: true, SourceSemanticTasksDrawer: true, SourceSemanticDialogs: true, SourceObservationDiffDialog: true,
  } } })
  await flushPromises(); await nextTick(); await flushPromises()
  return router
}
it('keeps the mounted source controller through same-project pagination in the real shared scope', async () => {
  const router = await setupScope()
  expect(api.page).toHaveBeenCalledOnce()
  await router.replace({ query: { ...router.currentRoute.value.query, sourcePage: '3' } })
  await flushPromises(); await nextTick(); await flushPromises()
  expect(api.page).toHaveBeenCalledTimes(2)
  expect(api.page.mock.lastCall).toEqual([7, expect.objectContaining({ current: 3, size: 20 })])
  for (const request of [api.project, api.docs, api.status, api.sensitive]) expect(request).toHaveBeenCalledOnce()
})
it('reads the final source page once when workspace and query change together', async () => {
  const router = await setupScope('graph')
  expect(api.page).not.toHaveBeenCalled()
  await router.push({ path: '/business-capabilities/java-methods/sources', query: {
    ...router.currentRoute.value.query, sourcePage: '3', sourceKeyword: 'final',
  } })
  await flushPromises(); await nextTick(); await flushPromises()
  expect(api.page).toHaveBeenCalledOnce()
  expect(api.page.mock.lastCall).toEqual([7, expect.objectContaining({ current: 3, size: 20, keyword: 'final' })])
  for (const request of [api.docs, api.status, api.sensitive]) expect(request).toHaveBeenCalledOnce()
})
