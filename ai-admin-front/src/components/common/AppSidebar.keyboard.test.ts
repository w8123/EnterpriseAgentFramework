import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMenuItem, ElSubMenu } from 'element-plus'
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
  markPlatformSessionAuthenticated({ sessionId: 'sidebar-keyboard', expiresAt: '2030-01-01T00:00:00Z',
    principal: { userId: 17, username: 'keyboard-test', permissions, permissionGrants: [] } })
}
async function settle() { await nextTick(); await flushPromises(); await nextTick() }
async function setup(collapsed = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
  ] })
  await router.push('/dashboard')
  const wrapper = mount(AppSidebar, { attachTo: document.body, props: { collapsed, hideProjectPanel: true },
    global: { plugins: [createPinia(), router, ElementPlus] } })
  wrappers.push(wrapper)
  await settle()
  const push = vi.spyOn(router, 'push')
  const leaf = (index: string) => wrapper.findAllComponents(ElMenuItem).find(item => item.props('index') === index)!.element as HTMLElement
  const group = (index: string) => wrapper.findAllComponents(ElSubMenu).find(item => item.props('index') === index)!.element as HTMLElement
  const entry = () => (wrapper.element as HTMLElement).querySelector<HTMLElement>('[role="menuitem"][tabindex="0"]')
  return { wrapper, router, push, leaf, group, entry }
}
async function key(element: HTMLElement, value: string, options: KeyboardEventInit = {}) {
  const event = new KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true, ...options })
  element.dispatchEvent(event)
  await settle()
  return event
}
const focus = () => document.activeElement as HTMLElement

describe('AppSidebar real Element Plus vertical keyboard navigation', () => {
  beforeEach(() => { localStorage.clear(); sessionStorage.clear(); session() })
  afterEach(() => {
    wrappers.splice(0).forEach(wrapper => wrapper.unmount())
    document.body.replaceChildren()
    markPlatformSessionAnonymous(false)
    vi.restoreAllMocks()
  })

  it('has one normal Tab entry, skips presentation groups, and moves without navigation', async () => {
    const { wrapper, leaf, entry, push } = await setup()
    expect(entry()).toBe(leaf('/dashboard'))
    expect(wrapper.element.querySelectorAll('[role="menuitem"][tabindex="0"]')).toHaveLength(1)
    expect(wrapper.get('.el-scrollbar__wrap').attributes('tabindex')).toBe('-1')
    entry()!.focus()
    await key(focus(), 'ArrowDown')
    expect(focus()).toBe(leaf('/registry/projects'))
    await key(focus(), 'ArrowDown')
    await key(focus(), 'ArrowDown')
    expect(focus()).toBe(leaf('/business-methods'))
    await key(focus(), 'ArrowDown')
    expect(focus()).toBe(leaf('/apis'))
    await key(focus(), 'ArrowDown')
    expect(focus()).toBe(leaf('/workflows'))
    await key(focus(), 'ArrowUp')
    expect(focus()).toBe(leaf('/apis'))
    await key(focus(), 'Home')
    expect(focus()).toBe(leaf('/dashboard'))
    expect(push).not.toHaveBeenCalled()
  })

  it.each(['/business-methods', '/apis', '/workflows'])('activates %s once via the existing click route', async target => {
    const { leaf, entry, push, router } = await setup()
    entry()!.focus()
    for (let step = 0; step < 8 && focus() !== leaf(target); step++) await key(focus(), 'ArrowDown')
    expect(focus()).toBe(leaf(target))
    await key(focus(), 'Enter')
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith(target)
    expect(router.currentRoute.value.path).toBe(target)
    await key(focus(), 'Enter', { repeat: true })
    await key(focus(), 'Enter', { isComposing: true })
    await key(focus(), 'Enter', { ctrlKey: true })
    await key(focus(), 'Escape')
    await key(focus(), 'x')
    expect(push).toHaveBeenCalledTimes(1)
  })

  it('opens actual inline children, returns on Left/Escape, and excludes closed children', async () => {
    const { leaf, group, push } = await setup()
    const parent = group('/integration-group')
    parent.focus()
    await key(parent, 'ArrowRight')
    expect(parent.getAttribute('aria-expanded')).toBe('true')
    expect(focus()).toBe(leaf('/api-market'))
    await key(focus(), 'ArrowDown')
    expect(focus()).toBe(leaf('/mcp-hub'))
    await key(focus(), 'ArrowLeft')
    expect(focus()).toBe(parent)
    expect(parent.getAttribute('aria-expanded')).toBe('false')
    await key(parent, 'ArrowDown')
    expect(focus()).toBe(group('/knowledge-group'))
    parent.focus()
    await key(parent, 'Enter')
    expect(parent.getAttribute('aria-expanded')).toBe('true')
    await key(parent, 'ArrowDown')
    await key(focus(), 'Escape')
    expect(focus()).toBe(parent)
    expect(parent.getAttribute('aria-expanded')).toBe('false')
    expect(push).not.toHaveBeenCalled()
  })

  it('uses real teleported collapsed children and leaves Tab untrapped in both directions', async () => {
    const { wrapper, leaf, group, entry, push } = await setup(true)
    entry()!.focus()
    await key(focus(), 'ArrowDown')
    expect(focus()).toBe(leaf('/registry/projects'))
    const parent = group('/integration-group')
    parent.focus()
    await key(parent, 'ArrowRight')
    const child = leaf('/api-market')
    expect(focus()).toBe(child)
    expect(wrapper.element.contains(child)).toBe(false)
    expect(child.closest('.el-popper')?.getAttribute('aria-hidden')).not.toBe('true')
    const tab = await key(child, 'Tab')
    expect(tab.defaultPrevented).toBe(false)
    expect(parent.getAttribute('aria-expanded')).toBe('false')
    expect(focus()).toBe(parent)
    await key(parent, 'ArrowRight')
    const backwards = await key(focus(), 'Tab', { shiftKey: true })
    expect(backwards.defaultPrevented).toBe(false)
    expect(focus()).toBe(parent)
    const outside = document.createElement('button')
    document.body.append(outside)
    outside.focus()
    await settle()
    expect(focus()).toBe(outside)
    expect(push).not.toHaveBeenCalled()
  })

  it('migrates focus on permission removal without retaining removed targets or stealing external focus', async () => {
    const { wrapper, leaf, push } = await setup()
    leaf('/business-methods').focus()
    session(['workflow:read'])
    await settle()
    expect(wrapper.text()).not.toContain('业务方法')
    expect(wrapper.element.contains(focus())).toBe(true)
    expect(focus().getAttribute('tabindex')).toBe('0')
    expect(focus().textContent).toContain('概览')
    await key(focus(), 'ArrowDown')
    await key(focus(), 'ArrowDown')
    expect(focus()).toBe(leaf('/workflows'))
    expect(push).not.toHaveBeenCalled()
    const outside = document.createElement('input')
    document.body.append(outside)
    outside.focus()
    session(['agent:read'])
    await settle()
    expect(focus()).toBe(outside)
    await key(outside, 'Enter')
    expect(push).not.toHaveBeenCalled()
  })

  it('returns hidden child focus after pointer close and collapse changes', async () => {
    const { wrapper, leaf, group, push } = await setup()
    let parent = group('/integration-group')
    parent.focus()
    await key(parent, 'ArrowRight')
    expect(focus()).toBe(leaf('/api-market'))
    parent.querySelector<HTMLElement>('.el-sub-menu__title')!.click()
    await settle()
    expect(focus()).toBe(parent)
    await key(parent, 'ArrowRight')
    await wrapper.setProps({ collapsed: true })
    await settle()
    parent = group('/integration-group') // Element Plus replaces its inline root for popup mode.
    expect(focus()).toBe(parent)
    expect(parent.getAttribute('aria-expanded')).toBe('false')
    await wrapper.setProps({ collapsed: false })
    await settle()
    parent = group('/integration-group')
    expect(focus()).toBe(parent)
    expect(push).not.toHaveBeenCalled()
  })

  it('skips disabled targets and scrolls focus inside the sidebar owner only', async () => {
    const { wrapper, leaf, group, entry, push } = await setup()
    const disabled = leaf('/registry/projects')
    disabled.setAttribute('aria-disabled', 'true')
    entry()!.focus()
    await key(focus(), 'ArrowDown')
    expect(focus()).toBe(leaf('/capability'))
    disabled.focus()
    await key(disabled, 'Enter')
    expect(push).not.toHaveBeenCalled()
    const owner = wrapper.get('.el-scrollbar__wrap').element as HTMLElement
    vi.spyOn(owner, 'getBoundingClientRect').mockReturnValue({ top: 100, bottom: 300 } as DOMRect)
    const last = group('/diagnostics-group')
    vi.spyOn(last.querySelector<HTMLElement>('.el-sub-menu__title')!, 'getBoundingClientRect').mockReturnValue({ top: 350, bottom: 390 } as DOMRect)
    leaf('/dashboard').focus()
    await key(focus(), 'End')
    expect(focus()).toBe(last)
    expect(owner.scrollTop).toBeGreaterThanOrEqual(90)
    expect(document.documentElement.scrollTop).toBe(0)
    const tab = await key(last, 'Tab')
    expect(tab.defaultPrevented).toBe(false)
  })
})
