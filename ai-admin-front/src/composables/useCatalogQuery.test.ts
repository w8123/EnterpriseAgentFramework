import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'
import { useCatalogQuery } from './useCatalogQuery'
import { legacyCatalogQuery } from '@/views/capability/businessCapabilityRoutes'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

async function setup(query: Record<string, string> = {}) {
  let methods!: ReturnType<typeof useCatalogQuery<'keyword' | 'enabled'>>
  let apis!: ReturnType<typeof useCatalogQuery<'keyword' | 'method'>>
  const Catalog = defineComponent({ setup() {
    methods = useCatalogQuery('method', ['keyword', 'enabled'])
    apis = useCatalogQuery('api', ['keyword', 'method'])
    return () => null
  } })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/catalog', component: Catalog },
    { path: '/sources', component: { template: '<div>来源</div>' } },
  ] })
  await router.push({ path: '/catalog', query })
  wrappers.push(mount({ template: '<router-view />' }, { global: { plugins: [router] } }))
  await flushPromises()
  return { router, methods, apis }
}

describe('catalog URL state', () => {
  it('preserves an unsubmitted keyword when a different committed filter changes', async () => {
    const { router, methods } = await setup({ methodKeyword: '已提交', methodPage: '3' })
    methods.draft.keyword = '尚未提交'
    await router.replace({ path: '/catalog', query: { methodKeyword: '已提交', methodEnabled: 'false', methodPage: '1' } })
    await flushPromises()
    expect(methods.draft.keyword).toBe('尚未提交')
    expect(methods.filters.value).toEqual({ keyword: '已提交', enabled: 'false' })
    expect(methods.draft.enabled).toBe('false')
  })

  it('commits only an explicitly selected field while preserving draft text and peer tab state', async () => {
    const { router, methods } = await setup({ projectId: '7', methodKeyword: '已提交', methodPage: '3', apiKeyword: '/orders' })
    methods.draft.keyword = '尚未提交'
    methods.draft.enabled = 'false'
    await methods.applyField('enabled')
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ projectId: '7', methodKeyword: '已提交', methodEnabled: 'false', methodPage: '1', apiKeyword: '/orders' })
    expect(methods.draft.keyword).toBe('尚未提交')
    await methods.apply()
    expect(methods.filters.value.keyword).toBe('尚未提交')
  })

  it('clears a stale classification target in the same filter commit without submitting a keyword draft or clearing peer filters', async () => {
    const { router, methods } = await setup({ scope: 'all', methodProjectId: '7', methodKeyword: '已提交', methodPage: '3',
      classificationId: '4', classificationAction: 'description', apiGroupId: '9', apiPage: '5' })
    methods.draft.keyword = '尚未提交'
    methods.draft.enabled = 'false'
    await methods.applyFields(['enabled'], ['classificationId', 'classificationAction'])
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ scope: 'all', methodProjectId: '7', methodKeyword: '已提交', methodEnabled: 'false',
      methodPage: '1', apiGroupId: '9', apiPage: '5' })
    expect(methods.draft.keyword).toBe('尚未提交')
    expect(methods.filters.value.keyword).toBe('已提交')
  })

  it('keeps all unsubmitted draft fields while paging or restoring only the peer tab bookmark', async () => {
    const { router, methods } = await setup({ methodKeyword: '已提交', methodPage: '3' })
    methods.draft.keyword = '尚未提交'
    methods.draft.enabled = 'false'
    await methods.changePage(4)
    expect(methods.draft).toEqual({ keyword: '尚未提交', enabled: 'false' })
    await router.push({ path: '/catalog', query: { ...router.currentRoute.value.query, apiKeyword: '/payments' } })
    await flushPromises()
    expect(methods.draft).toEqual({ keyword: '尚未提交', enabled: 'false' })
    await router.push({ path: '/catalog', query: { methodKeyword: '新的书签', methodEnabled: 'true' } })
    await flushPromises()
    expect(methods.draft).toEqual({ keyword: '新的书签', enabled: 'true' })
  })

  it('cannot cancel a source-view navigation with an old pagination event while an async guard is pending', async () => {
    const { router, methods } = await setup({ scope: 'all', methodPage: '3' })
    let continueNavigation!: () => void
    let guardStarted!: () => void
    const started = new Promise<void>(resolve => { guardStarted = resolve })
    router.beforeEach(to => to.path === '/sources'
      ? new Promise<boolean>(resolve => { continueNavigation = () => resolve(true); guardStarted() })
      : true)
    const navigation = router.push({ path: '/sources', query: router.currentRoute.value.query })
    await started
    await methods.changePage(1)
    continueNavigation()
    await navigation
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/sources?scope=all&methodPage=3')
    await methods.clear(['enabled'])
    expect(router.currentRoute.value.fullPath).toBe('/sources?scope=all&methodPage=3')
  })

  it('restores catalog query actions when leaving navigation is rejected', async () => {
    const { router, methods } = await setup({ methodPage: '3' })
    router.beforeEach(to => to.path !== '/sources')
    await router.push('/sources')
    await methods.changePage(2)
    expect(router.currentRoute.value.path).toBe('/catalog')
    expect(router.currentRoute.value.query.methodPage).toBe('2')
  })
  it('restores each tab independently and preserves the project and peer filters when applying or clearing one field', async () => {
    const { router, methods, apis } = await setup({ projectId: '7', projectCode: 'orders',
      methodKeyword: '查询', methodEnabled: 'true', methodPage: '3', methodSize: '50',
      apiKeyword: '/orders', apiMethod: 'GET', apiPage: '2' })
    expect(methods.draft).toEqual({ keyword: '查询', enabled: 'true' })
    expect(methods.page.value).toBe(3)
    expect(methods.size.value).toBe(50)
    expect(apis.draft).toEqual({ keyword: '/orders', method: 'GET' })

    methods.draft.keyword = '  取消  '
    methods.draft.enabled = 'false'
    expect(methods.filters.value.keyword).toBe('查询')
    await methods.apply()
    expect(router.currentRoute.value.query).toMatchObject({ projectId: '7', projectCode: 'orders',
      methodKeyword: '取消', methodEnabled: 'false', methodPage: '1',
      apiKeyword: '/orders', apiMethod: 'GET', apiPage: '2' })
    await methods.applyField('enabled', '')
    expect(methods.hasFilters.value).toBe(true)
    expect(router.currentRoute.value.query).toMatchObject({ projectId: '7', projectCode: 'orders', methodKeyword: '取消', methodPage: '1' })
    expect(router.currentRoute.value.query).not.toHaveProperty('methodEnabled')
    expect(apis.filters.value).toEqual({ keyword: '/orders', method: 'GET' })
    expect(apis.page.value).toBe(2)
  })

  it('changing page size resets only that tab page, and browser navigation restores committed filters', async () => {
    const { router, methods, apis } = await setup({ methodPage: '4', apiPage: '6' })
    await methods.changeSize(10)
    expect(methods.page.value).toBe(1)
    expect(methods.size.value).toBe(10)
    expect(apis.page.value).toBe(6)
    await router.push({ path: '/catalog', query: { methodKeyword: '从地址栏恢复', methodPage: '2' } })
    await flushPromises()
    expect(methods.draft.keyword).toBe('从地址栏恢复')
    expect(methods.page.value).toBe(2)
  })

  it.each(['-1', '0', 'bad', '1.5', '9007199254740992'])('normalizes invalid paging %s before making a request', async invalid => {
    const { methods } = await setup({ methodPage: invalid, methodSize: invalid })
    expect(methods.page.value).toBe(1)
    expect(methods.size.value).toBe(20)
  })

  it('caps an oversized page and migrates only the legacy catalog fields into its namespace', async () => {
    const { methods } = await setup({ methodSize: '500' })
    expect(methods.size.value).toBe(100)
    expect(legacyCatalogQuery({ projectId: '7', keyword: '/orders', method: 'GET', page: '3', methodKeyword: '查询' }, 'api'))
      .toEqual({ projectId: '7', apiKeyword: '/orders', apiMethod: 'GET', apiPage: '3', methodKeyword: '查询' })
  })
})
