import { flushPromises, shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import AppSidebar from '@/components/common/AppSidebar.vue'

const mocks = vi.hoisted(() => ({
  logoutPlatform: vi.fn(),
  replace: vi.fn(),
  projectStore: undefined as any,
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
  useProjectStore: () => mocks.projectStore,
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
    mocks.projectStore = reactive({
      currentProject: null,
      projects: [{ id: 1, name: '测试项目', projectCode: 'test' }],
      loading: false,
      status: 'ready',
      errorMessage: null,
      hasLoadedSuccessfully: true,
      fetchProjects: vi.fn(),
      selectCurrentProject: vi.fn(),
    })
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

  it('shows explicit loading feedback on the first project catalog load', () => {
    mocks.projectStore = reactive({
      currentProject: null,
      projects: [],
      loading: true,
      status: 'loading',
      errorMessage: null,
      hasLoadedSuccessfully: false,
      fetchProjects: vi.fn(),
      selectCurrentProject: vi.fn(),
    })
    const wrapper = mountSidebar()

    expect(wrapper.get('.sidebar-project-feedback').text()).toContain('正在加载项目列表')
    expect(wrapper.get('.sidebar-project-feedback').attributes('role')).toBe('status')
    expect(mocks.projectStore.fetchProjects).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('distinguishes a successful empty catalog from an unavailable first load', () => {
    mocks.projectStore = reactive({
      currentProject: null,
      projects: [],
      loading: false,
      status: 'ready',
      errorMessage: null,
      hasLoadedSuccessfully: true,
      fetchProjects: vi.fn(),
      selectCurrentProject: vi.fn(),
    })
    const wrapper = mountSidebar()

    expect(wrapper.get('.sidebar-project-feedback').text()).toContain('暂无可用项目')
    expect(wrapper.get('.sidebar-project-feedback').attributes('role')).toBe('status')
    expect(mocks.projectStore.fetchProjects).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('keeps the cached selection visible and exposes a guarded retry after refresh failure', async () => {
    const project = { id: 1, name: '测试项目', projectCode: 'test' }
    let resolveRetry!: () => void
    const projectStore = reactive({
      currentProject: project,
      projects: [project],
      loading: false,
      status: 'error',
      errorMessage: '项目列表加载失败，请稍后重试' as string | null,
      hasLoadedSuccessfully: true,
      fetchProjects: vi.fn(),
      selectCurrentProject: vi.fn(),
    })
    projectStore.fetchProjects.mockImplementationOnce(() => {
      projectStore.status = 'loading'
      projectStore.loading = true
      return new Promise<void>((resolve) => {
        resolveRetry = () => {
          projectStore.status = 'ready'
          projectStore.loading = false
          projectStore.errorMessage = null
          resolve()
        }
      })
    })
    mocks.projectStore = projectStore
    const wrapper = mountSidebar()

    expect(wrapper.get('.sidebar-project-feedback').text()).toContain('项目列表暂时无法刷新')
    expect(wrapper.get('.sidebar-project-feedback').attributes('role')).toBe('alert')
    expect(wrapper.get('.sidebar-project-retry').attributes('aria-label')).toBe('重试加载项目列表')
    expect(wrapper.get('.sidebar-project-pill').text()).toBe('项目级')
    expect((wrapper.vm as any).resolvedCurrentProjectId).toBe(project.id)
    expect(projectStore.fetchProjects).not.toHaveBeenCalled()

    await wrapper.get('.sidebar-project-retry').trigger('click')
    expect(projectStore.fetchProjects).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.sidebar-project-retry').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.sidebar-project-retry').text()).toContain('正在重试')

    await wrapper.get('.sidebar-project-retry').trigger('click')
    expect(projectStore.fetchProjects).toHaveBeenCalledTimes(1)

    resolveRetry()
    await flushPromises()
    expect(wrapper.find('.sidebar-project-retry').exists()).toBe(false)
    expect(wrapper.find('.sidebar-project-feedback').exists()).toBe(false)
    expect(wrapper.get('.sidebar-project-pill').text()).toBe('项目级')
    wrapper.unmount()
  })
})
