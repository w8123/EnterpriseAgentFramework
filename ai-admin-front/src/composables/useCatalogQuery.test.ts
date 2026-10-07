import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'
import { useCatalogQuery } from './useCatalogQuery'
import { legacyCatalogQuery } from '@/views/capability/businessCapabilityRoutes'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

async function setup(query: Record<string, string> = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/catalog', component: { template: '<div />' } },
  ] })
  await router.push({ path: '/catalog', query })
  let methods!: ReturnType<typeof useCatalogQuery<'keyword' | 'enabled'>>
  let apis!: ReturnType<typeof useCatalogQuery<'keyword' | 'method'>>
  wrappers.push(mount(defineComponent({ setup() {
    methods = useCatalogQuery('method', ['keyword', 'enabled'])
    apis = useCatalogQuery('api', ['keyword', 'method'])
    return () => null
  } }), { global: { plugins: [router] } }))
  await flushPromises()
  return { router, methods, apis }
}

describe('catalog URL state', () => {
  it('restores each tab independently and preserves the project and peer filters on apply/reset', async () => {
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
    await methods.reset()
    expect(methods.hasFilters.value).toBe(false)
    expect(router.currentRoute.value.query).not.toHaveProperty('methodKeyword')
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
