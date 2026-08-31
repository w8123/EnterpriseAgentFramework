import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createWorkbenchPage,
  deliverWorkflowEngineeringTask,
  getPageAccessCenterOverview,
  getWorkbenchPageReadiness,
  getPageMapSummary,
  listPageAnalysisFindings,
  listWorkbenchPages,
  updatePageAnalysisFindingStatus,
} from '@/api/pageWorkbench'
import { useAiCodingTask } from '@/composables/useAiCodingTask'
import { getScanProjects } from '@/api/scanProject'
import type {
  AiCodingAccessMode,
  AiCodingExecutionMode,
  AiCodingExecutorProvider,
  AiCodingHandoffPackage,
  AiCodingManagedExecutionDetail,
  AiCodingTask,
  AiCodingTaskDetail,
  AiCodingTaskTarget,
} from '@/types/aiCodingTask'
import type {
  AnalysisFindingStatus,
  ManualProjectPageRequest,
  PageAccessCenterOverview,
  PageImplementationGoalSelection,
  PageIntegrationReadiness,
  PageAnalysisFinding,
  PageMapSummary,
  ProjectPage,
  PublishedPageWorkflow,
  WorkflowDeliveryRequest,
  WorkflowDeliveryResult,
} from '@/types/pageWorkbench'
import type { ScanProject } from '@/types/scanProject'

export interface PageModuleView {
  key: string
  name: string
  count: number
}

export interface PageAiCodingTaskCommand {
  taskKind:
    | 'PAGE_MAP_SCAN'
    | 'PAGE_READONLY_ANALYSIS'
    | 'WORKFLOW_ENGINEERING'
    | 'CODE_IMPLEMENTATION'
    | 'BROWSER_ACCEPTANCE'
    | 'PRE_RELEASE_CHECK'
  executorProvider: AiCodingExecutorProvider
  executionMode?: AiCodingExecutionMode
  title: string
  objective: string
  goalSelection?: PageImplementationGoalSelection
  page?: ProjectPage | null
  workflow?: PublishedPageWorkflow | null
  createdBy?: string
}

export interface PageAiCodingTaskResult {
  task: AiCodingTask
  handoff: AiCodingHandoffPackage | null
  managedExecution: AiCodingManagedExecutionDetail | null
}

function emptyAccessCenter(projectCode = ''): PageAccessCenterOverview {
  return {
    schema: 'reachai.page-access-center.overview.v1',
    projectCode,
    summary: {
      discoveredCount: 0,
      waitingCount: 0,
      activeCount: 0,
      awaitingAcceptanceCount: 0,
      completedCount: 0,
      unavailableCount: 0,
    },
    pages: [],
    activities: [],
    onlineCapabilities: [],
    runtimeAvailable: true,
    runtimeMessage: '',
    generatedAt: '',
    catalogPages: [],
    pageMap: { scanned: false },
    findings: [],
    tasks: [],
  }
}

function hasBootstrapPayload(value: PageAccessCenterOverview) {
  return Array.isArray(value.catalogPages)
    && value.pageMap != null
    && Array.isArray(value.findings)
    && Array.isArray(value.tasks)
}

export function useBusinessPageWorkbench() {
  const route = useRoute()
  const projectCode = computed(() => String(route.params.projectCode || ''))
  const project = ref<ScanProject | null>(null)
  const pages = ref<ProjectPage[]>([])
  const accessCenter = ref<PageAccessCenterOverview>(emptyAccessCenter())
  const findings = ref<PageAnalysisFinding[]>([])
  const taskKernel = useAiCodingTask()
  const tasks = taskKernel.tasks
  const published = ref<PublishedPageWorkflow[]>([])
  const pageMap = ref<PageMapSummary>({
    scanned: false,
  })
  const selectedPageId = ref<number | null>(null)
  const loading = ref(false)
  const projectMissing = ref(false)
  const loadError = ref('')
  const tasksLoadError = ref('')
  const tasksLoading = ref(false)
  const publishedUnavailable = ref(false)
  const selectedTaskDetail = taskKernel.selectedTaskDetail
  let loadedProjectCode = ''
  let loadSequence = 0
  let publishedLoadSequence = 0
  let taskLoadSequence = 0

  const selectedPage = computed(
    () => pages.value.find((page) => page.id === selectedPageId.value) || null,
  )

  const modules = computed<PageModuleView[]>(() => {
    const map = new Map<string, PageModuleView>()
    for (const page of pages.value) {
      const key = page.moduleKey || '__ungrouped__'
      const current = map.get(key)
      if (current) {
        current.count += 1
      } else {
        map.set(key, {
          key,
          name: page.moduleName || (key === '__ungrouped__' ? '未分组' : key),
          count: 1,
        })
      }
    }
    return [...map.values()].sort((left, right) => left.name.localeCompare(right.name, 'zh-CN'))
  })

  function clearWorkbenchData() {
    taskLoadSequence += 1
    publishedLoadSequence += 1
    pages.value = []
    accessCenter.value = emptyAccessCenter(projectCode.value)
    findings.value = []
    published.value = []
    pageMap.value = { scanned: false }
    selectedPageId.value = null
    publishedUnavailable.value = false
    tasksLoadError.value = ''
    tasksLoading.value = false
    taskKernel.reset()
  }

  async function loadAll() {
    const requestedProjectCode = projectCode.value
    if (!requestedProjectCode) return
    const currentLoadSequence = ++loadSequence
    if (loadedProjectCode !== requestedProjectCode) {
      loadedProjectCode = requestedProjectCode
      project.value = null
      clearWorkbenchData()
    }
    loading.value = true
    projectMissing.value = false
    loadError.value = ''
    try {
      const projectsResponse = await getScanProjects()
      if (currentLoadSequence !== loadSequence) return
      const matchedProject =
        (projectsResponse.data || []).find(
          (item) => item.projectCode === requestedProjectCode,
        ) || null
      project.value = matchedProject
      if (!matchedProject?.id) {
        clearWorkbenchData()
        projectMissing.value = true
        return
      }
      const accessCenterResponse = await getPageAccessCenterOverview(
        requestedProjectCode,
      )
      if (currentLoadSequence !== loadSequence) return
      applyAccessCenter(accessCenterResponse.data)
      if (hasBootstrapPayload(accessCenterResponse.data)) {
        pages.value = accessCenterResponse.data.catalogPages || []
        pageMap.value = accessCenterResponse.data.pageMap || { scanned: false }
        findings.value = accessCenterResponse.data.findings || []
        tasks.value = accessCenterResponse.data.tasks || []
        tasksLoadError.value = ''
      } else {
        const [pagesResponse, mapResponse, findingsResponse] =
          await Promise.all([
            listWorkbenchPages(requestedProjectCode),
            getPageMapSummary(requestedProjectCode),
            listPageAnalysisFindings(requestedProjectCode),
            refreshTasks().catch(() => undefined),
          ])
        if (currentLoadSequence !== loadSequence) return
        pages.value = pagesResponse.data || []
        pageMap.value = mapResponse.data
        findings.value = findingsResponse.data || []
      }
      if (
        selectedPageId.value == null ||
        !pages.value.some((page) => page.id === selectedPageId.value)
      ) {
        selectedPageId.value = pages.value[0]?.id ?? null
      }
    } catch (error) {
      if (currentLoadSequence !== loadSequence) return
      clearWorkbenchData()
      tasksLoadError.value = '页面接入状态尚未加载，重新加载成功前不能创建任务'
      const message = (error as Error).message
      loadError.value = message
        ? `无法读取当前项目的页面数据：${message}`
        : '无法读取当前项目的页面数据，请检查服务状态后重新加载。'
    } finally {
      if (currentLoadSequence === loadSequence) {
        loading.value = false
      }
    }
  }

  async function refreshPages() {
    const requestedProjectCode = projectCode.value
    const [pagesResponse, mapResponse, accessCenterResponse] = await Promise.all([
      listWorkbenchPages(requestedProjectCode),
      getPageMapSummary(requestedProjectCode),
      getPageAccessCenterOverview(requestedProjectCode),
    ])
    if (requestedProjectCode !== projectCode.value) return
    pages.value = pagesResponse.data || []
    pageMap.value = mapResponse.data
    applyAccessCenter(accessCenterResponse.data)
    if (!pages.value.some((page) => page.id === selectedPageId.value)) {
      selectedPageId.value = pages.value[0]?.id ?? null
    }
  }

  async function refreshFindings() {
    const requestedProjectCode = projectCode.value
    const response = await listPageAnalysisFindings(requestedProjectCode)
    if (requestedProjectCode !== projectCode.value) return
    findings.value = response.data || []
    await refreshAccessCenter(requestedProjectCode)
  }

  async function refreshTasks() {
    const requestedProjectCode = projectCode.value
    const currentLoadSequence = ++taskLoadSequence
    tasksLoading.value = true
    try {
      const rows = await taskKernel.loadTasks({
        projectCode: requestedProjectCode,
        limit: 100,
      })
      if (
        currentLoadSequence !== taskLoadSequence
        || requestedProjectCode !== projectCode.value
      ) return rows
      tasks.value = rows
      tasksLoadError.value = ''
      return rows
    } catch (error) {
      if (
        currentLoadSequence === taskLoadSequence
        && requestedProjectCode === projectCode.value
      ) {
        tasks.value = []
        selectedTaskDetail.value = null
        const message = (error as Error).message
        tasksLoadError.value = message
          ? `无法读取当前项目的 AI 编程任务：${message}`
          : '无法读取当前项目的 AI 编程任务，请重新加载后再创建任务。'
      }
      throw error
    } finally {
      if (currentLoadSequence === taskLoadSequence) {
        tasksLoading.value = false
      }
    }
  }

  function applyAccessCenter(value: PageAccessCenterOverview) {
    accessCenter.value = value
    published.value = value.onlineCapabilities || []
    publishedUnavailable.value = !value.runtimeAvailable
  }

  async function refreshAccessCenter(
    requestedProjectCode = projectCode.value,
  ) {
    const currentLoadSequence = ++publishedLoadSequence
    try {
      const response = await getPageAccessCenterOverview(requestedProjectCode)
      if (
        currentLoadSequence !== publishedLoadSequence
        || requestedProjectCode !== projectCode.value
      ) return
      applyAccessCenter(response.data)
      return response.data
    } catch (error) {
      if (
        currentLoadSequence !== publishedLoadSequence
        || requestedProjectCode !== projectCode.value
      ) return
      throw error
    }
  }

  async function refreshPublished(requestedProjectCode = projectCode.value) {
    return refreshAccessCenter(requestedProjectCode)
  }

  async function addManualPage(data: ManualProjectPageRequest) {
    const response = await createWorkbenchPage(projectCode.value, data)
    await refreshPages()
    selectedPageId.value = response.data.id
    ElMessage.success('页面信息和手动操作已保存')
    return response.data
  }

  async function checkPageReadiness(
    page: ProjectPage,
  ): Promise<PageIntegrationReadiness> {
    const response = await getWorkbenchPageReadiness(projectCode.value, page.id)
    return response.data
  }

  async function deliverWorkflowTask(
    taskId: string,
    data: WorkflowDeliveryRequest,
  ): Promise<WorkflowDeliveryResult> {
    const response = await deliverWorkflowEngineeringTask(
      projectCode.value,
      taskId,
      data,
    )
    await Promise.all([refreshTasks(), refreshAccessCenter()])
    return response.data
  }

  async function createTask(
    data: PageAiCodingTaskCommand,
  ): Promise<PageAiCodingTaskResult> {
    if (tasksLoadError.value) {
      throw new Error('AI 编程任务状态尚未加载，重新加载成功前不能创建任务')
    }
    if (!project.value?.id) {
      throw new Error('当前项目不存在，无法创建 AI 编程任务')
    }
    const pageTask = data.taskKind !== 'PAGE_MAP_SCAN'
    if (pageTask && !data.page) {
      throw new Error('当前任务必须关联页面')
    }
    const accessMode: AiCodingAccessMode =
      data.taskKind === 'CODE_IMPLEMENTATION' || data.taskKind === 'BROWSER_ACCEPTANCE'
        ? 'READ_WRITE'
        : 'READ_ONLY'
    const primaryTarget = pageTask
      ? {
          targetType: 'PAGE',
          targetKey: data.page!.pageKey,
          targetRole: 'PRIMARY' as const,
          accessMode,
          snapshot: {
            pageId: data.page!.id,
            name: data.page!.name,
            routePattern: data.page!.routePattern,
            componentPath: data.page!.componentPath,
            ...(data.taskKind === 'CODE_IMPLEMENTATION' && data.goalSelection
              ? { goalSelection: data.goalSelection }
              : {}),
          },
        }
      : {
          targetType: 'PROJECT',
          targetKey: projectCode.value,
          targetRole: 'PRIMARY' as const,
          accessMode,
          snapshot: {
            projectId: project.value.id,
            name: project.value.name,
          },
        }
    const targets: Omit<AiCodingTaskTarget, 'id'>[] = [primaryTarget]
    if (data.taskKind === 'BROWSER_ACCEPTANCE' && data.workflow) {
      if (data.workflow.pageKey !== data.page!.pageKey) {
        throw new Error('验收工作流与当前页面不匹配')
      }
      targets.push({
        targetType: 'WORKFLOW',
        targetKey: data.workflow.workflowId,
        targetRole: 'RELATED',
        accessMode: 'READ_ONLY',
        snapshot: {
          workflowName: data.workflow.workflowName,
          workflowKeySlug: data.workflow.workflowKeySlug,
          workflowVersionId: data.workflow.workflowVersionId,
          workflowVersion: data.workflow.workflowVersion,
          workflowStatus: data.workflow.workflowStatus,
          pageKey: data.workflow.pageKey,
          agentId: data.workflow.agentId,
          agentName: data.workflow.agentName,
          modelInstanceId: data.workflow.modelInstanceId,
        },
      })
    }
    const request = {
      projectId: project.value.id,
      projectCode: projectCode.value,
      taskKind: data.taskKind,
      executorProvider: data.executorProvider,
      executionMode: data.executionMode || 'EXTERNAL_CLIENT',
      sandboxProfile: data.executionMode === 'MANAGED_SANDBOX'
        ? (accessMode === 'READ_ONLY' ? 'ANALYZE_READONLY' : 'WORKSPACE_PATCH')
        : undefined,
      title: data.title,
      objective: data.objective,
      createdBy: data.createdBy,
      targets,
    } as const
    const result = data.executionMode === 'MANAGED_SANDBOX'
      ? await taskKernel.createManagedTask(request)
      : await taskKernel.createTask(request, data.createdBy)
    await Promise.all([refreshTasks(), refreshAccessCenter()])
    return 'managedExecution' in result
      ? {
          task: result.task,
          handoff: null,
          managedExecution: result.managedExecution,
        }
      : {
          task: result.task,
          handoff: result.handoff,
          managedExecution: null,
        }
  }

  async function loadHandoffPrompt(taskId: string) {
    return taskKernel.reissueHandoff(taskId)
  }

  async function changeFindingStatus(id: number, status: AnalysisFindingStatus) {
    const response = await updatePageAnalysisFindingStatus(projectCode.value, id, status)
    const index = findings.value.findIndex((finding) => finding.id === id)
    if (index >= 0) findings.value[index] = response.data
    await refreshAccessCenter()
    return response.data
  }

  async function loadTaskDetail(taskId: string) {
    return taskKernel.refreshTask(taskId)
  }

  async function answerQuestion(taskId: string, questionId: string, answer: string) {
    await taskKernel.answerQuestion(taskId, questionId, answer)
    // Task detail and page journey are separate read models. Refresh both so the card does not
    // briefly show a stale acceptance state after an AI Coding mutation.
    await Promise.all([refreshTasks(), refreshPages()])
    ElMessage.success('回答已写回当前任务')
  }

  async function finishTaskAcceptance(
    taskId: string,
    passed: boolean,
    message: string,
  ) {
    const detail = await taskKernel.finishAcceptance(
      taskId,
      passed,
      message,
    )
    await Promise.all([refreshTasks(), refreshPages()])
    ElMessage[passed ? 'success' : 'warning'](
      passed ? '任务验收已通过' : '任务已标记为验收不通过',
    )
    return detail
  }

  async function verifyTaskAcceptanceReadiness(taskId: string) {
    const verification = await taskKernel.verifyAcceptanceReadiness(taskId)
    await Promise.all([refreshTasks(), refreshPages()])
    return verification
  }

  async function cancelTask(taskId: string) {
    const detail = await taskKernel.cancelTask(taskId)
    await Promise.all([refreshTasks(), refreshPages()])
    ElMessage.success('任务已取消')
    return detail
  }

  return {
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
  }
}
