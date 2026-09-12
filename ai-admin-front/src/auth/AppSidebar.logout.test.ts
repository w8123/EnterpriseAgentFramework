import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppSidebar from '@/components/common/AppSidebar.vue'

const mocks = vi.hoisted(() => ({
  logoutPlatform: vi.fn(),
  replace: vi.fn(),
}))

vi.mock('@/api/platformAuth', () => ({
  logoutPlatform: mocks.logoutPlatform,
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/dashboard', meta: {} }),
  useRouter: () => ({ replace: mocks.replace }),
}))

vi.mock('@/composables/useTheme', () => ({
  useTheme: () => ({
    theme: { value: 'light' },
    brand: { value: 'metro-green' },
    brandOptions: [],
    setBrand: vi.fn(),
  }),
}))

vi.mock('@/store/project', () => ({
  useProjectStore: () => ({
    currentProject: null,
    projects: [{ id: 1, name: '测试项目', projectCode: 'test' }],
    loading: false,
    fetchProjects: vi.fn(),
    selectCurrentProject: vi.fn(),
  }),
}))

function mountSidebar() {
  return shallowMount(AppSidebar, {
    global: {
      stubs: {
        ElIcon: true,
        ElMenu: true,
        ElMenuItem: true,
        ElOption: true,
        ElScrollbar: true,
        ElSelect: true,
        ElSubMenu: true,
      },
    },
  })
}

describe('AppSidebar account actions', () => {
  beforeEach(() => {
    mocks.logoutPlatform.mockReset()
    mocks.replace.mockReset()
    mocks.replace.mockResolvedValue(undefined)
  })

  it('shows 退出账号 in the personal panel and returns to login after logout', async () => {
    mocks.logoutPlatform.mockResolvedValue(undefined)
    const wrapper = mountSidebar()

    await wrapper.findAll('.footer-entry')[0].trigger('click')
    const logoutButton = wrapper.get('.profile-action')

    expect(logoutButton.text()).toBe('退出账号')

    await logoutButton.trigger('click')
    await flushPromises()

    expect(mocks.logoutPlatform).toHaveBeenCalledTimes(1)
    expect(mocks.replace).toHaveBeenCalledWith('/login')
    expect(wrapper.find('.sidebar-footer-popover').exists()).toBe(false)
  })

  it('uses the Capability platform positioning and hides unavailable theme modes', async () => {
    const wrapper = mountSidebar()

    expect(wrapper.get('.brand-sub').text()).toBe('企业 AI 能力中台')
    await wrapper.findAll('.footer-entry')[1].trigger('click')

    expect(wrapper.text()).toContain('主题配色')
    expect(wrapper.find('.appearance-mode').exists()).toBe(false)
    expect(wrapper.find('.appearance-mode-button').exists()).toBe(false)
  })

  it('keeps the current session when the server logout request fails', async () => {
    mocks.logoutPlatform.mockRejectedValue(new Error('network unavailable'))
    const wrapper = mountSidebar()

    await wrapper.findAll('.footer-entry')[0].trigger('click')
    await wrapper.get('.profile-action').trigger('click')
    await flushPromises()

    expect(mocks.replace).not.toHaveBeenCalled()
    expect(wrapper.get('.profile-action').attributes('disabled')).toBeUndefined()
  })
})
