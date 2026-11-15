import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import type { AiCodingTask, AiCodingTaskDetail } from '@/types/aiCodingTask'
import type { PageAccessJourney, ProjectPage } from '@/types/pageWorkbench'
import PageAssistantWizard from './PageAssistantWizard.vue'

const mocks = vi.hoisted(() => ({ workbench: null as unknown }))
vi.mock('@/views/registry/composables/useBusinessPageWorkbench', () => ({
  useBusinessPageWorkbench: () => mocks.workbench,
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { pageId: '1' }, params: { projectCode: 'demo' } }),
  useRouter: () => ({ push: vi.fn() }),
}))

const page = { id: 1, pageKey: 'mall.home', name: '商城首页', resources: [], actions: [] } as unknown as ProjectPage
const journey = {
  pageId: 1, stage: 'AI_IMPLEMENTATION', status: 'ACTION_REQUIRED', activeTaskId: 'analysis',
  nextAction: { code: 'OPEN_TASK', enabled: true },
} as PageAccessJourney
function task(taskId: string, taskKind: string, executionStatus = 'RUNNING', pageKey = page.pageKey) {
  return {
    taskId, taskKind, executionStatus, title: taskId, executorProvider: 'CLAUDE_CODE', createdAt: '2026-11-11T10:00:00',
    targets: [{ targetRole: 'PRIMARY', targetType: 'PAGE', targetKey: pageKey }],
  } as AiCodingTask
}

function createWorkbench() {
  return {
    projectCode: ref('demo'), project: ref({ projectCode: 'demo' }), pages: ref([page]),
    selectedPageId: ref(1), selectedPage: ref(page), selectedTaskDetail: ref<AiCodingTaskDetail | null>(null),
    accessCenter: ref({ pages: [journey], runtimeAvailable: true }),
    tasks: ref([task('analysis', 'PAGE_READONLY_ANALYSIS')]), findings: ref([]), published: ref([]),
    pageMap: ref({ scanned: false }), modules: ref([]), loading: ref(false), projectMissing: ref(false),
    loadError: ref(''), tasksLoadError: ref(''), tasksLoading: ref(false), publishedUnavailable: ref(false),
    loadAll: vi.fn(), loadTaskDetail: vi.fn(), refreshTasks: vi.fn(), refreshPages: vi.fn(),
    checkPageReadiness: vi.fn().mockResolvedValue({ items: [] }),
    refreshAccessCenter: vi.fn(), changeFindingStatus: vi.fn(), cancelTask: vi.fn().mockResolvedValue({}),
    createTask: vi.fn().mockResolvedValue({ handoff: { taskId: 'new-task' } }),
  }
}
let state: ReturnType<typeof createWorkbench>
let wrapper: VueWrapper
const confirmation = 'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>
beforeEach(() => {
  state = createWorkbench()
  mocks.workbench = state
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(confirmation)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close: vi.fn() }))
  vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close: vi.fn() }))
  wrapper = shallowMount(PageAssistantWizard, { global: { plugins: [ElementPlus] } })
})
afterEach(() => { wrapper.unmount(); vi.restoreAllMocks() })

async function retarget() {
  wrapper.findComponent({ name: 'PageWorkbenchPageDetail' }).vm.$emit('retarget', page, journey, {
    manualGoal: '查询我的订单', findingIds: [],
  })
  await flushPromises()
}

describe('page goal restart flow', () => {
  it('creates the first code implementation from a readonly-analysis scene after confirmation', async () => {
    await retarget()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('analysis'), '确认调整接入目标', expect.anything())
    expect(state.cancelTask).toHaveBeenCalledWith('analysis')
    expect(state.createTask).toHaveBeenCalledWith(expect.objectContaining({
      taskKind: 'CODE_IMPLEMENTATION', executorProvider: 'CODEX',
      goalSelection: expect.objectContaining({ manualGoal: '查询我的订单' }),
    }))
  })

  it('keeps the current workflow visible and only uses code history for the goal snapshot', async () => {
    state.tasks.value = [task('analysis', 'WORKFLOW_ENGINEERING'), task('code', 'CODE_IMPLEMENTATION', 'COMPLETED')]
    await flushPromises()
    const detail = wrapper.findComponent({ name: 'PageWorkbenchPageDetail' })
    expect(detail.props('task').taskId).toBe('analysis')
    expect(detail.props('implementationTask').taskId).toBe('code')
    await retarget()
    expect(state.cancelTask).toHaveBeenCalledTimes(1)
    expect(state.cancelTask).toHaveBeenCalledWith('analysis')
    expect(state.createTask).toHaveBeenCalledWith(expect.objectContaining({ executorProvider: 'CLAUDE_CODE' }))
  })

  it('does not mutate activities when the user keeps editing', async () => {
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    await retarget()
    expect(state.cancelTask).not.toHaveBeenCalled()
    expect(state.createTask).not.toHaveBeenCalled()
  })

  it('does not create a replacement if ending the current activity fails', async () => {
    state.cancelTask.mockRejectedValueOnce(new Error('取消失败'))
    await retarget()
    expect(state.createTask).not.toHaveBeenCalled()
    expect(ElMessage.error).toHaveBeenCalledWith('取消失败')
  })

  it('reports a creation failure and allows the same goal to be retried', async () => {
    state.tasks.value = []
    state.accessCenter.value.pages = [{ ...journey, activeTaskId: undefined }]
    state.createTask.mockRejectedValueOnce(new Error('创建失败'))
    await retarget()
    expect(ElMessage.error).toHaveBeenCalledWith('创建失败')
    await retarget()
    expect(state.createTask).toHaveBeenCalledTimes(2)
  })

  it('does not cancel another page or completed activities when restarting', async () => {
    state.tasks.value.push(task('other', 'WORKFLOW_ENGINEERING', 'RUNNING', 'mall.orders'), task('done', 'CODE_IMPLEMENTATION', 'COMPLETED'))
    await retarget()
    expect(state.cancelTask.mock.calls.map(([id]) => id)).toEqual(['analysis'])
  })

  it('ends all confirmed unfinished page activities before creating the replacement', async () => {
    state.tasks.value.push(task('workflow', 'WORKFLOW_ENGINEERING', 'WAITING_USER'))
    await retarget()
    expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('2 个活动'), expect.anything(), expect.anything())
    expect(state.cancelTask.mock.calls.map(([id]) => id)).toEqual(['analysis', 'workflow'])
    expect(state.createTask.mock.invocationCallOrder[0]).toBeGreaterThan(state.cancelTask.mock.invocationCallOrder[1])
  })

  it('invalidates a pending confirmation when the project changes', async () => {
    vi.mocked(ElMessageBox.confirm).mockImplementationOnce(async () => {
      state.projectCode.value = 'other-project'
      return confirmation
    })
    await retarget()
    expect(state.cancelTask).not.toHaveBeenCalled()
    expect(state.createTask).not.toHaveBeenCalled()
  })

  it('requires a fresh confirmation if a new activity arrives', async () => {
    vi.mocked(ElMessageBox.confirm).mockImplementationOnce(async () => {
      state.tasks.value.push(task('new-workflow', 'WORKFLOW_ENGINEERING'))
      return confirmation
    })
    await retarget()
    expect(state.cancelTask).not.toHaveBeenCalled()
    expect(state.createTask).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('当前页面活动已变化，请重新确认接入目标')
  })

  it('blocks adjustment when the current activity record is missing', async () => {
    state.tasks.value = []
    await flushPromises()
    expect(wrapper.findComponent({ name: 'PageWorkbenchPageDetail' }).props('tasksLoadError')).toContain('不完整')
    await retarget()
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    expect(state.createTask).not.toHaveBeenCalled()
  })

  it('recovers a current activity missing from the limited task list through its detail', async () => {
    state.tasks.value = []
    state.loadTaskDetail.mockImplementationOnce(async () => {
      state.selectedTaskDetail.value = { task: task('analysis', 'PAGE_READONLY_ANALYSIS') } as AiCodingTaskDetail
    })
    await flushPromises()
    wrapper.findComponent({ name: 'PageWorkbenchPageDetail' }).vm.$emit('refresh')
    await flushPromises()
    expect(state.loadTaskDetail).toHaveBeenCalledWith('analysis')
    expect(wrapper.findComponent({ name: 'PageWorkbenchPageDetail' }).props('tasksLoadError')).toBe('')
    await retarget()
    expect(state.cancelTask).toHaveBeenCalledWith('analysis')
    expect(state.createTask).toHaveBeenCalledTimes(1)
  })

  it('stops the restart if reloading activities fails after confirmation', async () => {
    state.refreshTasks.mockImplementationOnce(() => { state.tasksLoadError.value = '活动记录刷新失败' })
    await retarget()
    expect(ElMessage.error).toHaveBeenCalledWith('活动记录刷新失败')
    expect(state.cancelTask).not.toHaveBeenCalled()
    expect(state.createTask).not.toHaveBeenCalled()
  })

  it('prevents a second restart while its confirmation is pending', async () => {
    let resolveConfirmation!: (value: typeof confirmation) => void
    vi.mocked(ElMessageBox.confirm).mockImplementationOnce(() => new Promise((resolve) => { resolveConfirmation = resolve }))
    await retarget()
    await retarget()
    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    resolveConfirmation(confirmation)
    await flushPromises()
    expect(state.createTask).toHaveBeenCalledTimes(1)
  })
})
