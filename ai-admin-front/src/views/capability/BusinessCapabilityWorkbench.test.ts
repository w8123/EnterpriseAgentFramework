import { defineComponent, h, onBeforeUnmount, onMounted, computed, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter, onBeforeRouteLeave } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '@/App.vue'
import BusinessCapabilityWorkbench from './BusinessCapabilityWorkbench.vue'
import { routes } from '@/router'
import { BUSINESS_CAPABILITY_ROOT, BUSINESS_METHOD_CATALOG_PATH, HTTP_API_CATALOG_PATH } from './businessCapabilityRoutes'
import { useCatalogClassificationMode } from './composables/useCatalogClassificationMode'

vi.mock('@/composables/usePageProjectScope', () => ({ usePageProjectScope: () => ({
  currentScopeLabel: computed(() => '订单项目'),
}) }))

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

function workbenchRouter(panel: (kind: string) => ReturnType<typeof defineComponent>) {
  // Preserve the actual production route ranking, including the sibling HTTP detail route.
  const withPanels = (record: RouteRecordRaw): RouteRecordRaw => ({ ...record,
    ...('component' in record ? { component: record.name === 'BusinessCapabilityWorkbench'
      ? BusinessCapabilityWorkbench
      : record.name === 'BusinessMethodCatalog' ? panel('java')
      : record.name === 'HttpApiCatalog' ? panel('http')
      : record.name === 'BusinessCapabilitySourceWorkbench' ? panel('source')
      : record.name === 'HttpApiDetail' ? panel('detail')
      : defineComponent({ template: '<router-view />' }) } : {}),
    ...('children' in record ? { children: record.children?.map(withPanels) } : {}),
  }) as RouteRecordRaw
  return createRouter({ history: createMemoryHistory(), routes: routes.map(withPanels) })
}

async function goBack(router: ReturnType<typeof createRouter>) {
  const returned = new Promise<void>(resolve => { const stop = router.afterEach(() => { stop(); resolve() }) })
  router.back()
  await returned
  await flushPromises()
}

async function selectWorkspace(wrapper: ReturnType<typeof mount>, label: string) {
  const link = wrapper.findAll('.business-capability-workspaces a').find(item => item.text() === label)
  expect(link, `Missing workspace navigation: ${label}`).toBeDefined()
  await link!.trigger('click')
  await flushPromises()
}

const projectQuery = { projectId: '35', projectCode: 'bzjs20', methodKeyword: '查询', methodEnabled: 'false',
  methodPage: '2', apiKeyword: '/orders', apiMethod: 'GET', apiEnvironment: 'dev', apiSourceStatus: 'CURRENT', apiPage: '3' }
const sharedWorkspaces = ['sources', 'semantics', 'graph'] as const
const workspaceLabels = { sources: '接入与变更', semantics: '批量整理说明', graph: '关联分析' }

function adjustmentPanel(allowed = ref(true), beforeExit = async () => true) {
  return (kind: string) => defineComponent({ setup() {
    const mode = useCatalogClassificationMode()!
    onBeforeUnmount(mode.register(allowed, beforeExit))
    return () => h('div', { 'data-adjustment': mode.active.value ? 'active' : 'browse' }, kind)
  } })
}

async function enterAdjustment(wrapper: ReturnType<typeof mount>) {
  wrapper.getComponent({ name: 'ElDropdown' }).vm.$emit('command', 'classification')
  await flushPromises()
}

async function exitAdjustment(wrapper: ReturnType<typeof mount>) {
  const button = wrapper.findAll('button').find(item => item.text() === '退出调整')
  expect(button).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}

describe('business capability workbench', () => {
  it('offers explanation and graph tools while ignoring retired dropdown commands', async () => {
    const router = workbenchRouter(kind => defineComponent({ template: `<div>${kind}</div>` }))
    await router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: projectQuery })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } }); wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.find('.app-page-header .business-capability-catalog-actions').exists()).toBe(true)
    expect(wrapper.get('.business-capability-catalog-actions').text()).toBe('整理')
    expect(wrapper.findAllComponents({ name: 'ElDropdownItem' }).map(item => item.text())).toEqual(['调整分类', '批量整理说明', '关联分析'])
    expect(wrapper.findAllComponents({ name: 'ElDropdownItem' })[0].props('disabled')).toBe(true)
    await enterAdjustment(wrapper)
    expect(wrapper.findAll('button').some(item => item.text() === '退出调整')).toBe(false)
    wrapper.getComponent({ name: 'ElDropdown' }).vm.$emit('command', 'merge')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(BUSINESS_METHOD_CATALOG_PATH)
    expect(router.currentRoute.value.query).toEqual(projectQuery)
  })

  it('enters and exits in place without changing the catalog query or pane', async () => {
    const router = workbenchRouter(adjustmentPanel())
    await router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: projectQuery })
    const wrapper = mount(App, { attachTo: document.body, global: { plugins: [router, ElementPlus] } }); wrappers.push(wrapper)
    await flushPromises()
    const pane = wrapper.get('[data-adjustment]').element
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('browse')
    await enterAdjustment(wrapper)
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('active')
    expect(wrapper.get('[data-adjustment]').element).toBe(pane)
    expect(router.currentRoute.value.query).toEqual(projectQuery)
    expect(wrapper.find('.business-capability-workspaces').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'ElDropdown' }).exists()).toBe(false)
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['Java 业务方法', 'HTTP API'])
    expect(wrapper.get('#tab-java-methods').attributes('aria-selected')).toBe('true')
    expect(wrapper.findAll('button').filter(item => item.text() === '退出调整')).toHaveLength(1)
    expect(wrapper.get('.business-capability-catalog-actions').text()).toBe('退出调整')
    expect(wrapper.get('#tab-java-methods').attributes('aria-controls')).toBe('pane-java-methods')
    expect(wrapper.get('#pane-java-methods').attributes('aria-labelledby')).toBe('tab-java-methods')
    await exitAdjustment(wrapper)
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('browse')
    expect(router.currentRoute.value.query).toEqual(projectQuery)
    expect(wrapper.findAll('.business-capability-workspaces a').map(link => link.text())).toEqual(['能力目录', '接入与变更'])
    expect(wrapper.findComponent({ name: 'ElDropdown' }).exists()).toBe(true)
    expect(document.activeElement).toBe(wrapper.findAll('button').find(item => item.text() === '整理')!.element)
  })

  it('retains adjustment when a draft rejects exit and exits after the guard allows it', async () => {
    const guard = vi.fn().mockResolvedValue(false)
    const router = workbenchRouter(adjustmentPanel(ref(true), guard))
    await router.push({ path: HTTP_API_CATALOG_PATH, query: projectQuery })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } }); wrappers.push(wrapper)
    await flushPromises(); await enterAdjustment(wrapper); await exitAdjustment(wrapper)
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('active')
    expect(wrapper.find('.business-capability-workspaces').exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'ElDropdown' }).exists()).toBe(false)
    guard.mockResolvedValue(true)
    await exitAdjustment(wrapper)
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('browse')
  })

  it('keeps adjustment active when a tab navigation is cancelled by the editor', async () => {
    const router = workbenchRouter(kind => defineComponent({ setup() {
      const mode = useCatalogClassificationMode()!
      onBeforeUnmount(mode.register(ref(true), async () => true))
      if (kind === 'java') onBeforeRouteLeave(() => false)
      return () => h('div', { 'data-adjustment': mode.active.value ? 'active' : 'browse' }, kind)
    } }))
    await router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: projectQuery })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } }); wrappers.push(wrapper)
    await flushPromises(); await enterAdjustment(wrapper)
    await wrapper.get('#tab-http-apis').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.path).toBe(BUSINESS_METHOD_CATALOG_PATH)
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('active')
    expect(wrapper.get('#tab-java-methods').attributes('aria-selected')).toBe('true')
  })

  it('keeps adjustment through filtering but resets it on tab, project and workspace changes', async () => {
    const router = workbenchRouter(adjustmentPanel())
    await router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: projectQuery })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } }); wrappers.push(wrapper)
    await flushPromises(); await enterAdjustment(wrapper)
    await router.push({ query: { ...projectQuery, methodKeyword: '订单', methodPage: '1' } }); await flushPromises()
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('active')
    await wrapper.get('#tab-http-apis').trigger('click'); await flushPromises()
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('browse')
    await enterAdjustment(wrapper)
    await router.push({ query: { ...router.currentRoute.value.query, projectId: '36', projectCode: 'bzjs21' } }); await flushPromises()
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('browse')
    await enterAdjustment(wrapper)
    expect(wrapper.find('.business-capability-workspaces').exists()).toBe(false)
    await router.push({ path: `${HTTP_API_CATALOG_PATH}/sources`, query: router.currentRoute.value.query }); await flushPromises()
    await selectWorkspace(wrapper, '能力目录')
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('browse')
  })

  it('resets adjustment when the current project write permission is revoked', async () => {
    const allowed = ref(true)
    const router = workbenchRouter(adjustmentPanel(allowed))
    await router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: projectQuery })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } }); wrappers.push(wrapper)
    await flushPromises(); await enterAdjustment(wrapper)
    allowed.value = false; await flushPromises()
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('browse')
    await enterAdjustment(wrapper)
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('browse')
  })

  it('ignores an old pending exit after a new tab registers and enters adjustment', async () => {
    let resolveExit!: (allowed: boolean) => void
    const router = workbenchRouter(adjustmentPanel(ref(true), () => new Promise<boolean>(resolve => { resolveExit = resolve })))
    await router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: projectQuery })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } }); wrappers.push(wrapper)
    await flushPromises(); await enterAdjustment(wrapper); await exitAdjustment(wrapper)
    await wrapper.get('#tab-http-apis').trigger('click'); await flushPromises(); await enterAdjustment(wrapper)
    resolveExit(true); await flushPromises()
    expect(wrapper.get('[data-adjustment]').attributes('data-adjustment')).toBe('active')
  })

  it('keeps the current pane visible while its unsaved editor guards a tab switch', async () => {
    let resolveLeave!: (value: boolean) => void
    const router = workbenchRouter(kind => defineComponent({ setup() {
      const guarding = ref(false)
      if (kind === 'java') onBeforeRouteLeave(() => {
        guarding.value = true
        return new Promise<boolean>(resolve => { resolveLeave = value => { guarding.value = false; resolve(value) } })
      })
      return { kind, guarding }
    }, template: '<div><textarea v-if="kind === \'java\'">未保存的分类输入</textarea><p v-if="guarding">保留编辑或放弃输入</p>{{ kind }}</div>' }))
    await router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: projectQuery })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } }); wrappers.push(wrapper)
    await flushPromises()
    await wrapper.get('#tab-http-apis').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.path).toBe(BUSINESS_METHOD_CATALOG_PATH)
    expect(wrapper.get('#tab-java-methods').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#pane-java-methods').attributes('style') || '').not.toContain('display: none')
    expect(wrapper.get('textarea').element.value).toBe('未保存的分类输入')
    resolveLeave(false); await flushPromises()
    expect(wrapper.get('#tab-java-methods').attributes('aria-selected')).toBe('true')
    await wrapper.get('#tab-http-apis').trigger('click'); await flushPromises()
    resolveLeave(true); await flushPromises()
    expect(router.currentRoute.value.path).toBe(HTTP_API_CATALOG_PATH)
    expect(wrapper.get('#tab-http-apis').attributes('aria-selected')).toBe('true')
  })
  it('shares a compact header and persistent navigation, with catalog state restored across tabs and Back', async () => {
    const mounted = vi.fn(), unmounted = vi.fn()
    const panel = (kind: string) => defineComponent({ setup() {
      onMounted(() => mounted(kind)); onBeforeUnmount(() => unmounted(kind))
      return () => kind
    } })
    const router = workbenchRouter(panel)
    await router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: projectQuery })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.get('h1').text()).toBe('业务能力')
    expect(wrapper.findAll('.app-page-header')).toHaveLength(1)
    expect(wrapper.get('.app-page-header').classes()).toContain('app-page-header--standard')
    expect(wrapper.get('.app-page-header').classes()).toContain('is-compact')
    expect(wrapper.get('.app-page-header').classes()).not.toContain('has-artwork')
    expect(wrapper.get('.app-page-header').attributes('data-presentation')).toBe('plain')
    expect(wrapper.find('.app-page-header__eyebrow').exists()).toBe(false)
    expect(wrapper.find('.business-capability-scope').exists()).toBe(false)
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['Java 业务方法', 'HTTP API'])
    expect(mounted.mock.calls).toEqual([['java']])
    const header = wrapper.get('h1').element
    const navigation = wrapper.get('.business-capability-workspaces').element
    expect(navigation.closest('.el-tabs')).toBeNull()
    expect(wrapper.findAll('.business-capability-workspaces a').map(link => link.text())).toEqual(['能力目录', '接入与变更'])
    const apiTab = wrapper.get('#tab-http-apis').element

    await wrapper.get('#tab-http-apis').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(HTTP_API_CATALOG_PATH)
    expect(wrapper.get('h1').element).toBe(header)
    expect(wrapper.get('.business-capability-workspaces').element).toBe(navigation)
    expect(wrapper.get('#tab-http-apis').element).toBe(apiTab)
    expect(wrapper.get('#tab-http-apis').attributes('aria-selected')).toBe('true')
    expect(mounted.mock.calls).toEqual([['java'], ['http']])
    expect(unmounted).toHaveBeenCalledWith('java')
    expect(router.currentRoute.value.query).toEqual(projectQuery)

    await goBack(router)
    expect(router.currentRoute.value.path).toBe(BUSINESS_METHOD_CATALOG_PATH)
    expect(wrapper.get('#tab-java-methods').attributes('aria-selected')).toBe('true')
    expect(unmounted).toHaveBeenCalledWith('http')
    expect(router.currentRoute.value.query).toEqual(projectQuery)

    await router.push({ path: '/business-capabilities/java-methods/graph', query: projectQuery })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/business-capabilities/java-methods/graph')
    expect(wrapper.get('h1').element).toBe(header)
    expect(wrapper.get('.business-capability-workspaces').element).toBe(navigation)
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(0)
    expect(wrapper.find('.el-tabs').exists()).toBe(false)
    expect(wrapper.find('.business-capability-catalog-actions').exists()).toBe(false)
    expect(wrapper.find('.business-capability-scope').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('项目共享 · Java 业务方法与 HTTP API')
    expect(router.currentRoute.value.query).toEqual(projectQuery)

    await goBack(router)
    expect(router.currentRoute.value.path).toBe(BUSINESS_METHOD_CATALOG_PATH)
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['Java 业务方法', 'HTTP API'])
    expect(wrapper.get('#tab-java-methods').attributes('aria-selected')).toBe('true')
    expect(wrapper.find('.business-capability-scope').exists()).toBe(false)
    expect(router.currentRoute.value.query).toEqual(projectQuery)
  })

  it.each(['java-methods', 'http-apis'].flatMap(category => sharedWorkspaces.map(workspace => ({ category, workspace }))))(
    'keeps $category/$workspace project shared, restoring the URL category and both filters on return', async ({ category, workspace }) => {
    const router = workbenchRouter(kind => defineComponent({ template: `<div data-panel="${kind}">${kind}</div>` }))
    const catalogPath = `${BUSINESS_CAPABILITY_ROOT}/${category}`
    const workspacePath = `${catalogPath}/${workspace}`
    await router.push({ path: workspacePath, query: projectQuery })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } })
    wrappers.push(wrapper)
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('BusinessCapabilitySourceWorkbench')
    expect(router.currentRoute.value.params).toMatchObject({ category, workspace })
    expect(wrapper.find('[data-panel="source"]').exists()).toBe(true)
    expect(wrapper.find('[data-panel="detail"]').exists()).toBe(false)
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(0)
    expect(wrapper.find('.el-tabs').exists()).toBe(false)
    expect(wrapper.findAll('.app-page-header')).toHaveLength(1)
    expect(wrapper.findAll('.business-capability-workspaces')).toHaveLength(1)
    expect(wrapper.findAll('.business-capability-scope')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('项目共享 · Java 业务方法与 HTTP API')
    expect(wrapper.get('.business-capability-content').attributes('aria-label')).toBe(workspaceLabels[workspace])
    expect(wrapper.findAll('.business-capability-workspaces [aria-current="page"]').map(link => link.text())).toEqual([workspace === 'sources' ? '接入与变更' : '能力目录'])
    const header = wrapper.get('h1').element
    const navigation = wrapper.get('.business-capability-workspaces').element

    const nextWorkspace = workspace === 'sources' ? 'semantics' : 'sources'
    await router.push({ path: `${catalogPath}/${nextWorkspace}`, query: router.currentRoute.value.query })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(`${catalogPath}/${nextWorkspace}`)
    expect(router.currentRoute.value.name).toBe('BusinessCapabilitySourceWorkbench')
    expect(router.currentRoute.value.params).toMatchObject({ category, workspace: nextWorkspace })
    expect(router.currentRoute.value.query).toEqual(projectQuery)
    expect(wrapper.find('[data-panel="detail"]').exists()).toBe(false)
    expect(wrapper.get('h1').element).toBe(header)
    expect(wrapper.get('.business-capability-workspaces').element).toBe(navigation)
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(0)

    await goBack(router)
    expect(router.currentRoute.value.path).toBe(workspacePath)
    expect(router.currentRoute.value.query).toEqual(projectQuery)

    await selectWorkspace(wrapper, '能力目录')
    expect(router.currentRoute.value.path).toBe(catalogPath)
    expect(router.currentRoute.value.name).toBe(category === 'http-apis' ? 'HttpApiCatalog' : 'BusinessMethodCatalog')
    expect(wrapper.find('[data-panel="source"]').exists()).toBe(false)
    expect(wrapper.find(`[data-panel="${category === 'http-apis' ? 'http' : 'java'}"]`).exists()).toBe(true)
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['Java 业务方法', 'HTTP API'])
    expect(wrapper.get(`#tab-${category}`).attributes('aria-selected')).toBe('true')
    expect(wrapper.get('h1').element).toBe(header)
    expect(wrapper.get('.business-capability-workspaces').element).toBe(navigation)
    expect(wrapper.find('.business-capability-scope').exists()).toBe(false)
    expect(router.currentRoute.value.query).toEqual(projectQuery)

    await goBack(router)
    expect(router.currentRoute.value.path).toBe(workspacePath)
    expect(router.currentRoute.value.name).toBe('BusinessCapabilitySourceWorkbench')
    expect(wrapper.find('[data-panel="source"]').exists()).toBe(true)
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(0)
    expect(wrapper.find('.business-capability-scope').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('项目共享 · Java 业务方法与 HTTP API')
    expect(router.currentRoute.value.query).toEqual(projectQuery)
  })

  it('preserves all-project scope on shared navigation and return without guessing a project', async () => {
    const router = workbenchRouter(kind => defineComponent({ template: `<div data-panel="${kind}">${kind}</div>` }))
    const query = { scope: 'all', methodKeyword: '查询', apiKeyword: '/orders', apiPage: '2' }
    await router.push({ path: HTTP_API_CATALOG_PATH, query })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } })
    wrappers.push(wrapper)
    await flushPromises()

    await selectWorkspace(wrapper, '接入与变更')
    expect(router.currentRoute.value.path).toBe(`${HTTP_API_CATALOG_PATH}/sources`)
    expect(router.currentRoute.value.name).toBe('BusinessCapabilitySourceWorkbench')
    expect(router.currentRoute.value.query).toEqual(query)
    expect(wrapper.find('[data-panel="detail"]').exists()).toBe(false)
    expect(wrapper.findAll('[role="tab"]')).toHaveLength(0)

    await selectWorkspace(wrapper, '能力目录')
    expect(router.currentRoute.value.path).toBe(HTTP_API_CATALOG_PATH)
    expect(router.currentRoute.value.query).toEqual(query)
    expect(wrapper.get('#tab-http-apis').attributes('aria-selected')).toBe('true')
  })

  it('resolves numeric HTTP assets independently from project workspaces with the production route table', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes })
    expect(router.resolve(`${HTTP_API_CATALOG_PATH}/42`).name).toBe('HttpApiDetail')
    expect(router.resolve(`${HTTP_API_CATALOG_PATH}/42`).params.id).toBe('42')
    const legacyRecords = router.resolve('/apis/42').matched
    expect(legacyRecords[legacyRecords.length - 1]?.redirect).toBeTypeOf('function')
    for (const category of ['java-methods', 'http-apis']) {
      for (const workspace of sharedWorkspaces) {
        expect(router.resolve(`${BUSINESS_CAPABILITY_ROOT}/${category}/${workspace}`).name).toBe('BusinessCapabilitySourceWorkbench')
      }
    }
  })

  it.each(['java-methods', 'http-apis'])('redirects the old $0 groups page into its catalog and opens the referenced classification', async category => {
    const router = workbenchRouter(kind => defineComponent({ template: `<div data-panel="${kind}">${kind}</div>` }))
    await router.push({ path: `${BUSINESS_CAPABILITY_ROOT}/${category}/groups`,
      query: { ...projectQuery, subjectType: 'GROUP', subjectId: '361', subjectKey: 'group:361' } })
    expect(router.currentRoute.value.path).toBe(`${BUSINESS_CAPABILITY_ROOT}/${category}`)
    expect(router.currentRoute.value.query).toMatchObject({ ...projectQuery, classificationId: '361' })
    expect(router.currentRoute.value.query.subjectType).toBeUndefined()
    expect(router.currentRoute.value.query.subjectId).toBeUndefined()
    expect(router.currentRoute.value.query.subjectKey).toBeUndefined()
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } }); wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.find('[data-panel="source"]').exists()).toBe(false)
    expect(wrapper.find(`[data-panel="${category === 'http-apis' ? 'http' : 'java'}"]`).exists()).toBe(true)
    expect(wrapper.text()).not.toContain('管理分组')
  })
})
