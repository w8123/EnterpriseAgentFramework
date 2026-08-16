<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Check,
  Connection,
  CopyDocument,
  Document,
  MagicStick,
  Plus,
  Promotion,
  Refresh,
  Search,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppDialog from '@/components/common/AppDialog.vue'
import AppDrawer from '@/components/common/AppDrawer.vue'
import PageHeader from '@/components/common/PageHeader.vue'
import AiCodingProviderSelector from '@/components/ai-coding/AiCodingProviderSelector.vue'
import AiCodingTaskDetailPanel from '@/components/ai-coding/AiCodingTaskDetailPanel.vue'
import PageWorkbenchTabs, {
  type PageAccessCenterTab,
} from '@/views/registry/components/page-workbench/PageWorkbenchTabs.vue'
import PageWorkbenchMapPanel from '@/views/registry/components/page-workbench/PageWorkbenchMapPanel.vue'
import PageWorkbenchPageDetail from '@/views/registry/components/page-workbench/PageWorkbenchPageDetail.vue'
import PageWorkbenchAcceptanceDialog from '@/views/registry/components/page-workbench/PageWorkbenchAcceptanceDialog.vue'
import PageWorkbenchAnalysisRecommendationDialog from '@/views/registry/components/page-workbench/PageWorkbenchAnalysisRecommendationDialog.vue'
import PageWorkbenchHistoryDrawer from '@/views/registry/components/page-workbench/PageWorkbenchHistoryDrawer.vue'
import PageWorkbenchPageInfoDialog from '@/views/registry/components/page-workbench/PageWorkbenchPageInfoDialog.vue'
import PageWorkbenchPublishConfirmDialog from '@/views/registry/components/page-workbench/PageWorkbenchPublishConfirmDialog.vue'
import PageWorkbenchTasksPanel from '@/views/registry/components/page-workbench/PageWorkbenchTasksPanel.vue'
import PageWorkbenchPublishedPanel from '@/views/registry/components/page-workbench/PageWorkbenchPublishedPanel.vue'
import PageWorkbenchManualPageForm from '@/views/registry/components/page-workbench/PageWorkbenchManualPageForm.vue'
import PageWorkbenchWorkflowDeliveryCard from '@/views/registry/components/page-workbench/PageWorkbenchWorkflowDeliveryCard.vue'
import AiCodingHandoffPanel from '@/views/registry/components/page-workbench/AiCodingHandoffPanel.vue'
import ProjectRouteMissingState from '@/views/registry/components/ProjectRouteMissingState.vue'
import ProjectWorkbenchLoadErrorState from '@/views/registry/components/ProjectWorkbenchLoadErrorState.vue'
import { useBusinessPageWorkbench } from '@/views/registry/composables/useBusinessPageWorkbench'
import type { PageAiCodingTaskCommand } from '@/views/registry/composables/useBusinessPageWorkbench'
import type {
  AiCodingExecutorProvider,
  AiCodingHandoffPackage,
  AiCodingTask,
  AiCodingTaskDetail,
} from '@/types/aiCodingTask'
import type {
  AnalysisFindingStatus,
  ManualProjectPageRequest,
  PageAnalysisFinding,
  PageDetailCapabilityWorkspace,
  PageImplementationGoalDraft,
  PageImplementationGoalSelection,
  PageIntegrationReadiness,
  PageAccessJourney,
  ProjectPage,
  ProjectPageAction,
  PublishedPageWorkflow,
  WorkflowEngineeringDraftResult,
} from '@/types/pageWorkbench'
import {
  aiCodingExecutionIsTerminal,
  aiCodingProviderLabel,
} from '@/utils/aiCodingPresentation'
import {
  buildPageImplementationGoalSelection,
  pageImplementationObjective,
  readPageImplementationGoalSelection,
} from '@/utils/aiCodingGoalSelection'
import { copyAiCodingText } from '@/utils/aiCodingClipboard'
import { normalizeBusinessPageUrl } from '@/utils/businessPageUrl'
import {
  pageWorkbenchHumanText,
  pageWorkbenchPageName,
} from '@/utils/pageWorkbenchPresentation'

type TaskMode = 'TARGET' | 'ANALYZE'
type PageSourceTab = 'scan' | 'manual'
type AiCodingDialogStage = 'FORM' | 'HANDOFF'

const route = useRoute()
const router = useRouter()
const activeTab = ref<PageAccessCenterTab>('pages')
const sourceDialogVisible = ref(false)
const sourceDialogTab = ref<PageSourceTab>('scan')
const sourceDialogStage = ref<AiCodingDialogStage>('FORM')
const taskDialogVisible = ref(false)
const taskDialogStage = ref<AiCodingDialogStage>('FORM')
const handoffDialogVisible = ref(false)
const findingDrawerVisible = ref(false)
const taskDrawerVisible = ref(false)
const manualSubmitting = ref(false)
const scanSubmitting = ref(false)
const taskSubmitting = ref(false)
const taskDetailLoading = ref(false)
const implementationTaskDetailLoading = ref(false)
const handoffCopied = ref(false)
const handoffPackage = ref<AiCodingHandoffPackage | null>(null)
const selectedFinding = ref<PageAnalysisFinding | null>(null)
const manualPage = ref<ProjectPage | null>(null)
const readinessPage = ref<ProjectPage | null>(null)
const readinessResult = ref<PageIntegrationReadiness | null>(null)
const readinessLoading = ref(false)
const journeyBusy = ref(false)
const analysisRecommendationVisible = ref(false)
const pageInfoVisible = ref(false)
const detailCapabilityWorkspace = ref<PageDetailCapabilityWorkspace>('goals')
const detailDebugActionId = ref<number | null>(null)
const historyVisible = ref(false)
const publishConfirmVisible = ref(false)
const publishReviewLoading = ref(false)
const publishResult = ref<WorkflowEngineeringDraftResult | null>(null)
const acceptanceDialogVisible = ref(false)
const acceptanceTargetPage = ref<ProjectPage | null>(null)
const acceptanceTargetWorkflow = ref<PublishedPageWorkflow | null>(null)
const {
  projectCode,
  project,
  pages,
  accessCenter,
  findings,
  tasks,
  published,
  pageMap,
  modules,
  selectedPageId,
  selectedPage,
  selectedTaskDetail,
  loading,
  projectMissing,
  loadError,
  tasksLoadError,
  tasksLoading,
  publishedUnavailable,
  loadAll,
  refreshPages,
  refreshFindings,
  refreshTasks,
  refreshAccessCenter,
  refreshPublished,
  addManualPage,
  checkPageReadiness,
  deliverWorkflowTask,
  createTask,
  loadHandoffPrompt,
  changeFindingStatus,
  loadTaskDetail,
  answerQuestion,
  verifyTaskAcceptanceReadiness,
  finishTaskAcceptance,
  cancelTask,
} = useBusinessPageWorkbench()

const selectedJourney = computed(
  () => accessCenter.value.pages.find((item) => item.pageId === selectedPageId.value) || null,
)

const selectedPageFindings = computed(
  () => findings.value.filter((item) => item.pageId === selectedPageId.value),
)

const selectedPageTask = computed(
  () => {
    const activeTask = tasks.value.find((item) => item.taskId === selectedJourney.value?.activeTaskId)
    if (activeTask?.taskKind === 'CODE_IMPLEMENTATION') return activeTask

    return tasks.value
      .filter((item) => (
        item.taskKind === 'CODE_IMPLEMENTATION'
        && taskPageKey(item) === selectedPage.value?.pageKey
      ))
      .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))[0]
      || activeTask
      || null
  },
)

const selectedPageTaskDetail = computed(() => (
  selectedTaskDetail.value?.task.taskId === selectedJourney.value?.activeTaskId
    ? selectedTaskDetail.value
    : null
))

const selectedPageWorkflow = computed(() => {
  const workflowId = selectedJourney.value?.workflowId
  return published.value.find((item) => workflowId
    ? item.workflowId === workflowId
    : item.pageKey === selectedPage.value?.pageKey) || null
})

const selectedPagePublishedWorkflows = computed(() => published.value.filter(
  (workflow) => workflow.pageKey === selectedPage.value?.pageKey,
))

const detailPageId = computed(() => {
  const raw = Array.isArray(route.query.pageId) ? route.query.pageId[0] : route.query.pageId
  if (!raw) return null
  const value = Number(raw)
  return Number.isInteger(value) && value > 0 ? value : null
})

const detailMode = computed(() => detailPageId.value != null)

let implementationTaskDetailLoadSequence = 0

async function loadImplementationTaskDetail(taskId: string) {
  const loadSequence = ++implementationTaskDetailLoadSequence
  implementationTaskDetailLoading.value = true
  try {
    await loadTaskDetail(taskId)
  } catch (error) {
    if (loadSequence === implementationTaskDetailLoadSequence) {
      ElMessage.error((error as Error).message || '加载 AI 实施详情失败')
    }
  } finally {
    if (loadSequence === implementationTaskDetailLoadSequence) {
      implementationTaskDetailLoading.value = false
    }
  }
}

watch(
  [
    detailMode,
    detailCapabilityWorkspace,
    () => selectedJourney.value?.activeTaskId || null,
    taskDrawerVisible,
  ],
  ([isDetail, workspace, taskId, drawerVisible]) => {
    if (
      !isDetail
      || workspace !== 'implementation'
      || !taskId
      || drawerVisible
      || selectedTaskDetail.value?.task.taskId === taskId
    ) return
    void loadImplementationTaskDetail(taskId)
  },
  { immediate: true },
)

let initializedDetailWorkspacePageId: number | null = null

watch(
  [detailPageId, pages],
  ([pageId, currentPages]) => {
    if (pageId == null) {
      initializedDetailWorkspacePageId = null
      detailCapabilityWorkspace.value = 'goals'
      detailDebugActionId.value = null
      return
    }
    if (initializedDetailWorkspacePageId === pageId) return
    const page = currentPages.find((item) => item.id === pageId)
    if (!page) return
    detailCapabilityWorkspace.value = defaultDetailCapabilityWorkspace(page)
    detailDebugActionId.value = null
    initializedDetailWorkspacePageId = pageId
  },
  { immediate: true },
)

const selectedPageTasks = computed(() => tasks.value.filter(
  (item) => taskPageKey(item) === selectedPage.value?.pageKey,
))

const selectedPageReadiness = computed(() => (
  readinessPage.value?.id === selectedPage.value?.id ? readinessResult.value : null
))

const acceptanceModelReadiness = computed(() => (
  readinessPage.value?.id === acceptanceTargetPage.value?.id
    ? readinessResult.value?.items.find((item) => item.key === 'AGENT_MODEL_RUNTIME_READY') || null
    : null
))

const selectedBusinessPageUrl = computed(() => businessPageUrl(selectedPage.value))

function defaultDetailCapabilityWorkspace(page: ProjectPage): PageDetailCapabilityWorkspace {
  if (!page.resources.length) return 'resources'
  const journey = accessCenter.value.pages.find((item) => item.pageId === page.id)
  return journey?.stage === 'AI_IMPLEMENTATION' ? 'implementation' : 'goals'
}

watch(
  [detailPageId, pages, loading],
  ([pageId, currentPages, isLoading]) => {
    if (pageId == null) return
    const page = currentPages.find((item) => item.id === pageId)
    if (page) {
      selectedPageId.value = page.id
      return
    }
    if (!isLoading && currentPages.length) {
      ElMessage.warning('链接中的页面已不在当前页面地图中，已返回页面列表')
      closePageDetail()
    }
  },
  { immediate: true },
)

watch(activeTab, async (tab) => {
  if (loadError.value) return
  try {
    if (tab === 'pages') {
      await refreshPages()
    } else if (tab === 'activities') {
      await Promise.all([refreshTasks(), refreshAccessCenter()])
    } else {
      await refreshAccessCenter()
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '刷新当前工作区失败')
  }
})

const scanTaskForm = reactive<{
  provider: AiCodingExecutorProvider
  title: string
  objective: string
}>({
  provider: 'CODEX',
  title: '',
  objective: '',
})

const taskForm = reactive<{
  mode: TaskMode
  pageId: number | null
  taskType: PageAiCodingTaskCommand['taskKind']
  provider: AiCodingExecutorProvider
  title: string
  objective: string
  workflow: PublishedPageWorkflow | null
}>({
  mode: 'TARGET',
  pageId: null,
  taskType: 'CODE_IMPLEMENTATION',
  provider: 'CODEX',
  title: '',
  objective: '',
  workflow: null,
})

const taskTypeOptions: Array<{
  value: PageAiCodingTaskCommand['taskKind']
  label: string
}> = [
  { value: 'WORKFLOW_ENGINEERING', label: '页面工作流建设' },
  { value: 'CODE_IMPLEMENTATION', label: '明确目标的代码实施' },
  { value: 'BROWSER_ACCEPTANCE', label: '浏览器验收' },
  { value: 'PRE_RELEASE_CHECK', label: '发布前检查' },
]

watch(
  [() => taskForm.taskType, () => taskForm.pageId],
  ([taskType, pageId]) => {
    if (!taskForm.workflow) return
    const page = pages.value.find((item) => item.id === pageId)
    if (
      taskType !== 'BROWSER_ACCEPTANCE'
      || page?.pageKey !== taskForm.workflow.pageKey
    ) {
      taskForm.workflow = null
    }
  },
)

function openPageSourceDialog(page?: ProjectPage) {
  manualPage.value = page || null
  sourceDialogTab.value = page ? 'manual' : 'scan'
  sourceDialogStage.value = 'FORM'
  resetScanTaskForm()
  sourceDialogVisible.value = true
}

function openPageDetail(page: ProjectPage) {
  selectedPageId.value = page.id
  detailCapabilityWorkspace.value = defaultDetailCapabilityWorkspace(page)
  detailDebugActionId.value = null
  initializedDetailWorkspacePageId = page.id
  return router.push({
    name: 'PageAssistantWizard',
    params: { projectCode: projectCode.value },
    query: { ...route.query, pageId: String(page.id) },
  })
}

function closePageDetail() {
  detailCapabilityWorkspace.value = 'goals'
  detailDebugActionId.value = null
  const query = { ...route.query }
  delete query.pageId
  router.push({
    name: 'PageAssistantWizard',
    params: { projectCode: projectCode.value },
    query,
  })
}

function editPageFromDetail(page: ProjectPage) {
  pageInfoVisible.value = false
  openPageSourceDialog(page)
}

function analyzePageFromDetail(page: ProjectPage) {
  selectedPageId.value = page.id
  analysisRecommendationVisible.value = true
}

function openPageInformation(page: ProjectPage) {
  selectedPageId.value = page.id
  pageInfoVisible.value = true
}

async function checkReadinessFromDetail(page: ProjectPage) {
  detailCapabilityWorkspace.value = 'diagnostics'
  await loadPageReadiness(page)
}

async function openInlineDiagnostics(page: ProjectPage) {
  selectedPageId.value = page.id
  if (detailPageId.value !== page.id) await openPageDetail(page)
  detailCapabilityWorkspace.value = 'diagnostics'
  await loadPageReadiness(page)
}

async function openInlineDebugWorkspace(
  page: ProjectPage,
  action?: ProjectPageAction | null,
) {
  selectedPageId.value = page.id
  if (detailPageId.value !== page.id) await openPageDetail(page)
  detailDebugActionId.value = action?.id || page.actions[0]?.id || null
  detailCapabilityWorkspace.value = 'debug'
}

function openJourneyHistory(page: ProjectPage) {
  selectedPageId.value = page.id
  historyVisible.value = true
}

function openJourneyOnline(_workflow: PublishedPageWorkflow) {
  closePageDetail()
  activeTab.value = 'online'
}

async function refreshPageAccessCenter() {
  try {
    await Promise.all([refreshPages(), refreshTasks()])
  } catch (error) {
    ElMessage.error((error as Error).message || '刷新页面接入状态失败')
  }
}

function implementationGoalSelection(
  page: ProjectPage,
  draft: PageImplementationGoalDraft,
) {
  return buildPageImplementationGoalSelection(
    findings.value.filter((item) => item.pageId === page.id),
    draft,
  )
}

async function syncImplementationGoalStatuses(
  page: ProjectPage,
  selection: PageImplementationGoalSelection,
) {
  const selectedIds = new Set(selection.findingIds)
  const pageFindings = findings.value.filter((item) => item.pageId === page.id)
  for (const finding of pageFindings) {
    const nextStatus = selectedIds.has(finding.id) ? 'KEPT' : 'UNREAD'
    if (finding.status === nextStatus || (finding.status === 'IGNORED' && nextStatus === 'UNREAD')) {
      continue
    }
    await changeFindingStatus(finding.id, nextStatus)
  }
}

async function createJourneyActivity(command: PageAiCodingTaskCommand) {
  journeyBusy.value = true
  try {
    const result = await createTask(command)
    setTaskHandoff(result.handoff)
    showTaskHandoff(result.handoff)
  } catch (error) {
    ElMessage.error((error as Error).message || '创建页面接入活动失败')
  } finally {
    journeyBusy.value = false
  }
}

async function createAcceptanceActivity(
  page: ProjectPage,
  workflow: PublishedPageWorkflow,
  confirmationCompleted = false,
) {
  if (!confirmationCompleted) {
    try {
      await ElMessageBox.confirm(
        `将锁定「${pageWorkbenchPageName(page)}」、${workflow.workflowName} ${workflow.workflowVersion}，并要求 AI 在真实业务页面验证可见结果与 Trace。是否开始？`,
        '开始真实业务验收',
        {
          type: 'warning',
          confirmButtonText: '确认并生成验收交接包',
          cancelButtonText: '取消',
        },
      )
    } catch {
      return
    }
  }
  await createJourneyActivity({
    page,
    workflow,
    taskKind: 'BROWSER_ACCEPTANCE',
    executorProvider: 'CODEX',
    title: `真实验收：${pageWorkbenchPageName(page)}`,
    objective: `在真实业务页面中验收 ${workflow.workflowName}（${workflow.workflowVersion}）的页面交互、业务请求、可见结果和对应 Trace。`,
  })
}

async function openAcceptancePreparation(page: ProjectPage, workflow: PublishedPageWorkflow) {
  selectedPageId.value = page.id
  acceptanceTargetPage.value = page
  acceptanceTargetWorkflow.value = workflow
  acceptanceDialogVisible.value = true
  await loadPageReadiness(page)
}

function openModelCenterFromAcceptance() {
  acceptanceDialogVisible.value = false
  void router.push({ name: 'ModelInstances' })
}

async function confirmAcceptanceActivity(
  page: ProjectPage,
  workflow: PublishedPageWorkflow,
) {
  await createAcceptanceActivity(page, workflow, true)
  acceptanceDialogVisible.value = false
}

async function createAnalysisRecommendation(page: ProjectPage) {
  analysisRecommendationVisible.value = false
  await createJourneyActivity({
    page,
    taskKind: 'PAGE_READONLY_ANALYSIS',
    executorProvider: 'CODEX',
    title: `分析${pageWorkbenchPageName(page)}`,
    objective: `只读分析${pageWorkbenchPageName(page)}当前可改进或可自动化的页面交互，并给出最多 3 条有真实代码、API、权限或交互依据的结果；不要修改代码，也不要自动加入实施目标。`,
  })
}

function isPageAiCodingTaskKind(
  value: string,
): value is PageAiCodingTaskCommand['taskKind'] {
  return [
    'PAGE_MAP_SCAN',
    'PAGE_READONLY_ANALYSIS',
    'WORKFLOW_ENGINEERING',
    'CODE_IMPLEMENTATION',
    'BROWSER_ACCEPTANCE',
    'PRE_RELEASE_CHECK',
  ].includes(value)
}

async function retryJourneyTask(page: ProjectPage, journey: PageAccessJourney) {
  const previous = tasks.value.find((item) => item.taskId === journey.activeTaskId)
  if (!previous) {
    ElMessage.error('上一次活动详情不可用，请刷新页面接入状态后重试')
    return
  }
  if (previous.taskKind === 'BROWSER_ACCEPTANCE') {
    const workflow = published.value.find((item) => item.workflowId === journey.workflowId)
      || published.value.find((item) => item.pageKey === page.pageKey)
    if (workflow) openAcceptancePreparation(page, workflow)
    else ElMessage.error('当前页面的已发布工作流不可用，无法重新锁定验收版本')
    return
  }
  if (!isPageAiCodingTaskKind(previous.taskKind)) {
    ElMessage.error('上一次活动类型当前不支持从页面接入中心重新发起')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将按上一次活动的目标重新发起「${previous.title}」，原活动记录会继续保留。是否继续？`,
      '重新发起页面接入活动',
      {
        type: 'warning',
        confirmButtonText: '确认重新发起',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }
  await createJourneyActivity({
    page,
    taskKind: previous.taskKind,
    executorProvider: previous.executorProvider,
    title: `重试：${previous.title}`,
    objective: previous.objective,
    goalSelection: previous.taskKind === 'CODE_IMPLEMENTATION'
      ? readPageImplementationGoalSelection(
          previous,
          findings.value.filter((item) => item.pageId === page.id),
        )
      : undefined,
  })
}

async function handleJourneyAction(
  page: ProjectPage,
  journey: PageAccessJourney,
  draft?: PageImplementationGoalDraft,
) {
  if (['SELECT_GOAL', 'START_IMPLEMENTATION'].includes(journey.nextAction.code)) {
    if (draft === undefined) {
      openPageDetail(page)
      return
    }
    const goalSelection = implementationGoalSelection(page, draft)
    if (journey.nextAction.code === 'SELECT_GOAL' && !goalSelection.items.length) {
      openPageDetail(page)
      return
    }
    await createJourneyActivity({
      page,
      taskKind: 'CODE_IMPLEMENTATION',
      executorProvider: 'CODEX',
      title: `接入：${pageWorkbenchPageName(page)}`,
      objective: pageImplementationObjective(goalSelection),
      goalSelection,
    })
    return
  }
  if (journey.nextAction.code === 'RETRY_TASK') {
    await retryJourneyTask(page, journey)
    return
  }
  if (journey.nextAction.code === 'OPEN_TASK') {
    if (journey.activeTaskId) await openTaskDetail(journey.activeTaskId)
    return
  }
  if (journey.nextAction.code === 'PUBLISH_WORKFLOW') {
    await openPublishConfirmation(page, journey)
    return
  }
  if (journey.nextAction.code === 'CHECK_PAGE_ACTIONS') {
    await openInlineDiagnostics(page)
    return
  }
  if (journey.nextAction.code === 'START_WORKFLOW_ENGINEERING') {
    await createJourneyActivity({
      page,
      taskKind: 'WORKFLOW_ENGINEERING',
      executorProvider: 'CODEX',
      title: `生成页面助手：${pageWorkbenchPageName(page)}`,
      objective: `基于当前已登记的页面操作（${page.actions.map((item) => item.actionKey).join('、')}）生成 PAGE_ASSISTANT 工作流草稿并完成确定性校验；不要自动发布或接入智能体。`,
    })
    return
  }
  if (['START_ACCEPTANCE', 'RETRY_ACCEPTANCE'].includes(journey.nextAction.code)) {
    const workflow = published.value.find((item) => item.workflowId === journey.workflowId)
      || published.value.find((item) => item.pageKey === page.pageKey)
    if (workflow) openAcceptancePreparation(page, workflow)
    else ElMessage.error('当前页面的已发布工作流不可用，无法锁定验收版本')
    return
  }
  if (journey.nextAction.code === 'VIEW_ONLINE') {
    closePageDetail()
    activeTab.value = 'online'
    return
  }
  if (journey.nextAction.code === 'REFRESH_STATUS') {
    await refreshAccessCenter()
  }
}

async function handleImplementationRetarget(
  page: ProjectPage,
  journey: PageAccessJourney,
  draft: PageImplementationGoalDraft,
) {
  const currentTask = tasks.value
    .filter((item) => (
      item.taskKind === 'CODE_IMPLEMENTATION'
      && taskPageKey(item) === page.pageKey
    ))
    .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))[0]
  if (!currentTask) {
    ElMessage.error('当前 AI 实施活动不可用，请刷新页面状态后重试')
    return
  }
  const goalSelection = implementationGoalSelection(page, draft)
  if (!goalSelection.items.length) {
    ElMessage.warning('请至少保留一个接入目标')
    return
  }
  const currentTaskActive = !aiCodingExecutionIsTerminal(currentTask.executionStatus)
  try {
    await ElMessageBox.confirm(
      currentTaskActive
        ? `将取消当前 AI 实施活动，并按调整后的 ${goalSelection.items.length} 个目标创建新活动。原活动和目标快照会继续保留。`
        : `将按调整后的 ${goalSelection.items.length} 个目标创建新的 AI 实施活动。原活动和目标快照会继续保留。`,
      '确认调整接入目标',
      {
        type: 'warning',
        confirmButtonText: '确认并重新实施',
        cancelButtonText: '继续调整',
      },
    )
  } catch {
    return
  }

  journeyBusy.value = true
  try {
    if (currentTaskActive) await cancelTask(currentTask.taskId)
    const result = await createTask({
      page,
      taskKind: 'CODE_IMPLEMENTATION',
      executorProvider: currentTask.executorProvider,
      title: `重新接入：${pageWorkbenchPageName(page)}`,
      objective: pageImplementationObjective(goalSelection),
      goalSelection,
    })
    setTaskHandoff(result.handoff)
    showTaskHandoff(result.handoff)
    try {
      await syncImplementationGoalStatuses(page, goalSelection)
    } catch (error) {
      ElMessage.warning(
        `新活动已创建且目标快照已保留，但目标选择状态同步失败：${(error as Error).message || '未知错误'}`,
      )
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '重新创建 AI 实施活动失败')
  } finally {
    journeyBusy.value = false
  }
}

function resetScanTaskForm() {
  Object.assign(scanTaskForm, {
    provider: 'CODEX',
    title: pageMap.value.scanned ? '增量更新页面地图' : '扫描项目并建立页面地图',
    objective: pageMap.value.scanned
      ? '基于当前分支增量同步业务模块、路由、页面组件和直接关联 API。'
      : '扫描当前项目，建立业务模块、路由、页面组件和直接关联 API 的页面地图。',
  })
}

function openTaskComposer(options?: {
  page?: ProjectPage | null
  taskType?: PageAiCodingTaskCommand['taskKind']
  mode?: TaskMode
  title?: string
  objective?: string
  workflow?: PublishedPageWorkflow | null
}) {
  const page = options?.page === undefined ? selectedPage.value : options.page
  Object.assign(taskForm, {
    mode: options?.mode || 'TARGET',
    pageId: page?.id || null,
    taskType: options?.taskType || 'CODE_IMPLEMENTATION',
    provider: 'CODEX',
    title: options?.title || '',
    objective: options?.objective || '',
    workflow: options?.workflow || null,
  })
  taskDialogStage.value = 'FORM'
  taskDialogVisible.value = true
}

function openFindingTask(finding: PageAnalysisFinding) {
  const page = pages.value.find((item) => item.id === finding.pageId) || null
  const findingTitle = pageWorkbenchHumanText(finding.title, '当前页面分析结果')
  openTaskComposer({
    page,
    taskType: 'CODE_IMPLEMENTATION',
    mode: 'TARGET',
    title: `实施：${findingTitle}`,
    objective: [
      pageWorkbenchHumanText(
        finding.implementationReference || finding.title,
        '按照当前已保留的页面分析结果完成实施。',
      ),
      finding.acceptanceCriteria
        ? `验收条件：${pageWorkbenchHumanText(
          finding.acceptanceCriteria,
          '完成相关测试和真实浏览器自检。',
        )}`
        : '',
    ]
      .filter(Boolean)
      .join('\n'),
  })
}

function changeTaskMode(mode: TaskMode) {
  taskForm.mode = mode
  if (mode === 'ANALYZE') {
    taskForm.taskType = 'PAGE_READONLY_ANALYSIS'
    const page = pages.value.find((item) => item.id === taskForm.pageId)
    if (!taskForm.title && page) {
      taskForm.title = `分析${pageWorkbenchPageName(page)}`
    }
    if (!taskForm.objective && page) {
      taskForm.objective = `只读分析${pageWorkbenchPageName(page)}当前可改进或可自动化的页面交互，并给出最多 3 条有依据的结果。`
    }
  } else if (taskForm.taskType === 'PAGE_READONLY_ANALYSIS') {
    taskForm.taskType = 'CODE_IMPLEMENTATION'
    taskForm.title = ''
    taskForm.objective = ''
  }
}

async function submitManualPage(request: ManualProjectPageRequest) {
  manualSubmitting.value = true
  try {
    await addManualPage(request)
    sourceDialogVisible.value = false
  } catch (error) {
    ElMessage.error((error as Error).message || '保存页面失败')
  } finally {
    manualSubmitting.value = false
  }
}

async function loadPageReadiness(page: ProjectPage) {
  readinessPage.value = page
  readinessResult.value = null
  readinessLoading.value = true
  try {
    readinessResult.value = await checkPageReadiness(page)
    return readinessResult.value
  } catch (error) {
    ElMessage.error((error as Error).message || '页面接入检查失败')
    return null
  } finally {
    readinessLoading.value = false
  }
}

function workflowEngineeringResult(
  detail: AiCodingTaskDetail | null,
): WorkflowEngineeringDraftResult | null {
  if (detail?.task.taskKind !== 'WORKFLOW_ENGINEERING') return null
  const artifacts = [...(detail.artifacts || [])].reverse()
  for (const artifact of artifacts) {
    if (artifact.processingStatus !== 'APPLIED' || !artifact.applicationResult) continue
    const value = artifact.applicationResult as {
      workflowEngineering?: WorkflowEngineeringDraftResult
    }
    if (value.workflowEngineering?.workflow?.id) return value.workflowEngineering
  }
  return null
}

async function openPublishConfirmation(page: ProjectPage, journey: PageAccessJourney) {
  if (!journey.activeTaskId) {
    ElMessage.error('当前页面没有可供审阅的工作流建设活动')
    return
  }
  selectedPageId.value = page.id
  publishReviewLoading.value = true
  publishResult.value = null
  try {
    const detail = await loadTaskDetail(journey.activeTaskId)
    const result = workflowEngineeringResult(detail)
    if (!result) {
      ElMessage.warning('当前活动还没有已应用的页面助手草稿，请先在活动详情中完成审阅')
      taskDrawerVisible.value = true
      return
    }
    publishResult.value = result
    publishConfirmVisible.value = true
  } catch (error) {
    ElMessage.error((error as Error).message || '读取页面助手草稿失败')
  } finally {
    publishReviewLoading.value = false
  }
}

function openWorkflowStudio(result: WorkflowEngineeringDraftResult) {
  router.push({
    name: 'WorkflowStudio',
    params: { workflowId: result.workflow.id },
  })
}

async function deliverWorkflow(
  result: WorkflowEngineeringDraftResult,
  confirmationCompleted = false,
  replaceWorkflowId: string | null = result.replaceWorkflowId || null,
) {
  const task = selectedTaskDetail.value?.task
  if (!task || task.taskId !== result.taskId) return
  if (!confirmationCompleted) {
    publishResult.value = result
    publishConfirmVisible.value = true
    return
  }
  taskDetailLoading.value = true
  try {
    const delivered = await deliverWorkflowTask(task.taskId, {
      pageKey: result.pageKey,
      version: 'v1.0.0',
      publishedBy: 'ReachAI Page Workbench',
      replaceWorkflowId,
    })
    ElMessage.success(
      `${delivered.workflowName} ${delivered.workflowVersion} 已发布并接入页面副驾驶`,
    )
    taskDrawerVisible.value = false
    publishConfirmVisible.value = false
    if (!detailMode.value) {
      activeTab.value = 'online'
    }
  } catch (error) {
    ElMessage.error((error as Error).message || '工作流发布接入失败')
  } finally {
    taskDetailLoading.value = false
  }
}

async function confirmWorkflowDelivery(
  result: WorkflowEngineeringDraftResult,
  replaceWorkflowId: string | null,
) {
  await deliverWorkflow(result, true, replaceWorkflowId)
}

function setTaskHandoff(handoff: AiCodingHandoffPackage) {
  handoffPackage.value = handoff
  handoffCopied.value = false
}

function showTaskHandoff(handoff: AiCodingHandoffPackage) {
  setTaskHandoff(handoff)
  handoffDialogVisible.value = true
}

async function submitPageMapScan() {
  if (!scanTaskForm.objective.trim()) {
    ElMessage.warning('请填写这次扫描任务的目标')
    return
  }

  scanSubmitting.value = true
  try {
    const result = await createTask({
      taskKind: 'PAGE_MAP_SCAN',
      executorProvider: scanTaskForm.provider,
      title: scanTaskForm.title.trim() || '扫描项目并建立页面地图',
      objective: scanTaskForm.objective.trim(),
    })
    setTaskHandoff(result.handoff)
    sourceDialogStage.value = 'HANDOFF'
  } catch (error) {
    ElMessage.error((error as Error).message || '创建页面地图扫描任务失败')
  } finally {
    scanSubmitting.value = false
  }
}

async function submitTask() {
  const effectiveType =
    taskForm.mode === 'ANALYZE' ? 'PAGE_READONLY_ANALYSIS' : taskForm.taskType
  if (effectiveType !== 'PAGE_MAP_SCAN' && !taskForm.pageId) {
    ElMessage.warning('请选择当前任务关联的页面')
    return
  }
  if (!taskForm.objective.trim()) {
    ElMessage.warning('请填写这次任务的目标')
    return
  }

  taskSubmitting.value = true
  try {
    const result = await createTask({
      page: pages.value.find((page) => page.id === taskForm.pageId) || null,
      taskKind: effectiveType,
      executorProvider: taskForm.provider,
      title: taskForm.title.trim() || 'AI 编程任务',
      objective: taskForm.objective.trim(),
      workflow:
        effectiveType === 'BROWSER_ACCEPTANCE'
          ? taskForm.workflow
          : null,
    })
    setTaskHandoff(result.handoff)
    taskDialogStage.value = 'HANDOFF'
  } catch (error) {
    ElMessage.error((error as Error).message || '创建 AI 编程任务失败')
  } finally {
    taskSubmitting.value = false
  }
}

async function copyHandoffPrompt() {
  const prompt = handoffPackage.value?.prompt
  if (!prompt) {
    ElMessage.error('当前任务没有可复制的交接包，请重新生成')
    return
  }
  const copied = await copyAiCodingText(prompt)
  if (copied.copied) {
    handoffCopied.value = true
    ElMessage.success('交接包已复制，等待 AI 编程工具激活当前任务')
    return
  }
  handoffCopied.value = false
  ElMessage.warning('无法自动复制，交接包仍保留在文本框中，请手动选择全部内容后复制。')
}

async function reopenTaskHandoff(taskId: string) {
  try {
    const result = await loadHandoffPrompt(taskId)
    taskDrawerVisible.value = false
    showTaskHandoff(result)
  } catch (error) {
    ElMessage.error((error as Error).message || '加载任务交接包失败')
  }
}

async function updateFindingStatus(
  finding: PageAnalysisFinding,
  status: AnalysisFindingStatus,
) {
  try {
    const updated = await changeFindingStatus(finding.id, status)
    if (selectedFinding.value?.id === updated.id) selectedFinding.value = updated
    ElMessage.success(status === 'KEPT' ? '分析结果已保留' : '分析结果已忽略')
  } catch (error) {
    ElMessage.error((error as Error).message || '更新分析结果失败')
  }
}

function openFindingDetail(finding: PageAnalysisFinding) {
  selectedFinding.value = finding
  findingDrawerVisible.value = true
}

async function openTaskDetail(task: AiCodingTask | string) {
  taskDrawerVisible.value = true
  taskDetailLoading.value = true
  try {
    const taskId = typeof task === 'string' ? task : task.taskId
    await loadTaskDetail(taskId)
  } catch (error) {
    ElMessage.error((error as Error).message || '加载任务详情失败')
    taskDrawerVisible.value = false
  } finally {
    taskDetailLoading.value = false
  }
}

async function handleTaskAnswer(
  taskId: string,
  questionId: string,
  answer: string,
) {
  taskDetailLoading.value = true
  try {
    await answerQuestion(taskId, questionId, answer)
  } catch (error) {
    ElMessage.error((error as Error).message || '回答写回失败')
  } finally {
    taskDetailLoading.value = false
  }
}

async function handleTaskRefresh(taskId: string) {
  taskDetailLoading.value = true
  try {
    await loadTaskDetail(taskId)
    await refreshTasks()
  } catch (error) {
    ElMessage.error((error as Error).message || '刷新任务详情失败')
  } finally {
    taskDetailLoading.value = false
  }
}

async function handleTaskAcceptance(
  taskId: string,
  passed: boolean,
  message: string,
) {
  taskDetailLoading.value = true
  try {
    await finishTaskAcceptance(taskId, passed, message)
  } catch (error) {
    ElMessage.error((error as Error).message || '写入验收结果失败')
  } finally {
    taskDetailLoading.value = false
  }
}

async function handleTaskVerification(taskId: string) {
  taskDetailLoading.value = true
  try {
    const result = await verifyTaskAcceptanceReadiness(taskId)
    ElMessage[result.acceptanceReady ? 'success' : 'warning'](
      result.acceptanceReady
        ? '平台验证已通过，现在可以验收'
        : `仍有 ${result.blockers.length} 项未通过平台验证`,
    )
  } catch (error) {
    ElMessage.error((error as Error).message || '平台验证失败')
  } finally {
    taskDetailLoading.value = false
  }
}

async function handleTaskCancel(taskId: string) {
  taskDetailLoading.value = true
  try {
    await cancelTask(taskId)
  } catch (error) {
    ElMessage.error((error as Error).message || '取消任务失败')
  } finally {
    taskDetailLoading.value = false
  }
}

async function startPublishedAcceptance(workflow: PublishedPageWorkflow) {
  const page = pages.value.find((item) => item.pageKey === workflow.pageKey)
  if (!page) {
    ElMessage.error('发布绑定的页面不在当前页面地图中，无法发起验收')
    return
  }
  openAcceptancePreparation(page, workflow)
}

function openPublishedRun(workflow: PublishedPageWorkflow) {
  if (!workflow.latestTraceId) return
  router.push({ name: 'RunOpsDetail', params: { traceId: workflow.latestTraceId } })
}

function businessPageUrl(page?: ProjectPage | null) {
  return normalizeBusinessPageUrl(page?.businessPageUrl)
}

async function refreshWorkbenchStatus() {
  try {
    await loadAll()
    if (loadError.value) {
      ElMessage.error(loadError.value)
      return
    }
    const currentDetailPage = detailPageId.value == null
      ? null
      : pages.value.find((page) => page.id === detailPageId.value) || null
    if (currentDetailPage) {
      selectedPageId.value = currentDetailPage.id
      await loadPageReadiness(currentDetailPage)
    }
    ElMessage.success('已刷新当前项目的真实状态')
  } catch (error) {
    ElMessage.error((error as Error).message || '刷新当前项目状态失败')
  }
}

function formatDateTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function taskPageKey(task?: AiCodingTask) {
  return task?.targets.find(
    (target) => target.targetRole === 'PRIMARY' && target.targetType === 'PAGE',
  )?.targetKey
}

function findingStatusLabel(status?: string) {
  return (
    {
      UNREAD: '待确认',
      KEPT: '已加入',
      IGNORED: '已忽略',
    }[status || ''] || status || '—'
  )
}

function findingMetaLabel(value?: string, partialLabel = '部分满足') {
  if (value === 'PARTIAL') return partialLabel
  return (
    {
      PENDING: '待确认',
      CONFIRMED: '已确认',
      REJECTED: '不采用',
      FEASIBLE: '可实施',
      NOT_FEASIBLE: '不可实施',
      UNKNOWN: '待评估',
      LOW: '低',
      MEDIUM: '中',
      HIGH: '高',
      COMPLETE: '完整',
      MISSING: '信息不足',
    }[value || ''] || value || '—'
  )
}

function jsonText(value: unknown) {
  if (value == null) return '暂无'
  if (typeof value === 'string') return value
  return JSON.stringify(value, null, 2)
}

watch(projectCode, loadAll, { immediate: true })
</script>

<template>
  <div class="business-page-workbench project-workbench-page">
    <PageHeader
      v-if="!detailMode"
      title="页面接入中心"
      description="选择一个业务页面，ReachAI 会根据真实状态告诉你下一步；无需理解任务类型。"
      variant="workbench"
      height-preset="standard"
      domain="project"
    >
      <template #tags>
        <el-tag effect="light">页面接入</el-tag>
        <el-tag type="info" effect="light">{{ projectCode }}</el-tag>
      </template>
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="openPageSourceDialog()">
          扫描新页面
        </el-button>
      </template>
    </PageHeader>

    <ProjectRouteMissingState
      v-if="projectMissing"
      :project-code="projectCode"
    />

    <main v-else-if="detailMode" class="page-access-detail-route">
      <ProjectWorkbenchLoadErrorState
        v-if="loadError"
        title="页面接入工作台加载失败"
        :message="loadError"
        :loading="loading"
        @retry="loadAll"
      />
      <PageWorkbenchPageDetail
        v-else-if="selectedPage"
        :page="selectedPage"
        :journey="selectedJourney"
        :findings="selectedPageFindings"
        :task="selectedPageTask"
        :task-detail="selectedPageTaskDetail"
        :task-detail-loading="implementationTaskDetailLoading"
        :tasks="selectedPageTasks"
        :workflow="selectedPageWorkflow"
        :readiness="selectedPageReadiness"
        :readiness-loading="readinessLoading"
        v-model:capability-workspace="detailCapabilityWorkspace"
        :debug-action-id="detailDebugActionId"
        :business-page-url="selectedBusinessPageUrl"
        :runtime-available="accessCenter.runtimeAvailable"
        :runtime-message="accessCenter.runtimeMessage"
        :busy="journeyBusy || taskDetailLoading || implementationTaskDetailLoading"
        @back="closePageDetail"
        @edit-page="editPageFromDetail"
        @page-info="openPageInformation"
        @debug-action="openInlineDebugWorkspace"
        @check-readiness="checkReadinessFromDetail"
        @debug-success="loadPageReadiness"
        @analyze="analyzePageFromDetail"
        @finding-status="updateFindingStatus"
        @finding-detail="openFindingDetail"
        @primary="handleJourneyAction"
        @retarget="handleImplementationRetarget"
        @history="openJourneyHistory"
        @refresh="refreshWorkbenchStatus"
        @online="openJourneyOnline"
        @task-refresh="handleTaskRefresh"
        @task-answer="handleTaskAnswer"
        @task-reissue="reopenTaskHandoff"
        @task-verify="handleTaskVerification"
        @task-acceptance="handleTaskAcceptance"
        @task-cancel="handleTaskCancel"
      />
      <section v-else v-loading="loading" class="page-detail-route-loading">
        <el-skeleton :rows="8" animated />
      </section>
    </main>

    <main v-else class="page-workbench-shell">
      <PageWorkbenchTabs
        v-model="activeTab"
        :page-count="accessCenter.summary.discoveredCount"
        :activity-count="accessCenter.activities.length"
        :online-count="accessCenter.onlineCapabilities.length"
      />

      <ProjectWorkbenchLoadErrorState
        v-if="loadError"
        title="业务页面工作台加载失败"
        :message="loadError"
        :loading="loading"
        @retry="loadAll"
      />

      <template v-else>
        <el-alert
          v-if="!accessCenter.runtimeAvailable"
          class="page-access-runtime-warning"
          type="warning"
          :closable="false"
          show-icon
          :title="accessCenter.runtimeMessage"
          description="页面、资源与活动仍可使用；系统不会把运行数据不可用误判为未发布。"
        />

        <PageWorkbenchMapPanel
          v-if="activeTab === 'pages'"
          :pages="pages"
          :journeys="accessCenter.pages"
          :summary="accessCenter.summary"
          :modules="modules"
          :loading="loading"
          @scan="openPageSourceDialog"
          @detail="openPageDetail"
          @action="handleJourneyAction"
          @refresh="refreshPageAccessCenter"
        />

        <PageWorkbenchTasksPanel
          v-else-if="activeTab === 'activities'"
          variant="activity"
          :tasks="tasks"
          :loading="tasksLoading"
          :unavailable="tasksLoadError"
          @detail="openTaskDetail"
          @refresh="refreshPageAccessCenter"
        />

        <PageWorkbenchPublishedPanel
          v-else
          :workflows="published"
          :unavailable="publishedUnavailable"
          :loading="loading"
          @refresh="refreshAccessCenter"
          @accept="startPublishedAcceptance"
          @run="openPublishedRun"
        />
      </template>
    </main>

    <AppDialog
      v-model="sourceDialogVisible"
      :title="sourceDialogStage === 'FORM' ? '扫描 / 添加页面' : '交给 AI 编程工具'"
      width="min(820px, calc(100vw - 32px))"
      class="page-workbench-dialog page-source-dialog"
      :close-on-click-modal="sourceDialogStage === 'FORM' || handoffCopied"
      destroy-on-close
    >
      <section
        v-if="sourceDialogStage === 'HANDOFF'"
        class="page-source-pane page-source-pane--handoff"
      >
        <AiCodingHandoffPanel :handoff="handoffPackage" />
        <footer class="page-source-pane__footer">
          <p>交接包仅能激活一次；稍后可从任务详情重新生成。</p>
          <div class="page-source-pane__actions">
            <el-button @click="sourceDialogVisible = false">
              {{ handoffCopied ? '完成' : '稍后处理' }}
            </el-button>
            <el-button
              type="primary"
              :icon="handoffCopied ? Check : CopyDocument"
              :disabled="handoffCopied"
              @click="copyHandoffPrompt"
            >
              {{ handoffCopied ? '已复制，等待对接' : '复制交接包' }}
            </el-button>
          </div>
        </footer>
      </section>
      <el-tabs v-else v-model="sourceDialogTab" class="page-source-tabs" stretch>
        <el-tab-pane name="scan">
          <template #label>
            <span class="page-source-tab-label">
              <span class="page-source-tab-label__icon"><el-icon><MagicStick /></el-icon></span>
              <strong>AI 编程扫描</strong>
              <small>推荐</small>
            </span>
          </template>

          <section class="page-source-pane page-source-pane--form">
            <el-form class="page-source-form" label-position="top">
              <div class="form-grid form-grid--two">
                <el-form-item label="AI 编程工具" required>
                  <AiCodingProviderSelector
                    v-model="scanTaskForm.provider"
                    aria-label="选择页面扫描使用的 AI 编程工具"
                  />
                </el-form-item>
                <el-form-item label="任务标题">
                  <el-input
                    v-model="scanTaskForm.title"
                    placeholder="便于团队识别这次扫描任务"
                  />
                </el-form-item>
              </div>

              <el-form-item label="任务目标" required>
                <el-input
                  v-model="scanTaskForm.objective"
                  type="textarea"
                  :rows="4"
                  placeholder="描述扫描范围、边界和期望结果"
                />
              </el-form-item>

              <div class="task-contract-note">
                <el-icon><Connection /></el-icon>
                <span>
                  创建后复制一次性交接包到
                {{ aiCodingProviderLabel(scanTaskForm.provider) }}。
                  会话激活后，进度、问题和结果会回到当前任务。
                </span>
              </div>
            </el-form>
            <footer class="page-source-pane__footer">
              <p>{{ pageMap.scanned ? '仅同步当前分支中的页面变化。' : '首次扫描只建立页面地图。' }}</p>
              <div class="page-source-pane__actions">
                <el-button @click="sourceDialogVisible = false">取消</el-button>
                <el-button
                  type="primary"
                  :icon="Promotion"
                  :loading="scanSubmitting"
                  @click="submitPageMapScan"
                >
                  创建并生成交接包
                </el-button>
              </div>
            </footer>
          </section>
        </el-tab-pane>

        <el-tab-pane name="manual">
          <template #label>
            <span class="page-source-tab-label">
              <span class="page-source-tab-label__icon"><el-icon><Document /></el-icon></span>
              <strong>手动添加</strong>
            </span>
          </template>

          <PageWorkbenchManualPageForm
            v-if="project?.id"
            :project-id="project.id"
            :page="manualPage"
            :submitting="manualSubmitting"
            @submit="submitManualPage"
            @cancel="sourceDialogVisible = false"
          />
        </el-tab-pane>
      </el-tabs>
    </AppDialog>

    <AppDialog
      v-model="taskDialogVisible"
      :title="taskDialogStage === 'FORM' ? '新建 AI 编程任务' : '交给 AI 编程工具'"
      width="760px"
      class="page-workbench-dialog task-editor-dialog"
      :close-on-click-modal="taskDialogStage === 'FORM' || handoffCopied"
    >
      <AiCodingHandoffPanel
        v-if="taskDialogStage === 'HANDOFF'"
        :handoff="handoffPackage"
      />
      <el-form v-else label-position="top">
        <el-form-item v-if="taskForm.taskType !== 'PAGE_MAP_SCAN'" label="任务方式">
          <div class="task-mode-options">
            <button
              :class="{ 'is-active': taskForm.mode === 'TARGET' }"
              type="button"
              @click="changeTaskMode('TARGET')"
            >
              <el-icon><Check /></el-icon>
              <span>
                <strong>我有明确目标</strong>
                <small>工作流建设、代码实施、浏览器验收或发布前检查</small>
              </span>
            </button>
            <button
              :class="{ 'is-active': taskForm.mode === 'ANALYZE' }"
              type="button"
              @click="changeTaskMode('ANALYZE')"
            >
              <el-icon><Search /></el-icon>
              <span>
                <strong>先分析当前页面</strong>
                <small>限定当前页面的只读分析，最多返回 3 条结果</small>
              </span>
            </button>
          </div>
        </el-form-item>

        <div class="form-grid form-grid--two">
          <el-form-item label="AI 编程工具" required>
            <AiCodingProviderSelector
              v-model="taskForm.provider"
              aria-label="选择任务使用的 AI 编程工具"
            />
          </el-form-item>
          <el-form-item
            v-if="taskForm.taskType !== 'PAGE_MAP_SCAN'"
            label="当前页面"
            required
          >
            <el-select v-model="taskForm.pageId" filterable placeholder="选择页面">
              <el-option
                v-for="page in pages"
                :key="page.id"
                :label="pageWorkbenchPageName(page)"
                :value="page.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item
            v-if="taskForm.mode === 'TARGET' && taskForm.taskType !== 'PAGE_MAP_SCAN'"
            label="任务类型"
            required
          >
            <el-select v-model="taskForm.taskType">
              <el-option
                v-for="option in taskTypeOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="任务标题">
            <el-input v-model="taskForm.title" placeholder="便于团队识别这次任务" />
          </el-form-item>
        </div>

        <div
          v-if="taskForm.taskType === 'BROWSER_ACCEPTANCE' && taskForm.workflow"
          class="task-bound-workflow"
        >
          <span>本次验收目标</span>
          <strong>{{ taskForm.workflow.workflowName }}</strong>
          <small>{{ taskForm.workflow.workflowVersion }}</small>
        </div>

        <el-form-item label="任务目标" required>
          <el-input
            v-model="taskForm.objective"
            type="textarea"
            :rows="5"
            placeholder="描述你的业务目标、边界和期望结果"
          />
        </el-form-item>

        <div class="task-contract-note">
          <el-icon><Connection /></el-icon>
          <span>
            创建后复制一次性交接包到
            {{ aiCodingProviderLabel(taskForm.provider) }}。
            会话激活后，进度、问题、结果和验收材料会通过当前任务接口回到 ReachAI。
          </span>
        </div>
      </el-form>
      <template #footer>
        <template v-if="taskDialogStage === 'FORM'">
          <el-button @click="taskDialogVisible = false">取消</el-button>
          <el-button type="primary" :loading="taskSubmitting" @click="submitTask">
            创建并生成交接包
          </el-button>
        </template>
        <template v-else>
          <el-button @click="taskDialogVisible = false">
            {{ handoffCopied ? '完成' : '稍后处理' }}
          </el-button>
          <el-button
            type="primary"
            :icon="handoffCopied ? Check : CopyDocument"
            :disabled="handoffCopied"
            @click="copyHandoffPrompt"
          >
            {{ handoffCopied ? '已复制，等待对接' : '复制交接包' }}
          </el-button>
        </template>
      </template>
    </AppDialog>

    <AppDialog
      v-model="handoffDialogVisible"
      title="交给 AI 编程工具"
      width="760px"
      class="page-workbench-dialog handoff-dialog"
      :close-on-click-modal="handoffCopied"
    >
      <AiCodingHandoffPanel :handoff="handoffPackage" />
      <template #footer>
        <el-button @click="handoffDialogVisible = false">
          {{ handoffCopied ? '完成' : '稍后处理' }}
        </el-button>
        <el-button
          type="primary"
          :icon="handoffCopied ? Check : CopyDocument"
          :disabled="handoffCopied"
          @click="copyHandoffPrompt"
        >
          {{ handoffCopied ? '已复制，等待对接' : '复制交接包' }}
        </el-button>
      </template>
    </AppDialog>

    <PageWorkbenchAnalysisRecommendationDialog
      v-model="analysisRecommendationVisible"
      :page="selectedPage"
      :busy="journeyBusy"
      @create="createAnalysisRecommendation"
    />

    <PageWorkbenchPageInfoDialog
      v-model="pageInfoVisible"
      :page="selectedPage"
      @edit="editPageFromDetail"
    />

    <PageWorkbenchHistoryDrawer
      v-model="historyVisible"
      :page="selectedPage"
      :tasks="selectedPageTasks"
      :workflow="selectedPageWorkflow"
      @task="openTaskDetail"
      @run="openPublishedRun"
    />

    <PageWorkbenchPublishConfirmDialog
      v-model="publishConfirmVisible"
      :page="selectedPage"
      :result="publishResult"
      :existing-workflows="selectedPagePublishedWorkflows"
      :busy="taskDetailLoading || publishReviewLoading"
      @studio="openWorkflowStudio"
      @confirm="confirmWorkflowDelivery"
    />

    <PageWorkbenchAcceptanceDialog
      v-model="acceptanceDialogVisible"
      :page="acceptanceTargetPage"
      :workflow="acceptanceTargetWorkflow"
      :model-readiness="acceptanceModelReadiness"
      :preflight-loading="readinessLoading"
      :busy="journeyBusy"
      @confirm="confirmAcceptanceActivity"
      @model-center="openModelCenterFromAcceptance"
    />

    <AppDrawer
      v-model="findingDrawerVisible"
      title="分析结果详情"
      size="680px"
      class="page-workbench-drawer"
    >
      <article v-if="selectedFinding" class="finding-detail">
        <header>
          <span>{{ selectedFinding.pageKey }}</span>
          <h2>{{ pageWorkbenchHumanText(selectedFinding.title, '页面分析结果') }}</h2>
          <el-tag effect="plain">{{ findingStatusLabel(selectedFinding.status) }}</el-tag>
        </header>

        <dl class="finding-detail__summary">
          <div>
            <dt>业务确认状态</dt>
            <dd>{{ findingMetaLabel(selectedFinding.businessConfirmStatus) }}</dd>
          </div>
          <div>
            <dt>技术可行性</dt>
            <dd>{{ findingMetaLabel(selectedFinding.technicalFeasibility, '部分可实施') }}</dd>
          </div>
          <div>
            <dt>操作风险</dt>
            <dd>{{ findingMetaLabel(selectedFinding.operationRisk) }}</dd>
          </div>
          <div>
            <dt>信息完整度</dt>
            <dd>{{ findingMetaLabel(selectedFinding.informationCompleteness, '部分完整') }}</dd>
          </div>
        </dl>

        <section>
          <h3>使用示例</h3>
          <p>
            {{
              pageWorkbenchHumanText(
                selectedFinding.useCase,
                '尚未提供中文使用示例。',
              )
            }}
          </p>
        </section>
        <section>
          <h3>已确认事实</h3>
          <p>
            {{
              pageWorkbenchHumanText(
                selectedFinding.confirmedFact,
                '当前结果未提供中文事实说明，请重新发起页面分析。',
              )
            }}
          </p>
        </section>
        <section v-if="selectedFinding.technicalInference">
          <h3>技术推断</h3>
          <p>
            {{
              pageWorkbenchHumanText(
                selectedFinding.technicalInference,
                '当前结果未提供中文技术推断。',
              )
            }}
          </p>
        </section>
        <section v-if="selectedFinding.openQuestion">
          <h3>仍需确认</h3>
          <p>
            {{
              pageWorkbenchHumanText(
                selectedFinding.openQuestion,
                '当前结果未提供中文待确认事项。',
              )
            }}
          </p>
        </section>
        <section>
          <h3>分析依据</h3>
          <pre>{{ jsonText(selectedFinding.evidence) }}</pre>
        </section>
        <section>
          <h3>相关代码和已有配置</h3>
          <pre>{{ jsonText(selectedFinding.codeReferences) }}</pre>
        </section>
        <section>
          <h3>实现参考</h3>
          <p>
            {{
              pageWorkbenchHumanText(
                selectedFinding.implementationReference,
                '尚未提供中文实现参考。',
              )
            }}
          </p>
        </section>
        <section>
          <h3>验收条件</h3>
          <p>
            {{
              pageWorkbenchHumanText(
                selectedFinding.acceptanceCriteria,
                '尚未提供中文验收条件。',
              )
            }}
          </p>
        </section>

        <footer>
          <el-button
            v-if="selectedFinding.status === 'KEPT'"
            @click="updateFindingStatus(selectedFinding, 'UNREAD')"
          >
            移出目标
          </el-button>
          <el-button
            v-else
            @click="updateFindingStatus(selectedFinding, 'KEPT')"
          >
            加入目标
          </el-button>
          <el-button
            v-if="selectedFinding.status === 'KEPT'"
            type="primary"
            @click="findingDrawerVisible = false; openFindingTask(selectedFinding)"
          >
            新建 AI 编程任务
          </el-button>
        </footer>
      </article>
    </AppDrawer>

    <AppDrawer
      v-model="taskDrawerVisible"
      title="AI 任务详情"
      size="720px"
      class="page-workbench-drawer"
    >
      <template #header="{ titleId, titleClass }">
        <div class="ai-task-drawer-header">
          <h4 :id="titleId" :class="titleClass">AI 任务详情</h4>
          <el-button
            :icon="Refresh"
            :loading="taskDetailLoading"
            :disabled="!selectedTaskDetail?.task?.taskId"
            @click="selectedTaskDetail?.task && handleTaskRefresh(selectedTaskDetail.task.taskId)"
          >
            刷新
          </el-button>
        </div>
      </template>
      <PageWorkbenchWorkflowDeliveryCard
        :detail="selectedTaskDetail"
        :busy="taskDetailLoading"
        @open-studio="openWorkflowStudio"
        @deliver="deliverWorkflow"
      />
      <AiCodingTaskDetailPanel
        :detail="selectedTaskDetail"
        :loading="taskDetailLoading"
        :busy="taskDetailLoading"
        @refresh="handleTaskRefresh"
        @answer="handleTaskAnswer"
        @reissue="reopenTaskHandoff"
        @verify="handleTaskVerification"
        @acceptance="handleTaskAcceptance"
        @cancel="handleTaskCancel"
      />
    </AppDrawer>
  </div>
</template>

<style src="./styles/PageWorkbench.scss" lang="scss"></style>
