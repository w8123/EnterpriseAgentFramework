import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElTooltip } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RunOpsDetail from './RunOpsDetail.vue'
import type { RunDetail, RunSummary } from '@/types/runops'

const api = vi.hoisted(() => ({
  getRunOpsDetail: vi.fn(),
  compareRunOpsTrace: vi.fn(),
  createTraceWorkflowCandidateTask: vi.fn(),
  getTraceWorkflowCandidateEligibility: vi.fn(),
  replayRunOpsTrace: vi.fn(),
}))

vi.mock('@/api/runops', () => api)
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { traceId: 'owned-completed-run' }, query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('@/api/aiCodingTasks', () => ({ issueAiCodingHandoff: vi.fn() }))
vi.mock('@/auth/platformAccess', () => ({
  hasPlatformResourcePermission: () => false,
  PLATFORM_PERMISSION_RUNOPS_OPERATE: 'runops:operate',
  PLATFORM_PERMISSION_WORKFLOW_WRITE: 'workflow:write',
}))
vi.mock('@/auth/platformSession', async () => {
  const { ref } = await import('vue')
  return { platformSessionUser: ref({ permissionGrants: [] }) }
})
vi.mock('@/composables/useAiCodingTask', async () => {
  const { ref } = await import('vue')
  return { useAiCodingTask: () => ({ selectedTaskDetail: ref(null), reset: vi.fn() }) }
})

function mountDetail() {
  return mount(RunOpsDetail, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        WorkbenchPage: { template: '<main><slot /></main>' },
        PageHeader: { template: '<header><slot name="actions" /></header>' },
        AppDialog: true,
        AppDrawer: true,
        AiCodingTaskDetailPanel: true,
        RunOpsInvestigationWorkbench: true,
      },
    },
  })
}

describe('RunOps read-only refresh keyboard behavior', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.getRunOpsDetail.mockResolvedValue({ data: { summary: null, spans: [], toolCalls: [] } })
  })

  it('leaves activation to the actual button while retaining its tooltip and read-only refresh', async () => {
    const wrapper = mountDetail()
    try {
      await flushPromises()
      const tooltip = wrapper.findAllComponents(ElTooltip)
        .find(item => item.props('content') === '刷新运行详情')!
      expect(tooltip.props('triggerKeys')).toEqual([])
      const button = wrapper.get('button[aria-label="刷新运行详情"]')
      expect(button.attributes('disabled')).toBeUndefined()
      await button.trigger('click')
      await flushPromises()
      expect(api.getRunOpsDetail).toHaveBeenCalledTimes(2)
      expect(api.getRunOpsDetail).toHaveBeenLastCalledWith('owned-completed-run')
      expect(api.replayRunOpsTrace).not.toHaveBeenCalled()
      expect(api.createTraceWorkflowCandidateTask).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })

  it.each([['Enter', 'Enter'], [' ', 'Space']])('does not cancel the button default for %s', async (key, code) => {
    const wrapper = mountDetail()
    try {
      await flushPromises()
      const button = wrapper.get('button[aria-label="刷新运行详情"]')
      const event = new KeyboardEvent('keydown', { key, code, bubbles: true, cancelable: true })
      button.element.dispatchEvent(event)
      expect(event.defaultPrevented).toBe(false)
      // Happy DOM does not synthesize native button clicks. The real browser
      // acceptance separately proves keyboard activation issues one GET.
      expect(api.replayRunOpsTrace).not.toHaveBeenCalled()
      expect(api.createTraceWorkflowCandidateTask).not.toHaveBeenCalled()
    } finally {
      wrapper.unmount()
    }
  })

  it('enriches the generic Console root conclusion with safe TLS facts without changing UNKNOWN or issuing a call', async () => {
    const summary: RunSummary = { traceId: 'owned-completed-run', runType: 'CONSOLE_HTTP_API', entryType: 'CONSOLE',
      runtimeType: 'HTTP_API', status: 'FAILED', errorCode: 'HTTP_API_DISPATCH_UNKNOWN', toolCallCount: 1,
      errorMessage: '[rejected:HTTP_API_DISPATCH_UNKNOWN] [transport:code=RUNTIME_HTTP_TLS_FAILED;exceptionClass=SSLHandshakeException;elapsedMs=5008]' }
    const detail: RunDetail = { summary, guardDecisions: [], repairHints: [], toolCalls: [],
      spans: [{ id: 1, spanId: 'console-root', spanType: 'CONSOLE_CAPABILITY', runtimeType: 'HTTP_API',
        status: 'ERROR', errorCode: 'HTTP_API_DISPATCH_UNKNOWN', errorMessage: '[rejected:HTTP_API_DISPATCH_UNKNOWN]' }] }
    api.getRunOpsDetail.mockResolvedValue({ data: detail })
    const wrapper = mountDetail()
    try {
      await flushPromises()
      expect(wrapper.find('.run-conclusion-copy p').text()).toContain('TLS 握手失败')
      expect(wrapper.find('.run-conclusion-copy p').text()).toContain('结果仍未确认（UNKNOWN）')
      expect(wrapper.find('.run-conclusion-copy p').text()).toContain('不能证明请求未送达')
      expect(api.getRunOpsDetail).toHaveBeenCalledTimes(1)
      expect(api.replayRunOpsTrace).not.toHaveBeenCalled()
      expect(api.createTraceWorkflowCandidateTask).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it.each([false, true])('keeps old or unsafe Console root diagnostics generic: unsafe=%s', async unsafe => {
    const summary: RunSummary = { traceId: 'owned-completed-run', runType: 'CONSOLE_HTTP_API', entryType: 'CONSOLE',
      runtimeType: 'HTTP_API', status: 'FAILED', errorCode: 'HTTP_API_DISPATCH_UNKNOWN',
      errorMessage: unsafe ? '[rejected:HTTP_API_DISPATCH_UNKNOWN] transport https://secret.invalid/?token=value'
        : '[rejected:HTTP_API_DISPATCH_UNKNOWN]' }
    api.getRunOpsDetail.mockResolvedValue({ data: { summary, guardDecisions: [], repairHints: [], toolCalls: [],
      spans: [{ id: 1, spanType: 'CONSOLE_CAPABILITY', runtimeType: 'HTTP_API', status: 'ERROR',
        errorCode: 'HTTP_API_DISPATCH_UNKNOWN', errorMessage: '[rejected:HTTP_API_DISPATCH_UNKNOWN]' }] } })
    const wrapper = mountDetail()
    try {
      await flushPromises()
      const message = wrapper.find('.run-conclusion-copy p').text()
      expect(message).toBe('[rejected:HTTP_API_DISPATCH_UNKNOWN]')
      expect(message).not.toContain('secret.invalid')
      expect(message).not.toContain('TLS')
    } finally { wrapper.unmount() }
  })

  it('preserves the more specific deepest child failure ahead of the safe Console root', async () => {
    const summary: RunSummary = { traceId: 'owned-completed-run', runType: 'CONSOLE_HTTP_API', entryType: 'CONSOLE',
      runtimeType: 'HTTP_API', status: 'FAILED', errorCode: 'HTTP_API_DISPATCH_UNKNOWN',
      errorMessage: '[rejected:HTTP_API_DISPATCH_UNKNOWN] [transport:code=RUNTIME_HTTP_TLS_FAILED;exceptionClass=SSLHandshakeException;elapsedMs=5008]' }
    api.getRunOpsDetail.mockResolvedValue({ data: { summary, guardDecisions: [], repairHints: [], toolCalls: [],
      spans: [
        { id: 1, spanId: 'root', spanType: 'CONSOLE_CAPABILITY', runtimeType: 'HTTP_API', status: 'ERROR',
          errorCode: 'HTTP_API_DISPATCH_UNKNOWN', errorMessage: '[rejected:HTTP_API_DISPATCH_UNKNOWN]' },
        { id: 2, spanId: 'child', parentSpanId: 'root', spanType: 'WORKFLOW_NODE', runtimeType: 'TOOL',
          status: 'ERROR', errorCode: 'SPECIFIC_CHILD', errorMessage: '具体子节点失败' },
      ] } })
    const wrapper = mountDetail()
    try {
      await flushPromises()
      expect(wrapper.find('.run-conclusion-copy p').text()).toBe('具体子节点失败')
      expect(wrapper.find('.run-conclusion-copy p').text()).not.toContain('TLS')
    } finally { wrapper.unmount() }
  })
})
