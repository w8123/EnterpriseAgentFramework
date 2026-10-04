import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, nextTick } from 'vue'
import {
  createMemoryHistory,
  createRouter,
  RouterView,
  type RouteRecordRaw,
} from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  acknowledgePlatformExplorationNotice,
  markPlatformSessionAnonymous,
  markPlatformSessionAuthenticated,
  type PlatformSessionView,
} from '@/auth/platformSession'
import {
  PLATFORM_PERMISSION_AGENT_READ,
} from '@/auth/platformAccess'
import { useProjectStore } from '@/store/project'
import type { ScanProject } from '@/types/scanProject'
import {
  providePageProjectScope,
  type PageProjectScopeController,
} from './usePageProjectScope'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjects: mocks.getScanProjects,
}))

function createProject(id: number, projectCode: string): ScanProject {
  return {
    id,
    name: `项目 ${id}`,
    projectCode,
    baseUrl: '',
    contextPath: '',
    scanPath: '',
    scanType: 'auto',
    toolCount: 0,
    status: 'created',
  }
}

function createSession(
  sessionId: string,
  permissionGrants: PlatformSessionView['principal']['permissionGrants'] = [],
): PlatformSessionView {
  return {
    sessionId,
    expiresAt: '2030-01-01T00:00:00.000Z',
    principal: {
      userId: 7,
      username: 'project-scope-test',
      permissionGrants,
    },
  }
}

function projectGrant(scopeValue: string) {
  return {
    permissionCode: PLATFORM_PERMISSION_AGENT_READ,
    scopeType: 'PROJECT',
    scopeValue,
  }
}

function globalGrant() {
  return {
    permissionCode: PLATFORM_PERMISSION_AGENT_READ,
    scopeType: 'GLOBAL',
    scopeValue: '*',
  }
}

function createScopeRouter(): ReturnType<typeof createRouter> {
  const Probe = defineComponent({
    name: 'ProjectScopeProbe',
    setup: () => () => h('div', 'scope probe'),
  })
  const routes: RouteRecordRaw[] = [
    {
      path: '/agent',
      name: 'AgentList',
      component: Probe,
      meta: {
        projectScope: {
          permission: PLATFORM_PERMISSION_AGENT_READ,
          resourceLabel: 'Agent',
          requiresProjectCode: false,
        },
      },
    },
    {
      path: '/dashboard',
      name: 'Dashboard',
      component: Probe,
    },
  ]
  return createRouter({ history: createMemoryHistory(), routes })
}

function mountScopeHost(router: ReturnType<typeof createRouter>) {
  let controller!: PageProjectScopeController
  const Host = defineComponent({
    name: 'ProjectScopeHost',
    setup: () => {
      controller = providePageProjectScope()
      return () => h(RouterView)
    },
  })
  const wrapper = mount(Host, {
    global: {
      plugins: [router],
    },
  })
  return { wrapper, controller }
}

async function settleVueAndRouter() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}

describe('page project scope controller', () => {
  let wrapper: ReturnType<typeof mount> | null = null
  let sessionSequence = 0

  beforeEach(() => {
    markPlatformSessionAnonymous(false)
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    mocks.getScanProjects.mockReset()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    markPlatformSessionAnonymous(false)
    localStorage.clear()
    sessionStorage.clear()
  })

  function authenticate(permissionGrants: PlatformSessionView['principal']['permissionGrants']) {
    sessionSequence += 1
    markPlatformSessionAuthenticated(createSession(`scope-${sessionSequence}`, permissionGrants))
    expect(acknowledgePlatformExplorationNotice()).toBe(true)
  }

  it('resolves an explicit project address and exposes the same request params to consumers', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate([projectGrant('project-a'), projectGrant('project-b')])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })

    const router = createScopeRouter()
    await router.push('/agent?projectId=2')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()

    expect(mounted.controller.status.value).toBe('resolved')
    expect(mounted.controller.currentScopeLabel.value).toBe('项目 2')
    expect(mounted.controller.selection.value).toBe(2)
    expect(mounted.controller.requestParams.value).toEqual({
      projectId: 2,
      projectCode: 'project-b',
    })
    expect(mounted.controller.readableProjects.value.map((project) => project.id)).toEqual([1, 2])
  })

  it('normalizes an unspecified address from the captured current project without falling back to the first project', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate([projectGrant('project-a'), projectGrant('project-b')])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })
    const projectStore = useProjectStore()
    projectStore.selectCurrentProject(projectA.id)

    const router = createScopeRouter()
    await router.push('/agent')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()

    expect(router.currentRoute.value.query).toMatchObject({
      projectId: '1',
      projectCode: 'project-a',
    })
    expect(mounted.controller.selection.value).toBe(projectA.id)
    expect(projectStore.currentProjectId).toBe(projectA.id)
    expect(mounted.controller.requestParams.value).toEqual({
      projectId: projectA.id,
      projectCode: 'project-a',
    })
  })

  it('keeps a stale explicit project blocked after the catalog changes instead of selecting another project', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate([projectGrant('project-a'), projectGrant('project-b')])
    mocks.getScanProjects
      .mockResolvedValueOnce({ data: [projectA, projectB] })
      .mockResolvedValueOnce({ data: [projectB] })

    const router = createScopeRouter()
    await router.push('/agent?projectId=1')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()
    expect(mounted.controller.canLoadData.value).toBe(true)

    await mounted.controller.refreshCatalog()
    await settleVueAndRouter()

    expect(router.currentRoute.value.query.projectId).toBe('1')
    expect(mounted.controller.status.value).toBe('blocked')
    expect(mounted.controller.resolution.value).toEqual({
      kind: 'blocked',
      reason: 'project-not-found',
      request: { projectId: 1 },
    })
    expect(mounted.controller.selection.value).toBeNull()
    expect(mounted.controller.requestParams.value).toBeUndefined()
  })

  it('keeps a captured default blocked when that project disappears after address normalization', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate([projectGrant('project-a'), projectGrant('project-b')])
    mocks.getScanProjects
      .mockResolvedValueOnce({ data: [projectA, projectB] })
      .mockResolvedValueOnce({ data: [projectB] })
    const projectStore = useProjectStore()
    projectStore.selectCurrentProject(projectA.id)

    const router = createScopeRouter()
    await router.push('/agent')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()
    expect(router.currentRoute.value.query.projectId).toBe('1')

    await mounted.controller.refreshCatalog()
    await settleVueAndRouter()

    expect(mounted.controller.status.value).toBe('blocked')
    expect(mounted.controller.feedbackMessage.value).toBe('项目不存在或已不可见，请重新选择项目')
    expect(mounted.controller.selection.value).toBeNull()
    expect(mounted.controller.requestParams.value).toBeUndefined()
  })

  it('changes the URL first and synchronizes the store only after navigation confirmation', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate([projectGrant('project-a'), projectGrant('project-b')])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })
    const projectStore = useProjectStore()

    const router = createScopeRouter()
    await router.push('/agent?projectId=1')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()
    expect(projectStore.currentProjectId).toBe(1)

    await expect(mounted.controller.selectScope(2)).resolves.toBe(true)
    await settleVueAndRouter()

    expect(router.currentRoute.value.query).toMatchObject({
      projectId: '2',
      projectCode: 'project-b',
    })
    expect(projectStore.currentProjectId).toBe(2)
    expect(mounted.controller.currentScopeLabel.value).toBe('项目 2')
  })

  it('serializes an authorized global scope as scope=all', async () => {
    const projectA = createProject(1, 'project-a')
    authenticate([globalGrant()])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA] })

    const router = createScopeRouter()
    await router.push('/agent')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()

    expect(router.currentRoute.value.query.scope).toBe('all')
    expect(mounted.controller.selection.value).toBe('all')
    expect(mounted.controller.requestParams.value).toBeUndefined()
  })

  it('blocks a project-less address when the account has neither global nor project access', async () => {
    const projectA = createProject(1, 'project-a')
    authenticate([])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA] })

    const router = createScopeRouter()
    await router.push('/agent')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()

    expect(mounted.controller.status.value).toBe('blocked')
    expect(mounted.controller.feedbackMessage.value).toBe('暂无可读项目，请联系管理员开通访问权限')
    expect(mounted.controller.canLoadData.value).toBe(false)
  })

  it('exposes explicit recovery actions instead of requiring consumers to parse Chinese labels', async () => {
    const projectA = createProject(1, 'project-a')
    authenticate([projectGrant('project-a')])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA] })

    const router = createScopeRouter()
    await router.push('/agent?projectId=1')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()
    expect(mounted.controller.recoveryAction.value).toBe('none')

    setActivePinia(createPinia())
    const blockedRouter = createScopeRouter()
    await blockedRouter.push('/agent')
    await blockedRouter.isReady()
    const blockedMounted = mountScopeHost(blockedRouter)
    await settleVueAndRouter()
    expect(blockedMounted.controller.recoveryAction.value).toBe('select-project')
    blockedMounted.wrapper.unmount()
  })

  it('does not synchronize the store when a sidebar navigation is cancelled', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate([projectGrant('project-a'), projectGrant('project-b')])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })

    const router = createScopeRouter()
    router.beforeEach((to) => to.query.projectId === '2' ? false : true)
    await router.push('/agent?projectId=1')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()
    const projectStore = useProjectStore()
    expect(projectStore.currentProjectId).toBe(1)

    await expect(mounted.controller.selectScope(2)).resolves.toBe(false)
    await settleVueAndRouter()

    expect(router.currentRoute.value.query.projectId).toBe('1')
    expect(projectStore.currentProjectId).toBe(1)
    expect(mounted.controller.feedbackMessage.value).toBe('未能切换项目，仍保留当前范围，请重新选择')
  })

  it('treats selecting the already-confirmed canonical scope as a successful no-op', async () => {
    authenticate([projectGrant('project-a')])
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a')] })
    const router = createScopeRouter()
    await router.push('/agent?projectId=1&projectCode=project-a')
    await router.isReady()
    const mounted = mountScopeHost(router)
    wrapper = mounted.wrapper
    await settleVueAndRouter()

    await expect(mounted.controller.selectScope(1)).resolves.toBe(true)
    await settleVueAndRouter()

    expect(mounted.controller.canLoadData.value).toBe(true)
    expect(mounted.controller.feedbackMessage.value).toBe('')
  })
})
