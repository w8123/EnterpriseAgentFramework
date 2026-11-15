import { afterEach, describe, expect, it } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { AiCodingTask } from '@/types/aiCodingTask'
import type { PageAccessJourney, ProjectPage } from '@/types/pageWorkbench'
import PageWorkbenchPageDetail from './PageWorkbenchPageDetail.vue'

const page = {
  id: 1, pageKey: 'mall.home', name: '商城首页', resources: [], actions: [],
} as unknown as ProjectPage

function task(taskKind = 'CODE_IMPLEMENTATION', executionStatus = 'RUNNING') {
  return {
    taskId: `${taskKind}-${executionStatus}`, taskKind, executionStatus,
    objective: taskKind === 'CODE_IMPLEMENTATION' ? '人工描述：查询我的订单' : '只读分析商城首页，最多返回三条建议',
    targets: [],
  } as unknown as AiCodingTask
}

function journey(stage: PageAccessJourney['stage'] = 'AI_IMPLEMENTATION', status: PageAccessJourney['status'] = 'ACTION_REQUIRED') {
  return {
    pageId: 1, pageKey: page.pageKey, stage, status, currentStep: 2, completedSteps: 1,
    nextAction: { code: 'OPEN_TASK', enabled: true, label: '查看进度' },
  } as PageAccessJourney
}

let wrapper: VueWrapper
function render(props: Record<string, unknown> = {}) {
  wrapper = mount(PageWorkbenchPageDetail, {
    props: { page, journey: journey(), capabilityWorkspace: 'goals', ...props },
    global: {
      plugins: [ElementPlus],
      stubs: {
        AiCodingTaskDetailPanel: true,
        PageWorkbenchResourcePanel: true,
        PageWorkbenchActionPanel: true,
        PageWorkbenchDiagnosticsPanel: true,
        PageWorkbenchDebugPanel: true,
      },
    },
  })
  return wrapper
}

function button(text: string) {
  return wrapper.findAll('button').find((item) => item.text().trim() === text)!
}

afterEach(() => { wrapper?.unmount() })

describe('page workbench goal editing', () => {
  it.each(['READY', 'RUNNING', 'WAITING_USER', 'RESULT_SUBMITTED', 'RESULT_APPLIED', 'ACCEPTANCE_READY', 'COMPLETED', 'FAILED', 'CANCELLED'])(
    'offers an executable adjustment for code implementation in %s', async (executionStatus) => {
      render({ task: task('CODE_IMPLEMENTATION', executionStatus) })
      expect(wrapper.text()).toContain('点击“调整接入目标”')
      expect(button('调整接入目标').attributes('disabled')).toBeUndefined()
      await button('调整接入目标').trigger('click')
      expect(wrapper.find('textarea').element.value).toBe('查询我的订单')
      await button('确认新目标并重新实施').trigger('click')
      expect(wrapper.emitted('retarget')?.[0]?.[2]).toEqual({ manualGoal: '查询我的订单', findingIds: [] })
    },
  )

  it.each([
    ['AI_IMPLEMENTATION', 'PAGE_READONLY_ANALYSIS'],
    ['AI_IMPLEMENTATION', 'WORKFLOW_ENGINEERING'],
    ['PUBLISH_CONFIRMATION', 'PRE_RELEASE_CHECK'],
    ['REAL_ACCEPTANCE', 'BROWSER_ACCEPTANCE'],
    ['COMPLETE', 'BROWSER_ACCEPTANCE'],
  ] as const)('lets users select real implementation goals during %s / %s without a code task', async (stage, taskKind) => {
    render({ task: task(taskKind), journey: journey(stage) })
    expect(wrapper.text()).not.toContain('只读分析商城首页，最多返回三条建议')
    expect(wrapper.text()).toContain('尚未确认接入目标')
    await button('调整接入目标').trigger('click')
    expect(wrapper.find('textarea').element.value).toBe('')
    expect(button('确认新目标并重新实施').attributes('disabled')).toBeDefined()
    await wrapper.find('textarea').setValue('查看我的待办')
    await button('添加目标').trigger('click')
    await button('确认新目标并重新实施').trigger('click')
    expect(wrapper.emitted('retarget')?.[0]?.[2]).toEqual({ manualGoal: '查看我的待办', findingIds: [] })
  })

  it('keeps the initial goal selection directly editable', async () => {
    render({ journey: journey('GOAL_SELECTION', 'WAITING') })
    expect(button('调整接入目标')).toBeUndefined()
    expect(wrapper.text()).not.toContain('点击“调整接入目标”')
    await wrapper.find('textarea').setValue('查询订单')
    await button('添加目标').trigger('click')
    await button('确认并开始接入').trigger('click')
    expect(wrapper.emitted('primary')?.[0]?.[2]).toEqual({ manualGoal: '查询订单', findingIds: [] })
  })

  it('does not mark readonly analysis as confirmation of implementation goals', () => {
    render({ task: task('PAGE_READONLY_ANALYSIS') })
    expect(wrapper.find('[data-flow-node="goals"]').text()).not.toContain('已完成')
  })

  it('uses confirmed implementation goals while showing a different current task', async () => {
    render({ task: task('WORKFLOW_ENGINEERING'), implementationTask: task() })
    expect(wrapper.text()).toContain('查询我的订单')
    await button('调整接入目标').trigger('click')
    await wrapper.find('textarea').setValue('新的目标')
    await button('取消调整').trigger('click')
    expect(wrapper.text()).toContain('查询我的订单')
    expect(wrapper.emitted('retarget')).toBeUndefined()
  })

  it.each(['busy', 'tasksLoading'])('disables adjustment and removes the edit invitation while %s', async (flag) => {
    render({ task: task(), [flag]: true })
    expect(button('调整接入目标').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('点击“调整接入目标”')
    expect(wrapper.text()).toContain('请稍后调整接入目标')
    await button('调整接入目标').trigger('click')
    expect(wrapper.find('textarea').exists()).toBe(false)
  })

  it('provides refresh instead of an edit invitation when activity loading fails', async () => {
    render({ tasksLoadError: '加载失败' })
    expect(button('调整接入目标')).toBeUndefined()
    expect(wrapper.text()).not.toContain('点击“调整接入目标”')
    await button('刷新活动记录').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
  })

  it('keeps service-unavailable states out of the goal editor', () => {
    render({ journey: journey('PUBLISH_CONFIRMATION', 'UNAVAILABLE') })
    expect(button('调整接入目标')).toBeUndefined()
    expect(wrapper.text()).not.toContain('点击“调整接入目标”')
    expect(button('刷新状态')).toBeDefined()
  })

  it('explains a failed refresh while preserving an unsaved adjustment', async () => {
    render({ task: task() })
    await button('调整接入目标').trigger('click')
    await wrapper.find('textarea').setValue('刷新失败时保留的目标')
    await wrapper.setProps({ tasksLoadError: '加载失败' })
    expect(wrapper.find('[role="alert"]').text()).toContain('刷新后再确认')
    expect(button('确认新目标并重新实施').attributes('disabled')).toBeDefined()
    await button('刷新活动记录').trigger('click')
    expect(wrapper.emitted('refresh')).toHaveLength(1)
    expect(wrapper.find('textarea').element.value).toBe('刷新失败时保留的目标')
  })

  it('preserves legacy and missing-finding goal text when entering adjustment', async () => {
    const implementation = task()
    implementation.objective = '旧版自定义接入目标'
    render({ implementationTask: implementation })
    await button('调整接入目标').trigger('click')
    expect(wrapper.find('textarea').element.value).toBe('旧版自定义接入目标')
    await button('确认新目标并重新实施').trigger('click')
    expect(wrapper.emitted('retarget')?.[0]?.[2]).toEqual({ manualGoal: '旧版自定义接入目标', findingIds: [] })
  })

  it('retains a confirmed AI goal whose original finding is no longer available', async () => {
    const implementation = task()
    implementation.targets = [{
      targetRole: 'PRIMARY', targetType: 'PAGE', targetKey: page.pageKey, accessMode: 'READ_WRITE',
      snapshot: { goalSelection: { manualGoal: '原人工目标', findingIds: [77], items: [
        { source: 'MANUAL', content: '原人工目标' },
        { source: 'AI_FINDING', findingId: 77, content: '保留已失效建议的改造内容' },
      ] } },
    }]
    render({ implementationTask: implementation })
    await button('调整接入目标').trigger('click')
    await button('确认新目标并重新实施').trigger('click')
    expect(wrapper.emitted('retarget')?.[0]?.[2]).toEqual({
      manualGoal: '原人工目标\n保留已失效建议的改造内容', findingIds: [],
    })
  })

  it('preserves the draft across cancellation refresh and permits retry after failure', async () => {
    render({ task: task(), implementationTask: task() })
    await button('调整接入目标').trigger('click')
    await wrapper.find('textarea').setValue('保留失败后重试的目标')
    await wrapper.setProps({ busy: true })
    await wrapper.setProps({ task: null, journey: journey('GOAL_SELECTION') })
    await wrapper.setProps({ busy: false })
    expect(wrapper.find('textarea').element.value).toBe('保留失败后重试的目标')
    await button('确认新目标并重新实施').trigger('click')
    expect(wrapper.emitted('retarget')?.[0]?.[2]).toEqual({ manualGoal: '保留失败后重试的目标', findingIds: [] })
  })

  it('discards the adjustment when switching pages', async () => {
    render({ task: task() })
    await button('调整接入目标').trigger('click')
    await wrapper.setProps({ page: { ...page, id: 2, pageKey: 'mall.orders' }, task: null })
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('查询我的订单')
  })
})
