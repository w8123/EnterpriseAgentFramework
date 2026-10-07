import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import Dashboard from './Dashboard.vue'
import { defaultConsoleLayout } from './widgetRegistry'

const mocks = vi.hoisted(() => ({
  projects: vi.fn(), agents: vi.fn(), workflows: vi.fn(), stats: vi.fn(), runs: vi.fn(), health: vi.fn(),
  push: vi.fn(), setPrincipal: vi.fn(), setSession: vi.fn(),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: mocks.push }) }))
vi.mock('@/api/scanProject', () => ({ getScanProjects: (...args: unknown[]) => mocks.projects(...args) }))
vi.mock('@/api/workflow', () => ({
  listAgents: (...args: unknown[]) => mocks.agents(...args),
  listWorkflows: (...args: unknown[]) => mocks.workflows(...args),
  getAgentStatistics: (...args: unknown[]) => mocks.stats(...args),
}))
vi.mock('@/api/runops', () => ({ getRecentRunOps: (...args: unknown[]) => mocks.runs(...args) }))
vi.mock('@/api/request', () => ({ controlRequest: { get: (...args: unknown[]) => mocks.health(...args) } }))
vi.mock('@/auth/platformSession', async () => {
  const { computed, ref } = await import('vue')
  const session = ref({ sessionId: 'dashboard-session', principal: {} as Record<string, unknown> })
  mocks.setPrincipal.mockImplementation(principal => { session.value = { ...session.value, principal } })
  mocks.setSession.mockImplementation(sessionId => { session.value = { ...session.value, sessionId } })
  return { platformSessionUser: computed(() => session.value.principal), platformSessionId: computed(() => session.value.sessionId) }
})

const wrappers: VueWrapper[] = []
const forbidden = () => ({ response: { status: 403 } })
const projectPrincipal = (permissions = ['platform:read', 'workflow:read']) => ({
  userId: 55, username: 'finite-project', permissions,
  permissionGrants: permissions.map(permissionCode => ({ permissionCode, scopeType: 'PROJECT', scopeValue: 'NEW-PROJECT' })),
})
function ready() {
  mocks.projects.mockResolvedValue({ data: [] })
  mocks.agents.mockResolvedValue({ data: [] })
  mocks.workflows.mockResolvedValue({ data: [] })
  mocks.stats.mockResolvedValue({ data: { totalAgents: 0, enabledAgents: 0, workflowToolAgents: 0, activeWorkflowTools: 0 } })
  mocks.runs.mockResolvedValue({ data: [] })
  mocks.health.mockResolvedValue({ data: { services: Object.fromEntries(['runtime', 'capability', 'knowledge', 'model'].map(name => [name, { status: 'UP' }])) } })
}
function deniedOverview() {
  for (const request of [mocks.agents, mocks.workflows, mocks.stats, mocks.runs]) request.mockRejectedValue(forbidden())
}
function populated() {
  ready()
  mocks.projects.mockResolvedValue({ data: [{ id: 91, name: '授权订单系统', projectCode: 'ALLOWED', status: 'scanned' }] })
  mocks.agents.mockResolvedValue({ data: [{ id: 'allowed-agent', name: '授权订单助手', projectCode: 'ALLOWED', enabled: true, activeConfigVersionId: 8 }] })
  mocks.workflows.mockResolvedValue({ data: [{ id: 'allowed-workflow', name: '授权查询', status: 'ACTIVE' }] })
  mocks.stats.mockResolvedValue({ data: { totalAgents: 2, enabledAgents: 1, workflowToolAgents: 1, activeWorkflowTools: 1 } })
  mocks.runs.mockResolvedValue({ data: [{ traceId: 'allowed-trace', runType: 'AGENT', status: 'COMPLETED', projectCode: 'ALLOWED',
    agentId: 'allowed-agent', agentName: '授权订单助手', userId: 'allowed-user', startedAt: new Date().toISOString(), tokenCost: 128 }] })
}
function includeHealthWidget() {
  localStorage.setItem('reachai.dashboard.consoleLayout.local.v1', JSON.stringify({ ...defaultConsoleLayout,
    items: [...defaultConsoleLayout.items, { instanceId: 'health-test', widgetKey: 'service.data-source-status',
      rect: { x: 0, y: 13, w: 4, h: 3 }, config: {} }] }))
}
function render() {
  const wrapper = mount(Dashboard)
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  localStorage.clear()
  ready()
  mocks.setSession('dashboard-session')
  mocks.setPrincipal(projectPrincipal())
})

describe('Dashboard 部分授权', () => {
  it.each([
    ['agents', 'Agent 列表'], ['stats', 'Agent 统计'], ['runs', '运行数据'],
    ['workflows', 'Workflow'], ['projects', '业务系统'], ['health', '服务健康'],
  ] as const)('仅 %s 403 时保留其他有效200内容，不使整个总览退场', async (domain, label) => {
    populated()
    includeHealthWidget()
    mocks[domain].mockRejectedValue(forbidden())
    const wrapper = render()
    await flushPromises()
    expect(wrapper.find('.dash-layout').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('平台总览不可查看')
    expect(wrapper.text()).toContain(`部分总览数据读取受限：${label}`)
    expect(wrapper.find('.ops-range').exists()).toBe(true)
    if (domain !== 'stats') expect(wrapper.get('[data-grid-item="kpi.agent-inventory#default"] strong').text()).toBe('2')
    if (domain !== 'runs') {
      expect(wrapper.get('[data-grid-item="kpi.business-runs#default"] strong').text()).toBe('1')
      expect(wrapper.get('[data-grid-item="activity.runtime-stream#default"]').text()).toContain('授权订单助手')
    }
    if (domain === 'stats') expect(wrapper.get('[data-grid-item="kpi.agent-inventory#default"]').text()).toContain('读取受限')
    if (domain === 'runs') {
      expect(wrapper.get('[data-grid-item="kpi.business-runs#default"]').text()).toContain('读取受限')
      expect(wrapper.find('[data-grid-item="kpi.business-runs#default"] .ops-metric strong').exists()).toBe(false)
    }
    if (domain === 'agents' || domain === 'projects') {
      expect(wrapper.get('[data-grid-item="projects.operations-cards#default"]').text()).toContain('读取受限')
      expect(wrapper.find('.ops-project-card').exists()).toBe(false)
    }
    if (domain === 'health') {
      expect(wrapper.get('[data-grid-item="health-test"]').text()).toContain('服务数据源状态读取受限')
      expect(wrapper.text()).not.toContain('健康聚合不可用')
    }
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks[domain]).toHaveBeenCalledTimes(1)
    for (const key of ['agents', 'stats', 'runs', 'workflows', 'projects', 'health'] as const) {
      if (key !== domain) expect(mocks[key]).toHaveBeenCalledTimes(2)
    }
  })

  it('允许域保持可见且自动/可见性刷新，403域只在显式刷新时重新核验', async () => {
    populated()
    mocks.agents.mockRejectedValue(forbidden())
    const wrapper = render()
    await flushPromises()
    mocks.stats.mockResolvedValue({ data: { totalAgents: 3, enabledAgents: 2, workflowToolAgents: 1, activeWorkflowTools: 1 } })
    await vi.advanceTimersByTimeAsync(30_000)
    expect(wrapper.get('[data-grid-item="kpi.agent-inventory#default"] strong').text()).toBe('3')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(1)
    for (const request of [mocks.projects, mocks.stats, mocks.workflows, mocks.runs, mocks.health]) expect(request).toHaveBeenCalledTimes(3)
    expect(wrapper.find('.ops-refresh-status').text()).toContain('自动刷新')
    populated()
    await wrapper.find('.ops-icon-btn').trigger('click')
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('读取受限')
    expect(wrapper.get('[data-grid-item="ranking.agent-top#default"]').text()).toContain('授权订单助手')
  })

  it('成功空数据仍是授权内容，多个域403不能把它当成全域无权', async () => {
    mocks.agents.mockRejectedValue(forbidden())
    mocks.workflows.mockRejectedValue(forbidden())
    mocks.stats.mockRejectedValue(forbidden())
    const wrapper = render()
    await flushPromises()
    expect(wrapper.find('.dash-layout').exists()).toBe(true)
    expect(wrapper.get('[data-grid-item="kpi.business-runs#default"] strong').text()).toBe('0')
    expect(wrapper.text()).toContain('当前时间范围暂无运行记录')
    expect(wrapper.text()).not.toContain('平台总览不可查看')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.runs).toHaveBeenCalledTimes(2)
    expect(mocks.stats).toHaveBeenCalledTimes(1)
  })

  it('混合grants下403与503分别说明，已授权域不随故障消失', async () => {
    populated()
    mocks.setPrincipal({ ...projectPrincipal(), permissionGrants: [
      { permissionCode: 'platform:read', scopeType: 'PROJECT', scopeValue: 'NEW-PROJECT' },
      { permissionCode: 'workflow:read', scopeType: 'GLOBAL', scopeValue: '*' },
    ] })
    mocks.agents.mockRejectedValue(forbidden())
    mocks.health.mockRejectedValue({ response: { status: 503 } })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.find('.dash-layout').exists()).toBe(true)
    expect(wrapper.text()).toContain('部分总览数据读取受限：Agent 列表')
    expect(wrapper.text()).toContain('部分数据暂不可用：服务健康')
    expect(wrapper.text()).not.toContain('当前账号仅具项目范围')
    expect(wrapper.get('[data-grid-item="kpi.agent-inventory#default"] strong').text()).toBe('2')
  })

  it('切换时间范围不自动重查已拒绝运行域，显式刷新仍可恢复', async () => {
    populated()
    mocks.runs.mockRejectedValue(forbidden())
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('.ops-range button')[1]!.trigger('click')
    await flushPromises()
    expect(mocks.runs).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-grid-item="kpi.business-runs#default"]').text()).toContain('读取受限')
    populated()
    await wrapper.find('.ops-icon-btn').trigger('click')
    await flushPromises()
    expect(mocks.runs).toHaveBeenCalledTimes(2)
    expect(mocks.runs.mock.calls[1]?.[0]).toMatchObject({ days: 7 })
    expect(wrapper.get('[data-grid-item="kpi.business-runs#default"] strong').text()).toBe('1')
  })

  it.each([false, true])('旧域请求不能覆盖当前范围的允许/拒绝状态：新请求403=%s', async newDenied => {
    populated()
    let completeOld!: (response: { data: unknown[] }) => void
    let rejectOld!: (error: unknown) => void
    mocks.runs.mockImplementationOnce(() => new Promise((resolve, reject) => { completeOld = resolve; rejectOld = reject }))
    const wrapper = render()
    await flushPromises()
    if (newDenied) mocks.runs.mockRejectedValue(forbidden())
    await wrapper.findAll('.ops-range button')[1]!.trigger('click')
    await flushPromises()
    if (newDenied) completeOld({ data: [{ traceId: 'old-range', agentName: '旧范围数据', status: 'COMPLETED' }] })
    else rejectOld(forbidden())
    await flushPromises()
    const card = wrapper.get('[data-grid-item="kpi.business-runs#default"]')
    expect(card.text().includes('读取受限')).toBe(newDenied)
    if (!newDenied) expect(card.get('strong').text()).toBe('1')
    expect(wrapper.text()).not.toContain('旧范围数据')
    expect(wrapper.get('[data-grid-item="kpi.agent-inventory#default"] strong').text()).toBe('2')
    expect(wrapper.find('.dash-layout').exists()).toBe(true)
  })

  it('退出会话后旧范围响应不能恢复内容或更新当前身份的刷新时间', async () => {
    populated()
    const wrapper = render()
    await flushPromises()
    let completeOld!: (response: { data: unknown[] }) => void
    mocks.runs.mockImplementationOnce(() => new Promise(resolve => { completeOld = resolve }))
    await wrapper.findAll('.ops-range button')[1]!.trigger('click')
    mocks.setSession('')
    await flushPromises()
    completeOld({ data: [{ traceId: 'old-session', agentName: '旧身份范围数据', status: 'COMPLETED' }] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧身份范围数据')
    expect(wrapper.get('.ops-last-updated').text()).toBe('等待首次刷新')
  })
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  vi.useRealTimers()
})

describe('Dashboard 真实会话的受限入口', () => {
  it('四类全局403不是接入失败，使用共享菜单授权提供项目任务入口', async () => {
    deniedOverview()
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('当前账号仅具项目范围')
    expect(wrapper.text()).toContain('平台总览不可查看')
    expect(wrapper.text()).not.toContain('尚未接入业务系统')
    expect(wrapper.text()).not.toContain('部分数据暂不可用：Agent')
    const links = wrapper.findAll('.ops-project-entry a')
    expect(links.map(link => link.attributes('href'))).toEqual(['/business-capabilities', '/workflows'])
    expect(links.map(link => link.text())).toEqual(['业务能力', 'Workflow'])
  })

  it('自动轮询与可见性恢复不重试已403的数据，显式刷新可以重新检查', async () => {
    deniedOverview()
    const wrapper = render()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(60_000)
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    for (const request of [mocks.agents, mocks.workflows, mocks.stats, mocks.runs]) expect(request).toHaveBeenCalledTimes(1)
    expect(mocks.health.mock.calls.length).toBeGreaterThan(1)
    await wrapper.find('.ops-icon-btn').trigger('click')
    await flushPromises()
    for (const request of [mocks.agents, mocks.workflows, mocks.stats, mocks.runs]) expect(request).toHaveBeenCalledTimes(2)
  })

  it('权限/会话改变后重查拒绝项，原授权总览仍正常加载及自动刷新', async () => {
    deniedOverview()
    const wrapper = render()
    await flushPromises()
    ready()
    mocks.setPrincipal({ userId: 55, username: 'finite-project', permissions: ['platform:read', 'workflow:read'],
      permissionGrants: [{ permissionCode: 'platform:read', scopeType: 'GLOBAL', scopeValue: '*' }] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('平台总览不可查看')
    expect(wrapper.text()).toContain('Agent 总数')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.agents).toHaveBeenCalledTimes(3)
    mocks.setSession('next-session')
    await flushPromises()
    expect(mocks.agents).toHaveBeenCalledTimes(4)
  })

  it('只给真实可用菜单，没有权限时解释没有可用任务，不假称PROJECT', async () => {
    deniedOverview()
    mocks.setPrincipal({ userId: 55, username: 'no-tasks', permissions: [], permissionGrants: [] })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).not.toContain('当前账号仅具项目范围')
    expect(wrapper.text()).toContain('当前账号没有可用的业务能力或 Workflow 入口')
    expect(wrapper.findAll('.ops-project-entry a')).toHaveLength(0)
  })

  it.each([401, 503, undefined])('非403(%s)保留真实错误与重试，不冒充项目受限', async status => {
    mocks.agents.mockRejectedValue(status ? { response: { status } } : new Error('Network Error'))
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).not.toContain('平台总览不可查看')
    expect(wrapper.text()).toContain('部分数据暂不可用：Agent 列表')
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.agents).toHaveBeenCalledTimes(2)
  })

  it('403与真正后端不可用并存时分别解释，不将后端故障吞成403', async () => {
    deniedOverview()
    mocks.health.mockRejectedValue({ response: { status: 503 } })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('平台总览不可查看')
    expect(wrapper.text()).toContain('部分数据暂不可用：服务健康')
  })

  it('旧会话尚未结束的响应不能覆盖新会话的部分受限与有效200内容', async () => {
    populated()
    let completeOld!: (response: { data: unknown[] }) => void
    mocks.agents.mockImplementationOnce(() => new Promise(resolve => { completeOld = resolve }))
    const wrapper = render()
    await flushPromises()
    mocks.agents.mockRejectedValue(forbidden())
    mocks.setSession('new-actor-session')
    await flushPromises()
    expect(wrapper.text()).toContain('部分总览数据读取受限：Agent 列表')
    expect(wrapper.get('[data-grid-item="kpi.agent-inventory#default"] strong').text()).toBe('2')
    completeOld({ data: [{ id: 'old-agent', name: '旧账号数据' }] })
    await flushPromises()
    expect(wrapper.text()).toContain('部分总览数据读取受限：Agent 列表')
    expect(wrapper.text()).not.toContain('旧账号数据')
    expect(wrapper.find('.ops-icon-btn').attributes('disabled')).toBeUndefined()
  })
})
