import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { ScanProject } from '@/types/scanProject'
import { useRegistryProjectDetailNavigation } from './useRegistryProjectDetailNavigation'

let wrapper: ReturnType<typeof mount> | undefined
afterEach(() => wrapper?.unmount())

describe('project workspace capability navigation', () => {
  it('redirects a legacy changes bookmark only after its own project is confirmed and keeps the exact change search', async () => {
    const project = ref<ScanProject | null>(null)
    const projectCode = ref('orders')
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/projects/:projectCode', component: { template: '<div />' } },
      { path: '/business-capabilities/:category/:workspace?', component: { template: '<div />' } },
    ] })
    await router.push('/projects/orders?section=source-changes&changeSearch=orders%3Aread&methodKeyword=read')
    await router.isReady()
    let navigation!: ReturnType<typeof useRegistryProjectDetailNavigation>
    wrapper = mount(defineComponent({ setup() { navigation = useRegistryProjectDetailNavigation({ project, projectCode }); return () => null } }),
      { global: { plugins: [router] } })
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/projects/orders')
    project.value = { id: 8, projectCode: 'other' } as ScanProject
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/projects/orders')
    project.value = { id: 7, projectCode: 'orders' } as ScanProject
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/business-capabilities/java-methods/sources')
    expect(router.currentRoute.value.query).toEqual({ projectId: '7', projectCode: 'orders', sourceSection: 'changes',
      changeSearch: 'orders:read', methodKeyword: 'read' })
    expect(navigation.projectSection.value).toBe('overview')

    navigation.goCapabilitySources(); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/business-capabilities/java-methods')
    expect(router.currentRoute.value.query).toEqual({ projectId: '7', projectCode: 'orders' })
    navigation.goCapabilitySync(); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/business-capabilities/java-methods/sources')
    expect(router.currentRoute.value.query.sourceSection).toBe('changes')
  })
})
