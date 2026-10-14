import { createPinia } from 'pinia'
import { nextTick } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MainLayout from './MainLayout.vue'
import { useAppStore } from '@/store/app'

vi.mock('@/composables/useTheme', () => ({ useTheme: () => ({ theme: 'light' }) }))
vi.mock('@/composables/usePageProjectScope', () => ({ providePageProjectScope: vi.fn() }))
vi.mock('@/auth/platformSession', () => ({ isPlatformAuthenticated: false, isPlatformWorkspaceReady: true }))
vi.mock('@/components/common/AppSidebar.vue', () => ({ default: {
  name: 'AppSidebar', props: ['collapsed', 'hideProjectPanel'], emits: ['toggle-collapse'],
  template: '<button data-test="sidebar" @click="$emit(\'toggle-collapse\')">{{ collapsed }}</button>',
} }))
vi.mock('@/components/common/AppBreadcrumb.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/common/AppPageBackground.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/common/ExplorationStageDialog.vue', () => ({ default: { template: '<div />' } }))

let width = 390
const listeners = new Set<(event: MediaQueryListEvent) => void>()
const media = {
  get matches() { return width <= 768 },
  media: '(max-width: 768px)',
  addEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.add(listener)),
  removeEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener)),
}
const wrappers: VueWrapper[] = []
async function resize(nextWidth: number) {
  width = nextWidth
  listeners.forEach(listener => listener({ matches: media.matches } as MediaQueryListEvent))
  await nextTick()
}
async function setup(path = '/capabilities', desktopCollapsed = false) {
  const page = { template: '<div />' }
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/capabilities', component: page, meta: { compactSidebarOnNarrow: true } },
    { path: '/capabilities/detail', component: page, meta: { compactSidebarOnNarrow: true } },
    { path: '/unrelated', component: page },
  ] })
  await router.push(path)
  const pinia = createPinia()
  const app = useAppStore(pinia)
  app.sidebarCollapsed = desktopCollapsed
  const view = mount(MainLayout, { global: { plugins: [pinia, router], stubs: {
    ElContainer: { template: '<div><slot /></div>' },
    ElAside: { props: ['width'], template: '<aside :data-width="width"><slot /></aside>' },
    ElMain: { template: '<main><slot /></main>' },
  } } })
  wrappers.push(view)
  await flushPromises()
  const collapsed = () => view.findComponent({ name: 'AppSidebar' }).props('collapsed')
  const toggle = () => view.get('[data-test="sidebar"]').trigger('click')
  return { view, router, app, collapsed, toggle }
}

beforeEach(() => { width = 390; listeners.clear(); vi.clearAllMocks(); vi.stubGlobal('matchMedia', vi.fn(() => media)) })
afterEach(() => { wrappers.splice(0).forEach(view => view.unmount()); vi.unstubAllGlobals() })

describe('MainLayout optional narrow sidebar', () => {
  it('defaults to collapsed at 390px and lets the user expand and collapse without changing the desktop preference', async () => {
    const { view, app, collapsed, toggle } = await setup()
    expect(collapsed()).toBe(true)
    expect(view.get('aside').attributes('data-width')).toBe('var(--layout-sidebar-collapsed-width)')
    await toggle(); expect(collapsed()).toBe(false)
    await toggle(); expect(collapsed()).toBe(true)
    expect(app.sidebarCollapsed).toBe(false)
  })

  it.each([false, true])('restores desktop preference %s after a narrow-screen manual toggle', async desktopCollapsed => {
    width = 1280
    const { app, collapsed, toggle } = await setup('/capabilities', desktopCollapsed)
    expect(collapsed()).toBe(desktopCollapsed)
    await resize(390); expect(collapsed()).toBe(true)
    await toggle(); expect(collapsed()).toBe(false)
    await resize(1280); expect(collapsed()).toBe(desktopCollapsed)
    expect(app.sidebarCollapsed).toBe(desktopCollapsed)
  })

  it('keeps desktop toggle behavior and activates the narrow default only at 768px or below', async () => {
    width = 769
    const { app, collapsed, toggle } = await setup()
    expect(collapsed()).toBe(false)
    await toggle(); expect(app.sidebarCollapsed).toBe(true)
    await toggle(); expect(app.sidebarCollapsed).toBe(false)
    await resize(768); expect(collapsed()).toBe(true)
    await resize(769); expect(collapsed()).toBe(false)
  })

  it('keeps unrelated routes on their existing preference and preserves manual expansion between opted-in sibling routes', async () => {
    const { router, app, collapsed, toggle } = await setup('/unrelated')
    expect(collapsed()).toBe(false)
    await toggle(); expect(app.sidebarCollapsed).toBe(true)
    await router.push('/capabilities'); expect(collapsed()).toBe(true)
    await toggle(); expect(collapsed()).toBe(false)
    await router.push('/capabilities/detail'); expect(collapsed()).toBe(false)
    await router.push('/unrelated'); expect(collapsed()).toBe(true)
    expect(app.sidebarCollapsed).toBe(true)
    await router.push('/capabilities'); expect(collapsed()).toBe(true)
  })

  it('removes its media-query listener when the shared layout unmounts', async () => {
    const { view } = await setup()
    expect(window.matchMedia).toHaveBeenCalledWith('(max-width: 768px)')
    expect(listeners.size).toBe(1)
    view.unmount()
    expect(listeners.size).toBe(0)
    expect(media.removeEventListener).toHaveBeenCalledWith('change', media.addEventListener.mock.calls[0][1])
  })
})
