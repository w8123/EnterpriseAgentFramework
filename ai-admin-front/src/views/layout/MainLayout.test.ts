import { createPinia } from 'pinia'
import { defineComponent, nextTick, onUnmounted, ref } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, useRoute } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from '@/App.vue'
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
vi.mock('@/components/common/AppPageBackground.vue', () => ({ default: { template: '<div data-test="background" />' } }))
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
    { path: '/unrelated', component: page, meta: { compactSidebarOnNarrow: false } },
    { path: '/plain', component: page },
    { path: '/no-breadcrumb', component: page, meta: { hideBreadcrumb: true } },
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
  it('collapses routes without explicit opt-in at 390px', async () => {
    const { collapsed } = await setup('/plain')
    expect(collapsed()).toBe(true)
  })

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

  it('keeps opted-out routes on their existing preference and preserves manual expansion between opted-in sibling routes', async () => {
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

  it('hides breadcrumb rail when route meta specifies hideBreadcrumb', async () => {
    const { view, router } = await setup('/plain')
    expect(view.find('.breadcrumb-rail').exists()).toBe(true)
    await router.push('/no-breadcrumb')
    expect(view.find('.breadcrumb-rail').exists()).toBe(false)
  })
})

async function setupNavigation(path = '/overview') {
  const detailLoaded = vi.fn()
  const detailDisposed = vi.fn()
  const detail = defineComponent({
    setup() {
      const id = String(useRoute().params.id)
      const draft = ref('')
      detailLoaded(id)
      onUnmounted(() => detailDisposed(id))
      return { id, draft }
    },
    template: '<section data-test="detail"><span>{{ id }}</span><input v-model="draft" /></section>',
  })
  const workspace = defineComponent({
    setup: () => ({ draft: ref('') }),
    template: '<section data-test="workspace"><input v-model="draft" /><router-view /></section>',
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/login', component: { template: '<div data-test="login">Login</div>' } },
    { path: '/', component: MainLayout, children: [
      { path: 'overview', component: { template: '<section>Overview</section>' } },
      { path: 'assets', component: { template: '<section>Assets</section>' } },
      { path: 'details/:id', component: detail },
      { path: 'studio/:id', component: detail, meta: { layoutMode: 'studio' } },
      { path: 'workspace', component: workspace, meta: { rootViewKey: 'workspace' }, children: [
        { path: 'methods', component: { template: '<p>Methods</p>' } },
        { path: 'apis', component: { template: '<p>APIs</p>' } },
      ] },
    ] },
  ] })
  await router.push(path)
  const view = mount(App, { global: { plugins: [createPinia(), router], stubs: {
    ElContainer: { template: '<div><slot /></div>' },
    ElAside: { template: '<aside><slot /></aside>' },
    ElMain: { template: '<main><slot /></main>' },
  } } })
  wrappers.push(view)
  await flushPromises()
  return { view, router, detailLoaded, detailDisposed }
}

describe('MainLayout route continuity', () => {
  it('replaces only the page and retains the layout, background and narrow sidebar expansion', async () => {
    const { view, router } = await setupNavigation()
    const layout = view.get('.main-layout').element
    const content = view.get('.main-content').element
    const background = view.get('[data-test="background"]').element
    const sidebar = view.findComponent({ name: 'AppSidebar' }).vm
    await view.get('[data-test="sidebar"]').trigger('click')
    expect(view.get('[data-test="sidebar"]').text()).toBe('false')

    await router.push('/assets')
    await flushPromises()

    expect(view.get('.main-layout').element).toBe(layout)
    expect(view.get('.main-content').element).toBe(content)
    expect(view.findComponent({ name: 'AppSidebar' }).vm).toBe(sidebar)
    expect(view.get('[data-test="background"]').element).toBe(background)
    expect(view.get('[data-test="sidebar"]').text()).toBe('false')
    expect(view.get('.main-content').text()).toBe('Assets')
    expect(listeners.size).toBe(1)
  })

  it('loads another detail identity while preserving drafts on query-only navigation', async () => {
    const { view, router, detailLoaded, detailDisposed } = await setupNavigation('/details/first')
    const layout = view.get('.main-layout').element
    await view.get('input').setValue('unsaved edit')
    await router.push('/details/first?tab=settings')
    await flushPromises()
    expect((view.get('input').element as HTMLInputElement).value).toBe('unsaved edit')
    expect(detailLoaded.mock.calls).toEqual([['first']])

    await router.push('/details/second')
    await flushPromises()
    expect(view.get('[data-test="detail"] span').text()).toBe('second')
    expect((view.get('input').element as HTMLInputElement).value).toBe('')
    expect(detailLoaded.mock.calls).toEqual([['first'], ['second']])
    expect(detailDisposed.mock.calls).toEqual([['first']])
    expect(view.get('.main-layout').element).toBe(layout)
  })

  it('preserves nested workbench state while replacing its active tab', async () => {
    const { view, router } = await setupNavigation('/workspace/methods')
    const layout = view.get('.main-layout').element
    const workspace = view.get('[data-test="workspace"]').element
    await view.get('input').setValue('workbench edit')

    await router.push('/workspace/apis')
    await flushPromises()
    expect(view.get('.main-layout').element).toBe(layout)
    expect(view.get('[data-test="workspace"]').element).toBe(workspace)
    expect((view.get('input').element as HTMLInputElement).value).toBe('workbench edit')
    expect(view.get('[data-test="workspace"] p').text()).toBe('APIs')
  })

  it('starts another page at the top without resetting query-only navigation', async () => {
    const { view, router } = await setupNavigation()
    const content = view.get('.main-content').element as HTMLElement
    content.scrollTop = 180
    content.scrollLeft = 25
    await router.push('/overview?filter=active')
    await flushPromises()
    expect(content.scrollTop).toBe(180)

    await router.push('/assets')
    await flushPromises()
    expect(view.get('.main-content').element).toBe(content)
    expect(content.scrollTop).toBe(0)
    expect(content.scrollLeft).toBe(0)
  })

  it('retains the shell through studio mode and disposes it on login navigation', async () => {
    const { view, router } = await setupNavigation('/details/first')
    const layout = view.get('.main-layout').element
    await router.push('/studio/flow')
    await flushPromises()
    expect(view.get('.main-layout').element).toBe(layout)
    expect(view.get('.main-layout').classes()).toContain('layout-studio')
    expect(view.find('aside').exists()).toBe(false)
    expect(view.get('[data-test="detail"] span').text()).toBe('flow')

    await router.push('/assets')
    await flushPromises()
    expect(view.get('.main-layout').element).toBe(layout)
    expect(view.find('aside').exists()).toBe(true)
    await router.push('/login')
    await flushPromises()
    expect(view.find('.main-layout').exists()).toBe(false)
    expect(view.get('[data-test="login"]').text()).toBe('Login')
    expect(listeners.size).toBe(0)
  })
})
