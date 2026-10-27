import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import A2aHubLayout from './A2aHubLayout.vue'
import A2aHubOverview from './A2aHubOverview.vue'
import A2aPublicationList from './publications/A2aPublicationList.vue'
import A2aRemoteAgentCatalog from './remote-agents/A2aRemoteAgentCatalog.vue'
import A2aTaskCenter from './tasks/A2aTaskCenter.vue'

const mocks = vi.hoisted(() => ({
  overview: vi.fn(), tasks: vi.fn(), publications: vi.fn(), remoteAgents: vi.fn(),
  agents: vi.fn(), profiles: vi.fn(), create: vi.fn(), discover: vi.fn(), taskDetail: vi.fn(),
  permissions: ['*'],
}))
vi.mock('@/api/a2aHub', () => ({
  getA2aHubOverview: mocks.overview, listA2aTasks: mocks.tasks,
  listA2aPublications: mocks.publications, listA2aRemoteAgents: mocks.remoteAgents,
  listA2aTrustProfiles: mocks.profiles, createA2aPublication: mocks.create,
  discoverA2aRemoteAgent: mocks.discover,
  getA2aPublication: vi.fn(), preflightA2aPublication: vi.fn(), publishA2aPublication: vi.fn(),
  resumeA2aPublication: vi.fn(), suspendA2aPublication: vi.fn(),
  approveA2aRemoteAgentRevision: vi.fn(), disableA2aRemoteAgent: vi.fn(),
  enableA2aRemoteAgent: vi.fn(), getA2aRemoteAgent: vi.fn(), listA2aCredentials: vi.fn(),
  rediscoverA2aRemoteAgent: vi.fn(), rejectA2aRemoteAgentRevision: vi.fn(),
  cancelA2aTask: vi.fn(), getA2aArtifactPayload: vi.fn(), getA2aMessagePayload: vi.fn(), getA2aTask: mocks.taskDetail,
}))
vi.mock('@/api/workflow', () => ({ listAgents: mocks.agents, listAgentConfigVersions: vi.fn() }))
vi.mock('@/auth/usePlatformCan', () => ({
  usePlatformCan: () => ({ can: (permission: string) => mocks.permissions.includes('*') || mocks.permissions.includes(permission) }),
}))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

const overview = {
  generatedAt: '2026-10-26T06:00:00Z',
  publications: { published: 3, draft: 2, suspended: 1 },
  remoteAgents: { trusted: 4, quarantined: 1, unhealthy: 0 },
  tasks: { total: 0, completed: 0, failed: 0, working: 0, inputRequired: 0, authRequired: 0, completionRate: 0 },
  transport: { requests: 0, successRate: 0, p95LatencyMs: null },
  governance: { expiringCredentials: 0, failedConformanceRuns: 0 }, attention: [],
}
const slot = { template: '<div><slot /></div>' }
const wrappers: VueWrapper[] = []
async function openHub(path = '/a2a-hub/overview') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/a2a-hub', component: A2aHubLayout, children: [
      { path: 'overview', component: A2aHubOverview },
      { path: 'publications', component: A2aPublicationList },
      { path: 'remote-agents', component: A2aRemoteAgentCatalog },
      { path: 'tasks', component: A2aTaskCenter },
      { path: 'trust', component: { template: '<div>信任工作区</div>' } },
      { path: 'developer', component: { template: '<div>诊断工作区</div>' } },
    ] }],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount({ template: '<router-view />' }, {
    global: {
      plugins: [router], directives: { loading: () => {} },
      stubs: {
        WorkbenchPage: slot,
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        WorkbenchPanel: { props: ['title', 'description'], template: '<section><h2>{{ title }}</h2><p>{{ description }}</p><slot name="actions" /><slot /><slot name="footer" /></section>' },
        LiquidGlassNav: { template: '<nav><slot /></nav>' },
        LiquidGlassDropdown: { name: 'LiquidGlassDropdown', emits: ['command'], template: '<div><slot /><slot name="dropdown" /></div>' },
        LiquidGlassButton: { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
        LiquidGlassControl: slot, LiquidGlassFilterBar: slot, LiquidGlassSelect: true, LiquidGlassInput: true,
        LiquidGlassTable: { template: '<section><slot name="toolbar" /><slot /></section>' },
        LiquidGlassNotice: { props: ['title', 'description'], template: '<aside>{{ title }} {{ description }}</aside>' },
        LiquidGlassEmptyState: { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
        MetricStrip: { props: ['items'], template: '<div class="metrics"><span v-for="item in items" :key="item.key">{{ item.label }} {{ item.value }} {{ item.hint }}</span></div>' },
        AppDialog: { props: ['modelValue', 'title'], template: '<section v-if="modelValue" class="dialog"><h3>{{ title }}</h3><slot /><slot name="footer" /></section>' },
        AppDrawer: true, AgentCardPreview: true, TaskTimeline: true,
        ElIcon: slot, ElDropdownMenu: slot, ElDropdownItem: slot,
        ElForm: slot, ElFormItem: slot, ElSteps: slot, ElStep: true,
        ElSelect: true, ElOption: true, ElInput: true,
        ElButton: slot, ElTag: slot, ElDescriptions: slot, ElDescriptionsItem: slot,
        ElDivider: true, ElCheckbox: true, ElInputNumber: true, ElDatePicker: true, ElTabPane: true,
        ElTable: { name: 'ElTable', props: ['data'], template: '<div class="table" />' }, ElTableColumn: true,
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router }
}
async function click(wrapper: VueWrapper, text: string) {
  const button = wrapper.findAll('button').find(item => item.text() === text)
  expect(button, `missing button: ${text}`).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}

describe('A2A two-direction workspace', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.permissions = ['*']
    mocks.overview.mockResolvedValue({ data: structuredClone(overview) })
    for (const list of [mocks.tasks, mocks.publications, mocks.remoteAgents, mocks.profiles]) list.mockResolvedValue({ data: { items: [], total: 0 } })
    mocks.agents.mockResolvedValue({ data: [] })
  })
  afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

  it('keeps the four main views visible and preserves the active management workspace', async () => {
    const { wrapper, router } = await openHub()
    expect(wrapper.findAll('nav a').map(link => link.text())).toEqual(['总览', '对外发布', '外部 Agent', '协作任务'])
    expect(wrapper.findAll('.connection-count').map(value => value.text())).toEqual(['3', '4'])
    expect(wrapper.find('.metrics').exists()).toBe(false)
    await router.push('/a2a-hub/trust')
    await flushPromises()
    expect(wrapper.find('nav [aria-current="page"]').exists()).toBe(false)
    expect(wrapper.findAll('button').some(button => button.text() === '信任与策略')).toBe(true)
  })

  it('opens the publication wizard once, consumes its entry action, and allows reopening', async () => {
    const { wrapper, router } = await openHub()
    await click(wrapper, '发布 Agent')
    expect(router.currentRoute.value.path).toBe('/a2a-hub/publications')
    expect(router.currentRoute.value.query.action).toBeUndefined()
    expect(wrapper.find('.dialog h3').text()).toBe('对外发布 Agent')
    expect(mocks.agents).toHaveBeenCalledTimes(1)
    await click(wrapper, '取消')
    expect(wrapper.find('.dialog').exists()).toBe(false)
    await click(wrapper, '发布 Agent')
    expect(wrapper.find('.dialog').exists()).toBe(true)
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('keeps the overview quiet when there are no exceptional states', async () => {
    mocks.overview.mockResolvedValue({ data: {
      ...overview,
      publications: { published: 3, draft: 0, suspended: 0 },
      remoteAgents: { trusted: 4, quarantined: 0, unhealthy: 0 },
    } })
    const { wrapper } = await openHub()
    expect(wrapper.findAll('.connection-count').map(value => value.text())).toEqual(['3', '4'])
    expect(wrapper.find('.connection-grid').text()).not.toContain('草稿')
    expect(wrapper.find('.connection-grid').text()).not.toContain('健康异常')
    expect(wrapper.text()).not.toContain('任务成功率')
    expect(wrapper.text()).not.toContain('暂无待处理事项')
  })

  it('opens external onboarding and leaves publication and binding as separate actions', async () => {
    const { wrapper, router } = await openHub()
    await click(wrapper, '接入外部 Agent')
    expect(router.currentRoute.value.path).toBe('/a2a-hub/remote-agents')
    expect(router.currentRoute.value.query.action).toBeUndefined()
    expect(wrapper.find('.dialog h3').text()).toBe('接入外部 Agent')
    expect(wrapper.text()).not.toContain('接入后，还需在本地 Agent 中添加协作对象')
    expect(mocks.discover).not.toHaveBeenCalled()
  })

  it('keeps usage guidance available on demand', async () => {
    const { wrapper } = await openHub('/a2a-hub/remote-agents')
    expect(wrapper.find('.dialog').exists()).toBe(false)
    wrapper.findComponent({ name: 'LiquidGlassDropdown' }).vm.$emit('command', 'help')
    await flushPromises()
    expect(wrapper.find('.dialog h3').text()).toBe('A2A 使用说明')
    expect(wrapper.find('.dialog').text()).toContain('协作配置中绑定已审核版本')
  })

  it('uses the full task identity when opening a row from the compact recent list', async () => {
    const taskId = 'task_653966b340605473490bf4eb7ea1fd860'
    const row = { taskId, direction: 'INBOUND', state: 'TASK_STATE_COMPLETED' }
    mocks.tasks.mockResolvedValue({ data: { items: [row], total: 1 } })
    mocks.taskDetail.mockResolvedValue({ data: { task: row } })
    const { wrapper, router } = await openHub()
    wrapper.findComponent({ name: 'ElTable' }).vm.$emit('row-click', row)
    await flushPromises()
    expect(router.currentRoute.value.query).toMatchObject({ taskId, direction: 'INBOUND' })
    expect(mocks.taskDetail).toHaveBeenCalledWith(taskId, 'INBOUND')
  })

  it('does not turn a failed overview request into zero assets or an all-clear notice', async () => {
    mocks.overview.mockRejectedValue(new Error('unavailable'))
    const { wrapper } = await openHub()
    expect(wrapper.text()).toContain('互联状态加载失败')
    expect(wrapper.findAll('.connection-count').every(value => value.text() === '—')).toBe(true)
    expect(wrapper.find('.all-clear').exists()).toBe(false)
    expect(wrapper.find('.recent-task-panel .table').exists()).toBe(true)
  })

  it('restores all directions when returning to the main task view and follows direction history', async () => {
    const { router } = await openHub('/a2a-hub/tasks?direction=OUTBOUND')
    expect(mocks.tasks).toHaveBeenLastCalledWith(expect.objectContaining({ direction: 'OUTBOUND' }))
    await router.push('/a2a-hub/tasks')
    await flushPromises()
    expect(mocks.tasks).toHaveBeenLastCalledWith(expect.objectContaining({ direction: undefined }))
    await router.push('/a2a-hub/tasks?direction=INBOUND&state=TASK_STATE_AUTH_REQUIRED')
    await flushPromises()
    expect(mocks.tasks).toHaveBeenLastCalledWith(expect.objectContaining({ direction: 'INBOUND', state: 'TASK_STATE_AUTH_REQUIRED' }))
    await router.push('/a2a-hub/tasks?direction=INVALID&state=INVALID')
    await flushPromises()
    expect(mocks.tasks).toHaveBeenLastCalledWith(expect.objectContaining({ direction: undefined, state: undefined }))
  })

  it('keeps connection statistics available when recent tasks fail', async () => {
    mocks.tasks.mockRejectedValue(new Error('unavailable'))
    const { wrapper } = await openHub()
    expect(wrapper.find('.connection-count').text()).toBe('3')
    expect(wrapper.text()).toContain('协作任务加载失败')
  })

  it('shows read-only navigation without offering publication or onboarding writes', async () => {
    mocks.permissions = ['a2a-hub:read']
    const { wrapper, router } = await openHub()
    expect(wrapper.findAll('button').map(button => button.text())).not.toContain('发布 Agent')
    expect(wrapper.findAll('button').map(button => button.text())).not.toContain('接入外部 Agent')
    expect(wrapper.find('.connection-panel a[href="/a2a-hub/publications"]').exists()).toBe(true)
    await router.push('/a2a-hub/publications?action=create')
    await flushPromises()
    expect(wrapper.find('.dialog').exists()).toBe(false)
    expect(mocks.agents).not.toHaveBeenCalled()
    await router.push('/a2a-hub/remote-agents?action=create')
    await flushPromises()
    expect(wrapper.find('.dialog').exists()).toBe(false)
  })
})
