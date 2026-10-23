import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import WorkflowStudioVersionsDialog from './WorkflowStudioVersionsDialog.vue'
import { listWorkflowVersions } from '@/api/workflow'
import { listWorkflowAgentReferences } from '@/api/agentWorkflowFreshness'

vi.mock('@/api/workflow', () => ({
  listWorkflowVersions: vi.fn(),
  getWorkflow: vi.fn(),
  rollbackWorkflowVersion: vi.fn(),
}))

vi.mock('@/api/agentWorkflowFreshness', () => ({
  listWorkflowAgentReferences: vi.fn(),
  publishLatestAgentWorkflowVersions: vi.fn(),
  formatWorkflowVersionLabel: vi.fn((v: string) => v),
  agentWorkflowFreshnessFailure: vi.fn(),
}))

vi.mock('@/auth/platformAccess', () => ({
  PLATFORM_PERMISSION_AGENT_PUBLISH: 'agent:publish',
  PLATFORM_PERMISSION_WORKFLOW_PUBLISH: 'workflow:publish',
  hasPlatformResourcePermission: () => true,
}))

vi.mock('@/auth/platformSession', () => ({
  platformSessionUser: { value: { permissionGrants: [] } },
}))

const mockVersions = [
  {
    id: 101,
    workflowId: 'wf-test-1',
    version: '1.0.0',
    status: 'ACTIVE',
    note: '首个稳定版本',
    createdAt: '2026-10-20T10:00:00Z',
    publishedBy: 'admin',
  },
]

const mockAgents = [
  {
    agentId: 'agent-101',
    agentName: '智能客服助理',
    keySlug: 'customer-service-bot',
    projectCode: 'default',
    agentEnabled: true,
    activeConfigVersionId: 1,
    activeConfigVersionNo: 2,
    stale: false,
  },
]

describe('WorkflowStudioVersionsDialog', () => {
  let wrapper: VueWrapper | undefined
  afterEach(() => wrapper?.unmount())

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(listWorkflowVersions).mockResolvedValue({
      data: mockVersions,
    } as any)
    vi.mocked(listWorkflowAgentReferences).mockResolvedValue({
      data: {
        workflowId: 'wf-test-1',
        latestWorkflowVersionId: 101,
        latestWorkflowVersion: '1.0.0',
        staleCount: 0,
        agents: mockAgents,
      },
    } as any)
  })

  it('renders agent cards with link to agent edit detail page', async () => {
    wrapper = mount(WorkflowStudioVersionsDialog, {
      props: {
        modelValue: true,
        workflowId: 'wf-test-1',
        workflowName: '客户咨询流',
      },
      global: {
        plugins: [ElementPlus],
        stubs: {
          ElDialog: {
            props: ['modelValue'],
            template: '<div v-if="modelValue" class="el-dialog-stub"><slot name="header" /><slot /></div>',
          },
          RouterLink: {
            props: ['to', 'target'],
            template: '<a :href="to" :target="target" class="router-link-stub"><slot /></a>',
          },
        },
      },
    })

    await flushPromises()

    // 验证弹窗是否渲染出 Agent 卡片
    const agentCard = wrapper.find('.agent-card')
    expect(agentCard.exists()).toBe(true)

    // 验证 Agent 链接属性指向指定的 Agent 详情编辑页面，且为新标签页打开
    const link = agentCard.find('a.agent-card-info')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/agent/agent-101/edit')
    expect(link.attributes('target')).toBe('_blank')

    // 验证 Agent 名称展示
    const agentName = agentCard.find('.agent-name')
    expect(agentName.text()).toBe('智能客服助理')
  })
})
