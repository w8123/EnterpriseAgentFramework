import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import type { RunDetail, RunSummary } from '@/types/runops'
import RunOpsInvestigationWorkbench from './RunOpsInvestigationWorkbench.vue'

describe('RunOps invocation evidence', () => {
  it('shows safe Console TLS/UNKNOWN facts and actual audits instead of legacy Tool count, without a false jump', async () => {
    const summary: RunSummary = { traceId: 'console-safe', runType: 'CONSOLE_HTTP_API', entryType: 'CONSOLE',
      runtimeType: 'HTTP_API', status: 'FAILED', toolCallCount: 1, errorCode: 'HTTP_API_DISPATCH_UNKNOWN',
      errorMessage: '[rejected:HTTP_API_DISPATCH_UNKNOWN] [transport:code=RUNTIME_HTTP_TLS_FAILED;exceptionClass=SSLHandshakeException;elapsedMs=5008]' }
    const detail: RunDetail = { summary, guardDecisions: [],
      repairHints: ['检查 Supervisor 调度链。', '检查 Workflow Tool 与 Workflow Node。'], toolCalls: [],
      spans: [{ id: 1, spanId: 'root', spanType: 'CONSOLE_CAPABILITY', runtimeType: 'HTTP_API',
        toolName: 'http-api:owned:DEVELOPMENT:market:stable', status: 'ERROR', errorCode: 'HTTP_API_DISPATCH_UNKNOWN',
        errorMessage: '[rejected:HTTP_API_DISPATCH_UNKNOWN]' }] }
    const wrapper = mount(RunOpsInvestigationWorkbench, { props: { summary, detail }, global: { plugins: [ElementPlus] } })
    try {
      expect(wrapper.find('.node-diagnostic').text()).toContain('TLS 握手失败')
      expect(wrapper.find('.node-diagnostic').text()).toContain('结果仍未确认（UNKNOWN）')
      expect(wrapper.find('.workbench-summary').text()).toContain('1 次 API 试调用 · 0 条 Tool 审计')
      expect(wrapper.find('.workbench-summary').text()).toContain('1 个阶段')
      expect(wrapper.findAll('button').some(button => button.text() === '查看工具调用')).toBe(false)
      const steps = wrapper.find('.next-steps').text()
      expect(steps).toContain('同一调用 ID')
      expect(steps).toContain('核对目标状态')
      expect(steps).toContain('不要自动重发')
      expect(steps).toContain('当前 API 连接与调用契约')
      expect(steps).toContain('原调用实际记录的传输分类')
      expect(steps).toContain('不猜测证书或其他根因')
      expect(steps).not.toContain('Supervisor')
      expect(steps).not.toContain('Workflow')
      await wrapper.findAll('button').find(button => button.text() === '调用与治理')!.trigger('click')
      const tools = wrapper.findAll('section.data-view').find(view => view.find('h2').text() === '工具调用')!
      expect(tools.text()).toContain('没有工具调用审计记录')
      expect(tools.text()).toContain('缺少 Tool 审计不能证明请求未派发')
      expect(tools.text()).not.toContain('该运行未调用工具')
    } finally { wrapper.unmount() }
  })

  it.each([false, true])('preserves existing repair hints for a non-Console or deeper failed span: consoleChild=%s', consoleChild => {
    const summary: RunSummary = { traceId: 'existing-hints', runType: consoleChild ? 'CONSOLE_HTTP_API' : 'WORKFLOW',
      entryType: consoleChild ? 'CONSOLE' : 'API', runtimeType: 'HTTP_API', status: 'FAILED' }
    const hints = ['检查原有 Workflow Node 输入与契约。']
    const detail: RunDetail = { summary, guardDecisions: [], repairHints: hints, toolCalls: [],
      spans: [{ id: 1, spanId: 'failed-node', parentSpanId: consoleChild ? 'console-root' : undefined,
        spanType: consoleChild ? 'CONSOLE_CAPABILITY' : 'WORKFLOW_NODE', runtimeType: 'HTTP_API',
        status: 'ERROR', errorCode: 'SPECIFIC_ERROR', errorMessage: '具体节点失败' }] }
    const wrapper = mount(RunOpsInvestigationWorkbench, { props: { summary, detail }, global: { plugins: [ElementPlus] } })
    try {
      expect(wrapper.find('.next-steps').text()).toContain(hints[0])
      expect(wrapper.find('.next-steps').text()).not.toContain('同一调用 ID')
    } finally { wrapper.unmount() }
  })

  it('only offers a Console root Tool jump for an actual span-correlated audit, not a matching Tool name', async () => {
    const summary: RunSummary = { traceId: 'console-safe', runType: 'CONSOLE_HTTP_API', entryType: 'CONSOLE',
      runtimeType: 'HTTP_API', status: 'COMPLETED', toolCallCount: 99 }
    const detail: RunDetail = { summary, guardDecisions: [], repairHints: [],
      toolCalls: [{ id: 1, toolName: 'owned-api', success: true, statusSourceSpanId: 'other-root' }],
      spans: [{ id: 1, spanId: 'root', spanType: 'CONSOLE_CAPABILITY', runtimeType: 'HTTP_API', toolName: 'owned-api', status: 'SUCCESS' }] }
    const wrapper = mount(RunOpsInvestigationWorkbench, { props: { summary, detail }, global: { plugins: [ElementPlus] } })
    try {
      expect(wrapper.find('.workbench-summary').text()).toContain('1 条 Tool 审计')
      expect(wrapper.find('.workbench-summary').text()).not.toContain('99 Tool')
      expect(wrapper.findAll('button').some(button => button.text() === '查看工具调用')).toBe(false)
      await wrapper.setProps({ detail: { ...detail, toolCalls: [{ ...detail.toolCalls[0]!, statusSourceSpanId: 'root' }] } })
      expect(wrapper.findAll('button').some(button => button.text() === '查看工具调用')).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('distinguishes absent persisted payload from a missing execution result without issuing another write', async () => {
    const summary: RunSummary = { traceId: 'confirmed-write', runType: 'MCP', entryType: 'MCP', status: 'COMPLETED' }
    const detail: RunDetail = { summary, guardDecisions: [], repairHints: [], toolCalls: [],
      spans: [{ id: 1, spanId: 'variable-node', spanType: 'WORKFLOW_NODE', runtimeType: 'VARIABLE_ASSIGN',
        nodeId: 'variable-node', status: 'SUCCESS' }],
    }
    const wrapper = mount(RunOpsInvestigationWorkbench, { props: { summary, detail }, global: { plugins: [ElementPlus] } })
    try {
      await wrapper.findAll('button').find(button => button.text() === '输入 / 输出')!.trigger('click')
      expect(wrapper.find('.payload-view').text()).toContain('此运行未记录节点输出摘要')
      expect(wrapper.find('.payload-view').text()).toContain('不表示没有实际输出')
      expect(wrapper.find('.payload-view').text()).toContain('不会重新发起写入')
      expect(wrapper.find('.payload-view').text()).not.toContain('NOTED')
      await wrapper.setProps({ detail: { ...detail, spans: [{ ...detail.spans[0]!, outputSummary: '{"order_state":"NOTED"}' }] } })
      expect(wrapper.find('.payload-view').text()).toContain('NOTED')
      expect(wrapper.find('.payload-absence-note').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('shows an after-dispatch API write as unconfirmed rather than a successful node or pending approval', () => {
    const summary: RunSummary = { traceId: 'write-unknown', runType: 'MCP', entryType: 'MCP', status: 'FAILED',
      errorCode: 'HTTP_API_WORKFLOW_RESULT_UNCONFIRMED' }
    const detail: RunDetail = { summary, guardDecisions: [], repairHints: [], toolCalls: [],
      spans: [{ id: 1, spanId: 'write-api', spanType: 'WORKFLOW_NODE', runtimeType: 'TOOL', nodeId: 'api-node',
        status: 'ERROR', errorCode: 'HTTP_API_WORKFLOW_RESULT_UNCONFIRMED', metadata: { attempt: 1, maxAttempts: 2 } }],
    }
    const wrapper = mount(RunOpsInvestigationWorkbench, { props: { summary, detail }, global: { plugins: [ElementPlus] } })
    try {
      expect(wrapper.find('.execution-row').text()).toContain('结果未确认')
      expect(wrapper.find('.node-inspector').text()).toContain('写入结果未确认')
      expect(wrapper.find('.node-inspector').text()).toContain('勿自动重试')
      expect(wrapper.find('.node-inspector').text()).not.toContain('节点已成功完成')
      expect(wrapper.find('.node-inspector').text()).not.toContain('完成当前审批')
    } finally { wrapper.unmount() }
  })

  it('never describes an ordinary ERROR span as successfully completed', () => {
    const summary: RunSummary = { traceId: 'write-failed', runType: 'MCP', entryType: 'MCP', status: 'FAILED' }
    const detail: RunDetail = { summary, guardDecisions: [], repairHints: [], toolCalls: [],
      spans: [{ id: 1, spanId: 'write-api', spanType: 'WORKFLOW_NODE', runtimeType: 'TOOL', nodeId: 'api-node',
        status: 'ERROR', errorCode: 'HTTP_API_WORKFLOW_HTTP_FAILED' }],
    }
    const wrapper = mount(RunOpsInvestigationWorkbench, { props: { summary, detail }, global: { plugins: [ElementPlus] } })
    try {
      expect(wrapper.find('.node-inspector').text()).toContain('节点执行失败')
      expect(wrapper.find('.node-inspector').text()).not.toContain('节点已成功完成')
    } finally { wrapper.unmount() }
  })

  it('labels an API console trace as an API call, not a Workflow node', () => {
    const summary: RunSummary = {
      traceId: 'console-api-1', runType: 'CONSOLE_HTTP_API', entryType: 'CONSOLE', status: 'COMPLETED',
    }
    const detail: RunDetail = {
      summary, guardDecisions: [], repairHints: [], toolCalls: [],
      spans: [{ id: 1, spanId: 'root', spanType: 'NODE', runtimeType: 'HTTP_API',
        nodeId: 'orders.findOrder', status: 'SUCCESS' }],
    }
    const wrapper = mount(RunOpsInvestigationWorkbench, { props: { summary, detail }, global: { plugins: [ElementPlus] } })
    try {
      expect(wrapper.find('.execution-row em').text()).toBe('API 试调用')
      expect(wrapper.find('.node-inspector__header').text()).toContain('API 试调用')
      expect(wrapper.find('.node-inspector__header').text()).not.toContain('工作流节点')
    } finally {
      wrapper.unmount()
    }
  })

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
