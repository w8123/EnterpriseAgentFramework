import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppSidebar from './AppSidebar.vue'
import { markPlatformSessionAnonymous, markPlatformSessionAuthenticated } from '@/auth/platformSession'

vi.mock('@/api/scanProject', () => ({ getScanProjects: vi.fn().mockResolvedValue({ data: [] }) }))
vi.mock('@/api/platformAuth', () => ({ logoutPlatform: vi.fn().mockResolvedValue(undefined) }))

const wrappers: VueWrapper[] = []

function session(permissions = ['*']) {
  markPlatformSessionAuthenticated({
    sessionId: 'sidebar-groups-test',
    expiresAt: '2030-01-01T00:00:00Z',
    principal: { userId: 1, username: 'admin', permissions, permissionGrants: [] },
  })
}

async function settle() {
  await nextTick()
  await flushPromises()
  await nextTick()
}

async function setup(initialRoute = '/dashboard', collapsed = false, hideProjectPanel = false) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
    ],
  })
  await router.push(initialRoute)
  const wrapper = mount(AppSidebar, {
    attachTo: document.body,
    props: { collapsed, hideProjectPanel },
    global: { plugins: [createPinia(), router, ElementPlus] },
  })
  wrappers.push(wrapper)
  await settle()
  return { wrapper, router }
}

describe('AppSidebar menu groups collapse behavior', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    session()
  })

  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    document.body.replaceChildren()
    markPlatformSessionAnonymous(false)
    vi.restoreAllMocks()
  })

  it('collapses AI 资产, 运行与治理, and 平台资源 by default on dashboard while keeping 智能化改造 open', async () => {
    const { wrapper } = await setup('/dashboard')
    const groupLabels = wrapper.findAll<HTMLButtonElement>('.menu-group-label')
    const findGroup = (label: string) => groupLabels.find(item => item.text().trim() === label)!

    const transformation = findGroup('智能化改造')
    expect(transformation.attributes('aria-expanded')).toBe('true')
    expect(transformation.find('.menu-group-arrow').exists()).toBe(false)
    expect(wrapper.find('[data-sidebar-index="/dashboard"]').exists()).toBe(true)
    expect(wrapper.find('[data-sidebar-index="/getting-started"]').exists()).toBe(true)
    expect(wrapper.find('[data-sidebar-index="/registry/projects"]').exists()).toBe(true)

    for (const label of ['AI 资产', '运行与治理', '平台资源']) {
      const button = findGroup(label)
      expect(button, `group ${label} should exist`).toBeDefined()
      expect(button.attributes('aria-expanded')).toBe('false')
      expect(button.find('.menu-group-arrow').exists()).toBe(true)
    }

    // 子项在默认折叠状态下不呈现
    expect(wrapper.find('[data-sidebar-index="/business-capabilities"]').exists()).toBe(false)
    expect(wrapper.find('[data-sidebar-index="/workflows"]').exists()).toBe(false)
    expect(wrapper.find('[data-sidebar-index="/agent"]').exists()).toBe(false)
    expect(wrapper.find('[data-sidebar-index="/automations"]').exists()).toBe(false)
  })

  it('toggles group collapse state when clicking the group header button', async () => {
    const { wrapper } = await setup('/dashboard')
    const findGroup = (label: string) => wrapper.findAll<HTMLButtonElement>('.menu-group-label')
      .find(item => item.text().trim() === label)!

    // 点击「AI 资产」展开
    await findGroup('AI 资产').trigger('click')
    await settle()
    expect(findGroup('AI 资产').attributes('aria-expanded')).toBe('true')
    expect(findGroup('AI 资产').find('.menu-group-arrow').exists()).toBe(false)
    expect(wrapper.find('[data-sidebar-index="/business-capabilities"]').exists()).toBe(true)
    expect(wrapper.find('[data-sidebar-index="/workflows"]').exists()).toBe(true)

    // 再次点击「AI 资产」折叠
    await findGroup('AI 资产').trigger('click')
    await settle()
    expect(findGroup('AI 资产').attributes('aria-expanded')).toBe('false')
    expect(findGroup('AI 资产').find('.menu-group-arrow').exists()).toBe(true)
    expect(wrapper.find('[data-sidebar-index="/business-capabilities"]').exists()).toBe(false)
  })

  it('automatically keeps active group expanded when visiting a sub-route directly', async () => {
    // 直接进入属于「AI 资产」分组的 /workflows 页面
    const { wrapper } = await setup('/workflows')
    const findGroup = (label: string) => wrapper.findAll<HTMLButtonElement>('.menu-group-label')
      .find(item => item.text().trim() === label)!

    // 「AI 资产」包含当前活跃项，保持展开
    expect(findGroup('AI 资产').attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('[data-sidebar-index="/workflows"]').exists()).toBe(true)

    // 其他默认折叠组依然折叠
    expect(findGroup('运行与治理').attributes('aria-expanded')).toBe('false')
    expect(findGroup('平台资源').attributes('aria-expanded')).toBe('false')
  })

  it('hides the project scope panel when hideProjectPanel is true', async () => {
    const hidden = await setup('/dashboard', false, true)
    expect(hidden.wrapper.find('.sidebar-project-panel').exists()).toBe(false)

    const visible = await setup('/agent', false, false)
    expect(visible.wrapper.find('.sidebar-project-panel').exists()).toBe(true)
  })

  it('labels the resource section as 平台资源 instead of 参考 in developer workspace', async () => {
    const { wrapper } = await setup('/dashboard', false, true)
    const vm = wrapper.vm as any
    vm.setWorkspace('developer')
    await settle()

    const groups = wrapper.findAll<HTMLButtonElement>('.menu-group-label').map(item => item.text().trim())
    expect(groups).toContain('平台资源')
    expect(groups).not.toContain('参考')
  })
})
