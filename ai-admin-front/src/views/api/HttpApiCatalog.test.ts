import { computed, reactive, ref, watch, type Ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElInput, ElSelect } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HttpApiCatalog from './HttpApiCatalog.vue'
import { HTTP_API_CATALOG_PATH } from '@/views/capability/businessCapabilityRoutes'
import type { HttpApiSummary } from '@/types/httpApi'

const mocks = vi.hoisted(() => ({ list: vi.fn(), classifications: vi.fn(), scope: null as unknown,
  modeActive: null as unknown as Ref<boolean>, modeRegister: vi.fn(),
  auth: null as unknown as { sessionId: string; principal: { permissionGrants: Array<{ permissionCode: string; scopeType: string; scopeValue: string }> } } }))
vi.mock('@/api/httpApi', () => ({ listHttpApis: mocks.list }))
vi.mock('@/api/semanticDoc', () => ({ getCatalogClassificationIndex: mocks.classifications }))
vi.mock('@/views/capability/components/CatalogClassificationEditor.vue', async () => {
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
vi.mock('@/views/capability/composables/useCatalogClassificationMode', () => ({ useCatalogClassificationMode: () => ({ active: mocks.modeActive, register: mocks.modeRegister }) }))
vi.mock('@/composables/usePageProjectScope', () => ({ usePageProjectScope: () => mocks.scope }))
vi.mock('@/auth/platformSession', async () => {
  const { computed } = await import('vue')
  return { platformSessionId: computed(() => mocks.auth.sessionId), platformSessionUser: computed(() => mocks.auth.principal) }
})

const api = (projectId = 7, routeTemplate = '/orders'): HttpApiSummary => ({ id: projectId * 10, projectId,
  projectCode: `project-${projectId}`, environment: 'dev', qualifiedName: `project-${projectId}:GET:${routeTemplate}`,
  httpMethod: 'GET', routeTemplate, sourceStatus: 'ACCEPTED', sourceConfirmed: true, sourceReason: null,
  candidateContractHash: null, acceptedContractHash: 'accepted', sourceSetRevision: 'source', activeSourceCount: 1,
  sourceKinds: ['OPENAPI_SCAN'], acceptedBy: 'reader', acceptedAt: null, connectionStatus: 'UNCONFIGURED' })
const page = (records: HttpApiSummary[] = []) => ({ data: { records, total: records.length, current: 1, size: 20, pages: 1 } })
const group = (id: number, displayName: string, memberCount: number) => ({
  id, projectId: 7, name: `group-${id}`, displayName, sourceClasses: [], memberCount,
})
const filterSelect = (wrapper: ReturnType<typeof mount>, label: string) =>
  wrapper.findAllComponents(ElSelect).find(select => select.props('ariaLabel')?.startsWith(label))!
const ready = ref(false), projectId = ref(7), context = ref('account-a|project:7')
const wrappers: ReturnType<typeof mount>[] = []
async function setup(query: Record<string, string> = {}, attachToDocument = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: HTTP_API_CATALOG_PATH, component: HttpApiCatalog },
    { path: `${HTTP_API_CATALOG_PATH}/:id`, component: { template: '<div />' } },
    { path: '/registry/projects', component: { template: '<div />' } },
  ] })
  await router.push({ path: HTTP_API_CATALOG_PATH, query: { projectId: '7', ...query } })
  const wrapper = mount(HttpApiCatalog, { ...(attachToDocument ? { attachTo: document.body } : {}), global: { plugins: [router, ElementPlus] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
beforeEach(() => {
  vi.clearAllMocks(); ready.value = false; projectId.value = 7; context.value = 'account-a|project:7'
  mocks.modeActive = ref(false); mocks.modeRegister.mockReturnValue(() => {})
  mocks.auth ||= reactive({ sessionId: 'account-a', principal: { permissionGrants: [] } })
  mocks.auth.sessionId = 'account-a'; mocks.auth.principal.permissionGrants = []
  mocks.list.mockResolvedValue(page())
  mocks.classifications.mockImplementation((id: number) => Promise.resolve({ data: { projectId: id,
    subjectType: 'HTTP_API', groups: [], memberships: [], ungroupedCount: 0 } }))
  mocks.scope = { canLoadData: computed(() => ready.value), requestParams: computed(() => ({ projectId: projectId.value })),
    requestKey: computed(() => context.value), readableProjects: computed(() => [{ id: 7, environment: 'dev' }]),
    feedbackMessage: computed(() => '请先确认项目范围'), status: computed(() => ready.value ? 'resolved' : 'pending'),
    recoveryAction: computed(() => 'none'), isCatalogLoading: computed(() => false) }
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.restoreAllMocks() })

describe('HTTP API catalog ownership and URL state', () => {
  it('applies every dropdown and classification immediately while retaining unsubmitted keyword', async () => {
    ready.value = true
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', ungroupedCount: 0,
      groups: [group(4, '订单', 1)], memberships: [{ subjectId: 70, groupIds: [4] }] } })
    const { wrapper, router } = await setup({ apiKeyword: '/committed', apiPage: '3', methodKeyword: '查询' })
    const input = wrapper.get('input[aria-label="搜索 API"]')
    await input.setValue('/draft')
    for (const [label, value, field] of [['环境筛选', 'dev', 'environment'], ['HTTP 方法筛选', 'GET', 'method'], ['来源状态筛选', 'ACCEPTED', 'sourceStatus']] as const) {
      const select = filterSelect(wrapper, label)
      select.vm.$emit('update:modelValue', value); select.vm.$emit('change', value)
      await flushPromises()
      expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ [field]: value, keyword: '/committed', current: 1 }))
      expect((input.element as HTMLInputElement).value).toBe('/draft')
    }
    filterSelect(wrapper, '业务分类筛选').vm.$emit('change', '4'); await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: 4, keyword: '/committed', environment: 'dev', method: 'GET', sourceStatus: 'ACCEPTED' }))
    expect(router.currentRoute.value.query).toMatchObject({ apiGroupId: '4', apiPage: '1', methodKeyword: '查询' })
    expect((input.element as HTMLInputElement).value).toBe('/draft')
    await router.replace({ path: HTTP_API_CATALOG_PATH, query: { ...router.currentRoute.value.query, apiPage: '3' } }); await flushPromises()
    filterSelect(wrapper, '业务分类筛选').vm.$emit('change', 'unclassified'); await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: undefined, ungrouped: true, keyword: '/committed', current: 1 }))
    expect(router.currentRoute.value.query).toMatchObject({ apiGroupId: 'unclassified', apiPage: '1', methodKeyword: '查询' })
    expect((input.element as HTMLInputElement).value).toBe('/draft')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '/draft' }))
    expect(router.currentRoute.value.query.methodKeyword).toBe('查询')
  })

  it.each(['project', 'account'])('clears unsubmitted keyword on an actual %s context switch with identical committed keywords', async change => {
    ready.value = true
    const { wrapper } = await setup()
    await wrapper.get('input[aria-label="搜索 API"]').setValue('/old-context-draft')
    if (change === 'project') { projectId.value = 8; context.value = 'account-a|project:8' }
    else { mocks.auth.sessionId = 'account-b'; context.value = 'account-b|project:7' }
    await flushPromises()
    expect((wrapper.get('input[aria-label="搜索 API"]').element as HTMLInputElement).value).toBe('')
  })

  it.each(['', undefined])('clears dropdown URL fields immediately for clearable payload %s without committing keyword', async value => {
    ready.value = true
    const { wrapper, router } = await setup({ apiGroupId: 'unclassified', apiEnvironment: 'dev', apiMethod: 'GET', apiSourceStatus: 'ACCEPTED', apiPage: '3', methodGroupId: '9', methodKeyword: '查询' })
    await wrapper.get('input[aria-label="搜索 API"]').setValue('/draft')
    for (const [label, key, field] of [['业务分类筛选', 'apiGroupId', 'groupId'], ['环境筛选', 'apiEnvironment', 'environment'], ['HTTP 方法筛选', 'apiMethod', 'method'], ['来源状态筛选', 'apiSourceStatus', 'sourceStatus']] as const) {
      const select = filterSelect(wrapper, label)
      select.vm.$emit('update:modelValue', value); select.vm.$emit('change', value); await flushPromises()
      expect(router.currentRoute.value.query[key]).toBeUndefined()
      expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ [field]: undefined, keyword: undefined, current: 1 }))
      expect(router.currentRoute.value.query).toMatchObject({ apiPage: '1', methodGroupId: '9', methodKeyword: '查询' })
      expect(mocks.list.mock.lastCall?.[0].ungrouped).toBeUndefined()
      expect((wrapper.get('input[aria-label="搜索 API"]').element as HTMLInputElement).value).toBe('/draft')
    }
  })

  it('keeps mixed and multi-classified APIs in owner order exactly once in one flat table', async () => {
    ready.value = true
    mocks.list.mockResolvedValue(page([
      { ...api(7, '/shipping'), id: 71 }, api(), { ...api(7, '/unclassified'), id: 72 }, { ...api(7, '/pending'), id: 73 },
    ]))
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', ungroupedCount: 1,
      groups: [group(4, '订单', 2), group(5, '物流', 2)],
      memberships: [{ subjectId: 70, groupIds: [4, 5] }, { subjectId: 71, groupIds: [5] },
        { subjectId: 72, groupIds: [] }, { subjectId: 999, groupIds: [4] }] } })
    const { wrapper, router } = await setup({ apiKeyword: '/orders' })
    const assertFlat = () => {
      expect(wrapper.findAll('.api-table')).toHaveLength(1)
      const rows = wrapper.findAll('.api-table .el-table__row')
      expect(rows).toHaveLength(4)
      expect(rows.map(row => row.get('.api-route').text())).toEqual(['/shipping', '/orders', '/unclassified', '/pending'])
      expect(rows[0]?.findAll('.catalog-category-link').map(button => button.text())).toEqual(['物流'])
      expect(rows[1]?.findAll('.catalog-category-link').map(button => button.text())).toEqual(['订单', '物流'])
      expect(rows[2]?.text()).toContain('未分类')
      expect(rows[3]?.text()).toContain('分类待更新')
      expect(wrapper.find('.classification-section__toggle').exists()).toBe(false)
      expect(wrapper.find('.classification-overview').exists()).toBe(false)
      expect(wrapper.find('.classification-navigation').exists()).toBe(false)
    }
    assertFlat()
    expect(wrapper.findAll('.api-table .el-table__header th').map(item => item.text())).toContain('业务分类')
    expect(wrapper.findAllComponents(ElSelect).filter(select => select.element.closest('.business-capability-filter')).map(select => select.props('ariaLabel')))
      .toEqual(['业务分类筛选，数量为项目全量', '环境筛选', 'HTTP 方法筛选', '来源状态筛选'])
    expect(filterSelect(wrapper, '业务分类筛选').findAllComponents({ name: 'ElOption' }).map(option => option.props('label')))
      .toEqual(['全部能力（4）', '订单（2）', '物流（2）', '未分类（1）'])
    await router.replace({ path: HTTP_API_CATALOG_PATH, query: { ...router.currentRoute.value.query, apiPage: '2' } }); await flushPromises()
    assertFlat()
    await wrapper.get('form').trigger('submit'); await flushPromises()
    assertFlat()
  })

  it('opens each classification detail from the list without navigating or reloading the API', async () => {
    ready.value = true; mocks.list.mockResolvedValue(page([api()]))
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', ungroupedCount: 0,
      groups: [group(4, '订单', 1), group(5, '查询', 1)], memberships: [{ subjectId: 70, groupIds: [4, 5] }] } })
    const { wrapper, router } = await setup()
    const indexReads = mocks.classifications.mock.calls.length, listReads = mocks.list.mock.calls.length
    for (const [id, label] of [[4, '订单'], [5, '查询']] as const) {
      await wrapper.get(`button[aria-label="查看${label}分类详情"]`).trigger('click'); await flushPromises()
      expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props())
        .toMatchObject({ visible: true, mode: 'description', group: { id } })
      expect(router.currentRoute.value.path).toBe(HTTP_API_CATALOG_PATH)
      expect(mocks.classifications).toHaveBeenCalledTimes(indexReads)
      expect(mocks.list).toHaveBeenCalledTimes(listReads)
    }
  })

  it('shows classification writes only in adjustment mode for project writers', async () => {
    ready.value = true; mocks.list.mockResolvedValue(page([api()]))
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', ungroupedCount: 0,
      groups: [group(4, '订单', 1), group(5, '查询', 1)], memberships: [{ subjectId: 70, groupIds: [4, 5] }] } })
    ;(mocks.scope as any).readableProjects = computed(() => [{ id: 7, projectCode: 'orders', environment: 'dev' }])
    mocks.auth.principal.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    const { wrapper } = await setup()
    expect(wrapper.find('button[aria-label="归类/orders"]').exists()).toBe(false)
    expect(wrapper.find('.api-table .el-checkbox').exists()).toBe(false)
    expect(wrapper.find('a.api-identity').exists()).toBe(true)
    const sections = wrapper.getComponent({ name: 'CatalogClassificationSections' })
    sections.vm.$emit('assign', [api()]); sections.vm.$emit('edit', { id: 4, projectId: 7 }, 'rename')
    await flushPromises()
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props('visible')).toBe(false)
    mocks.modeActive.value = true; await flushPromises()
    const adjustHeaders = wrapper.findAll('.api-table .el-table__header th').map(item => item.text())
    for (const label of ['环境', '来源与接纳', '连接 / 试调用', '下一步']) expect(adjustHeaders).not.toContain(label)
    expect(adjustHeaders).toContain('当前归属')
    expect(adjustHeaders).toContain('归类')
    expect(wrapper.findAll('.catalog-category-link').map(button => button.text())).toEqual(['订单', '查询'])
    expect(wrapper.find('.classification-overview').exists()).toBe(false)
    expect(wrapper.find('.classification-section__toggle').exists()).toBe(false)
    expect(wrapper.find('.api-qualified-name').exists()).toBe(false)
    expect(wrapper.find('.api-compact-summary').exists()).toBe(false)
    expect(wrapper.find('a.api-identity').exists()).toBe(false)
    expect(wrapper.find('.api-identity--static').exists()).toBe(true)
    const classify = wrapper.get('button[aria-label="归类/orders"]')
    expect(classify.text()).toBe('归类')
    await classify.trigger('click'); await flushPromises()
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props()).toMatchObject({ visible: true, mode: 'assign', subject: { subjectType: 'HTTP_API', subjectId: 70 } })
    expect(wrapper.find('.api-table .el-checkbox').exists()).toBe(true)
    mocks.auth.principal.permissionGrants = []; await flushPromises()
    expect(wrapper.find('button[aria-label="归类/orders"]').exists()).toBe(false)
    const restoredHeaders = wrapper.findAll('.api-table .el-table__header th').map(item => item.text())
    for (const label of ['环境', '来源与接纳', '连接 / 试调用', '下一步']) expect(restoredHeaders).toContain(label)
    expect(wrapper.find('a.api-identity').exists()).toBe(true)
  })

  it('creates a standalone classification only after entering adjustment mode', async () => {
    ready.value = true; mocks.list.mockResolvedValue(page())
    ;(mocks.scope as any).readableProjects = computed(() => [{ id: 7, projectCode: 'orders', environment: 'dev' }])
    mocks.auth.principal.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    const { wrapper } = await setup()
    const sections = wrapper.getComponent({ name: 'CatalogClassificationSections' })
    sections.vm.$emit('create'); await flushPromises()
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props('visible')).toBe(false)
    expect(wrapper.find('button.classification-create').exists()).toBe(false)
    mocks.modeActive.value = true; await flushPromises()
    expect(wrapper.find('.data-table-shell__empty').exists()).toBe(true)
    expect(wrapper.find('.classification-overview').exists()).toBe(false)
    expect(filterSelect(wrapper, '业务分类筛选').props('disabled')).toBe(false)
    await wrapper.get('button.classification-create').trigger('click'); await flushPromises()
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props()).toMatchObject({ visible: true, projectId: 7, mode: 'create', group: null })
  })

  it('retains a selected empty classification and exposes other empty classifications in adjustment mode', async () => {
    ready.value = true
    ;(mocks.scope as any).readableProjects = computed(() => [{ id: 7, projectCode: 'orders', environment: 'dev' }])
    mocks.auth.principal.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', ungroupedCount: 0,
      groups: [group(4, '空订单分类', 0), group(5, '另一个空分类', 0)], memberships: [] } })
    const { wrapper, router } = await setup({ apiGroupId: '4', apiPage: '2' }, true)
    const options = () => filterSelect(wrapper, '业务分类筛选').findAllComponents({ name: 'ElOption' }).map(option => option.props('label'))
    expect(options()).toEqual(['全部能力（0）', '空订单分类（0）', '未分类（0）'])
    expect(router.currentRoute.value.query.apiGroupId).toBe('4')
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: 4, current: 2 }))
    expect(wrapper.text()).toContain('没有符合当前条件的 API')
    expect(wrapper.find('.classification-section__toggle').exists()).toBe(false)
    const footerActions = () => filterSelect(wrapper, '业务分类筛选').findAllComponents({ name: 'ElButton' })
    const description = () => footerActions().find(button => button.attributes('aria-label') === '查看空订单分类分类说明')!
    expect(description().exists()).toBe(true)
    expect(footerActions().some(button => button.attributes('aria-label') === '重命名空订单分类分类')).toBe(false)
    const classificationFilter = filterSelect(wrapper, '业务分类筛选')
    const expanded = () => classificationFilter.get('[role="combobox"]').attributes('aria-expanded')
    const editor = wrapper.getComponent({ name: 'CatalogClassificationEditor' })
    const closeEditor = async () => {
      ;(wrapper.get('[data-test="classification-draft"]').element as HTMLInputElement).focus()
      editor.vm.$emit('update:visible', false); await flushPromises()
      editor.vm.$emit('close-auto-focus'); await flushPromises()
    }
    const assertClassificationFocus = () => {
      expect(document.activeElement).toBe(classificationFilter.get('[role="combobox"]').element)
      expect(document.activeElement?.getAttribute('aria-label')).toBe('业务分类筛选，数量为项目全量')
      expect(expanded()).toBe('false')
    }
    await classificationFilter.get('.el-select__wrapper').trigger('click'); await flushPromises()
    expect(expanded()).toBe('true')
    await description().trigger('click'); await flushPromises()
    expect(expanded()).toBe('false')
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props())
      .toMatchObject({ visible: true, mode: 'description', group: { id: 4 } })
    await closeEditor()
    assertClassificationFocus()
    mocks.modeActive.value = true; await flushPromises()
    expect(options()).toEqual(['全部能力（0）', '空订单分类（0）', '另一个空分类（0）', '未分类（0）'])
    await classificationFilter.get('.el-select__wrapper').trigger('click'); await flushPromises()
    expect(expanded()).toBe('true')
    await footerActions().find(button => button.attributes('aria-label') === '重命名空订单分类分类')!.trigger('click'); await flushPromises()
    expect(expanded()).toBe('false')
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props())
      .toMatchObject({ visible: true, mode: 'rename', group: { id: 4 } })
    await closeEditor()
    assertClassificationFocus()
    await classificationFilter.get('.el-select__wrapper').trigger('click'); await flushPromises()
    await description().trigger('click'); await flushPromises()
    mocks.classifications.mockImplementation((id: number) => Promise.resolve({ data: { projectId: id,
      subjectType: 'HTTP_API', groups: [], memberships: [], ungroupedCount: 0 } }))
    projectId.value = 8; context.value = 'account-a|project:8'; await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 8 }))
    expect(filterSelect(wrapper, '业务分类筛选').props('disabled')).toBe(false)
    const currentKeyword = wrapper.get('input[aria-label="搜索 API"]').element as HTMLInputElement
    currentKeyword.focus()
    editor.vm.$emit('close-auto-focus'); await flushPromises()
    expect(document.activeElement).toBe(currentKeyword)
    expect(expanded()).toBe('false')
  })

  it('keeps failed classification reads distinct from unclassified APIs and retries without reloading owners', async () => {
    ready.value = true; mocks.list.mockResolvedValue(page([api()]))
    mocks.classifications.mockRejectedValueOnce(new Error('unavailable')).mockResolvedValue({ data: { projectId: 7,
      subjectType: 'HTTP_API', groups: [], memberships: [{ subjectId: 70, groupIds: [] }], ungroupedCount: 1 } })
    const { wrapper } = await setup()
    expect(wrapper.text()).toContain('业务分类读取失败')
    expect(wrapper.get('.api-table .el-table__row').text()).toContain('/orders')
    expect(wrapper.get('.catalog-category-empty').text()).toBe('分类待更新')
    expect(filterSelect(wrapper, '业务分类筛选').props('disabled')).toBe(true)
    await wrapper.get('button[aria-label="重新读取业务分类"]').trigger('click'); await flushPromises()
    expect(mocks.classifications).toHaveBeenCalledTimes(2)
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.catalog-category-empty').text()).toBe('未分类')
    expect(filterSelect(wrapper, '业务分类筛选').props('disabled')).toBe(false)
    expect(wrapper.text()).not.toContain('业务分类读取失败')
  })

  it('refreshes the flat category column after a classification rename or assignment', async () => {
    ready.value = true; mocks.list.mockResolvedValue(page([api()]))
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API',
      groups: [group(4, '订单', 1)], memberships: [{ subjectId: 70, groupIds: [4] }], ungroupedCount: 0 } })
    const { wrapper } = await setup()
    expect(wrapper.get('.catalog-category-link').text()).toBe('订单')
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API',
      groups: [group(4, '字典查询', 1)], memberships: [{ subjectId: 70, groupIds: [4] }], ungroupedCount: 0 } })
    wrapper.getComponent({ name: 'CatalogClassificationEditor' }).vm.$emit('updated'); await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.catalog-category-link').text()).toBe('字典查询')
    expect(wrapper.find('button[aria-label="查看订单分类详情"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="查看字典查询分类详情"]').exists()).toBe(true)
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API',
      groups: [group(4, '字典查询', 0)], memberships: [{ subjectId: 70, groupIds: [] }], ungroupedCount: 1 } })
    wrapper.getComponent({ name: 'CatalogClassificationEditor' }).vm.$emit('updated'); await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(3)
    expect(wrapper.find('.catalog-category-link').exists()).toBe(false)
    expect(wrapper.get('.catalog-category-empty').text()).toBe('未分类')
    expect(wrapper.findAll('.api-table .el-table__row')).toHaveLength(1)
  })

  it('keeps an active classification draft through a cancelled project navigation', async () => {
    ready.value = true; mocks.list.mockResolvedValue(page([api()]))
    ;(mocks.scope as any).resolution = computed(() => ({ kind: 'project', project: { id: projectId.value, projectCode: 'orders', name: '订单' } }))
    ;(mocks.scope as any).requestParams = computed(() => ready.value ? { projectId: projectId.value, projectCode: 'orders' } : undefined)
    ;(mocks.scope as any).readableProjects = computed(() => [{ id: 7, projectCode: 'orders', environment: 'dev' }])
    mocks.modeRegister.mockImplementation((allowed: Ref<boolean>) => watch(allowed, value => { if (!value) mocks.modeActive.value = false }, { flush: 'sync' }))
    mocks.auth.principal.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    const { wrapper } = await setup()
    mocks.modeActive.value = true; await flushPromises()
    await wrapper.get('button[aria-label="归类/orders"]').trigger('click'); await flushPromises()
    const editor = wrapper.getComponent({ name: 'CatalogClassificationEditor' })
    await wrapper.get('[data-test="classification-draft"]').setValue('待保存的归类')
    const before = mocks.list.mock.calls.length
    ready.value = false; context.value = 'account-a|navigation-pending'; await flushPromises()
    expect((mocks.scope as any).requestParams.value).toBeUndefined()
    expect(mocks.modeRegister.mock.calls[0]?.[0].value).toBe(true)
    expect(mocks.modeActive.value).toBe(true)
    expect(editor.props()).toMatchObject({ visible: true, projectId: 7, projectCode: 'orders' })
    expect((wrapper.get('[data-test="classification-draft"]').element as HTMLInputElement).value).toBe('待保存的归类')
    expect(mocks.list).toHaveBeenCalledTimes(before)
    ready.value = true; context.value = 'account-a|project:7'; await flushPromises()
    expect(mocks.modeActive.value).toBe(true)
    expect(editor.props()).toMatchObject({ visible: true, projectId: 7, projectCode: 'orders' })
    expect((wrapper.get('[data-test="classification-draft"]').element as HTMLInputElement).value).toBe('待保存的归类')
  })

  it('clears a classification absent from the refreshed index before reloading its API list', async () => {
    ready.value = true
    const group = { id: 4, projectId: 7, name: 'old', displayName: '原分类', sourceClasses: [], memberCount: 0 }
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', groups: [group], memberships: [], ungroupedCount: 0 } })
    const { wrapper, router } = await setup({ apiGroupId: '4', apiPage: '2', apiKeyword: '查询', methodGroupId: '8' })
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', groups: [{ ...group, id: 5 }], memberships: [], ungroupedCount: 0 } })
    const before = mocks.list.mock.calls.length
    wrapper.getComponent({ name: 'CatalogClassificationEditor' }).vm.$emit('updated')
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ apiPage: '1', apiKeyword: '查询', methodGroupId: '8' })
    expect(router.currentRoute.value.query.apiGroupId).toBeUndefined()
    expect(mocks.list.mock.calls.slice(before).every(([params]) => params.groupId === undefined)).toBe(true)
  })
  it.each([undefined, '4'])('discards a retired classification action with category %s without opening an editor', async classificationId => {
    ready.value = true
    ;(mocks.scope as any).readableProjects = computed(() => [{ id: 7, projectCode: 'orders', environment: 'dev' }])
    mocks.auth.principal.permissionGrants = [{ permissionCode: 'platform:write', scopeType: 'PROJECT', scopeValue: 'orders' }]
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', ungroupedCount: 0,
      groups: [{ id: 4, projectId: 7, name: 'orders', displayName: '订单', sourceClasses: [], memberCount: 0 }], memberships: [] } })
    const query = { classificationAction: 'merge', apiKeyword: '/orders', apiPage: '2', methodKeyword: '查询',
      ...(classificationId ? { classificationId } : {}) }
    const { wrapper, router } = await setup(query)
    expect(wrapper.getComponent({ name: 'CatalogClassificationEditor' }).props('visible')).toBe(false)
    expect(router.currentRoute.value.query).toMatchObject({ apiKeyword: '/orders', apiPage: '2', methodKeyword: '查询' })
    expect(router.currentRoute.value.query.classificationAction).toBeUndefined()
    expect(router.currentRoute.value.query.classificationId).toBeUndefined()
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '/orders', current: 2 }))
  })
  it('preserves the fresh classification explicitly addressed to a new verified project', async () => {
    ready.value = true
    const { router } = await setup()
    await router.push({ path: HTTP_API_CATALOG_PATH, query: { projectId: '8', apiGroupId: '4', apiKeyword: '查询' } })
    projectId.value = 8; context.value = 'account-a|project:8'
    await flushPromises()
    expect(router.currentRoute.value.query.apiGroupId).toBe('4')
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 8, groupId: 4, keyword: '查询' }))
  })
  it('queries unclassified owners on the server and retains the category when opening an API', async () => {
    ready.value = true; mocks.list.mockResolvedValue(page([api()]))
    mocks.classifications.mockResolvedValue({ data: { projectId: 7, subjectType: 'HTTP_API', groups: [],
      memberships: [{ subjectId: 70, groupIds: [] }], ungroupedCount: 1 } })
    const { wrapper } = await setup({ apiGroupId: 'unclassified', apiPage: '2' })
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ ungrouped: true, groupId: undefined, current: 2 }))
    expect(wrapper.get('a.api-identity').attributes('href')).toContain('apiGroupId=unclassified')
    expect(wrapper.text()).toContain('未分类')
    expect(wrapper.findComponent({ name: 'CatalogGroupFilter' }).exists()).toBe(false)
  })
  it('preserves the same project group and page while URL changes temporarily invalidate scope confirmation', async () => {
    ready.value = true
    const { router } = await setup({ apiGroupId: '4', apiPage: '2' })
    const before = mocks.list.mock.calls.length
    ready.value = false; context.value = 'account-a|route:2|not-ready'
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(before)
    expect(router.currentRoute.value.query).toMatchObject({ apiGroupId: '4', apiPage: '2' })
    ready.value = true; context.value = 'account-a|route:2|project:7'
    await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 7, groupId: 4, current: 2 }))
    expect(router.currentRoute.value.query).toMatchObject({ apiGroupId: '4', apiPage: '2' })
  })
  it('filters the full owner result by group and discards that group when switching projects', async () => {
    ready.value = true
    const { router } = await setup({ apiGroupId: '4', apiKeyword: '/orders', methodGroupId: '6' })
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 7, groupId: 4 }))
    projectId.value = 8; context.value = 'account-a|project:8'
    await flushPromises()
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ projectId: 8, groupId: undefined, keyword: '/orders', current: 1 }))
    expect(router.currentRoute.value.query.methodGroupId).toBeUndefined()
  })
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
    mocks.list.mockResolvedValue(page([api(7, '/recovered-api')]))
    await wrapper.get('.el-alert button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('/recovered-api')
    expect(wrapper.text()).not.toContain('API 目录加载失败')
    expect(wrapper.getComponent({ name: 'ElPagination' }).props('total')).toBe(1)
  })

  it('keeps server pagination totals while environment and HTTP method filters apply immediately', async () => {
    ready.value = true
    mocks.list.mockResolvedValue({ data: { records: [api()], total: 37, current: 2, size: 20, pages: 2 } })
    const { wrapper } = await setup({ apiEnvironment: 'dev', apiMethod: 'GET', apiPage: '2' })
    const pagination = () => wrapper.getComponent({ name: 'ElPagination' })
    expect(pagination().props('total')).toBe(37)
    expect(pagination().props('currentPage')).toBe(2)
    expect(wrapper.get('.el-pagination__total').text()).toContain('37')
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ current: 2, environment: 'dev', method: 'GET' }))
    await wrapper.get('input[aria-label="搜索 API"]').setValue('/draft-only')
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(pagination().props('total')).toBe(37)
    for (const label of ['环境筛选', 'HTTP 方法筛选']) {
      const select = filterSelect(wrapper, label)
      select.vm.$emit('update:modelValue', ''); select.vm.$emit('change', '')
      await flushPromises()
    }
    expect(pagination().props('total')).toBe(37)
    expect(pagination().props('currentPage')).toBe(1)
    expect((wrapper.get('input[aria-label="搜索 API"]').element as HTMLInputElement).value).toBe('/draft-only')
    expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ current: 1, environment: undefined, method: undefined }))
  })
})
