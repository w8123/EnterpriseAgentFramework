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
import { ElMessageBox } from 'element-plus'
import AgentList from './AgentList.vue'
import {
  acknowledgePlatformExplorationNotice,
  markPlatformSessionAnonymous,
  markPlatformSessionAuthenticated,
  type PlatformSessionView,
} from '@/auth/platformSession'
import {
  PLATFORM_PERMISSION_AGENT_READ,
  PLATFORM_PERMISSION_AGENT_WRITE,
} from '@/auth/platformAccess'
import { providePageProjectScope } from '@/composables/usePageProjectScope'
import { useProjectStore } from '@/store/project'
import type { ScanProject } from '@/types/scanProject'
import type { Agent, AgentStatistics } from '@/types/workflow'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
  listAgents: vi.fn(),
  getAgentStatistics: vi.fn(),
  updateAgent: vi.fn(),
  deleteAgent: vi.fn(),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjects: mocks.getScanProjects,
}))

vi.mock('@/api/workflow', () => ({
  listAgents: (...args: unknown[]) => mocks.listAgents(...args),
  getAgentStatistics: (...args: unknown[]) => mocks.getAgentStatistics(...args),
  updateAgent: (...args: unknown[]) => mocks.updateAgent(...args),
  deleteAgent: (...args: unknown[]) => mocks.deleteAgent(...args),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
    success: vi.fn(),
    warning: vi.fn(),
  },
  ElMessageBox: {
    confirm: vi.fn(),
  },
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

function createAgent(id: string, projectId: number, projectCode: string): Agent {
  return {
    id,
    projectId,
    projectCode,
    keySlug: `key-${id}`,
    name: `Agent ${id}`,
    description: `${id} description`,
    visibility: 'PROJECT',
    enabled: true,
    configStatus: 'NONE',
    workflowToolCount: 0,
  }
}

function createSession(
  sessionId: string,
  projectCodes: string[],
  writableProjectCodes: string[] = [],
  globalRead = false,
): PlatformSessionView {
  const permissionGrants = projectCodes.map((projectCode) => ({
    permissionCode: PLATFORM_PERMISSION_AGENT_READ,
    scopeType: 'PROJECT',
    scopeValue: projectCode,
  }))
  if (globalRead) {
    permissionGrants.push({
      permissionCode: PLATFORM_PERMISSION_AGENT_READ,
      scopeType: 'GLOBAL',
      scopeValue: '*',
    })
  }
  writableProjectCodes.forEach((projectCode) => {
    permissionGrants.push({
      permissionCode: PLATFORM_PERMISSION_AGENT_WRITE,
      scopeType: 'PROJECT',
      scopeValue: projectCode,
    })
  })
  return {
    sessionId,
    expiresAt: '2030-01-01T00:00:00.000Z',
    principal: {
      userId: 7,
      username: 'agent-list-test',
      permissionGrants,
    },
  }
}

function createStatistics(totalAgents: number): AgentStatistics {
  return {
    totalAgents,
    enabledAgents: totalAgents,
    workflowToolAgents: 0,
    activeWorkflowTools: 0,
  }
}

const passthroughStub = (name: string, className = name) => defineComponent({
  name,
  setup: (_props, { slots }) => () => h('div', { class: className }, slots.default?.()),
})

// Review the visible named slots as well as component state. The previous
// default-slot-only stubs omitted both the header counts and summary feedback.
const namedSlotsStub = (name: string) => defineComponent({
  name,
  setup: (_props, { slots }) => () => h('div', Object.values(slots).flatMap((slot) => slot?.() || [])),
})

const DataTableShellStub = defineComponent({
  name: 'DataTableShell',
  props: { empty: Boolean, loading: Boolean },
  setup: (props, { slots }) => () => h('section', { class: 'data-table-shell-stub' }, [
    slots.toolbar?.(),
    slots.default?.(),
    props.empty && !props.loading ? slots.empty?.() : null,
    slots.pagination?.(),
  ]),
})

const FilterBarStub = defineComponent({
  name: 'FilterBar',
  setup: (_props, { slots }) => () => h('form', { class: 'filter-bar-stub' }, [
    slots.default?.(),
    slots.actions?.(),
  ]),
})

const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup: (props, { slots, expose }) => {
    expose({ doLayout: () => {} })
    return () => h('div', { class: 'el-table-stub' }, props.data.length
      ? slots.default?.()
      : slots.empty?.())
  },
})

const ElButtonStub = defineComponent({
  name: 'ElButton',
  inheritAttrs: false,
  emits: ['click'],
  setup: (_props, { attrs, slots, emit }) => () => h(
    'button',
    { ...attrs, onClick: () => emit('click') },
    slots.default?.(),
  ),
})

const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  setup: () => () => h('div', { class: 'el-table-column-stub' }),
})

const wrappers: VueWrapper[] = []
let sessionSequence = 0

function createAgentRouter() {
  const routes: RouteRecordRaw[] = [
    {
      path: '/agent',
      name: 'AgentList',
      component: AgentList,
      meta: {
        projectScope: {
          permission: PLATFORM_PERMISSION_AGENT_READ,
          resourceLabel: 'Agent',
          requiresProjectCode: false,
        },
      },
    },
    {
      path: '/agent/new/edit',
      name: 'AgentCreate',
      component: defineComponent({ setup: () => () => h('div', 'agent create') }),
    },
  ]
  return createRouter({ history: createMemoryHistory(), routes })
}

function authenticate(
  projectCodes: string[],
  writableProjectCodes: string[] = [],
  globalRead = false,
) {
  sessionSequence += 1
  markPlatformSessionAuthenticated(createSession(
    `agent-list-${sessionSequence}`,
    projectCodes,
    writableProjectCodes,
    globalRead,
  ))
  expect(acknowledgePlatformExplorationNotice()).toBe(true)
}

async function mountAgentList(path: string, pinia = createPinia()) {
  setActivePinia(pinia)
  const router = createAgentRouter()
  await router.push(path)
  await router.isReady()
  const Host = defineComponent({
    name: 'AgentListTestHost',
    setup: () => {
      providePageProjectScope()
      return () => h(RouterView)
    },
  })
  const wrapper = mount(Host, {
    global: {
      plugins: [pinia, router],
      stubs: {
        WorkbenchPage: passthroughStub('WorkbenchPage', 'workbench-page-stub'),
        CollapsibleHeaderRegion: namedSlotsStub('CollapsibleHeaderRegion'),
        PageHeader: namedSlotsStub('PageHeader'),
        MetricStrip: passthroughStub('MetricStrip', 'metric-strip-stub'),
        ViewToggle: passthroughStub('ViewToggle', 'view-toggle-stub'),
        FilterBar: FilterBarStub,
        DataTableShell: DataTableShellStub,
        ElButton: ElButtonStub,
        ElTable: ElTableStub,
        ElTableColumn: ElTableColumnStub,
        ElTag: passthroughStub('ElTag'),
        ElInput: passthroughStub('ElInput'),
        ElSelect: passthroughStub('ElSelect'),
        ElOption: passthroughStub('ElOption'),
        ElSwitch: passthroughStub('ElSwitch'),
        ElTooltip: passthroughStub('ElTooltip'),
        ElPagination: passthroughStub('ElPagination'),
        ElEmpty: passthroughStub('ElEmpty'),
        ElIcon: passthroughStub('ElIcon'),
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  await nextTick()
  await flushPromises()
  return { wrapper, router, agentWrapper: wrapper.findComponent(AgentList) }
}

async function settleAgentList() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}

describe('AgentList project scope integration', () => {
  beforeEach(() => {
    markPlatformSessionAnonymous(false)
    localStorage.clear()
    sessionStorage.clear()
    vi.clearAllMocks()
    mocks.getScanProjects.mockResolvedValue({ data: [] })
    mocks.listAgents.mockResolvedValue({ data: [] })
    mocks.getAgentStatistics.mockResolvedValue({ data: createStatistics(0) })
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    markPlatformSessionAnonymous(false)
    localStorage.clear()
    sessionStorage.clear()
  })

  it('uses the confirmed project scope for both list and statistics requests', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate(['project-a', 'project-b'])
    const pinia = createPinia()
    setActivePinia(pinia)
    useProjectStore().selectCurrentProject(projectA.id)
    expect(useProjectStore().currentProjectId).toBe(projectA.id)
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })
    mocks.listAgents.mockResolvedValue({ data: [createAgent('agent-b', 2, 'project-b')] })
    mocks.getAgentStatistics.mockResolvedValue({ data: createStatistics(1) })

    const mounted = await mountAgentList('/agent?projectId=2', pinia)

    expect(mocks.listAgents).toHaveBeenCalledTimes(1)
    expect(mocks.getAgentStatistics).toHaveBeenCalledTimes(1)
    expect(mocks.listAgents).toHaveBeenCalledWith({
      projectId: 2,
      projectCode: 'project-b',
    })
    expect(mocks.getAgentStatistics).toHaveBeenCalledWith({
      projectId: 2,
      projectCode: 'project-b',
    })
    expect(mounted.agentWrapper.vm).toHaveProperty('agents', [createAgent('agent-b', 2, 'project-b')])
    expect(mounted.wrapper.find('.agent-scope-summary').exists()).toBe(false)
    expect(mounted.wrapper.find('.filter-bar-stub').exists()).toBe(true)
  })

  it('does not request agent data while the address is unresolved or blocked', async () => {
    const projectA = createProject(1, 'project-a')
    authenticate(['project-a'])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA] })

    await mountAgentList('/agent?projectId=999')

    expect(mocks.listAgents).not.toHaveBeenCalled()
    expect(mocks.getAgentStatistics).not.toHaveBeenCalled()
  })

  it('requires an explicit project for a restricted user instead of choosing the first readable project', async () => {
    authenticate(['project-a'])
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a')] })

    const mounted = await mountAgentList('/agent')

    expect(mocks.listAgents).not.toHaveBeenCalled()
    expect(mocks.getAgentStatistics).not.toHaveBeenCalled()
    expect(mounted.wrapper.find('.agent-scope-panel').exists()).toBe(true)
    expect(mounted.wrapper.find('.filter-bar-stub').exists()).toBe(false)
    expect(mounted.wrapper.find('.el-table-column-stub').exists()).toBe(false)
    expect(mounted.wrapper.text()).toContain('范围未确认')
    expect(mounted.wrapper.text()).not.toContain('范围：范围未确认')
    expect(mounted.wrapper.text()).toContain('请选择项目')
  })

  it('does not fall back to another writable project when the confirmed project is read-only', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate(['project-a', 'project-b'], ['project-a'])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })

    const mounted = await mountAgentList('/agent?projectId=2')
    const agentVm = mounted.agentWrapper.vm as unknown as {
      canCreateAgent: boolean
      handleCreate: () => void
    }

    expect(agentVm.canCreateAgent).toBe(false)
    agentVm.handleCreate()
    await nextTick()
    expect(mounted.router.currentRoute.value.fullPath).toBe('/agent?projectId=2')
  })

  it('passes an explicit all scope to the create route without preselecting a writable project', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate(['project-a', 'project-b'], ['project-a'], true)
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })

    const mounted = await mountAgentList('/agent?scope=all')
    const agentVm = mounted.agentWrapper.vm as unknown as {
      canCreateAgent: boolean
      handleCreate: () => void
    }

    expect(agentVm.canCreateAgent).toBe(true)
    agentVm.handleCreate()
    await mounted.router.isReady()
    await flushPromises()
    expect(mounted.router.currentRoute.value.fullPath).toBe('/agent/new/edit?scope=all')
  })

  it('keeps URL, requests and filters aligned across back-forward navigation and reset', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    const agentA = createAgent('agent-a', 1, 'project-a')
    const agentB = createAgent('agent-b', 2, 'project-b')
    authenticate(['project-a', 'project-b'])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })
    mocks.listAgents.mockImplementation((params: { projectId?: number }) => Promise.resolve({
      data: [params.projectId === 2 ? agentB : agentA],
    }))
    mocks.getAgentStatistics.mockImplementation((params: { projectId?: number }) => Promise.resolve({
      data: createStatistics(params.projectId === 2 ? 1 : 1),
    }))

    const mounted = await mountAgentList('/agent?projectId=1')
    expect(mocks.listAgents).toHaveBeenLastCalledWith({ projectId: 1, projectCode: 'project-a' })

    await mounted.router.push('/agent?projectId=2')
    await settleAgentList()
    expect(mounted.router.currentRoute.value.query.projectId).toBe('2')
    expect(mocks.listAgents).toHaveBeenLastCalledWith({ projectId: 2, projectCode: 'project-b' })

    await mounted.router.back()
    await settleAgentList()
    expect(mounted.router.currentRoute.value.query.projectId).toBe('1')
    expect(mocks.listAgents).toHaveBeenLastCalledWith({ projectId: 1, projectCode: 'project-a' })

    await mounted.router.forward()
    await settleAgentList()
    expect(mounted.router.currentRoute.value.query.projectId).toBe('2')
    expect(mocks.listAgents).toHaveBeenLastCalledWith({ projectId: 2, projectCode: 'project-b' })

    await mounted.router.back()
    await settleAgentList()
    const agentVm = mounted.agentWrapper.vm as unknown as {
      filterKeyword: string
      filterEnabled: boolean | ''
      handleQuery: () => void
      resetFilters: () => void
    }
    agentVm.filterKeyword = 'agent'
    agentVm.filterEnabled = false
    agentVm.handleQuery()
    await settleAgentList()
    expect(mounted.router.currentRoute.value.query.projectId).toBe('1')
    expect(mocks.listAgents).toHaveBeenLastCalledWith({ projectId: 1, projectCode: 'project-a' })

    agentVm.resetFilters()
    await settleAgentList()
    expect(agentVm.filterKeyword).toBe('')
    expect(agentVm.filterEnabled).toBe('')
    expect(mounted.router.currentRoute.value.query.projectId).toBe('1')
    expect(mocks.listAgents).toHaveBeenLastCalledWith({ projectId: 1, projectCode: 'project-a' })
  })

  it('ignores stale A to B to A responses and keeps the final confirmed scope data', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate(['project-a', 'project-b'])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })

    type AgentRequest = {
      params: { projectId?: number; projectCode?: string }
      resolve: (value: { data: Agent[] }) => void
    }
    type StatisticsRequest = {
      params: { projectId?: number; projectCode?: string }
      resolve: (value: { data: AgentStatistics }) => void
    }
    const pendingListResponses: AgentRequest[] = []
    const statisticsRequests: StatisticsRequest[] = []
    mocks.listAgents.mockImplementation((params) => new Promise((resolve) => {
      pendingListResponses.push({ params, resolve })
    }))
    mocks.getAgentStatistics.mockImplementation((params) => new Promise((resolve) => {
      statisticsRequests.push({ params, resolve })
    }))

    const mounted = await mountAgentList('/agent?projectId=1')
    expect(pendingListResponses).toHaveLength(1)
    expect(statisticsRequests).toHaveLength(1)

    await mounted.router.push('/agent?projectId=2')
    await nextTick()
    await flushPromises()
    await mounted.router.push('/agent?projectId=1')
    await nextTick()
    await flushPromises()

    expect(pendingListResponses.map((request) => request.params)).toEqual([
      { projectId: 1, projectCode: 'project-a' },
      { projectId: 2, projectCode: 'project-b' },
      { projectId: 1, projectCode: 'project-a' },
    ])
    expect(statisticsRequests.map((request) => request.params)).toEqual([
      { projectId: 1, projectCode: 'project-a' },
      { projectId: 2, projectCode: 'project-b' },
      { projectId: 1, projectCode: 'project-a' },
    ])

    const finalAgent = createAgent('agent-a-final', 1, 'project-a')
    pendingListResponses[2].resolve({ data: [finalAgent] })
    statisticsRequests[2].resolve({ data: createStatistics(1) })
    await flushPromises()
    await nextTick()
    await flushPromises()

    pendingListResponses[1].resolve({ data: [createAgent('agent-b-stale', 2, 'project-b')] })
    statisticsRequests[1].resolve({ data: createStatistics(99) })
    pendingListResponses[0].resolve({ data: [createAgent('agent-a-stale', 1, 'project-a')] })
    statisticsRequests[0].resolve({ data: createStatistics(98) })
    await flushPromises()
    await nextTick()
    await flushPromises()

    expect(mounted.agentWrapper.vm).toHaveProperty('agents', [finalAgent])
    expect(mounted.agentWrapper.vm).toHaveProperty('statistics', createStatistics(1))
  })

  it('keeps the final A request loading when the first A and middle B requests finish first', async () => {
    const projectA = createProject(1, 'project-a')
    const projectB = createProject(2, 'project-b')
    authenticate(['project-a', 'project-b'])
    mocks.getScanProjects.mockResolvedValue({ data: [projectA, projectB] })

    type PendingAgentRequest = {
      params: { projectId?: number; projectCode?: string }
      resolve: (value: { data: Agent[] }) => void
      reject: (reason?: unknown) => void
    }
    type PendingStatisticsRequest = {
      params: { projectId?: number; projectCode?: string }
      resolve: (value: { data: AgentStatistics }) => void
      reject: (reason?: unknown) => void
    }
    const pendingListResponses: PendingAgentRequest[] = []
    const statisticsRequests: PendingStatisticsRequest[] = []
    mocks.listAgents.mockImplementation((params) => new Promise<{ data: Agent[] }>((resolve, reject) => {
      pendingListResponses.push({ params, resolve, reject })
    }))
    mocks.getAgentStatistics.mockImplementation((params) => new Promise<{ data: AgentStatistics }>((resolve, reject) => {
      statisticsRequests.push({ params, resolve, reject })
    }))

    const mounted = await mountAgentList('/agent?projectId=1')
    await mounted.router.push('/agent?projectId=2')
    await settleAgentList()
    await mounted.router.push('/agent?projectId=1')
    await settleAgentList()
    expect(pendingListResponses).toHaveLength(3)
    expect(statisticsRequests).toHaveLength(3)

    pendingListResponses[0].resolve({ data: [createAgent('agent-a-stale', 1, 'project-a')] })
    statisticsRequests[0].resolve({ data: createStatistics(11) })
    pendingListResponses[1].reject(new Error('stale B list failure'))
    statisticsRequests[1].reject(new Error('stale B statistics failure'))
    await settleAgentList()

    const agentVm = mounted.agentWrapper.vm as unknown as {
      loading: boolean
      agents: Agent[]
      statistics: AgentStatistics | null
    }
    expect(agentVm.loading).toBe(true)
    expect(agentVm.agents).toEqual([])
    expect(agentVm.statistics).toBeNull()

    const finalAgent = createAgent('agent-a-final', 1, 'project-a')
    pendingListResponses[2].resolve({ data: [finalAgent] })
    statisticsRequests[2].resolve({ data: createStatistics(1) })
    await settleAgentList()

    expect(agentVm.loading).toBe(false)
    expect(agentVm.agents).toEqual([finalAgent])
    expect(agentVm.statistics).toEqual(createStatistics(1))
  })

  it('does not present a zero-result pagination count before the project scope is confirmed', async () => {
    authenticate(['project-a'])
    let resolveCatalog!: (response: { data: ScanProject[] }) => void
    mocks.getScanProjects.mockReturnValueOnce(new Promise((resolve) => { resolveCatalog = resolve }))

    const mounted = await mountAgentList('/agent?projectId=1')
    const pendingText = mounted.wrapper.text()
    const listCallsBeforeConfirmation = mocks.listAgents.mock.calls.length
    resolveCatalog({ data: [createProject(1, 'project-a')] })
    await flushPromises()

    expect(listCallsBeforeConfirmation).toBe(0)
    expect(pendingText).toContain('正在确认项目范围')
    expect(pendingText).not.toMatch(/共\s*0\s*条/)
  })

  it('does not display a zero Agent count while the first asset requests are still pending', async () => {
    authenticate(['project-a'])
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a')] })
    let resolveAgents!: (response: { data: Agent[] }) => void
    let resolveStatistics!: (response: { data: AgentStatistics }) => void
    mocks.listAgents.mockReturnValueOnce(new Promise((resolve) => { resolveAgents = resolve }))
    mocks.getAgentStatistics.mockReturnValueOnce(new Promise((resolve) => { resolveStatistics = resolve }))

    const mounted = await mountAgentList('/agent?projectId=1')
    const loadingText = mounted.wrapper.text()
    resolveAgents({ data: [createAgent('agent-a', 1, 'project-a')] })
    resolveStatistics({ data: createStatistics(1) })
    await flushPromises()

    expect(loadingText).not.toMatch(/当前\s*0\s*个/)
  })

  it('does not claim that the Agent list loaded when both list and statistics failed', async () => {
    authenticate(['project-a'])
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a')] })
    mocks.listAgents.mockRejectedValueOnce(new Error('list unavailable'))
    mocks.getAgentStatistics.mockRejectedValueOnce(new Error('statistics unavailable'))

    const mounted = await mountAgentList('/agent?projectId=1')

    expect(mounted.wrapper.text()).toContain('智能体列表加载失败')
    expect(mounted.wrapper.text()).not.toContain('Agent 列表已加载')
    expect(mounted.wrapper.text()).not.toMatch(/当前\s*0\s*个/)
  })

  it('shows a real empty result and zero only after the list succeeds with an empty array', async () => {
    authenticate(['project-a'])
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a')] })
    mocks.listAgents.mockResolvedValue({ data: [] })
    mocks.getAgentStatistics.mockResolvedValue({ data: createStatistics(0) })

    const mounted = await mountAgentList('/agent?projectId=1')

    expect(mounted.wrapper.text()).toContain('当前 0 个')
    expect(mounted.wrapper.text()).toContain('共 0 条')
    expect(mounted.wrapper.text()).toContain('创建第一个 Agent')
  })

  it('keeps finite list metrics when statistics fail, with a distinct statistics warning', async () => {
    authenticate(['project-a'])
    const agent = createAgent('agent-a', 1, 'project-a')
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a')] })
    mocks.listAgents.mockResolvedValue({ data: [agent] })
    mocks.getAgentStatistics.mockRejectedValueOnce(new Error('statistics unavailable'))

    const mounted = await mountAgentList('/agent?projectId=1')

    expect(mounted.wrapper.text()).toContain('当前范围的 Agent 列表已加载，统计暂时不可用')
    expect(mounted.wrapper.text()).not.toContain('智能体列表加载失败')
    expect(mounted.wrapper.text()).toContain('当前 1 个')
    expect(mounted.wrapper.text()).toContain('共 1 条')
  })

  it('does not submit a pending delete confirmation after the page is disposed', async () => {
    authenticate(['project-a'], ['project-a'])
    mocks.getScanProjects.mockResolvedValue({ data: [createProject(1, 'project-a')] })
    mocks.listAgents.mockResolvedValue({ data: [createAgent('agent-a', 1, 'project-a')] })
    let resolveConfirmation!: () => void
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(new Promise<never>((resolve) => {
      resolveConfirmation = () => resolve(undefined as never)
    }))
    const mounted = await mountAgentList('/agent?projectId=1')
    const agentVm = mounted.agentWrapper.vm as unknown as { handleDelete: (id: string) => Promise<void> }

    const action = agentVm.handleDelete('agent-a')
    mounted.wrapper.unmount()
    resolveConfirmation()
    await action

    expect(mocks.deleteAgent).not.toHaveBeenCalled()
  })

  it('does not submit a delete confirmed under A after the address changes to B', async () => {
    authenticate(['project-a', 'project-b'], ['project-a', 'project-b'])
    mocks.getScanProjects.mockResolvedValue({
      data: [createProject(1, 'project-a'), createProject(2, 'project-b')],
    })
    mocks.listAgents.mockResolvedValue({ data: [createAgent('agent-a', 1, 'project-a')] })
    mocks.getAgentStatistics.mockResolvedValue({ data: createStatistics(1) })
    let resolveConfirmation!: () => void
    vi.mocked(ElMessageBox.confirm).mockReturnValueOnce(new Promise<never>((resolve) => {
      resolveConfirmation = () => resolve(undefined as never)
    }))

    const mounted = await mountAgentList('/agent?projectId=1')
    const agentVm = mounted.agentWrapper.vm as unknown as {
      handleDelete: (id: string) => Promise<void>
    }
    const pendingDelete = agentVm.handleDelete('agent-a')

    await mounted.router.push('/agent?projectId=2')
    await settleAgentList()
    resolveConfirmation()
    await pendingDelete

    expect(mounted.router.currentRoute.value.query.projectId).toBe('2')
    expect(mocks.deleteAgent).not.toHaveBeenCalled()
  })
})
