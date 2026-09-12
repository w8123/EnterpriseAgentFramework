import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import type { RunDetail, RunSummary } from '@/types/runops'
import RunOpsInvestigationWorkbench from './RunOpsInvestigationWorkbench.vue'

describe('RunOps invocation evidence', () => {
  it('opens the correlated invocation when the same Workflow was called twice', async () => {
    const summary: RunSummary = { traceId: 'resumed', runType: 'AGENT', entryType: 'API', status: 'COMPLETED' }
    const detail: RunDetail = {
      summary, guardDecisions: [], repairHints: [],
      spans: [
        { id: 1, spanId: 'earlier', spanType: 'WORKFLOW_TOOL', nodeId: 'first-call', toolName: 'lookup', status: 'SUCCESS' },
        { id: 2, spanId: 'resumed', spanType: 'WORKFLOW_TOOL', nodeId: 'second-call', toolName: 'lookup', status: 'SUCCESS' },
      ],
      toolCalls: [{ id: 1, toolName: 'lookup', success: false, status: 'SUCCESS', statusSourceSpanId: 'resumed' }],
    }
    const wrapper = mount(RunOpsInvestigationWorkbench, { props: { summary, detail }, global: { plugins: [ElementPlus] } })
    try {
      await wrapper.findAll('.execution-row')[0]!.trigger('click')
      await wrapper.findAll('button').find(button => button.text() === '调用与治理')!.trigger('click')
      await flushPromises()
      const calls = wrapper.findAll('section.data-view').find(view => view.find('h2').text() === '工具调用')!
      await calls.find('.el-table__body-wrapper .el-table__row td').trigger('click')
      expect(wrapper.findAll('.execution-row')[1]!.classes()).toContain('selected')
      expect(wrapper.findAll('.execution-row')[0]!.classes()).not.toContain('selected')
    } finally {
      wrapper.unmount()
    }
  })

  it.each([
    ['SUCCESS', '成功'], ['WAITING_USER', '等待用户交互'], ['UNKNOWN', '状态未确认'], ['FAILED', '失败'],
  ])('renders the current %s outcome in the actual table', async (status, label) => {
    const summary: RunSummary = { traceId: 'resumed', runType: 'AGENT', entryType: 'API', status: 'COMPLETED' }
    const detail: RunDetail = {
      summary, spans: [], guardDecisions: [], snapshot: {}, executionPath: [], repairHints: [],
      toolCalls: [{ id: 1, toolName: 'lookup', success: false, status, statusSourceSpanId: 'workflow-call' }],
    }
    const wrapper = mount(RunOpsInvestigationWorkbench, { props: { summary, detail }, global: { plugins: [ElementPlus] } })
    try {
      await wrapper.findAll('button').find(button => button.text() === '调用与治理')!.trigger('click')
      await flushPromises()
      const row = wrapper.find('.el-table__body-wrapper .el-table__row')
      expect(row.exists()).toBe(true)
      expect(row.find('.el-tag').text()).toBe(label)
      expect(row.text()).toContain('lookup')
    } finally {
      wrapper.unmount()
    }
  })
})
