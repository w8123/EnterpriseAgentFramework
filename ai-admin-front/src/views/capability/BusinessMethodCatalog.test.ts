import { computed, reactive, ref, watch, type Ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElSelect } from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import BusinessMethodCatalog from './BusinessMethodCatalog.vue'
import MetricStrip from '@/components/common/MetricStrip.vue'
import { businessMethodFixture } from '@/test/fixtures/businessMethod'
import { businessMethodReadiness } from './businessMethodDetail'

const mocks = vi.hoisted(() => ({
  getBusinessMethods: vi.fn(),
  classifications: vi.fn(),
  push: vi.fn(),
  replace: vi.fn(),
  route: { query: {} } as { query: Record<string, string | undefined> },
  projectStore: {
    projects: [] as Array<unknown>,
    currentProjectId: null as number | null,
    selectCurrentProject: vi.fn(),
    fetchProjects: vi.fn(),
    projectLabel: vi.fn(),
  },
  pageScope: null as unknown,
  auth: null as unknown as { sessionId: string; principal: { permissionGrants: Array<{ permissionCode: string; scopeType: string; scopeValue: string }> } },
  modeActive: null as unknown as Ref<boolean>,
  modeRegister: vi.fn(),
}))

vi.mock('@/api/businessMethod', () => ({
  getBusinessMethods: mocks.getBusinessMethods,
  getBusinessMethod: vi.fn(),
  getBusinessMethodReferences: vi.fn(),
}))
vi.mock('@/api/semanticDoc', () => ({ getCatalogClassificationIndex: mocks.classifications }))
vi.mock('./components/CatalogClassificationEditor.vue', async () => {
  const { ref, watch } = await import('vue')
  return { default: {
    name: 'CatalogClassificationEditor', props: ['visible', 'projectId', 'projectCode', 'group', 'mode', 'subject'],
    setup(props: { projectId: number; projectCode?: string }) {
      const draft = ref('')
      watch([() => props.projectId, () => props.projectCode], () => { draft.value = '' })
      return { draft }
    },
    template: '<div><input v-if="visible" data-test="classification-draft" v-model="draft" /></div>',
  } }
})
vi.mock('./composables/useCatalogClassificationMode', () => ({ useCatalogClassificationMode: () => ({ active: mocks.modeActive, register: mocks.modeRegister }) }))
vi.mock('@/composables/usePageProjectScope', () => ({ usePageProjectScope: () => mocks.pageScope }))
vi.mock('@/store/project', () => ({ useProjectStore: () => mocks.projectStore }))
vi.mock('@/auth/platformSession', async () => {
  const { computed } = await import('vue')
  return { platformSessionId: computed(() => mocks.auth.sessionId), platformSessionUser: computed(() => mocks.auth.principal) }
})
vi.mock('vue-router', async () => ({
  ...await vi.importActual<typeof import('vue-router')>('vue-router'),
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.push, replace: mocks.replace, afterEach: () => () => {} }),
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

const filterSelect = (wrapper: ReturnType<typeof mount>, role: 'project' | 'classification' | 'status') =>
  wrapper.findAllComponents(ElSelect).find(select => select.element.closest(`.filter-control.is-${role}`))!

describe('business method catalog scope ownership', () => {
  let wrapper: ReturnType<typeof mount>
  const canLoadData = ref(false)
  const allProjects = ref(false)
  const projectId = ref(7)
  const requestKey = ref('account-a|project:7')

  beforeEach(() => {
    vi.clearAllMocks()
    mocks.modeActive = ref(false)
    mocks.modeRegister.mockReturnValue(() => {})
    mocks.classifications.mockImplementation((id: number) => Promise.resolve({ data: {
      projectId: id, subjectType: 'BUSINESS_METHOD', groups: [], memberships: [], ungroupedCount: 0,
    } }))
    mocks.route = reactive({ query: {} })
    mocks.auth ||= reactive({ sessionId: 'account-a', principal: { permissionGrants: [] } })
    mocks.auth.sessionId = 'account-a'; mocks.auth.principal.permissionGrants = []
    mocks.projectStore.projects = [
      { id: 7, name: '订单系统', projectCode: 'orders' },
      { id: 8, name: '库存系统', projectCode: 'inventory' },
    ]
    mocks.projectStore.currentProjectId = 7
    mocks.replace.mockImplementation(async ({ query }: { query: Record<string, string | undefined> }) => {
      mocks.route.query = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== undefined))
    })
    canLoadData.value = false
    allProjects.value = false
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
      readableProjects: computed(() => mocks.projectStore.projects),
      requestParams: computed(() => canLoadData.value && !allProjects.value ? { projectId: projectId.value } : undefined),
      requestKey: computed(() => requestKey.value),
      selectScope: vi.fn(),
      retryScope: vi.fn().mockResolvedValue(true),
    }
    mocks.projectStore.fetchProjects.mockResolvedValue([])
  })

  afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks() })

  it('filters classification and enabled status immediately without submitting or losing keyword draft', async () => {
    canLoadData.value = true
    mocks.route.query = { methodKeyword: '已提交', methodPage: '3', apiKeyword: '/orders' }
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    const input = wrapper.get('input[aria-label="搜索 Java 业务方法"]')
    await input.setValue('尚未提交')
    filterSelect(wrapper, 'classification').vm.$emit('change', '4')
    await flushPromises()
    expect((input.element as HTMLInputElement).value).toBe('尚未提交')
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: 4, current: 1, keyword: '已提交' }))
    const enabled = filterSelect(wrapper, 'status')
    enabled.vm.$emit('update:modelValue', 'false'); enabled.vm.$emit('change', 'false')
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: 4, enabled: false, keyword: '已提交' }))
    expect((input.element as HTMLInputElement).value).toBe('尚未提交')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '尚未提交', enabled: false }))
    expect(mocks.route.query.apiKeyword).toBe('/orders')
  })

  it.each(['project', 'account'])('discards text drafts on an actual %s context change even when committed keywords are both empty', async change => {
    canLoadData.value = true; mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    await wrapper.get('input[aria-label="搜索 Java 业务方法"]').setValue('旧上下文草稿')
    if (change === 'project') { projectId.value = 8; requestKey.value = 'account-a|project:8' }
    else { mocks.auth.sessionId = 'account-b'; requestKey.value = 'account-b|project:7' }
    await flushPromises()
    expect((wrapper.get('input[aria-label="搜索 Java 业务方法"]').element as HTMLInputElement).value).toBe('')
  })

  it.each(['', undefined])('clears enabled filter immediately for clearable payload %s and retains keyword draft', async value => {
    canLoadData.value = true; mocks.route.query = { methodEnabled: 'true', methodPage: '3' }
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    await wrapper.get('input[aria-label="搜索 Java 业务方法"]').setValue('尚未提交')
    const enabled = filterSelect(wrapper, 'status')
    enabled.vm.$emit('update:modelValue', value); enabled.vm.$emit('change', value); await flushPromises()
    expect(mocks.route.query.methodEnabled).toBeUndefined()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ enabled: undefined, current: 1, keyword: undefined }))
    expect((wrapper.get('input[aria-label="搜索 Java 业务方法"]').element as HTMLInputElement).value).toBe('尚未提交')
  })

  it('keeps one flat table while classification, search and paging change', async () => {
    canLoadData.value = true; mocks.route.query = { methodKeyword: '订单' }
    mocks.getBusinessMethods.mockResolvedValue(page([{ assetId: 101, title: '订单查询', classifications: [{ id: 4, displayName: '订单' }] }]))
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD', ungroupedCount: 0,
      groups: [{ id: 4, projectId: 7, name: 'orders', displayName: '订单', sourceClasses: [], memberCount: 1 }],
      memberships: [{ subjectId: 101, groupIds: [4] }] } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.find('.classification-section__toggle').exists()).toBe(false)
    expect(wrapper.findAll('.capability-table')).toHaveLength(1)
    expect(wrapper.get('.catalog-category-link').text()).toBe('订单')
    mocks.route.query = { ...mocks.route.query, methodPage: '2' }; await flushPromises()
    expect(wrapper.findAll('.capability-table')).toHaveLength(1)
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.findAll('.capability-table')).toHaveLength(1)
  })

  it('shows classification writes only in adjustment mode for project writers', async () => {
    canLoadData.value = true
    mocks.projectStore.projects = [{ id: 7, projectCode: 'orders' }]
    mocks.auth.principal.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    const method = businessMethodFixture({ projectId: 7, assetId: 101, title: '订单查询' })
    mocks.getBusinessMethods.mockResolvedValue({ data: { records: [method], total: 1 } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    const headers = wrapper.findAll('.el-table__header th').map(item => item.text())
    expect(headers).toContain('状态'); expect(headers).not.toContain('来源'); expect(headers).not.toContain('来源状态')
    const status = wrapper.findAllComponents({ name: 'ElTooltip' }).find(item => item.props('content') === `${businessMethodReadiness(method).title}：${businessMethodReadiness(method).description}`)
    expect(status).toBeDefined()
    expect(wrapper.find('.status-tag--pulse').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="归类订单查询"]').exists()).toBe(false)
    expect(wrapper.find('.capability-table .el-checkbox').exists()).toBe(false)
    const sections = wrapper.getComponent({ name: 'CatalogClassificationSections' })
    sections.vm.$emit('assign', [method]); sections.vm.$emit('edit', { id: 4, projectId: 7 }, 'rename')
    await flushPromises()
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props('visible')).toBe(false)
    mocks.modeActive.value = true; await flushPromises()
    const adjustHeaders = wrapper.findAll('.capability-table .el-table__header th').map(item => item.text())
    expect(adjustHeaders).toContain('归类')
    expect(adjustHeaders).not.toContain('操作性质')
    expect(adjustHeaders).not.toContain('状态')
    expect(wrapper.find('.catalog-method-meta').exists()).toBe(false)
    expect(wrapper.find('.capability-cell__name-static').exists()).toBe(true)
    expect(wrapper.find('button.capability-cell__name').exists()).toBe(false)
    const classify = wrapper.get('button[aria-label="归类订单查询"]')
    expect(classify.text()).toBe('归类')
    await classify.trigger('click'); await flushPromises()
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props()).toMatchObject({ visible: true, mode: 'assign', subject: { subjectType: 'BUSINESS_METHOD', subjectId: 101 } })
    expect(wrapper.find('.capability-table .el-checkbox').exists()).toBe(true)
    mocks.auth.principal.permissionGrants = []; await flushPromises()
    expect(wrapper.find('button[aria-label="归类订单查询"]').exists()).toBe(false)
    const restoredHeaders = wrapper.findAll('.capability-table .el-table__header th').map(item => item.text())
    expect(restoredHeaders).toContain('操作性质')
    expect(restoredHeaders).toContain('状态')
    expect(wrapper.find('.catalog-method-meta').exists()).toBe(true)
  })

  it('creates a standalone classification only after entering adjustment mode', async () => {
    canLoadData.value = true
    mocks.projectStore.projects = [{ id: 7, projectCode: 'orders' }]
    mocks.auth.principal.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    const sections = wrapper.getComponent({ name: 'CatalogClassificationSections' })
    sections.vm.$emit('create'); await flushPromises()
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props('visible')).toBe(false)
    expect(wrapper.find('button.classification-create').exists()).toBe(false)
    mocks.modeActive.value = true; await flushPromises()
    expect(wrapper.find('.data-table-shell__empty').exists()).toBe(true)
    expect(wrapper.find('.classification-overview').exists()).toBe(false)
    expect(filterSelect(wrapper, 'classification').props('disabled')).toBe(false)
    await wrapper.get('button.classification-create').trigger('click'); await flushPromises()
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props()).toMatchObject({ visible: true, projectId: 7, mode: 'create', group: null })
  })

  it('keeps an active classification draft through a cancelled project navigation', async () => {
    canLoadData.value = true
    ;(mocks.pageScope as any).resolution = computed(() => ({ kind: 'project', project: { id: projectId.value, projectCode: 'orders', name: '订单' } }))
    mocks.modeRegister.mockImplementation((allowed: Ref<boolean>) => watch(allowed, value => { if (!value) mocks.modeActive.value = false }, { flush: 'sync' }))
    mocks.projectStore.projects = [{ id: 7, projectCode: 'orders' }]
    mocks.auth.principal.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    mocks.getBusinessMethods.mockResolvedValue(page([{ assetId: 101, title: '订单查询' }]))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    mocks.modeActive.value = true; await flushPromises()
    await wrapper.get('button[aria-label="归类订单查询"]').trigger('click'); await flushPromises()
    const editor = wrapper.getComponent({ name: 'CatalogClassificationEditor' })
    await wrapper.get('[data-test="classification-draft"]').setValue('待保存的归类')
    const before = mocks.getBusinessMethods.mock.calls.length
    canLoadData.value = false; requestKey.value = 'account-a|navigation-pending'; await flushPromises()
    expect((mocks.pageScope as any).requestParams.value).toBeUndefined()
    expect(mocks.modeRegister.mock.calls[0]?.[0].value).toBe(true)
    expect(mocks.modeActive.value).toBe(true)
    expect(editor.props()).toMatchObject({ visible: true, projectId: 7, projectCode: 'orders' })
    expect((wrapper.get('[data-test="classification-draft"]').element as HTMLInputElement).value).toBe('待保存的归类')
    expect(mocks.getBusinessMethods).toHaveBeenCalledTimes(before)
    canLoadData.value = true; requestKey.value = 'account-a|project:7'; await flushPromises()
    expect(mocks.modeActive.value).toBe(true)
    expect(editor.props()).toMatchObject({ visible: true, projectId: 7, projectCode: 'orders' })
    expect((wrapper.get('[data-test="classification-draft"]').element as HTMLInputElement).value).toBe('待保存的归类')
  })

  it('offers method state descriptions to keyboard users without opening row details', async () => {
    canLoadData.value = true
    const method = businessMethodFixture({ projectId: 7, assetId: 101, title: '订单查询' })
    mocks.getBusinessMethods.mockResolvedValue({ data: { records: [method], total: 1 } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: DetailDialogStub } } })
    await flushPromises()
    const readiness = businessMethodReadiness(method)
    const trigger = wrapper.get('button.catalog-method-status')
    expect(trigger.attributes('aria-label')).toContain(readiness.label)
    expect(trigger.attributes('aria-label')).toContain(readiness.description)
    const tooltip = wrapper.findAllComponents({ name: 'ElTooltip' }).find(item => item.props('content') === `${readiness.title}：${readiness.description}`)!
    expect(tooltip.props('trigger')).toEqual(['hover', 'focus'])
    await trigger.trigger('focus'); await flushPromises()
    await trigger.trigger('click'); await flushPromises()
    expect(wrapper.getComponent({ name: 'BusinessMethodDetailDialog' }).props('modelValue')).toBe(false)
  })

  it('queries unclassified owners before paging and never sends the sentinel as a group id', async () => {
    canLoadData.value = true
    mocks.route.query = { methodGroupId: 'unclassified', methodKeyword: '查询', methodPage: '2' }
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 7, groupId: undefined, ungrouped: true, current: 2, keyword: '查询' }))
    expect(wrapper.findComponent({ name: 'CatalogGroupFilter' }).exists()).toBe(false)
  })
  it('clears a classification absent from the refreshed index before reloading the owner list', async () => {
    canLoadData.value = true
    mocks.route.query = { methodGroupId: '4', methodPage: '2', methodKeyword: '查询', apiGroupId: '8' }
    const group = { id: 4, projectId: 7, name: 'old', displayName: '原分类', sourceClasses: [], memberCount: 0 }
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD', groups: [group], memberships: [], ungroupedCount: 0 } })
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD', groups: [{ ...group, id: 5 }], memberships: [], ungroupedCount: 0 } })
    const before = mocks.getBusinessMethods.mock.calls.length
    wrapper.getComponent({ name: 'CatalogClassificationEditor' }).vm.$emit('updated')
    await flushPromises()
    expect(mocks.route.query).toMatchObject({ methodPage: 1, methodKeyword: '查询', apiGroupId: '8' })
    expect(mocks.route.query.methodGroupId).toBeUndefined()
    expect(mocks.getBusinessMethods.mock.calls.slice(before).every(([params]) => params.groupId === undefined)).toBe(true)
  })
  it('preserves a fresh classification when navigating from all projects to the verified owner', async () => {
    canLoadData.value = true; allProjects.value = true
    mocks.route.query = { scope: 'all', methodKeyword: '查询' }
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    mocks.route.query = { projectId: '7', methodGroupId: '4', methodPage: '1', methodKeyword: '查询' }
    allProjects.value = false
    requestKey.value = 'account-a|project:7'
    await flushPromises()
    expect(mocks.route.query.methodGroupId).toBe('4')
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 7, groupId: 4, keyword: '查询' }))
  })

  it('shows each persistent category in one list row and opens its existing detail without opening the method', async () => {
    canLoadData.value = true
    const method = businessMethodFixture({ projectId: 7, assetId: 101, title: '订单查询', classifications: [
      { id: 4, displayName: '订单' }, { id: 5, displayName: '查询' },
    ] })
    mocks.getBusinessMethods.mockResolvedValue({ data: { records: [method], total: 35, current: 1, size: 20, pages: 2 } })
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD', ungroupedCount: 2,
      groups: [{ id: 4, projectId: 7, name: 'orders', displayName: '订单', sourceClasses: [], memberCount: 22 },
        { id: 5, projectId: 7, name: 'read', displayName: '查询', sourceClasses: [], memberCount: 12 }],
      memberships: [{ subjectId: 101, groupIds: [4, 5] },
        ...Array.from({ length: 21 }, (_, i) => ({ subjectId: 1000 + i, groupIds: [4] })),
        ...Array.from({ length: 11 }, (_, i) => ({ subjectId: 2000 + i, groupIds: [5] })),
        { subjectId: 3000, groupIds: [] }, { subjectId: 3001, groupIds: [] }] } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.findAll('.capability-table .el-table__row')).toHaveLength(1)
    expect(wrapper.findAll('.capability-table')).toHaveLength(1)
    expect(wrapper.find('.classification-section__toggle').exists()).toBe(false)
    expect(wrapper.find('.classification-overview').exists()).toBe(false)
    expect(wrapper.find('form .is-classification').exists()).toBe(true)
    expect(filterSelect(wrapper, 'classification').findAllComponents({ name: 'ElOption' }).map(option => option.props('label')))
      .toEqual(['全部能力（35）', '订单（22）', '查询（12）', '未分类（2）'])
    expect(wrapper.findAll('.catalog-category-link').map(button => button.text())).toEqual(['订单', '查询'])
    const indexReads = mocks.classifications.mock.calls.length
    const listReads = mocks.getBusinessMethods.mock.calls.length
    await wrapper.get('button[aria-label="查看订单分类详情"]').trigger('click'); await flushPromises()
    expect(mocks.classifications).toHaveBeenCalledTimes(indexReads)
    expect(mocks.getBusinessMethods).toHaveBeenCalledTimes(listReads)
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props()).toMatchObject({ visible: true, mode: 'description', group: { id: 4 } })
    expect(wrapper.getComponent({ name: 'BusinessMethodDetailDialog' }).props('modelValue')).toBe(false)
    await wrapper.get('.catalog-category-cell').trigger('click')
    expect(wrapper.getComponent({ name: 'BusinessMethodDetailDialog' }).props('modelValue')).toBe(false)
    const classificationFilter = filterSelect(wrapper, 'classification')
    classificationFilter.vm.$emit('change', '4'); await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: 4, current: 1 }))
    expect(mocks.route.query.methodGroupId).toBe('4')
    classificationFilter.vm.$emit('change', 'unclassified'); await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: undefined, ungrouped: true, current: 1 }))
    classificationFilter.vm.$emit('change', undefined); await flushPromises()
    expect(mocks.route.query.methodGroupId).toBeUndefined()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: undefined, ungrouped: undefined, current: 1 }))
  })

  it('distinguishes unclassified rows from rows whose classification response is missing', async () => {
    canLoadData.value = true
    mocks.classifications.mockRejectedValue(new Error('classification unavailable'))
    mocks.getBusinessMethods.mockResolvedValue(page([
      { assetId: 101, title: '已确认未分类', classifications: [] },
      { assetId: 102, title: '归属未确认' },
      { assetId: 103, title: '分类查询失败', classifications: null },
    ]))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    const rows = wrapper.findAll('.capability-table .el-table__row')
    expect(rows).toHaveLength(3)
    expect(rows[0]?.text()).toContain('未分类')
    expect(rows[1]?.text()).toContain('分类待更新')
    expect(rows[2]?.text()).toContain('分类待更新')
  })

  it('does not substitute an older index for an explicitly failed row classification read', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockResolvedValue(page([{ assetId: 101, title: '分类读取失败', classifications: null }]))
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD', ungroupedCount: 0,
      groups: [{ id: 4, projectId: 7, name: 'orders', displayName: '订单', sourceClasses: [], memberCount: 1 }],
      memberships: [{ subjectId: 101, groupIds: [4] }] } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.get('.catalog-category-empty').text()).toBe('分类待更新')
    expect(wrapper.find('button[aria-label="查看订单分类详情"]').exists()).toBe(false)
    expect(wrapper.findAllComponents({ name: 'ElOption' }).map(option => option.props('label'))).toContain('订单（1）')
  })

  it('refreshes the category column after a classification rename or assignment update', async () => {
    canLoadData.value = true
    const group = { id: 4, projectId: 7, name: 'orders', displayName: '订单', sourceClasses: [], memberCount: 1 }
    mocks.getBusinessMethods.mockResolvedValue(page([{ assetId: 101, title: '订单查询', classifications: [{ id: 4, displayName: '订单' }] }]))
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD',
      groups: [group], memberships: [{ subjectId: 101, groupIds: [4] }], ungroupedCount: 0 } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.get('.catalog-category-link').text()).toBe('订单')
    mocks.getBusinessMethods.mockResolvedValue(page([{ assetId: 101, title: '订单查询', classifications: [{ id: 4, displayName: '字典查询' }] }]))
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD',
      groups: [{ ...group, displayName: '字典查询' }], memberships: [{ subjectId: 101, groupIds: [4] }], ungroupedCount: 0 } })
    wrapper.getComponent({ name: 'CatalogClassificationEditor' }).vm.$emit('updated')
    await flushPromises()
    expect(wrapper.get('.catalog-category-link').text()).toBe('字典查询')
    expect(wrapper.find('button[aria-label="查看订单分类详情"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="查看字典查询分类详情"]').exists()).toBe(true)
  })

  it('navigates from all projects to the clicked category owner before opening its detail', async () => {
    canLoadData.value = true; allProjects.value = true
    mocks.route.query = { scope: 'all', methodKeyword: '查询', methodPage: '3', apiKeyword: '/orders' }
    mocks.getBusinessMethods.mockResolvedValue(page([{ assetId: 101, projectCode: 'orders', title: '订单查询',
      classifications: [{ id: 4, displayName: '订单' }] }]))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.find('.classification-scope-hint').exists()).toBe(false)
    expect(filterSelect(wrapper, 'classification').props()).toMatchObject({
      placeholder: '选择项目后按分类筛选', disabled: true,
    })
    await wrapper.get('button[aria-label="查看订单分类详情"]').trigger('click'); await flushPromises()
    expect(mocks.push).toHaveBeenCalledWith(expect.objectContaining({ query: expect.objectContaining({
      projectId: '7', projectCode: 'orders', classificationId: '4', methodKeyword: '查询', apiKeyword: '/orders',
    }) }))
    const query = mocks.push.mock.lastCall?.[0]?.query
    expect(query).not.toHaveProperty('scope')
    expect(query).not.toHaveProperty('methodPage')
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props('visible')).toBe(false)
  })

  it('opens a bookmarked category description once without losing the catalog keyword or page', async () => {
    canLoadData.value = true
    mocks.route.query = { classificationId: '4', methodKeyword: '查询', methodPage: '2', apiKeyword: '/orders' }
    mocks.getBusinessMethods.mockResolvedValue(page())
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD', ungroupedCount: 0,
      groups: [{ id: 4, projectId: 7, name: 'orders', displayName: '订单', sourceClasses: [], memberCount: 0 }], memberships: [] } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    const editor = wrapper.findComponent({ name: 'CatalogClassificationEditor' })
    expect(editor.props()).toMatchObject({ visible: true, projectId: 7, mode: 'description', group: { id: 4 } })
    expect(mocks.route.query.classificationId).toBeUndefined()
    expect(mocks.route.query).toMatchObject({ methodKeyword: '查询', methodPage: '2', apiKeyword: '/orders' })
  })

  it.each([undefined, '4'])('discards a retired classification action with category %s without opening an editor', async classificationId => {
    canLoadData.value = true
    mocks.route.query = { classificationAction: 'merge', classificationId, methodKeyword: '查询', methodPage: '2', apiKeyword: '/orders' }
    mocks.projectStore.projects = [{ id: 7, projectCode: 'orders' }]
    mocks.auth.principal.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD', ungroupedCount: 0,
      groups: [{ id: 4, projectId: 7, name: 'orders', displayName: '订单', sourceClasses: [], memberCount: 0 }], memberships: [] } })
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props('visible')).toBe(false)
    expect(mocks.route.query).toMatchObject({ methodKeyword: '查询', methodPage: '2', apiKeyword: '/orders' })
    expect(mocks.route.query.classificationAction).toBeUndefined()
    expect(mocks.route.query.classificationId).toBeUndefined()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '查询', current: 2 }))
  })

  it('keeps index failures distinct from unclassified rows and recovers through classification retry', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockResolvedValue(page([{ assetId: 101, title: '订单查询' }]))
    mocks.classifications.mockRejectedValueOnce(new Error('unavailable')).mockResolvedValue({ data: { projectId: 7,
      subjectType: 'BUSINESS_METHOD', groups: [], memberships: [{ subjectId: 101, groupIds: [] }], ungroupedCount: 1 } })
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('业务分类读取失败')
    expect(wrapper.text()).toContain('分类待更新')
    expect(wrapper.text()).not.toContain('未分类')
    await wrapper.get('button[aria-label="重新读取业务分类"]').trigger('click'); await flushPromises()
    expect(mocks.classifications).toHaveBeenCalledTimes(2)
    expect(mocks.getBusinessMethods).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('未分类')
    expect(wrapper.text()).not.toContain('业务分类读取失败')
  })

  it('sends group filters to the complete owner query and clears only group scope on a real project change', async () => {
    canLoadData.value = true
    mocks.route.query = { methodGroupId: '4', methodKeyword: '查询', methodPage: '2', apiGroupId: '6' }
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenCalledWith(expect.objectContaining({ projectId: 7, groupId: 4, current: 2 }))
    projectId.value = 8; requestKey.value = 'account-a|project:8'
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 8, groupId: undefined, current: 1, keyword: '查询' }))
    expect(mocks.route.query.apiGroupId).toBeUndefined()
  })

  it('keeps group and page bookmarks when only the scope request revision changes with URL filters', async () => {
    canLoadData.value = true
    mocks.route.query = { methodGroupId: '4', methodKeyword: '查询', methodPage: '2' }
    ;(mocks.pageScope as any).requestKey = computed(() => `${requestKey.value}|${JSON.stringify(mocks.route.query)}`)
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    mocks.route.query = { ...mocks.route.query, methodPage: '3' }
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: 4, current: 3 }))
    expect(mocks.route.query.methodGroupId).toBe('4')
    expect(mocks.route.query.methodPage).toBe('3')
    expect(mocks.replace).not.toHaveBeenCalled()
  })

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

  it('loads the owner page without an aggregate and preserves committed method filters', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()

    expect(mocks.getBusinessMethods).toHaveBeenCalledTimes(1)
    mocks.route.query = { methodKeyword: '取消', methodEnabled: 'false', methodPage: '2', apiKeyword: '/orders' }
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({
      current: 2, size: 20, keyword: '取消', enabled: false, projectId: 7,
    }))
    expect(mocks.route.query.apiKeyword).toBe('/orders')
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
    expect(mocks.route.query).toMatchObject({ methodPage: 1, methodKeyword: '查询' })
    expect(mocks.route.query.apiPage).toBeUndefined()
  })

  it('reports search failure and recovers through the current owner list retry', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods
      .mockResolvedValueOnce(page([{ name: 'orders_read', title: '订单查询' }]))
      .mockRejectedValueOnce({ response: { status: 503 } })
      .mockResolvedValueOnce(page([{ name: 'orders_read', title: '恢复后的订单查询' }]))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('订单查询')
    await wrapper.get('input[aria-label="搜索 Java 业务方法"]').setValue('订单查询')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.text()).toContain('业务方法目录暂时不可用')
    await wrapper.get('.catalog-load-alert button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('恢复后的订单查询')
    expect(wrapper.text()).not.toContain('业务方法目录暂时不可用')
    expect(mocks.getBusinessMethods).toHaveBeenCalledTimes(3)
  })

  it('keeps the method description and source while providing semantic name and view actions in a project', async () => {
    canLoadData.value = true
    mocks.getBusinessMethods.mockResolvedValue(page([{ name: 'orders_read', title: '订单查询', description: '按订单标识查询', sourceProjectName: '订单系统' }]))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: DetailDialogStub } } })
    await flushPromises()

    expect(wrapper.get('.capability-cell__copy > span').text()).toBe('按订单标识查询')
    expect(wrapper.text()).not.toContain('当前范围')
    expect(wrapper.find('.catalog-method-meta').text()).not.toContain('订单系统')
    expect(wrapper.find('.catalog-method-meta').text()).toContain('SDK 注册')
    expect(wrapper.find('.source-cell').exists()).toBe(false)
    expect(wrapper.find('.capability-cell__mark').exists()).toBe(false)
    expect(wrapper.get('.capability-cell__name').element.tagName).toBe('BUTTON')

    await wrapper.get('.capability-cell__name').trigger('click')
    expect(wrapper.findComponent({ name: 'BusinessMethodDetailDialog' }).props('modelValue')).toBe(true)
    wrapper.findComponent({ name: 'BusinessMethodDetailDialog' }).vm.$emit('update:modelValue', false)
    await flushPromises()
    const view = wrapper.findAll('.capability-table button').find(button => button.text() === '查看')!
    await view.trigger('click')
    expect(wrapper.findComponent({ name: 'BusinessMethodDetailDialog' }).props('modelValue')).toBe(true)
  })

  it('omits owner and source metadata from all-project rows while retaining the complete owner in details', async () => {
    canLoadData.value = true
    allProjects.value = true
    requestKey.value = 'account-a|all'
    mocks.getBusinessMethods.mockResolvedValue(page([{ name: 'orders_read', title: '订单查询', sourceProjectName: '订单系统' }]))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()

    expect(wrapper.find('.catalog-method-meta').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('所属项目：')
    expect(wrapper.text()).not.toContain('SDK 注册')
    expect(wrapper.findAll('.el-table__header th').map(item => item.text())).not.toContain('所属项目 / 来源')
    expect(mocks.getBusinessMethods).toHaveBeenCalledWith(expect.objectContaining({ projectId: undefined }))
    await wrapper.get('.capability-cell__name').trigger('click')
    expect(wrapper.getComponent({ name: 'BusinessMethodDetailDialog' }).props('method')).toMatchObject({ projectId: 7, sourceProjectName: '订单系统', source: 'sdk' })
  })

  it('starts with all projects and filters locally without changing the sidebar scope', async () => {
    canLoadData.value = true; allProjects.value = true; requestKey.value = 'account-a|all'
    mocks.projectStore.currentProjectId = null
    mocks.route.query = { scope: 'all', methodKeyword: '查询', apiKeyword: '/orders', apiPage: '3' }
    mocks.getBusinessMethods.mockImplementation(({ projectId: id }: { projectId?: number }) => Promise.resolve(page([
      { title: id === 8 ? '库存查询' : '订单查询' },
    ], id || 7)))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    const project = filterSelect(wrapper, 'project')
    expect(project.props('ariaLabel')).toBe('项目筛选')
    expect(project.props('modelValue')).toBe('')
    expect(project.findAllComponents({ name: 'ElOption' }).map(option => ({ value: option.props('value'), label: option.props('label') })))
      .toEqual(expect.arrayContaining([{ value: '', label: '全部项目' }, { value: '7', label: '订单系统' }, { value: '8', label: '库存系统' }]))
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: undefined, keyword: '查询' }))
    await wrapper.get('input[aria-label="搜索 Java 业务方法"]').setValue('尚未提交')
    project.vm.$emit('change', '8'); await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 8, keyword: '查询', current: 1 }))
    expect(wrapper.text()).toContain('库存查询')
    expect(wrapper.find('.catalog-method-meta').exists()).toBe(false)
    expect(filterSelect(wrapper, 'classification').props('disabled')).toBe(false)
    expect(mocks.route.query).toMatchObject({ scope: 'all', methodProjectId: '8', methodKeyword: '查询', apiKeyword: '/orders', apiPage: '3' })
    expect(mocks.route.query.projectId).toBeUndefined()
    expect((wrapper.get('input[aria-label="搜索 Java 业务方法"]').element as HTMLInputElement).value).toBe('尚未提交')
    expect((mocks.pageScope as any).requestParams.value).toBeUndefined()
    expect((mocks.pageScope as any).selectScope).not.toHaveBeenCalled()
    expect(mocks.projectStore.currentProjectId).toBeNull()
    expect(mocks.projectStore.selectCurrentProject).not.toHaveBeenCalled()
    project.vm.$emit('change', ''); await flushPromises()
    expect(mocks.route.query.methodProjectId).toBeUndefined()
    expect(mocks.route.query.scope).toBe('all')
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: undefined, keyword: '查询' }))
    expect(project.props('modelValue')).toBe('')
    expect((wrapper.get('input[aria-label="搜索 Java 业务方法"]').element as HTMLInputElement).value).toBe('尚未提交')
  })

  it('resets local classification and paging on project-filter changes while keeping keyword drafts, enabled state and HTTP filters', async () => {
    canLoadData.value = true; allProjects.value = true; requestKey.value = 'account-a|all'
    mocks.auth.principal.permissionGrants = ['orders', 'inventory'].map(scopeValue => ({ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue }))
    mocks.modeActive.value = true
    mocks.route.query = { scope: 'all', methodProjectId: '7', methodKeyword: '已提交', methodEnabled: 'false', methodGroupId: '4', methodPage: '3',
      classificationId: '4', classificationAction: 'description', apiKeyword: '/orders', apiGroupId: '9', apiPage: '5', apiEnvironment: 'dev' }
    mocks.classifications.mockImplementation((id: number) => Promise.resolve({ data: { projectId: id, subjectType: 'BUSINESS_METHOD',
      groups: [{ id: 4, projectId: id, name: 'queries', displayName: '查询', sourceClasses: [], memberCount: 0 }], memberships: [], ungroupedCount: 0 } }))
    mocks.getBusinessMethods.mockImplementation(({ projectId: id }: { projectId: number }) => Promise.resolve(page([], id)))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 7, groupId: 4, current: 3 }))
    expect(wrapper.getComponent({ name: 'CatalogClassificationSections' }).props('canWrite')).toBe(false)
    expect(wrapper.find('.capability-table--adjusting').exists()).toBe(false)
    await wrapper.get('input[aria-label="搜索 Java 业务方法"]').setValue('尚未提交')
    filterSelect(wrapper, 'project').vm.$emit('change', '8'); await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 8, groupId: undefined, current: 1, keyword: '已提交', enabled: false }))
    expect(mocks.route.query).toMatchObject({ scope: 'all', methodProjectId: '8', methodKeyword: '已提交', methodEnabled: 'false',
      apiKeyword: '/orders', apiGroupId: '9', apiPage: '5', apiEnvironment: 'dev' })
    expect(mocks.route.query.methodGroupId).toBeUndefined()
    expect(mocks.route.query.classificationId).toBeUndefined()
    expect(mocks.route.query.classificationAction).toBeUndefined()
    expect((wrapper.get('input[aria-label="搜索 Java 业务方法"]').element as HTMLInputElement).value).toBe('尚未提交')
    expect(filterSelect(wrapper, 'status').props('modelValue')).toBe('false')
  })

  it('waits for the project catalog before resolving a bookmarked local project and never falls back to an all-project query', async () => {
    canLoadData.value = true; allProjects.value = true; requestKey.value = 'account-a|all'
    const catalogLoading = ref(true)
    ;(mocks.pageScope as any).isCatalogLoading = computed(() => catalogLoading.value)
    ;(mocks.pageScope as any).readableProjects = computed(() => catalogLoading.value ? [] : mocks.projectStore.projects)
    mocks.route.query = { scope: 'all', methodProjectId: '7', methodGroupId: '4', methodPage: '2', methodKeyword: '查询' }
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'BUSINESS_METHOD',
      groups: [{ id: 4, projectId: 7, name: 'queries', displayName: '查询', sourceClasses: [], memberCount: 1 }],
      memberships: [{ subjectId: 701, groupIds: [4] }], ungroupedCount: 0 } })
    mocks.getBusinessMethods.mockResolvedValue(page([{ title: '书签中的订单查询' }]))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('正在确认所选项目，请稍候。')
    expect(wrapper.text()).not.toContain('所选项目不存在或已不可访问')
    expect(mocks.getBusinessMethods).not.toHaveBeenCalled()
    expect(mocks.classifications).not.toHaveBeenCalled()
    catalogLoading.value = false; await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenCalledTimes(1)
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 7, groupId: 4, current: 2, keyword: '查询' }))
    expect(mocks.classifications).toHaveBeenCalledWith(7, 'BUSINESS_METHOD')
    expect(mocks.getBusinessMethods.mock.calls.every(([params]) => params.projectId === 7)).toBe(true)
    expect(wrapper.text()).toContain('书签中的订单查询')
    expect(wrapper.text()).not.toContain('所选项目不存在或已不可访问')
    expect(mocks.route.query).toMatchObject({ scope: 'all', methodProjectId: '7', methodGroupId: '4', methodPage: '2' })
  })

  it('uses the sidebar project and hides local project filtering when an older local filter remains in the URL', async () => {
    canLoadData.value = true
    mocks.route.query = { projectId: '7', methodProjectId: '8', methodKeyword: '查询' }
    mocks.getBusinessMethods.mockResolvedValue(page([{ title: '侧栏项目方法' }]))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.find('form .is-project').exists()).toBe(false)
    expect(mocks.getBusinessMethods).toHaveBeenCalled()
    expect(mocks.getBusinessMethods.mock.calls.every(([params]) => params.projectId === 7)).toBe(true)
    expect(mocks.classifications.mock.calls.every(([id]) => id === 7)).toBe(true)
    expect(wrapper.get('.catalog-method-meta').text()).toContain('SDK 注册')
    expect(mocks.route.query.projectId).toBe('7')
  })

  it('discards a late response from the previous local project filter', async () => {
    canLoadData.value = true; allProjects.value = true; requestKey.value = 'account-a|all'
    mocks.route.query = { scope: 'all', methodProjectId: '7' }
    let resolveOld!: (value: ReturnType<typeof page>) => void
    const previous = new Promise<ReturnType<typeof page>>(resolve => { resolveOld = resolve })
    mocks.getBusinessMethods.mockImplementation(({ projectId: id }: { projectId?: number }) => id === 7 ? previous
      : Promise.resolve(page([{ title: '库存项目方法' }], 8)))
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    filterSelect(wrapper, 'project').vm.$emit('change', '8'); await flushPromises()
    expect(wrapper.text()).toContain('库存项目方法')
    resolveOld(page([{ title: '迟到的订单方法' }], 7)); await flushPromises()
    expect(wrapper.text()).toContain('库存项目方法')
    expect(wrapper.text()).not.toContain('迟到的订单方法')
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 8 }))
  })

  it.each([
    { label: 'inaccessible', value: '999' },
    { label: 'malformed', value: 'invalid' },
    { label: 'repeated query field', value: ['7', '8'] },
    { label: 'null query field', value: null },
  ])('fails closed for a $label local project and lets the user recover to all projects', async ({ value: localProjectId }) => {
    canLoadData.value = true; allProjects.value = true; requestKey.value = 'account-a|all'
    if (localProjectId === '999') {
      mocks.projectStore.projects.push({ id: 999, name: '不可见项目', projectCode: 'private' })
      ;(mocks.pageScope as any).readableProjects = computed(() => mocks.projectStore.projects.filter(project => (project as { id: number }).id !== 999))
    }
    mocks.route.query = { scope: 'all', methodProjectId: localProjectId as any, methodKeyword: '查询' }
    mocks.getBusinessMethods.mockResolvedValue(page())
    wrapper = mount(BusinessMethodCatalog, { global: { plugins: [ElementPlus], stubs: { BusinessMethodDetailDialog: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('所选项目不存在或已不可访问，请重新选择项目。')
    expect(mocks.getBusinessMethods).not.toHaveBeenCalled()
    expect(mocks.classifications).not.toHaveBeenCalled()
    expect(wrapper.find('form .is-project').exists()).toBe(true)
    expect(filterSelect(wrapper, 'project').findAllComponents({ name: 'ElOption' }).map(option => option.props('label'))).not.toContain('不可见项目')
    const malformedQueryValue = Array.isArray(localProjectId) || localProjectId === null
    if (malformedQueryValue) {
      await wrapper.get('input[aria-label="搜索 Java 业务方法"]').setValue('重新查询')
      await wrapper.get('form').trigger('submit'); await flushPromises()
      expect(mocks.route.query.methodProjectId).toEqual(localProjectId)
      expect(mocks.route.query.methodKeyword).toBe('重新查询')
      expect(mocks.getBusinessMethods).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('所选项目不存在或已不可访问，请重新选择项目。')
      await wrapper.get('input[aria-label="搜索 Java 业务方法"]').setValue('')
      wrapper.getComponent({ name: 'ElInput' }).vm.$emit('clear'); await flushPromises()
      expect(mocks.route.query.methodProjectId).toEqual(localProjectId)
      expect(mocks.route.query.methodKeyword).toBeUndefined()
      expect(mocks.getBusinessMethods).not.toHaveBeenCalled()
      expect(mocks.classifications).not.toHaveBeenCalled()
      expect(wrapper.text()).toContain('所选项目不存在或已不可访问，请重新选择项目。')
    }
    filterSelect(wrapper, 'project').vm.$emit('change', ''); await flushPromises()
    expect(mocks.getBusinessMethods).toHaveBeenCalledTimes(1)
    expect(mocks.getBusinessMethods).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: undefined, keyword: malformedQueryValue ? undefined : '查询' }))
    expect(mocks.route.query.scope).toBe('all')
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

describe('metric presentation compatibility', () => {
  const items = [{ key: 'total', label: '匹配 API', value: 37, hint: '当前筛选结果，包含全部分页' }]

  it('retains the panel presentation for existing consumers', () => {
    const wrapper = mount(MetricStrip, { props: { items } })
    expect(wrapper.get('.metric-strip').attributes('data-presentation')).toBe('panel')
    expect(wrapper.findComponent({ name: 'MetricIconBg' }).exists()).toBe(true)
    expect(wrapper.get('.metric-strip__hint').classes()).not.toContain('metric-strip__hint--assistive')
    wrapper.unmount()
  })

  it('keeps inline explanations accessible and displays unknown-state explanations in place', async () => {
    const wrapper = mount(MetricStrip, { props: { items, presentation: 'inline', ariaLabel: 'HTTP API 指标概览' } })
    expect(wrapper.get('[role="list"]').attributes('aria-label')).toBe('HTTP API 指标概览')
    expect(wrapper.get('.metric-strip').attributes('data-presentation')).toBe('inline')
    expect(wrapper.findComponent({ name: 'MetricIconBg' }).exists()).toBe(false)
    expect(wrapper.get('.metric-strip__item').attributes('title')).toBe(items[0]!.hint)
    expect(wrapper.get('.metric-strip__hint--assistive').text()).toBe(items[0]!.hint)
    await wrapper.setProps({ items: [{ ...items[0]!, value: '—', hint: '目录读取失败' }] })
    expect(wrapper.get('.metric-strip__value').text()).toBe('—')
    expect(wrapper.get('.metric-strip__hint').text()).toBe('目录读取失败')
    expect(wrapper.find('.metric-strip__hint--assistive').exists()).toBe(false)
    wrapper.unmount()
  })
})
