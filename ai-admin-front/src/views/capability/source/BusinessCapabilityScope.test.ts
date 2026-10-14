// Pagination regression: production catalog/scope/router/store/session; all APIs mocked.
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus from 'element-plus'
import { afterEach, expect, it, vi } from 'vitest'
import BusinessMethodCatalog from '@/views/capability/BusinessMethodCatalog.vue'
import HttpApiCatalog from '@/views/api/HttpApiCatalog.vue'
import { providePageProjectScope } from '@/composables/usePageProjectScope'
import { useProjectStore } from '@/store/project'
import { markPlatformSessionAnonymous, markPlatformSessionAuthenticated,
  acknowledgePlatformExplorationNotice } from '@/auth/platformSession'
import { PLATFORM_PERMISSION_READ } from '@/auth/platformAccess'

const api = vi.hoisted(() => ({ projects: vi.fn(), methods: vi.fn(), http: vi.fn(), index: vi.fn() }))
vi.mock('@/api/scanProject', () => ({ getScanProjects: api.projects }))
vi.mock('@/api/businessMethod', () => ({ getBusinessMethods: api.methods, getBusinessMethod: vi.fn() }))
vi.mock('@/api/httpApi', () => ({ listHttpApis: api.http }))
vi.mock('@/api/semanticDoc', () => ({ getCatalogClassificationIndex: api.index }))
vi.mock('@/views/capability/components/BusinessMethodDetailDialog.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/views/capability/components/CatalogClassificationEditor.vue', () => ({ default: { template: '<div />' } }))

let wrapper: ReturnType<typeof mount> | null = null
afterEach(() => {
  wrapper?.unmount(); wrapper = null
  markPlatformSessionAnonymous(false)
  localStorage.clear(); sessionStorage.clear()
})

it.each(['java-methods', 'http-apis'] as const)('loads %s only once when page 2 changes to 3 in the real shared scope', async category => {
  vi.clearAllMocks()
  const list = category === 'java-methods' ? api.methods : api.http
  const pageField = category === 'java-methods' ? 'methodPage' : 'apiPage'
  const path = `/business-capabilities/${category}`
  markPlatformSessionAnonymous(false)
  localStorage.clear(); sessionStorage.clear()
  const pinia = createPinia(); setActivePinia(pinia)
  markPlatformSessionAuthenticated({ sessionId: 'frontend-audit-fixture', expiresAt: '2030-01-01T00:00:00.000Z',
    principal: { userId: 7, username: 'audit-fixture', permissionGrants: [
      { permissionCode: PLATFORM_PERMISSION_READ, scopeType: 'PROJECT', scopeValue: 'orders' },
    ] } })
  expect(acknowledgePlatformExplorationNotice()).toBe(true)
  api.projects.mockResolvedValue({ data: [{ id: 7, projectCode: 'orders', name: 'Orders',
    baseUrl: '', contextPath: '', scanPath: '', scanType: 'auto', toolCount: 0, status: 'created' }] })
  list.mockImplementation((params: Record<string, unknown>) => Promise.resolve({ data: {
    records: [], total: 0, current: params.current, size: params.size, pages: 0,
  } }))
  api.index.mockImplementation((projectId: number, subjectType: string) => Promise.resolve({ data: {
    projectId, subjectType, groups: [], memberships: [], ungroupedCount: 0,
  } }))
  await useProjectStore().fetchProjects()
  const router = createRouter({ history: createMemoryHistory(), routes: [{
    path, component: category === 'java-methods' ? BusinessMethodCatalog : HttpApiCatalog,
    meta: { projectScope: { permission: PLATFORM_PERMISSION_READ, resourceLabel: 'Business capability', requiresProjectCode: false } },
  }] })
  await router.push({ path, query: { projectId: '7', projectCode: 'orders', [pageField]: '2' } })
  await router.isReady()
  const Host = defineComponent({ setup() { providePageProjectScope(); return () => h(RouterView) } })
  wrapper = mount(Host, { global: { plugins: [pinia, router, ElementPlus], stubs: {
    BusinessCapabilityCatalogList: true, CatalogClassificationSections: true,
  } } })
  await flushPromises(); await nextTick(); await flushPromises()
  const before = { list: list.mock.calls.length, classification: api.index.mock.calls.length }
  await router.replace({ path, query: { ...router.currentRoute.value.query, [pageField]: '3' } })
  await flushPromises(); await nextTick(); await flushPromises()
  const results = { probe: 'real-production-catalog-and-scope', category, operation: 'page-2-to-3',
    initial: before, delta: { list: list.mock.calls.length - before.list,
      classification: api.index.mock.calls.length - before.classification },
    listParams: list.mock.calls.slice(before.list).map(([params]) => params),
    classificationParams: api.index.mock.calls.slice(before.classification).map(params => params),
  }
  expect(results.delta.list).toBe(1)
  expect(results.delta.classification).toBe(0)
  expect(results.listParams.every(params => params.current === 3 && params.projectId === 7)).toBe(true)
})
