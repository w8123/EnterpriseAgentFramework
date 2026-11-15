import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'

const mocks = vi.hoisted(() => ({
  list: vi.fn(), connection: vi.fn(), publications: vi.fn(), access: vi.fn(), providers: vi.fn(),
  overview: vi.fn(), applyAccount: vi.fn(), push: vi.fn(), admin: { value: true },
}))
vi.mock('@/api/httpApi', () => ({ listHttpApis: mocks.list, getHttpApiProjectDefaultConnection: mocks.connection }))
vi.mock('@/api/mcp', () => ({ listMcpPublications: mocks.publications, getMcpEmployeeAccess: mocks.access }))
vi.mock('@/api/platformAuth', () => ({ listBusinessSsoProviders: mocks.providers }))
vi.mock('@/api/projectEval', () => ({ getProjectEvalOverview: mocks.overview, applyPlatformEvalAccount: mocks.applyAccount }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@/auth/platformSession', () => ({ platformSessionUser: { value: { permissions: ['platform:admin'] } } }))
vi.mock('@/auth/platformAccess', () => ({ PLATFORM_PERMISSION_ADMIN: 'platform:admin', hasPlatformPermission: () => mocks.admin.value }))

import ProjectOnboardingGuide from './ProjectOnboardingGuide.vue'

function mountGuide() {
  return mount(ProjectOnboardingGuide, {
    props: { projectId: 2, projectCode: 'mall', canWrite: true },
    global: { plugins: [ElementPlus] },
  })
}

describe('ProjectOnboardingGuide', () => {
  beforeEach(() => {
    Object.values(mocks).forEach(mock => typeof mock === 'function' && mock.mockReset())
    mocks.admin.value = true
    mocks.list.mockImplementation(async ({ sourceStatus }: { sourceStatus?: string }) => ({
      data: { total: sourceStatus ? 13 : 35, records: [{ id: 301 }] } }))
    mocks.connection.mockResolvedValue({ data: { status: 'CONFIGURED' } })
    mocks.publications.mockResolvedValue({ data: { items: [{ id: 7, state: 'PUBLISHED' }, { id: 8, state: 'DRAFT' }] } })
    mocks.access.mockResolvedValue({ data: { projectCode: 'mall', enabled: true, toolCount: 13, hiddenToolCount: 0 } })
    mocks.providers.mockResolvedValue({ data: { providers: [] } })
    mocks.overview.mockResolvedValue({ data: { projects: [{ setting: { projectCode: 'mall', evalUserId: null }, runs: [] }] } })
    mocks.applyAccount.mockResolvedValue({ data: {} })
  })

  it('shows what is done and offers the next step in place', async () => {
    const wrapper = mountGuide()
    await flushPromises()

    expect(wrapper.text()).toContain('3 / 4')
    expect(wrapper.text()).toContain('35 个接口，已接纳 13 个')
    expect(wrapper.text()).toContain('员工和助手现在能用 13 个能力')
    expect(mocks.connection).toHaveBeenCalledWith(301)
    expect(mocks.access).toHaveBeenCalledTimes(1)

    mocks.overview.mockResolvedValue({ data: { projects: [{ setting: { projectCode: 'mall', evalUserId: 'reachai-eval' }, runs: [] }] } })
    const account = wrapper.findAll('button').find(button => button.text() === '使用平台评测账号')
    await account!.trigger('click')
    await flushPromises()
    expect(mocks.applyAccount).toHaveBeenCalledWith('mall')
    expect(wrapper.text()).toContain('评测账号已配好，还没评测过')
  })

  it('opens the existing dialogs through the page and links out for the rest', async () => {
    mocks.connection.mockResolvedValue({ data: { status: 'UNCONFIGURED' } })
    const wrapper = mountGuide()
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === '去配置')!.trigger('click')
    expect(wrapper.emitted('auth')).toHaveLength(1)
    const login = wrapper.findAll('.onboarding__step').find(step => step.text().includes('员工登录入口'))!
    await login.find('button').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith({ path: '/settings/auth-providers' })
  })

  it('folds away once the system is fully connected, and hides the login entry from non-administrators', async () => {
    mocks.admin.value = false
    mocks.overview.mockResolvedValue({ data: { projects: [{ setting: { projectCode: 'mall', evalUserId: 'reachai-eval' },
      runs: [{ passRate: 1 }] }] } })
    const wrapper = mountGuide()
    await flushPromises()

    expect(mocks.providers).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.find('.onboarding__steps').exists()).toBe(false)
    await wrapper.find('.onboarding__toggle').trigger('click')
    expect(wrapper.text()).toContain('需要平台管理员在「认证源」里查看和配置')
  })
})
