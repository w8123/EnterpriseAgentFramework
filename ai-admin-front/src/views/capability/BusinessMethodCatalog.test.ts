import { computed, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import BusinessMethodCatalog from './BusinessMethodCatalog.vue'
import { businessMethodFixture } from '@/test/fixtures/businessMethod'

const mocks = vi.hoisted(() => ({
  getBusinessMethods: vi.fn(),
  getBusinessMethodSummary: vi.fn(),
  push: vi.fn(),
  replace: vi.fn(),
  route: { query: {} } as { query: Record<string, string | undefined> },
  projectStore: {
    projects: [] as Array<unknown>,
    fetchProjects: vi.fn(),
    projectLabel: vi.fn(),
  },
  pageScope: null as unknown,
}))

vi.mock('@/api/businessMethod', () => ({
  getBusinessMethods: mocks.getBusinessMethods,
  getBusinessMethodSummary: mocks.getBusinessMethodSummary,
  getBusinessMethod: vi.fn(),
  getBusinessMethodReferences: vi.fn(),
}))
vi.mock('@/composables/usePageProjectScope', () => ({ usePageProjectScope: () => mocks.pageScope }))
vi.mock('@/store/project', () => ({ useProjectStore: () => mocks.projectStore }))
vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
}))

const page = (records: Array<Record<string, unknown>> = [], projectId = 7) => ({
  data: { records: records.map(record => businessMethodFixture({ ...record, projectId })), total: records.length, size: 20, current: 1, pages: 1 },
})

const DetailDialogStub = {
  name: 'BusinessMethodDetailDialog',
  props: ['modelValue', 'method', 'contextKey'],
  emits: ['refreshed', 'update:modelValue'],
  template: '<section data-test="detail-dialog"><span v-if="modelValue">{{ method?.title }}</span></section>',
}

describe('business method catalog scope ownership', () => {
  let wrapper: ReturnType<typeof mount>
  const canLoadData = ref(false)
  const projectId = ref(7)
  const requestKey = ref('account-a|project:7')

  beforeEach(() => {
    vi.clearAllMocks()
    mocks.route = reactive({ query: {} })
    mocks.replace.mockImplementation(async ({ query }: { query: Record<string, string | undefined> }) => {
      mocks.route.query = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== undefined))
    })
    mocks.getBusinessMethodSummary.mockResolvedValue({ data: { total: 0, enabled: 0, disabled: 0 } })
    canLoadData.value = false
    projectId.value = 7
    requestKey.value = 'account-a|project:7'
    mocks.pageScope = {
      canLoadData: computed(() => canLoadData.value),
      status: computed(() => canLoadData.value ? 'resolved' : 'pending'),
      feedbackMessage: computed(() => canLoadData.value ? '' : '正在确认项目范围…'),
      recoveryAction: computed(() => 'none'),
      recoveryLabel: computed(() => ''),
      isCatalogLoading: computed(() => false),
      currentScopeLabel: computed(() => canLoadData.value ? `项目 ${projectId.value}` : '范围未确认'),
      requestParams: computed(() => canLoadData.value ? { projectId: projectId.value } : undefined),
      requestKey: computed(() => requestKey.value),
      retryScope: vi.fn().mockResolvedValue(true),
    }
    mocks.projectStore.fetchProjects.mockResolvedValue([])
  })

  afterEach(() => wrapper?.unmount())

  it('waits for the shared scope, then discards a late list response after account or project context changes', async () => {
    let resolveOld!: (value: ReturnType<typeof page>) => void
    const oldList = new Promise<ReturnType<typeof page>>(resolve => { resolveOld = resolve })
    mocks.getBusinessMethods.mockImplementation((params: { size?: number; projectId?: number }) => {
      if (params.size === 20 && params.projectId === 7) return oldList
      if (params.size === 20 && params.projectId === 8) {
        return Promise.resolve(page([{
          name: 'orders_create_v2',
          title: '新账号的创建订单',
          description: 'new definition',
          parameters: [],
          source: 'sdk',
          enabled: true,
        }], 8))
      }
      return Promise.resolve(page())
    })

    wrapper = mount(BusinessMethodCatalog, {
            global: {
        plugins: [ElementPlus],
        stubs: { BusinessMethodDetailDialog: true },
      },
    })
    await flushPromises()

    expect(mocks.getBusinessMethods).not.toHaveBeenCalled()
    expect(mocks.getBusinessMethodSummary).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('业务方法范围尚未确认')

    canLoadData.value = true
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenCalledWith(expect.objectContaining({ projectId: 7 }))

    projectId.value = 8
    requestKey.value = 'account-b|project:8'
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenCalledWith(expect.objectContaining({ projectId: 8 }))

    resolveOld(page([{
      name: 'orders_create_v1',
      title: '旧账号的创建订单',
      description: 'stale definition',
      parameters: [],
      source: 'sdk',
      enabled: true,
    }]))
    await flushPromises()

    expect(wrapper.text()).toContain('新账号的创建订单')
    expect(wrapper.text()).not.toContain('旧账号的创建订单')
  })

  it('synchronously closes a selected method on every business scope switch and ignores late detail refreshes', async () => {
    canLoadData.value = true
    const accountA = {
      name: 'orders_read', title: '账号 A 的订单查询', description: 'account A', parameters: [], source: 'sdk' as const, enabled: true,
    }
    const accountB = {
      name: 'orders_read', title: '账号 B 的订单查询', description: 'account B', parameters: [], source: 'sdk' as const, enabled: true,
    }
    mocks.getBusinessMethods.mockImplementation((params: { projectId?: number }) => Promise.resolve(
      page(params.projectId === 8 ? [accountB] : [accountA], params.projectId),
    ))

    wrapper = mount(BusinessMethodCatalog, {
            global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: DetailDialogStub } },
    })
    await flushPromises()
    await wrapper.find('.capability-table .el-table__row').trigger('click')
    await flushPromises()

    const detail = wrapper.findComponent({ name: 'BusinessMethodDetailDialog' })
    expect(detail.props('modelValue')).toBe(true)
    expect(wrapper.get('[data-test="detail-dialog"]').text()).toContain('账号 A 的订单查询')

    projectId.value = 8
    await flushPromises()
    expect(detail.props('modelValue')).toBe(false)
    expect(detail.props('method')).toBeNull()

    requestKey.value = 'account-b|project:8'
    await flushPromises()
    expect(detail.props('modelValue')).toBe(false)
    expect(detail.props('method')).toBeNull()

    detail.vm.$emit('refreshed', businessMethodFixture(accountA))
    await flushPromises()
    expect(detail.props('modelValue')).toBe(false)
    expect(detail.props('method')).toBeNull()
    expect(wrapper.text()).toContain('账号 B 的订单查询')
    expect(wrapper.text()).not.toContain('账号 A 的订单查询')

    projectId.value = 7
    requestKey.value = 'account-a|project:7'
    await flushPromises()
    expect(detail.props('modelValue')).toBe(false)
    expect(detail.props('method')).toBeNull()
  })

  it('uses safe business-directory error copy and retains a compact technical diagnosis', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockRejectedValue({
      message: 'GET /api/business-methods failed with a raw upstream detail',
      response: { status: 403 },
    })
    wrapper = mount(BusinessMethodCatalog, {
            global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('当前账号无权读取业务方法')
    expect(wrapper.text()).toContain('技术诊断：HTTP 403')
    expect(wrapper.text()).not.toContain('/api/business-methods')
  })

  it.each([
    [404, '当前环境暂未提供业务方法目录'],
    [503, '业务方法目录暂时不可用'],
  ])('uses an actionable business-directory message for HTTP %s', async (status, expectedCopy) => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockRejectedValue({
      message: 'GET /api/business-methods exposed an implementation detail',
      response: { status },
    })
    wrapper = mount(BusinessMethodCatalog, {
            global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain(expectedCopy)
    expect(wrapper.text()).toContain(`技术诊断：HTTP ${status}`)
    expect(wrapper.text()).not.toContain('/api/business-methods')
  })
  it('rejects a projection without a source asset identity instead of presenting it as a method', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockResolvedValue({ data: {
      records: [{ assetType: 'BUSINESS_METHOD', id: 99, name: 'projection-only', title: '伪装的方法', parameters: [] }],
      total: 1, current: 1, size: 20, pages: 1,
    } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('业务方法目录加载失败')
    expect(wrapper.text()).not.toContain('伪装的方法')
    expect(wrapper.text()).not.toContain('业务方法目录还是空的')
  })

  it('loads one owner page and one aggregate, and filters only reload the page', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockResolvedValue(page())
    mocks.getBusinessMethodSummary.mockResolvedValue({ data: { total: 12, enabled: 9, disabled: 3 } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()

    expect(mocks.getBusinessMethods).toHaveBeenCalledTimes(1)
    expect(mocks.getBusinessMethodSummary).toHaveBeenCalledTimes(1)
    expect(mocks.getBusinessMethodSummary).toHaveBeenCalledWith(7)
    mocks.route.query = { methodKeyword: '取消', methodEnabled: 'false', methodPage: '2', apiKeyword: '/orders' }
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({
      current: 2, size: 20, keyword: '取消', enabled: false, projectId: 7,
    }))
    expect(mocks.getBusinessMethodSummary).toHaveBeenCalledTimes(1)
  })

  it('restores bookmarked paging when scope first resolves, then resets paging on an actual owner switch', async () => {
    mocks.route.query = { methodPage: '2', methodKeyword: '查询', apiPage: '3' }
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(mocks.getBusinessMethods).not.toHaveBeenCalled()
    canLoadData.value = true
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ current: 2, keyword: '查询', projectId: 7 }))
    expect(mocks.route.query.methodPage).toBe('2')

    projectId.value = 8
    requestKey.value = 'account-b|project:8'
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ current: 1, projectId: 8 }))
    expect(mocks.route.query).toMatchObject({ methodPage: 1, methodKeyword: '查询', apiPage: '3' })
  })

  it('shows unknown metrics when the aggregate fails instead of inventing zero counts', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockResolvedValue(page([{ name: 'orders_read', title: '订单查询' }]))
    mocks.getBusinessMethodSummary.mockRejectedValue({ response: { status: 503 } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('指标读取失败')
    expect(wrapper.text()).toContain('状态未知')
    expect(wrapper.text()).toContain('订单查询')
  })

  it('shows a load failure when pagination metadata is invalid instead of a false empty result', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockResolvedValue({ data: { records: [], total: -1 } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('业务方法目录加载失败')
    expect(wrapper.text()).not.toContain('Java 业务方法还是空的')
  })

})
