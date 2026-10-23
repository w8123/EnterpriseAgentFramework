import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AgentDebugApprovalPanel from './AgentDebugApprovalPanel.vue'
import AgentDebugExecutionPanel from './AgentDebugExecutionPanel.vue'
import type { PendingHumanApproval, StepRecord } from '@/types/agent'

const global = { plugins: [ElementPlus] }

describe('AgentDebugExecutionPanel', () => {
  const steps = [
    { stepId: 's1', name: 'resolve_agent', detail: 'ok', state: 'completed' },
    { stepId: 's2', name: 'workflow', detail: { tool: 'stock' }, state: 'started' },
  ] as StepRecord[]

  it('renders step names and toggles a step detail', async () => {
    const wrapper = mount(AgentDebugExecutionPanel, {
      global,
      props: {
        steps,
        phase: 'running' as const,
        statusText: 'Supervisor 正在执行',
        planCount: 1,
        replanCount: null,
        workflowCallCount: 1,
        stepState: (index: number) => (index === 0 ? 'complete' as const : 'active' as const),
        selectedIndex: -1,
        'onUpdate:selectedIndex': (value: number) => wrapper.setProps({ selectedIndex: value }),
      },
    })
    expect(wrapper.text()).toContain('识别 Agent 范围')
    expect(wrapper.text()).toContain('调用 Workflow')
    expect(wrapper.text()).toContain('1 / —')
    await wrapper.findAll('.execution-step button')[1].trigger('click')
    expect(wrapper.text()).toContain('{"tool":"stock"}')
  })

  it('shows the empty hint before any step arrives', () => {
    const wrapper = mount(AgentDebugExecutionPanel, {
      global,
      props: {
        steps: [], phase: 'idle' as const, statusText: '等待开始调试', planCount: null, replanCount: null,
        workflowCallCount: null, stepState: () => 'pending' as const,
      },
    })
    expect(wrapper.find('.empty-detail').exists()).toBe(true)
  })
})

describe('AgentDebugApprovalPanel', () => {
  it('lists approvals and forwards refresh and trace clicks', async () => {
    const approvals = [
      { interactionId: 'i1', title: '确认出库', status: 'PENDING', nodeId: 'approve', traceId: 't1', message: '请确认' },
    ] as PendingHumanApproval[]
    const wrapper = mount(AgentDebugApprovalPanel, { global, props: { approvals, loading: false } })
    expect(wrapper.text()).toContain('确认出库')
    await wrapper.find('.approval-meta button').trigger('click')
    expect(wrapper.emitted('open-trace')?.[0]).toEqual(['t1'])
    await wrapper.find('.approval-toolbar button').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('shows the empty state without approvals', () => {
    const wrapper = mount(AgentDebugApprovalPanel, { global, props: { approvals: [], loading: false } })
    expect(wrapper.text()).toContain('暂无待处理审批')
  })
})
