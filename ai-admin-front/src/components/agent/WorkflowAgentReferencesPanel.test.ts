import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import WorkflowAgentReferencesPanel from './WorkflowAgentReferencesPanel.vue'
import { listWorkflowAgentReferences, publishLatestAgentWorkflowVersions } from '@/api/agentWorkflowFreshness'

const access = vi.hoisted(() => ({ publishProjects: ['orders'] as string[] }))

vi.mock('@/api/agentWorkflowFreshness', async (original) => ({
  ...await original<typeof import('@/api/agentWorkflowFreshness')>(),
  listWorkflowAgentReferences: vi.fn(),
  publishLatestAgentWorkflowVersions: vi.fn(),
}))
vi.mock('@/auth/platformAccess', () => ({
  PLATFORM_PERMISSION_AGENT_PUBLISH: 'agent:publish',
  hasPlatformResourcePermission: (_grants: unknown, _permission: string, _scope: string, _workspace: unknown,
    projectCode?: string | null) => access.publishProjects.includes(String(projectCode)),
}))
vi.mock('@/auth/platformSession', () => ({ platformSessionUser: { value: { permissionGrants: [] } } }))
vi.mock('element-plus', async (original) => ({
  ...await original<typeof import('element-plus')>(),
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

const staleAgent = {
  agentId: 'agent-1', agentName: '订单助手', keySlug: 'orders-agent', projectCode: 'orders', agentEnabled: true,
  activeConfigVersionId: 5, activeConfigVersionNo: 3, toolName: 'orders', pinnedWorkflowVersionId: 10,
  pinnedWorkflowVersion: '1.0.1', latestWorkflowVersionId: 20, latestWorkflowVersion: '1.0.2', stale: true,
}
const currentAgent = {
  ...staleAgent, agentId: 'agent-2', agentName: '客服助手', keySlug: 'service-agent', activeConfigVersionId: 7,
  pinnedWorkflowVersionId: 20, pinnedWorkflowVersion: '1.0.2', stale: false,
}

describe('WorkflowAgentReferencesPanel', () => {
  let wrapper: VueWrapper

  beforeEach(() => {
    vi.clearAllMocks()
    access.publishProjects = ['orders']
    vi.mocked(listWorkflowAgentReferences).mockResolvedValue({ data: {
      workflowId: 'wf-orders', latestWorkflowVersionId: 20, latestWorkflowVersion: '1.0.2', staleCount: 1,
      hiddenAgentCount: 2, agents: [staleAgent, currentAgent],
    } } as any)
  })
  afterEach(() => wrapper?.unmount())

  async function mountPanel() {
    wrapper = mount(WorkflowAgentReferencesPanel, {
      props: { workflowId: 'wf-orders' },
      global: { plugins: [ElementPlus], stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } } },
    })
    await flushPromises()
  }

  const row = (agentId: string) => wrapper.find(`[data-agent-id="${agentId}"]`)
  const updateButton = (agentId: string) => row(agentId).findAll('button').find(button => button.text() === '更新并发布')

  it('lists pinned versions, flags stale Agents and links to each Agent', async () => {
    await mountPanel()
    expect(listWorkflowAgentReferences).toHaveBeenCalledWith('wf-orders', { errorFeedback: 'local' })
    expect(wrapper.text()).toContain('1 个 Agent 仍在使用旧版本，发布 Agent 后才会用到 v1.0.2')
    expect(row('agent-1').text()).toContain('仍在使用 v1.0.1，发布 Agent 后才会用到新版本 v1.0.2')
    expect(row('agent-1').text()).toContain('线上配置 v3')
    expect(row('agent-1').find('a').attributes('href')).toBe('/agent/agent-1/edit')
    expect(row('agent-2').text()).toContain('已是当前发布版本')
    expect(updateButton('agent-2')).toBeUndefined()
    expect(wrapper.text()).toContain('另有 2 个你无权查看的 Agent')
  })

  it('hides the one-click update without Agent publish permission', async () => {
    access.publishProjects = []
    await mountPanel()
    expect(updateButton('agent-1')).toBeUndefined()
  })

  it('confirms, publishes with the seen ACTIVE config id and reloads', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)
    vi.mocked(publishLatestAgentWorkflowVersions).mockResolvedValue({ data: {
      published: true, config: { id: 8, versionNo: 4 }, updatedTools: [],
    } } as any)
    await mountPanel()
    await updateButton('agent-1')!.trigger('click')
    await flushPromises()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('线上配置 v3'),
      '更新 Workflow 版本并发布 Agent', expect.objectContaining({ confirmButtonText: '更新并发布' }))
    expect(publishLatestAgentWorkflowVersions).toHaveBeenCalledWith('agent-1', 5, 'admin-ui', { errorFeedback: 'local' })
    expect(ElMessage.success).toHaveBeenCalledWith(expect.stringContaining('已发布配置 v4'))
    expect(listWorkflowAgentReferences).toHaveBeenCalledTimes(2)
  })

  it('does nothing when the confirmation is cancelled', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValue('cancel')
    await mountPanel()
    await updateButton('agent-1')!.trigger('click')
    await flushPromises()
    expect(publishLatestAgentWorkflowVersions).not.toHaveBeenCalled()
  })

  it('explains a concurrent Agent publish (409) and refreshes the list', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as any)
    vi.mocked(publishLatestAgentWorkflowVersions).mockRejectedValue({ response: { status: 409, data: {} } })
    await mountPanel()
    await updateButton('agent-1')!.trigger('click')
    await flushPromises()
    expect(ElMessage.error).toHaveBeenCalledWith('Agent 的线上配置刚被其他人更新，请刷新后再试')
    expect(listWorkflowAgentReferences).toHaveBeenCalledTimes(2)
  })

  it('shows an empty state when no published Agent uses the Workflow', async () => {
    vi.mocked(listWorkflowAgentReferences).mockResolvedValue({ data: {
      workflowId: 'wf-orders', staleCount: 0, agents: [],
    } } as any)
    await mountPanel()
    expect(wrapper.text()).toContain('暂无已发布的 Agent 使用此 Workflow')
  })
})
