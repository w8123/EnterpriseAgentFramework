import { computed, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CapabilityKernel from './CapabilityKernel.vue'

const mocks = vi.hoisted(() => ({
  getTools: vi.fn(),
  getBusinessMethods: vi.fn(),
  push: vi.fn(),
  projectStore: {
    projects: [] as Array<unknown>,
    fetchProjects: vi.fn(),
    projectLabel: vi.fn(),
  },
  pageScope: null as unknown,
}))

vi.mock('@/api/tool', () => ({
  getTools: mocks.getTools,
  getBusinessMethods: mocks.getBusinessMethods,
  getBusinessMethod: vi.fn(),
  getTool: vi.fn(),
  getCapabilityReferences: vi.fn(),
}))
vi.mock('@/composables/usePageProjectScope', () => ({ usePageProjectScope: () => mocks.pageScope }))
vi.mock('@/store/project', () => ({ useProjectStore: () => mocks.projectStore }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))

const page = (records: Array<Record<string, unknown>> = []) => ({
  data: { records, total: records.length, size: 20, current: 1, pages: 1 },
})

const DetailDialogStub = {
  name: 'CapabilityDetailDialog',
  props: ['modelValue', 'capability', 'contextKey'],
  emits: ['refreshed', 'update:modelValue'],
  template: '<section data-test="detail-dialog"><span v-if="modelValue">{{ capability?.title }}</span></section>',
}

describe('business method catalog scope ownership', () => {
  let wrapper: ReturnType<typeof mount>
  const canLoadData = ref(false)
  const projectId = ref(7)
  const requestKey = ref('account-a|project:7')

  beforeEach(() => {
    vi.clearAllMocks()
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
    mocks.getTools.mockResolvedValue(page())
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
        }]))
      }
      return Promise.resolve(page())
    })

    wrapper = mount(CapabilityKernel, {
      props: { catalogKind: 'business-method' },
      global: {
        plugins: [ElementPlus],
        stubs: { CapabilityDetailDialog: true },
      },
    })
    await flushPromises()

    expect(mocks.getBusinessMethods).not.toHaveBeenCalled()
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
    expect(mocks.getTools).not.toHaveBeenCalled()
  })

  it('synchronously closes a selected method on every business scope switch and ignores late detail refreshes', async () => {
    canLoadData.value = true
    const accountA = {
      name: 'orders_read', title: '账号 A 的订单查询', description: 'account A', parameters: [], source: 'sdk', enabled: true,
    }
    const accountB = {
      name: 'orders_read', title: '账号 B 的订单查询', description: 'account B', parameters: [], source: 'sdk', enabled: true,
    }
    mocks.getBusinessMethods.mockImplementation((params: { projectId?: number }) => Promise.resolve(
      page(params.projectId === 8 ? [accountB] : [accountA]),
    ))

    wrapper = mount(CapabilityKernel, {
      props: { catalogKind: 'business-method' },
      global: { plugins: [ElementPlus], stubs: { CapabilityDetailDialog: DetailDialogStub } },
    })
    await flushPromises()
    await wrapper.find('.capability-table .el-table__row').trigger('click')
    await flushPromises()

    const detail = wrapper.findComponent({ name: 'CapabilityDetailDialog' })
    expect(detail.props('modelValue')).toBe(true)
    expect(wrapper.get('[data-test="detail-dialog"]').text()).toContain('账号 A 的订单查询')

    projectId.value = 8
    await flushPromises()
    expect(detail.props('modelValue')).toBe(false)
    expect(detail.props('capability')).toBeNull()

    requestKey.value = 'account-b|project:8'
    await flushPromises()
    expect(detail.props('modelValue')).toBe(false)
    expect(detail.props('capability')).toBeNull()

    detail.vm.$emit('refreshed', accountA)
    await flushPromises()
    expect(detail.props('modelValue')).toBe(false)
    expect(detail.props('capability')).toBeNull()
    expect(wrapper.text()).toContain('账号 B 的订单查询')
    expect(wrapper.text()).not.toContain('账号 A 的订单查询')

    projectId.value = 7
    requestKey.value = 'account-a|project:7'
    await flushPromises()
    expect(detail.props('modelValue')).toBe(false)
    expect(detail.props('capability')).toBeNull()
  })

  it('uses safe business-directory error copy and retains a compact technical diagnosis', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockRejectedValue({
      message: 'GET /api/business-methods failed with a raw upstream detail',
      response: { status: 403 },
    })
    wrapper = mount(CapabilityKernel, {
      props: { catalogKind: 'business-method' },
      global: { plugins: [ElementPlus], stubs: { CapabilityDetailDialog: true } },
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
    wrapper = mount(CapabilityKernel, {
      props: { catalogKind: 'business-method' },
      global: { plugins: [ElementPlus], stubs: { CapabilityDetailDialog: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain(expectedCopy)
    expect(wrapper.text()).toContain(`技术诊断：HTTP ${status}`)
    expect(wrapper.text()).not.toContain('/api/business-methods')
  })
})
