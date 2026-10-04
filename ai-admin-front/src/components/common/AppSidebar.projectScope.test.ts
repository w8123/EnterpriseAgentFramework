import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, nextTick } from 'vue'
import {
  createMemoryHistory,
  createRouter,
  RouterView,
  type RouteRecordRaw,
} from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppSidebar from './AppSidebar.vue'
import {
  acknowledgePlatformExplorationNotice,
  markPlatformSessionAnonymous,
  markPlatformSessionAuthenticated,
  type PlatformSessionView,
} from '@/auth/platformSession'
import { PLATFORM_PERMISSION_AGENT_READ } from '@/auth/platformAccess'
import { providePageProjectScope } from '@/composables/usePageProjectScope'
import { useProjectStore } from '@/store/project'
import type { ScanProject } from '@/types/scanProject'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
  logoutPlatform: vi.fn(),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjects: mocks.getScanProjects,
}))

vi.mock('@/api/platformAuth', () => ({
  logoutPlatform: mocks.logoutPlatform,
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

function createSession(sessionId: string, projectCodes: string[]): PlatformSessionView {
  return {
    sessionId,
    expiresAt: '2030-01-01T00:00:00.000Z',
    principal: {
      userId: 7,
      username: 'sidebar-scope-test',
      permissions: ['*'],
      permissionGrants: projectCodes.map((projectCode) => ({
        permissionCode: PLATFORM_PERMISSION_AGENT_READ,
        scopeType: 'PROJECT',
        scopeValue: projectCode,
      })),
    },
  }
}

const ElSelectStub = defineComponent({
  name: 'ElSelect',
  props: {
    modelValue: { type: [Number, String], default: null },
    loading: Boolean,
    placeholder: { type: String, default: '' },
  },
  setup: (props, { attrs, slots }) => () => h(
    'div',
    { ...attrs, class: ['el-select-stub', attrs.class] },
    [slots.prefix?.(), slots.default?.(), h('span', { class: 'select-state' }, String(props.modelValue ?? ''))],
  ),
})

const ElOptionStub = defineComponent({
  name: 'ElOption',
  props: {
    label: { type: String, default: '' },
    value: { type: [Number, String], default: null },
    disabled: Boolean,
  },
  setup: (props, { slots }) => () => h(
    'div',
    { class: 'el-option-stub', 'data-label': props.label, 'data-value': String(props.value ?? '') },
    slots.default?.() || props.label,
  ),
})

const SlotStub = (name: string) => defineComponent({
  name,
  setup: (_props, { slots }) => () => h('div', { class: `${name}-stub` }, slots.default?.()),
})

const wrappers: VueWrapper[] = []
let sessionSequence = 0

function createAgentRouter() {
  const Probe = defineComponent({ setup: () => () => h('div', 'agent probe') })
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
  ]
  return createRouter({ history: createMemoryHistory(), routes })
}

async function mountSidebar(path: string, pinia = createPinia(), authenticateSession = true) {
  if (authenticateSession) {
    sessionSequence += 1
    markPlatformSessionAuthenticated(createSession(`sidebar-${sessionSequence}`, ['project-a', 'project-b']))
    expect(acknowledgePlatformExplorationNotice()).toBe(true)
  }
  setActivePinia(pinia)
  const router = createAgentRouter()
  await router.push(path)
  await router.isReady()
  const Host = defineComponent({
    name: 'SidebarScopeTestHost',
    setup: () => {
      providePageProjectScope()
      return () => h('main', [h(AppSidebar), h(RouterView)])
    },
  })
  const wrapper = mount(Host, {
    global: {
      plugins: [pinia, router],
      stubs: {
        ElSelect: ElSelectStub,
        ElOption: ElOptionStub,
        ElIcon: SlotStub('ElIcon'),
        ElScrollbar: SlotStub('ElScrollbar'),
        ElMenu: SlotStub('ElMenu'),
        ElMenuItem: SlotStub('ElMenuItem'),
        ElSubMenu: SlotStub('ElSubMenu'),
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  await nextTick()
  await flushPromises()
  return { wrapper, router }
}

describe('AppSidebar page project scope integration', () => {
  beforeEach(() => {
    markPlatformSessionAnonymous(false)
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a'), createProject(2, 'project-b')] })
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    markPlatformSessionAnonymous(false)
    localStorage.clear()
    sessionStorage.clear()
  })

  it('uses the confirmed B address as the sidebar selection while the recent store selection is A', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    sessionSequence += 1
    markPlatformSessionAuthenticated(createSession(`sidebar-${sessionSequence}`, ['project-a', 'project-b']))
    expect(acknowledgePlatformExplorationNotice()).toBe(true)
    useProjectStore().selectCurrentProject(1)
    expect(useProjectStore().currentProjectId).toBe(1)

    const mounted = await mountSidebar('/agent?projectId=2', pinia, false)
    const select = mounted.wrapper.findComponent(ElSelectStub)
    const options = mounted.wrapper.findAllComponents(ElOptionStub)

    expect(select.props('modelValue')).toBe(2)
    expect(select.props('placeholder')).toBe('项目 2')
    expect(mounted.wrapper.get('.sidebar-project-pill').text()).toBe('项目级')
    expect(options.map((option) => option.attributes('data-label'))).toEqual([
      '全部项目',
      '项目 1',
      '项目 2',
    ])
    expect(options[0].props('disabled')).toBe(true)
    expect(mounted.wrapper.text()).toContain('项目 2')
  })
})
