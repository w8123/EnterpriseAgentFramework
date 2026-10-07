import { computed, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElInput } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HttpApiCatalog from './HttpApiCatalog.vue'
import { HTTP_API_CATALOG_PATH } from '@/views/capability/businessCapabilityRoutes'
import type { HttpApiSummary } from '@/types/httpApi'

const mocks = vi.hoisted(() => ({ list: vi.fn(), scope: null as unknown }))
vi.mock('@/api/httpApi', () => ({ listHttpApis: mocks.list }))
vi.mock('@/composables/usePageProjectScope', () => ({ usePageProjectScope: () => mocks.scope }))

const api = (projectId = 7, routeTemplate = '/orders'): HttpApiSummary => ({ id: projectId * 10, projectId,
  projectCode: `project-${projectId}`, environment: 'dev', qualifiedName: `project-${projectId}:GET:${routeTemplate}`,
  httpMethod: 'GET', routeTemplate, sourceStatus: 'ACCEPTED', sourceConfirmed: true, sourceReason: null,
  candidateContractHash: null, acceptedContractHash: 'accepted', sourceSetRevision: 'source', activeSourceCount: 1,
  sourceKinds: ['OPENAPI_SCAN'], acceptedBy: 'reader', acceptedAt: null, connectionStatus: 'UNCONFIGURED' })
const page = (records: HttpApiSummary[] = []) => ({ data: { records, total: records.length, current: 1, size: 20, pages: 1 } })
const ready = ref(false), projectId = ref(7), context = ref('account-a|project:7')
const wrappers: ReturnType<typeof mount>[] = []
async function setup(query: Record<string, string> = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: HTTP_API_CATALOG_PATH, component: HttpApiCatalog },
    { path: `${HTTP_API_CATALOG_PATH}/:id`, component: { template: '<div />' } },
    { path: '/registry/projects', component: { template: '<div />' } },
  ] })
  await router.push({ path: HTTP_API_CATALOG_PATH, query: { projectId: '7', ...query } })
  const wrapper = mount(HttpApiCatalog, { global: { plugins: [router, ElementPlus] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
beforeEach(() => {
  vi.clearAllMocks(); ready.value = false; projectId.value = 7; context.value = 'account-a|project:7'
  mocks.list.mockResolvedValue(page())
  mocks.scope = { canLoadData: computed(() => ready.value), requestParams: computed(() => ({ projectId: projectId.value })),
    requestKey: computed(() => context.value), readableProjects: computed(() => [{ id: 7, environment: 'dev' }]),
    feedbackMessage: computed(() => '请先确认项目范围'), status: computed(() => ready.value ? 'resolved' : 'pending'),
    recoveryAction: computed(() => 'none'), isCatalogLoading: computed(() => false) }
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('HTTP API catalog ownership and URL state', () => {
  it('waits for project scope and drops late responses from a previous project', async () => {
    let resolveOld!: (value: ReturnType<typeof page>) => void
    const old = new Promise<ReturnType<typeof page>>(resolve => { resolveOld = resolve })
    mocks.list.mockImplementation(({ projectId }: { projectId: number }) => projectId === 7 ? old : Promise.resolve(page([api(8, '/new-project')])) )
    const { wrapper } = await setup()
    expect(mocks.list).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请选择一个项目查看 API')
    ready.value = true; await flushPromises()
    projectId.value = 8; context.value = 'account-b|project:8'; await flushPromises()
    resolveOld(page([api(7, '/old-project')]))
    await flushPromises()
    expect(wrapper.text()).toContain('/new-project')
    expect(wrapper.text()).not.toContain('/old-project')
  })

  it('loads committed URL filters and keeps project and peer fields on search and detail navigation', async () => {
    ready.value = true
    mocks.list.mockResolvedValue(page([api()]))
    const { wrapper, router } = await setup({ apiKeyword: '/orders', apiMethod: 'GET', apiPage: '2', methodKeyword: '查询' })
    expect(mocks.list).toHaveBeenCalledWith(expect.objectContaining({ keyword: '/orders', method: 'GET', current: 2, projectId: 7 }))
    const input = wrapper.get('input[aria-label="搜索 API"]')
    await input.setValue('/payments')
    expect(mocks.list).toHaveBeenCalledTimes(1)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '/payments', current: 1 }))
    expect(router.currentRoute.value.query).toMatchObject({ projectId: '7', methodKeyword: '查询', apiKeyword: '/payments' })
    const link = wrapper.get('a.api-identity')
    expect(link.attributes('href')).toContain(`${HTTP_API_CATALOG_PATH}/70`)
    expect(link.attributes('href')).toContain('apiKeyword=/payments')
    expect(link.attributes('href')).toContain('methodKeyword=')
  })

  it('blocks search while a Chinese input composition is active', async () => {
    ready.value = true
    const { wrapper } = await setup()
    const input = wrapper.get('input[aria-label="搜索 API"]')
    wrapper.findComponent(ElInput).vm.$emit('compositionstart', new CompositionEvent('compositionstart'))
    await input.setValue('订单')
    await wrapper.get('form').trigger('submit')
    expect(mocks.list).toHaveBeenCalledTimes(1)
    wrapper.findComponent(ElInput).vm.$emit('compositionend', new CompositionEvent('compositionend'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
  })

  it.each(['unavailable', 'foreign owner', 'invalid total'])('keeps %s separate from a genuine empty project', async failure => {
    ready.value = true
    if (failure === 'unavailable') mocks.list.mockRejectedValue({ response: { status: 503 } })
    else if (failure === 'foreign owner') mocks.list.mockResolvedValue(page([api(8, '/foreign-api')]))
    else mocks.list.mockResolvedValue({ data: { records: [], total: -1 } })
    const { wrapper } = await setup()
    expect(wrapper.text()).toContain('API 目录加载失败')
    expect(wrapper.text()).not.toContain('当前项目还没有 API')
    expect(wrapper.text()).not.toContain('/foreign-api')
    expect(wrapper.text()).toContain('重新加载')
  })
})
