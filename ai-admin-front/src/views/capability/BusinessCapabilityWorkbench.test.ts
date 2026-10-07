import { defineComponent, onBeforeUnmount, onMounted, computed } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '@/App.vue'
import BusinessCapabilityWorkbench from './BusinessCapabilityWorkbench.vue'
import { BUSINESS_CAPABILITY_ROOT, BUSINESS_METHOD_CATALOG_PATH, HTTP_API_CATALOG_PATH } from './businessCapabilityRoutes'

vi.mock('@/composables/usePageProjectScope', () => ({ usePageProjectScope: () => ({
  currentScopeLabel: computed(() => '订单项目'),
}) }))

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('business capability workbench', () => {
  it('shares one header and mounts only the active catalog, with URL state restored across tabs and Back', async () => {
    const mounted = vi.fn(), unmounted = vi.fn()
    const panel = (kind: string) => defineComponent({ setup() {
      onMounted(() => mounted(kind)); onBeforeUnmount(() => unmounted(kind))
      return () => kind
    } })
    const router = createRouter({ history: createMemoryHistory(), routes: [{
      path: '/', component: { template: '<div><router-view /></div>' }, children: [
        {
          path: BUSINESS_CAPABILITY_ROOT, component: BusinessCapabilityWorkbench,
          meta: { rootViewKey: 'business-capability-workbench' }, children: [
            { path: 'java-methods', name: 'BusinessMethodCatalog', component: panel('java') },
            { path: 'http-apis', name: 'HttpApiCatalog', component: panel('http') },
          ],
        },
      ],
    }] })
    await router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: { projectId: '7', projectCode: 'orders',
      methodKeyword: '查询', methodPage: '2', apiKeyword: '/orders', apiMethod: 'GET' } })
    const wrapper = mount(App, { global: { plugins: [router, ElementPlus] } })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.findAll('[role="tab"]').map(tab => tab.text())).toEqual(['Java 业务方法', 'HTTP API'])
    expect(mounted.mock.calls).toEqual([['java']])
    const header = wrapper.get('h1').element
    const apiTab = wrapper.get('#tab-http-apis').element

    await wrapper.get('#tab-http-apis').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(HTTP_API_CATALOG_PATH)
    expect(wrapper.get('h1').element).toBe(header)
    expect(wrapper.get('#tab-http-apis').element).toBe(apiTab)
    expect(wrapper.get('#tab-http-apis').attributes('aria-selected')).toBe('true')
    expect(mounted.mock.calls).toEqual([['java'], ['http']])
    expect(unmounted).toHaveBeenCalledWith('java')
    expect(router.currentRoute.value.query).toMatchObject({ methodKeyword: '查询', methodPage: '2', apiMethod: 'GET' })

    const returned = new Promise<void>(resolve => { const stop = router.afterEach(() => { stop(); resolve() }) })
    router.back()
    await returned
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(BUSINESS_METHOD_CATALOG_PATH)
    expect(wrapper.get('#tab-java-methods').attributes('aria-selected')).toBe('true')
    expect(unmounted).toHaveBeenCalledWith('http')
    expect(router.currentRoute.value.query.methodPage).toBe('2')

  })
})
