import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElTooltip } from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RunOpsDetail from './RunOpsDetail.vue'

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
})
