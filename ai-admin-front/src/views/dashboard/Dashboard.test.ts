import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Dashboard from './Dashboard.vue'

const mocks = vi.hoisted(() => ({
  getScanProjects: vi.fn(),
  listAgents: vi.fn(),
  getAgentStatistics: vi.fn(),
  listWorkflows: vi.fn(),
  getRecentRunOps: vi.fn(),
  controlGet: vi.fn(),
  push: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjects: () => mocks.getScanProjects(),
}))

vi.mock('@/api/workflow', () => ({
  listAgents: () => mocks.listAgents(),
  getAgentStatistics: () => mocks.getAgentStatistics(),
  listWorkflows: () => mocks.listWorkflows(),
}))

vi.mock('@/api/runops', () => ({
  getRecentRunOps: (...args: unknown[]) => mocks.getRecentRunOps(...args),
}))

vi.mock('@/api/request', () => ({
  controlRequest: { get: (...args: unknown[]) => mocks.controlGet(...args) },
}))

const wrappers: VueWrapper[] = []

function mockAllReady() {
  const now = new Date().toISOString()
  mocks.getScanProjects.mockResolvedValue({
    data: [
      {
        id: 1,
        name: '订单系统',
        projectCode: 'ORDER',
        baseUrl: '',
        contextPath: '',
        scanPath: '',
        scanType: 'auto',
        toolCount: 12,
        status: 'scanned',
      },
    ],
  })
  mocks.listAgents.mockResolvedValue({
    data: [
      {
        id: 'agent-1',
        keySlug: 'order-agent',
        name: '订单助手',
        projectCode: 'ORDER',
        enabled: true,
        activeConfigVersionId: 8,
      },
    ],
  })
  mocks.listWorkflows.mockResolvedValue({
    data: [{ id: 'w1', keySlug: 'order-sync', name: '订单同步', status: 'ACTIVE' }],
  })
  mocks.getAgentStatistics.mockResolvedValue({
    data: { totalAgents: 2, enabledAgents: 1, workflowToolAgents: 1, activeWorkflowTools: 1 },
  })
  mocks.getRecentRunOps.mockResolvedValue({
    data: [
      {
        traceId: 'trace-1',
        runType: 'AGENT',
        entryType: 'API',
        status: 'COMPLETED',
        projectCode: 'ORDER',
        userId: 'user-1',
        agentId: 'agent-1',
        agentName: '订单助手',
        startedAt: now,
        latencyMs: 1200,
        tokenCost: 128,
      },
      {
        traceId: 'trace-2',
        runType: 'AGENT',
        entryType: 'API',
        status: 'FAILED',
        projectCode: 'ORDER',
        userId: 'user-2',
        agentId: 'agent-1',
        agentName: '订单助手',
        startedAt: now,
        tokenCost: 0,
      },
    ],
  })
  mocks.controlGet.mockResolvedValue({
    data: {
      services: {
        runtime: { status: 'UP' },
        capability: { status: 'UP' },
        model: { status: 'UP' },
        knowledge: { status: 'DOWN' },
      },
    },
  })
}

function mountDashboard() {
  const wrapper = mount(Dashboard)
  wrappers.push(wrapper)
  return wrapper
}

describe('Dashboard 智能体运营中心', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  })

  it('以真实接口样本渲染 KPI、排行、项目入口和 Token 分布', async () => {
    mockAllReady()
    const wrapper = mountDashboard()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('智能体运营中心')
    expect(text).toContain('平台级 AI 运营态势与业务价值总览')
    expect(text).toContain('Agent 总数')
    expect(text).toContain('已启用 Agent')
    expect(text).toContain('这是配置状态，不冒充“正在工作”')
    expect(text).toContain('订单助手')
    expect(text).toContain('订单系统')
    expect(text).toContain('Token 使用分布')
    expect(text).toContain('非零记录 1/2 条')
    expect(text).toContain('50.0%')
    expect(wrapper.findAll('.ops-metric')).toHaveLength(6)
    expect(wrapper.findAll('.ops-metric__signal')).toHaveLength(0)
    expect(wrapper.get('.ops-scope-chip').element.tagName).toBe('SPAN')
    expect(wrapper.find('.ops-filter-button').exists()).toBe(false)
    expect(mocks.getRecentRunOps).toHaveBeenCalledWith({ days: 1, limit: 100 })
    expect(text).not.toContain('38.6M')
    expect(text).not.toContain('高频问题')
  })

  it('单个接口失败时保留其他区块并明确标记部分不可用', async () => {
    mockAllReady()
    mocks.getRecentRunOps.mockRejectedValue(new Error('network down'))
    const wrapper = mountDashboard()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('部分数据暂不可用：运行数据')
    expect(text).toContain('运行态势暂不可用')
    expect(text).toContain('Agent 排名暂不可用')
    expect(text).toContain('Agent 总数')
    expect(text).toContain('2')
  })

  it('空数据时展示真实空态，不生成排行、趋势或 Token 数字', async () => {
    mocks.getScanProjects.mockResolvedValue({ data: [] })
    mocks.listAgents.mockResolvedValue({ data: [] })
    mocks.listWorkflows.mockResolvedValue({ data: [] })
    mocks.getAgentStatistics.mockResolvedValue({
      data: { totalAgents: 0, enabledAgents: 0, workflowToolAgents: 0, activeWorkflowTools: 0 },
    })
    mocks.getRecentRunOps.mockResolvedValue({ data: [] })
    mocks.controlGet.mockResolvedValue({
      data: {
        services: {
          runtime: { status: 'UP' },
          capability: { status: 'UP' },
          model: { status: 'UP' },
          knowledge: { status: 'UP' },
        },
      },
    })
    const wrapper = mountDashboard()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('尚未接入业务系统')
    expect(text).toContain('当前范围暂无 Agent 运行样本')
    expect(text).toContain('当前时间范围暂无运行样本')
    expect(text).toContain('暂无可确认的 Token 记录')
    expect(text).toContain('尚未创建 Workflow')
  })

  it('畸形 payload 进入错误态而不是伪装成空数据', async () => {
    mockAllReady()
    mocks.getScanProjects.mockResolvedValue({ data: { records: [] } })
    mocks.getAgentStatistics.mockResolvedValue({ data: { totalAgents: 'many' } })
    const wrapper = mountDashboard()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('部分数据暂不可用：业务系统、Agent 统计')
    expect(text).toContain('暂不可用')
    expect(text).not.toContain('尚未接入业务系统')
  })

  it('Token 为 0 时明确显示未知采集状态而不是 0 消耗', async () => {
    mockAllReady()
    mocks.getRecentRunOps.mockResolvedValue({
      data: [
        {
          traceId: 'trace-zero',
          runType: 'AGENT',
          entryType: 'API',
          status: 'COMPLETED',
          projectCode: 'ORDER',
          agentId: 'agent-1',
          tokenCost: 0,
          startedAt: new Date().toISOString(),
        },
      ],
    })
    const wrapper = mountDashboard()
    await flushPromises()

    expect(wrapper.text()).toContain('非零记录 0/1 条')
    expect(wrapper.text()).toContain('暂无可确认的 Token 记录')
  })

  it('切换时间范围只按新范围重载运行样本', async () => {
    mockAllReady()
    const wrapper = mountDashboard()
    await flushPromises()
    await wrapper.findAll('.ops-range button')[1].trigger('click')
    await flushPromises()

    expect(mocks.getRecentRunOps).toHaveBeenLastCalledWith({ days: 7, limit: 100 })
    expect(mocks.getScanProjects).toHaveBeenCalledTimes(1)
  })

  it('后台刷新时保留上一轮可用数据，避免整页骨架闪烁', async () => {
    mockAllReady()
    const wrapper = mountDashboard()
    await flushPromises()

    let resolveRuns!: (value: { data: unknown[] }) => void
    mocks.getRecentRunOps.mockImplementationOnce(
      () => new Promise((resolve) => { resolveRuns = resolve }),
    )
    await wrapper.find('.ops-icon-btn').trigger('click')

    expect(wrapper.text()).toContain('非零记录 1/2 条')
    expect(wrapper.findAll('.ops-metric__skeleton')).toHaveLength(0)

    resolveRuns({ data: [] })
    await flushPromises()
  })

  it('Agent 与项目卡片钻取到现有真实路由', async () => {
    mockAllReady()
    const wrapper = mountDashboard()
    await flushPromises()

    await wrapper.find('.agent-ranking tbody tr').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith('/agent/agent-1/edit')
    await wrapper.find('.project-ops-card').trigger('click')
    expect(mocks.push).toHaveBeenCalledWith('/registry/projects/1')
  })
})
