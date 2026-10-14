import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, reactive } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import ElementPlus, { ElMessageBox } from 'element-plus'
import ApiMarket from './ApiMarket.vue'
import { providePageProjectScope } from '@/composables/usePageProjectScope'

const mocks = vi.hoisted(() => ({ entries: vi.fn(), integrations: vi.fn(), updateStatus: vi.fn(), fetchProjects: vi.fn(),
  store: {} as any, session: null as any }))
vi.mock('@/api/apiMarket', () => ({
  listApiMarketEntries: mocks.entries, listApiMarketIntegrations: mocks.integrations,
  getApiMarketStats: vi.fn(async () => ({ data: {} })),
  listApiMarketSources: vi.fn(async () => ({ data: [] })), getApiMarketEntry: vi.fn(),
  createApiMarketIntegration: vi.fn(), updateApiMarketIntegrationStatus: mocks.updateStatus,
}))
vi.mock('@/store/project', () => ({ useProjectStore: () => mocks.store }))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue')
  mocks.session = ref({ permissionGrants: [] })
  return { platformSessionUser: mocks.session, platformSessionId: ref('market-ui-test'), isPlatformWorkspaceReady: ref(true) }
})

function fixture(projectId = 36, projectCode = 'bzjs21') {
  const qualifiedName = `http-api:${projectCode}:DEVELOPMENT:market:${'a'.repeat(64)}`
  const operation = { id: 7, operationKey: 'current_weather', title: '天气查询', httpMethod: 'GET', sideEffect: 'READ_ONLY' }
  const row = { id: 2, projectId, projectCode, projectName: `Project ${projectId}`, environment: 'DEVELOPMENT',
    status: 'READY', version: { id: 7 }, selectedOperations: [operation], credentialRequired: false,
    entry: { title: '天气 API', entryKey: 'weather' },
    apiBindings: [{ apiId: 4, operationId: 7, qualifiedName, environment: 'DEVELOPMENT', blockingReason: null }] }
  return { row }
}
let wrapper: ReturnType<typeof mount> | undefined
let router: ReturnType<typeof createRouter>
async function start(url = '/api-market?projectId=36&projectCode=bzjs21&marketTab=integrations') {
  router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/api-market', component: ApiMarket, meta: { projectScope: { permission: 'platform:read', resourceLabel: 'API 接入', requiresProjectCode: true } } },
    { path: '/other', component: { template: '<div>Other page</div>' } },
    { path: '/business-capabilities/http-apis/:id', name: 'HttpApiDetail', component: { template: '<div>API detail</div>' } },
  ] })
  await router.push(url); await router.isReady()
  const Host = defineComponent({ setup() { providePageProjectScope(); return () => h(RouterView) } })
  wrapper = mount(Host, { attachTo: document.body, global: { plugins: [router, ElementPlus],
    stubs: { ApiMarketDetailDrawer: true, ApiMarketEntryCard: true } } })
  await flushPromises()
}
function button(label: string) {
  return Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === label)!
}
function pending<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(success => { resolve = success })
  return { promise, resolve }
}
function grant(permissionCode: string, scopeType = 'GLOBAL', scopeValue = '*') {
  return { permissionCode, scopeType, scopeValue }
}

beforeEach(() => {
  vi.resetAllMocks()
  mocks.store = reactive({ projects: [{ id: 36, name: 'Business', projectCode: 'bzjs21' },
    { id: 27, name: 'Other', projectCode: 'other' }], status: 'ready', hasLoadedSuccessfully: true,
    currentProjectId: 36, selectCurrentProject: vi.fn((id: number | null) => { mocks.store.currentProjectId = id }),
    fetchProjects: mocks.fetchProjects })
  mocks.fetchProjects.mockImplementation(async () => mocks.store.projects)
  mocks.session.value = { permissionGrants: [grant('platform:read'), grant('workflow:write'), grant('platform:write')] }
  mocks.entries.mockResolvedValue({ data: { records: [], total: 0, current: 1, size: 24, pages: 0 } })
  mocks.integrations.mockImplementation(async ({ projectId }: { projectId: number }) => ({ data: [fixture(projectId, projectId === 36 ? 'bzjs21' : 'other').row] }))
  mocks.updateStatus.mockResolvedValue({ data: { ...fixture().row, status: 'DISABLED' } })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.restoreAllMocks() })

describe('API market project navigation and integration management', () => {
  it('opens my integrations from the URL with the shared confirmed project', async () => {
    await start()
    expect(document.querySelector('.market-tabs .is-active')?.textContent).toContain('我的接入')
    expect(mocks.integrations).toHaveBeenCalledWith({ projectId: 36, status: undefined })
    expect(document.body.textContent).toContain('bzjs21')
    expect(button('创建 Workflow')).toBeUndefined()
  })

  it('keeps the public discover catalog readable without project query, grants or available projects', async () => {
    mocks.store.projects = []; mocks.store.currentProjectId = null; mocks.session.value.permissionGrants = []
    await start('/api-market')
    expect(document.querySelector('.market-tabs .is-active')?.textContent).toContain('发现 API')
    expect(mocks.entries).toHaveBeenCalled()
    expect(mocks.integrations).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('没有找到符合条件的 API')
  })

  it('does not guess the prior sidebar project when the URL project is inaccessible', async () => {
    mocks.session.value.permissionGrants = [grant('platform:read', 'PROJECT', 'bzjs21')]
    await start('/api-market?projectId=27&projectCode=other&marketTab=integrations')
    expect(mocks.integrations).not.toHaveBeenCalled()
    expect(document.body.textContent).toContain('当前账号没有该项目的 API 接入 查看权限')
    expect(router.currentRoute.value.query.projectId).toBe('27')
    expect(mocks.entries).toHaveBeenCalled()
  })

  it('preserves query, hash and discover filters across tab navigation and browser back', async () => {
    await start('/api-market?scope=all&apiKeyword=retained#catalog')
    const keyword = document.querySelector('[placeholder="搜索 API 名称、能力或提供方"]') as HTMLInputElement
    keyword.value = 'weather'; keyword.dispatchEvent(new Event('input', { bubbles: true })); await flushPromises()
    document.querySelectorAll<HTMLButtonElement>('.market-tabs button')[1].click(); await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ scope: 'all', apiKeyword: 'retained', marketTab: 'integrations' })
    expect(router.currentRoute.value.hash).toBe('#catalog')
    router.back(); await flushPromises(); await flushPromises()
    expect(document.querySelector('.market-tabs .is-active')?.textContent).toContain('发现 API')
    expect((document.querySelector('[placeholder="搜索 API 名称、能力或提供方"]') as HTMLInputElement).value).toBe('weather')
  })

  it('delegates project selection to the shared scope and retains tab/query/hash', async () => {
    await start('/api-market?projectId=36&projectCode=bzjs21&marketTab=integrations&marketSource=official#mine')
    wrapper!.findAllComponents({ name: 'ElSelect' }).find(select => select.props('placeholder') === '请选择项目')!
      .vm.$emit('change', 27)
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ projectId: '27', projectCode: 'other',
      marketTab: 'integrations', marketSource: 'official' })
    expect(router.currentRoute.value.hash).toBe('#mine')
    expect(mocks.integrations).toHaveBeenLastCalledWith({ projectId: 27, status: undefined })
  })

  it('opens the bound API for configuration with the integration project and return context', async () => {
    await start('/api-market?projectId=36&projectCode=bzjs21&marketTab=integrations&marketSource=official#mine')
    button('配置 API').click(); await flushPromises()
    expect(router.currentRoute.value.name).toBe('HttpApiDetail')
    expect(router.currentRoute.value.params.id).toBe('4')
    expect(router.currentRoute.value.query).toMatchObject({ projectId: '36', projectCode: 'bzjs21',
      marketTab: 'integrations', marketSource: 'official' })
    expect(router.currentRoute.value.hash).toBe('#mine')
    expect(mocks.updateStatus).not.toHaveBeenCalled()
  })

  it('changes the selected integration status only after confirmation and refreshes its list', async () => {
    const approval = pending<any>()
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockReturnValueOnce(approval.promise)
    await start()
    mocks.integrations.mockClear()
    button('停用').click(); await flushPromises()
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('不会修改旧发布快照'), '停用 天气 API',
      expect.objectContaining({ confirmButtonText: '确认停用' }))
    expect(mocks.updateStatus).not.toHaveBeenCalled()
    approval.resolve('confirm'); await flushPromises()
    expect(mocks.updateStatus).toHaveBeenCalledWith(2, { status: 'DISABLED' })
    expect(mocks.integrations).toHaveBeenCalledWith({ projectId: 36, status: undefined })
  })

  it('keeps API configuration reachable when integration write permission is missing', async () => {
    mocks.session.value.permissionGrants = [grant('platform:read', 'PROJECT', 'bzjs21')]
    await start()
    expect(button('配置 API').disabled).toBe(false)
    expect(button('停用').disabled).toBe(true)
  })

  it('reloads the confirmed project when its status filter changes', async () => {
    await start()
    wrapper!.findAllComponents({ name: 'ElSelect' }).find(select => select.props('placeholder') === '全部状态')!
      .vm.$emit('update:modelValue', 'BROKEN')
    await flushPromises()
    expect(mocks.integrations).toHaveBeenLastCalledWith({ projectId: 36, status: 'BROKEN' })
  })

  it('does not replace the current project integrations with a late previous-project response', async () => {
    await start()
    const old = pending<{ data: ReturnType<typeof fixture>['row'][] }>()
    mocks.integrations.mockReturnValueOnce(old.promise)
    const statusFilter = wrapper!.findAllComponents({ name: 'ElSelect' }).find(select => select.props('placeholder') === '全部状态')!
    statusFilter.vm.$emit('update:modelValue', 'READY')
    await flushPromises()
    await router.push('/api-market?projectId=27&projectCode=other&marketTab=integrations'); await flushPromises()
    expect(document.querySelector('.integration-table')?.textContent).toContain('Project 27 · other')
    old.resolve({ data: [fixture().row] }); await flushPromises()
    expect(document.querySelector('.integration-table')?.textContent).toContain('Project 27 · other')
    expect(document.querySelector('.integration-table')?.textContent).not.toContain('Project 36 · bzjs21')
  })
})
