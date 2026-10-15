import { computed, defineComponent, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus, { ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HttpApiCatalog from './HttpApiCatalog.vue'
import BusinessMethodCatalog from '@/views/capability/BusinessMethodCatalog.vue'
import { providePageProjectScope, type PageProjectScopeController } from '@/composables/usePageProjectScope'
import { BUSINESS_METHOD_CATALOG_PATH, HTTP_API_CATALOG_PATH } from '@/views/capability/businessCapabilityRoutes'
import { businessMethodFixture } from '@/test/fixtures/businessMethod'

const mocks = vi.hoisted(() => ({ list: vi.fn(), methods: vi.fn(), classifications: vi.fn(), store: null as any, auth: null as any }))
vi.mock('@/api/httpApi', () => ({ listHttpApis: mocks.list }))
vi.mock('@/api/businessMethod', () => ({ getBusinessMethods: mocks.methods, getBusinessMethod: vi.fn() }))
vi.mock('@/api/semanticDoc', () => ({ getCatalogClassificationIndex: mocks.classifications }))
vi.mock('@/store/project', () => ({ useProjectStore: () => mocks.store }))
vi.mock('@/auth/platformSession', async () => {
  const { computed } = await import('vue')
  return { platformSessionId: computed(() => mocks.auth.sessionId), platformSessionUser: computed(() => mocks.auth.principal),
    isPlatformWorkspaceReady: computed(() => true) }
})
vi.mock('@/views/capability/composables/useCatalogClassificationMode', () => ({ useCatalogClassificationMode: () => null }))
vi.mock('@/views/capability/components/CatalogClassificationEditor.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/views/capability/components/BusinessMethodDetailDialog.vue', () => ({ default: { template: '<div />' } }))

const meta = { projectScope: { permission: 'platform:read', resourceLabel: '业务能力', requiresProjectCode: true } }
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  vi.clearAllMocks()
  mocks.auth = reactive({ sessionId: 'account', principal: { userId: 1, permissionGrants: ['orders', 'inventory'].map(scopeValue =>
    ({ permissionCode: 'platform:read', scopeType: 'PROJECT', scopeValue })) } })
  mocks.store = reactive({ projects: [{ id: 36, name: 'Orders', projectCode: 'orders', environment: 'dev' },
    { id: 37, name: 'Inventory', projectCode: 'inventory', environment: 'dev' }], status: 'ready',
    hasLoadedSuccessfully: true, currentProjectId: 36, selectCurrentProject: vi.fn(), fetchProjects: vi.fn() })
  mocks.classifications.mockImplementation((projectId: number, subjectType: string) => Promise.resolve({ data: {
    projectId, subjectType, groups: [], memberships: [], ungroupedCount: 1 } }))
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.restoreAllMocks() })

async function setup(category: 'HTTP_API' | 'BUSINESS_METHOD' = 'HTTP_API', addressPath?: string,
  addressQuery: Record<string, string> = { projectId: '36', projectCode: 'orders' }) {
  const path = category === 'HTTP_API' ? HTTP_API_CATALOG_PATH : BUSINESS_METHOD_CATALOG_PATH
  const destination = category === 'HTTP_API' ? `${path}/4` : `${path}/sources`
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path, component: category === 'HTTP_API' ? HttpApiCatalog : BusinessMethodCatalog, meta },
    { path: destination, component: { template: '<div class="destination" />' }, meta },
  ] })
  const starts: string[] = []
  const list = category === 'HTTP_API' ? mocks.list : mocks.methods
  list.mockImplementation(({ projectId }: { projectId?: number }) => {
    starts.push(router.currentRoute.value.path)
    const projectCode = projectId === 37 ? 'inventory' : 'orders'
    const records = category === 'HTTP_API' ? [{ id: 4, projectId, projectCode, environment: 'dev',
      qualifiedName: `${projectCode}:GET:/v1/forecast`, httpMethod: 'GET', routeTemplate: '/v1/forecast',
      sourceStatus: 'ACCEPTED', sourceConfirmed: true, sourceKinds: ['OPENAPI_SCAN'], activeSourceCount: 1,
    }] : [businessMethodFixture({ projectId: projectId ?? 36, projectCode })]
    return Promise.resolve({ data: { records, total: 1 } })
  })
  await router.push({ path: addressPath || path, query: addressQuery })
  let scope!: PageProjectScopeController
  const app = defineComponent({ components: { RouterView }, setup() { scope = providePageProjectScope() }, template: '<RouterView />' })
  wrapper = mount(app, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  expect(list).toHaveBeenCalledTimes(1)
  return { router, list, starts, path, destination, scope, view: wrapper }
}

describe('catalog navigation with the real shared project scope', () => {
  it('restores a bookmarked local project, classification and page on first load and browser back without changing the all-project scope', async () => {
    mocks.auth.principal.permissionGrants.push({ permissionCode: 'platform:read', scopeType: 'GLOBAL', scopeValue: '*' })
    mocks.classifications.mockImplementation((projectId: number, subjectType: string) => Promise.resolve({ data: {
      projectId, subjectType, groups: [{ id: 6, projectId, name: 'queries', displayName: '查询', sourceClasses: [], memberCount: 1 }],
      memberships: [{ subjectId: projectId * 100 + 1, groupIds: [6] }], ungroupedCount: 0,
    } }))
    const bookmarked = { scope: 'all', methodProjectId: '37', methodGroupId: '6', methodPage: '3', methodKeyword: '已提交' }
    const { router, list, scope, path, view } = await setup('BUSINESS_METHOD', undefined, bookmarked)
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 37, groupId: 6, current: 3, keyword: '已提交' }))
    expect(scope.selection.value).toBe('all')
    await router.push({ path, query: { scope: 'all', methodProjectId: '36', methodKeyword: '其他' } })
    await flushPromises()
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 36, groupId: undefined, current: 1, keyword: '其他' }))
    const returned = new Promise<void>(resolve => {
      const remove = router.afterEach(() => { remove(); resolve() })
    })
    router.back(); await returned; await flushPromises()
    expect(router.currentRoute.value.query).toEqual(bookmarked)
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 37, groupId: 6, current: 3, keyword: '已提交' }))
    expect(scope.selection.value).toBe('all')
    expect(scope.currentScopeLabel.value).toBe('全部项目')
    expect(view.findAllComponents(ElSelect).find(item => item.classes().includes('is-project'))!.props('modelValue')).toBe('37')
    expect(view.findAllComponents(ElSelect).find(item => item.classes().includes('is-classification'))!.props('modelValue')).toBe('6')
  })

  it('keeps the real sidebar scope at all projects while filtering Java methods to a local owner', async () => {
    mocks.auth.principal.permissionGrants.push({ permissionCode: 'platform:read', scopeType: 'GLOBAL', scopeValue: '*' })
    const { router, list, scope, view } = await setup('BUSINESS_METHOD')
    mocks.store.selectCurrentProject.mockImplementation((id: number | null) => { mocks.store.currentProjectId = id })
    list.mockImplementation(({ projectId }: { projectId?: number }) => Promise.resolve({ data: {
      records: [businessMethodFixture({ projectId: projectId ?? 36, projectCode: projectId === 37 ? 'inventory' : 'orders',
        title: projectId === 37 ? '库存查询' : '订单查询' })], total: 1,
    } }))
    expect(await scope.selectScope('all')).toBe(true)
    await flushPromises()
    expect(scope.currentScopeLabel.value).toBe('全部项目')
    expect(mocks.store.currentProjectId).toBeNull()
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: undefined }))
    await view.get('input[aria-label="搜索 Java 业务方法"]').setValue('尚未提交')
    const storeChanges = mocks.store.selectCurrentProject.mock.calls.length
    const project = view.findAllComponents(ElSelect).find(item => item.classes().includes('is-project'))!
    project.vm.$emit('change', '37'); await flushPromises()
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 37, current: 1, keyword: undefined }))
    expect(router.currentRoute.value.query).toMatchObject({ scope: 'all', methodProjectId: '37' })
    expect(router.currentRoute.value.query.projectId).toBeUndefined()
    expect(scope.selection.value).toBe('all')
    expect(scope.resolution.value).toEqual({ kind: 'all' })
    expect(scope.currentScopeLabel.value).toBe('全部项目')
    expect(scope.requestParams.value).toBeUndefined()
    expect(mocks.store.currentProjectId).toBeNull()
    expect(mocks.store.selectCurrentProject.mock.calls.slice(storeChanges).every(([id]: [number | null]) => id === null)).toBe(true)
    expect((view.get('input[aria-label="搜索 Java 业务方法"]').element as HTMLInputElement).value).toBe('尚未提交')
    expect(view.find('.catalog-method-meta').exists()).toBe(false)
    project.vm.$emit('change', ''); await flushPromises()
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: undefined }))
    expect(scope.selection.value).toBe('all')
    expect(mocks.store.currentProjectId).toBeNull()
    expect(router.currentRoute.value.query.methodProjectId).toBeUndefined()
  })

  it('recognizes the matched catalog for a legal trailing slash address and stops its reads after leaving', async () => {
    const { router, list, starts, destination, view } = await setup('HTTP_API', `${HTTP_API_CATALOG_PATH}/`)
    expect(router.currentRoute.value.path).toBe(`${HTTP_API_CATALOG_PATH}/`)
    expect(view.find('a.api-identity').exists()).toBe(true)
    await router.push({ path: destination, query: router.currentRoute.value.query })
    await flushPromises()
    expect(view.find('.destination').exists()).toBe(true)
    expect(list).toHaveBeenCalledTimes(1)
    expect(starts).toEqual([`${HTTP_API_CATALOG_PATH}/`])
  })

  it('does not query the previous HTTP catalog when a detail link commits before catalog unmount', async () => {
    const { router, list, starts, destination, view } = await setup()
    await view.get('a.api-identity').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(destination)
    expect(view.find('.destination').exists()).toBe(true)
    expect(list).toHaveBeenCalledTimes(1)
    expect(starts).toEqual([HTTP_API_CATALOG_PATH])
  })

  it('keeps Java catalog post-flush loads inside the current catalog during navigation to sources', async () => {
    const { router, list, starts, destination, view } = await setup('BUSINESS_METHOD')
    await router.push({ path: destination, query: router.currentRoute.value.query })
    await flushPromises()
    expect(view.find('.destination').exists()).toBe(true)
    expect(list).toHaveBeenCalledTimes(1)
    expect(starts).toEqual([BUSINESS_METHOD_CATALOG_PATH])
  })

  it('still runs one HTTP catalog query for a committed search on the same catalog route', async () => {
    const { list, starts, view } = await setup()
    await view.get('input[aria-label="搜索 API"]').setValue('forecast')
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(list).toHaveBeenCalledTimes(2)
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 36, keyword: 'forecast' }))
    expect(starts).toEqual([HTTP_API_CATALOG_PATH, HTTP_API_CATALOG_PATH])
  })

  it('applies the classification dropdown within the confirmed catalog and preserves peer tab filters', async () => {
    mocks.classifications.mockResolvedValue({ data: { projectId: 36, subjectType: 'HTTP_API', ungroupedCount: 0,
      groups: [{ id: 6, projectId: 36, name: 'weather', displayName: '天气', sourceClasses: [], memberCount: 1 }],
      memberships: [{ subjectId: 4, groupIds: [6] }] } })
    const { router, list, starts, path, view } = await setup()
    await router.replace({ path, query: { ...router.currentRoute.value.query, apiPage: '3', apiKeyword: '/committed', methodKeyword: '查询', methodGroupId: '9' } })
    await flushPromises()
    await view.get('input[aria-label="搜索 API"]').setValue('/draft')
    const before = list.mock.calls.length
    const select = view.findAllComponents(ElSelect).find(item => item.props('ariaLabel')?.startsWith('业务分类筛选'))!
    expect(select.props('disabled')).toBe(false)
    select.vm.$emit('change', '6'); await flushPromises()
    expect(list).toHaveBeenCalledTimes(before + 1)
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 36, groupId: 6, keyword: '/committed', current: 1 }))
    expect(router.currentRoute.value.query).toMatchObject({ projectId: '36', projectCode: 'orders', apiGroupId: '6', apiPage: '1',
      apiKeyword: '/committed', methodKeyword: '查询', methodGroupId: '9' })
    expect((view.get('input[aria-label="搜索 API"]').element as HTMLInputElement).value).toBe('/draft')
    expect(starts.every(start => start === HTTP_API_CATALOG_PATH)).toBe(true)
  })

  it('still reads the newly selected project on the same HTTP catalog route', async () => {
    const { list, starts, scope, router } = await setup()
    expect(await scope.selectScope(37)).toBe(true)
    await flushPromises()
    expect(router.currentRoute.value.query.projectId).toBe('37')
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 37 }))
    expect(starts.every(path => path === HTTP_API_CATALOG_PATH)).toBe(true)
  })

  it('keeps the current HTTP catalog usable after a cancelled detail navigation', async () => {
    const { router, list, destination, path, view } = await setup()
    router.beforeEach(to => to.path === destination ? false : undefined)
    await router.push({ path: destination, query: router.currentRoute.value.query })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(path)
    expect(list).toHaveBeenCalledTimes(1)
    await view.get('input[aria-label="搜索 API"]').setValue('forecast')
    await view.get('form').trigger('submit')
    await flushPromises()
    expect(list).toHaveBeenCalledTimes(2)
    expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: 'forecast' }))
  })
})
