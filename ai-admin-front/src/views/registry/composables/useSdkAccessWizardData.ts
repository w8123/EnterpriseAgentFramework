import { computed, ref, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listRegistryProjectInstances } from '@/api/registry'
import {
  getAiOnboardingManifest,
  getScanProjectDetail,
  getScanProjects,
  runSdkAccessCheck,
} from '@/api/scanProject'
import { useAiCodingTask } from '@/composables/useAiCodingTask'
import type { ProjectInstance } from '@/types/registry'
import type { AiCodingTask } from '@/types/aiCodingTask'
import type {
  AiOnboardingManifest,
  ScanProject,
  SdkAccessCheckResponse,
} from '@/types/scanProject'
import type { SdkAccessLanguage, SdkAccessMode } from './useSdkAccessWizardUiState'
import { instanceMatchesSdkLanguage } from './useSdkAccessWizardProgress'
import { aiCodingExecutionIsTerminal } from '@/utils/aiCodingPresentation'

export interface AiOnboardingDisplayStep {
  stepKey: string
  title: string
  status: 'TODO' | 'RUNNING' | 'PASS' | 'WARN' | 'FAIL' | 'SKIPPED'
  message?: string
}

export interface TaskGuidance {
  title: string
  description: string
  action: 'start' | 'detail' | 'question' | 'acceptance' | 'verify' | 'none'
  tone: 'neutral' | 'active' | 'warning' | 'success' | 'error'
}

export interface UseSdkAccessWizardDataDeps {
  sdkLanguage?: Ref<SdkAccessLanguage>
  accessMode?: Ref<SdkAccessMode>
  projectCode?: Ref<string>
}

const STEP_TITLES: Record<string, string> = {
  PROJECT: '项目识别',
  STARTER: '后端 SDK',
  GATEWAY: '网关路由',
  BUSINESS_API: '业务服务校验',
  EMBED_TOKEN: '前端 Embed Token',
  FINAL_CHECK: '测试与浏览器验收',
}

function preferredOnboardingTask(tasks: AiCodingTask[]) {
  return tasks.find(
    (task) => !aiCodingExecutionIsTerminal(task.executionStatus),
  ) || tasks[0] || null
}

export function useSdkAccessWizardData(deps: UseSdkAccessWizardDataDeps) {
  const route = useRoute()
  const projectCode =
    deps.projectCode ?? computed(() => String(route.params.projectCode || ''))
  const project = ref<ScanProject | null>(null)
  const instances = ref<ProjectInstance[]>([])
  const loading = ref(false)
  const projectMissing = ref(false)
  const loadError = ref('')
  const aiTaskLoadError = ref('')
  const taskLastUpdatedAt = ref<Date | null>(null)
  const taskRefreshFailed = ref(false)
  const instanceRefreshFailed = ref(false)
  const taskRefreshError = computed(() =>
    taskRefreshFailed.value && instanceRefreshFailed.value
      ? '任务和实例状态刷新失败，当前显示上次成功的数据。'
      : taskRefreshFailed.value ? '任务状态刷新失败，当前显示上次成功的数据。'
        : instanceRefreshFailed.value ? '实例状态刷新失败，当前显示上次成功的数据。' : '',
  )
  const checking = ref(false)
  const aiOnboardingManifest = ref<AiOnboardingManifest | null>(null)
  const checkResult = ref<SdkAccessCheckResponse | null>(null)
  const taskKernel = useAiCodingTask()
  let loadSequence = 0
  let onboardingTaskLoadSequence = 0
  let selfCheckSequence = 0
  let backgroundRefreshSequence = 0

  const onboardingTask = computed<AiCodingTask | null>(
    () => preferredOnboardingTask(taskKernel.tasks.value),
  )
  const detectedSdkLanguage = computed<SdkAccessLanguage | null>(() => {
    const task = onboardingTask.value
    if (!task) return null
    const target = task.targets?.find((item) => item.targetRole === 'PRIMARY')
    const value = asRecord(target?.snapshot).sdkLanguage
    // Task contexts created before the language snapshot used Java by default.
    if (value == null || value === '') return 'java'
    if (typeof value !== 'string') return null
    const language = value.trim().toLowerCase()
    return ['java', 'csharp', 'python', 'node', 'go'].includes(language)
      ? language as SdkAccessLanguage
      : null
  })
  const effectiveSdkLanguage = computed<SdkAccessLanguage>(() =>
    deps.accessMode?.value === 'ai-coding'
      ? detectedSdkLanguage.value || 'java'
      : deps.sdkLanguage?.value || 'java',
  )
  const onboardingTaskDetail = taskKernel.selectedTaskDetail
  const currentOnboardingTaskDetail = computed(() =>
    onboardingTaskDetail.value?.task.taskId === onboardingTask.value?.taskId
      ? onboardingTaskDetail.value
      : null,
  )
  const latestTaskEvent = computed(() => {
    const events = currentOnboardingTaskDetail.value?.events || []
    const latest = [...events].reverse().find((event) => Boolean(event.message?.trim()))
    if (!latest) return null
    return latest.eventType === 'HANDOFF_ISSUED'
      ? { ...latest, message: '任务连接已准备' }
      : latest
  })
  const openTaskQuestions = computed(() =>
    (currentOnboardingTaskDetail.value?.questions || []).filter((question) => question.status === 'OPEN'),
  )
  const taskGuidance = computed<TaskGuidance>(() => {
    if (aiTaskLoadError.value) return {
      title: '任务状态暂不可用', description: '请重新加载任务状态，确认当前接入进展。',
      action: 'none', tone: 'warning',
    }
    const task = onboardingTask.value
    if (!task) return {
      title: '等待开始接入',
      description: '在业务系统代码仓库中，让已连接 ReachAI 的 AI 工具开始接入。',
      action: 'start', tone: 'neutral',
    }
    if (task.executionStatus === 'WAITING_USER') return {
      title: '等待你的回答',
      description: openTaskQuestions.value.length
        ? `AI 提出了 ${openTaskQuestions.value.length} 个问题，请回答后继续。`
        : '请查看任务详情，确认需要回答的问题。',
      action: 'question', tone: 'warning',
    }
    if (task.executionStatus === 'ACCEPTANCE_READY') {
      const readiness = currentOnboardingTaskDetail.value?.readiness || []
      if (readiness.some((item) => item.status !== 'PASS')) return {
        title: '验收条件待确认', description: '平台验证存在待处理项，请查看详情并重新验证。',
        action: 'verify', tone: 'warning',
      }
      return {
        title: '等待验收', description: '请查看任务结果和平台验证，完成本次接入验收。',
        action: 'acceptance', tone: 'success',
      }
    }
    if (task.executionStatus === 'RESULT_APPLIED') return {
      title: '等待平台验证', description: 'AI 已提交接入结果，请查看验证状态并确认验收条件。',
      action: 'verify', tone: 'warning',
    }
    if (task.executionStatus === 'FAILED') return {
      title: '接入任务未完成', description: '请查看任务动态和结果，确认需要处理的问题。',
      action: 'detail', tone: 'error',
    }
    if (task.executionStatus === 'COMPLETED') return {
      title: '接入已验收', description: '任务结果和验收记录可在详情中查看。',
      action: 'detail', tone: 'success',
    }
    if (task.executionStatus === 'CANCELLED') return {
      title: '接入任务已取消', description: '需要继续接入时，可让已连接的 AI 重新发起。',
      action: 'start', tone: 'neutral',
    }
    if (task.connection.status === 'TIMED_OUT') return {
      title: 'AI 连接已中断', description: '请在原 AI 工具中检查连接，并根据任务状态继续。',
      action: 'start', tone: 'warning',
    }
    if (task.executionStatus === 'RUNNING') return {
      title: 'AI 正在接入', description: '查看最近动态，遇到需要确认的问题时请及时回答。',
      action: 'detail', tone: 'active',
    }
    if (task.executionStatus === 'RESULT_SUBMITTED') return {
      title: '结果正在处理', description: 'AI 已提交结果，等待平台完成处理。',
      action: 'detail', tone: 'active',
    }
    return {
      title: '等待 AI 开始', description: '请在业务仓库中让已连接 ReachAI 的 AI 工具继续接入。',
      action: 'start', tone: 'neutral',
    }
  })
  const taskLanguageMatches = computed(() => {
    const task = onboardingTask.value
    if (!task) return true
    return detectedSdkLanguage.value !== null
      && detectedSdkLanguage.value === effectiveSdkLanguage.value
  })
  const taskPlatformReadiness = computed(
    () => taskLanguageMatches.value ? (currentOnboardingTaskDetail.value?.readiness || []) : [],
  )
  const selfCheckReadiness = computed(() =>
    taskPlatformReadiness.value.length
      ? taskPlatformReadiness.value
      : (checkResult.value?.readiness || []),
  )
  const selfCheckSource = computed<'TASK' | 'MANUAL' | 'NONE'>(() => {
    if (taskPlatformReadiness.value.length) return 'TASK'
    if (checkResult.value?.readiness?.length) return 'MANUAL'
    return 'NONE'
  })
  const selfCheckVerified = computed(
    () =>
      (deps.accessMode?.value !== 'ai-coding' || detectedSdkLanguage.value !== null)
      && effectiveSdkLanguage.value !== 'python' && effectiveSdkLanguage.value !== 'node' && effectiveSdkLanguage.value !== 'go'
      && selfCheckReadiness.value.length > 0
      && selfCheckReadiness.value.every((item) => item.status === 'PASS')
      && (effectiveSdkLanguage.value === 'java' || instances.value.some(
        (instance) => instance.status === 'ONLINE' && instanceMatchesSdkLanguage(instance, effectiveSdkLanguage.value),
      )),
  )

  const appliedReport = computed(() => currentOnboardingTaskDetail.value?.artifacts.find(
      (artifact) =>
        artifact.contractKey === 'reachai.project-onboarding-report'
        && artifact.processingStatus === 'APPLIED',
    ))
  const aiReportAvailable = computed(() => {
    const application = asRecord(appliedReport.value?.applicationResult)
    const steps = asRecord(application.codingReport).steps
    return Array.isArray(steps) && steps.length > 0
  })
  const aiDisplaySteps = computed<AiOnboardingDisplayStep[]>(() => {
    const application = asRecord(appliedReport.value?.applicationResult)
    const codingReport = asRecord(application.codingReport)
    const reportedSteps = Array.isArray(codingReport.steps)
      ? codingReport.steps.map(asRecord)
      : []
    if (reportedSteps.length) {
      return reportedSteps.map((step) => {
        const stepKey = String(step.stepKey || '')
        return {
          stepKey,
          title: STEP_TITLES[stepKey] || stepKey,
          status: normalizeStepStatus(step.status),
          message: typeof step.message === 'string' ? step.message : undefined,
        }
      })
    }
    return Object.entries(STEP_TITLES).map(([stepKey, title]) => ({
      stepKey,
      title,
      status: 'TODO',
    }))
  })

  const aiAccessCompletedSteps = computed(
    () => aiDisplaySteps.value.filter((step) => step.status === 'PASS').length,
  )
  const aiAccessTotalSteps = computed(() => aiDisplaySteps.value.length)
  const taskLoading = computed(() => taskKernel.loading.value || taskKernel.detailLoading.value)

  const isSdkBackedProject = computed(() => {
    const kind = project.value?.projectKind || ''
    return kind === 'REGISTERED' || kind === 'HYBRID'
  })
  const onlineInstanceCount = computed(
    () => instances.value.filter((item) => item.status === 'ONLINE').length,
  )

  async function loadOnboardingTask(projectId: number) {
    const currentLoadSequence = ++onboardingTaskLoadSequence
    try {
      const tasks = await taskKernel.loadTasks({
        projectId,
        taskKind: 'PROJECT_ONBOARDING',
        limit: 20,
      })
      if (currentLoadSequence !== onboardingTaskLoadSequence) return
      const selected = preferredOnboardingTask(tasks)
      if (selected) {
        await taskKernel.refreshTask(selected.taskId)
      }
      if (currentLoadSequence === onboardingTaskLoadSequence) {
        aiTaskLoadError.value = ''
        taskLastUpdatedAt.value = new Date()
        taskRefreshFailed.value = false
      }
    } catch (error) {
      if (currentLoadSequence !== onboardingTaskLoadSequence) return
      const message = (error as Error).message
      aiTaskLoadError.value = message
        ? `无法读取当前项目的 AI Coding 任务状态：${message}`
        : '无法读取当前项目的 AI Coding 任务状态，请重新加载任务状态。'
      throw error
    }
  }

  function clearWorkbenchData() {
    selfCheckSequence += 1
    onboardingTaskLoadSequence += 1
    backgroundRefreshSequence += 1
    project.value = null
    instances.value = []
    aiOnboardingManifest.value = null
    checkResult.value = null
    checking.value = false
    aiTaskLoadError.value = ''
    taskLastUpdatedAt.value = null
    taskRefreshFailed.value = false
    instanceRefreshFailed.value = false
    taskKernel.reset()
  }

  async function loadAll() {
    const requestedProjectCode = projectCode.value
    if (!requestedProjectCode) return
    const currentLoadSequence = ++loadSequence
    loading.value = true
    projectMissing.value = false
    loadError.value = ''
    clearWorkbenchData()
    try {
      const { data: projects } = await getScanProjects()
      if (currentLoadSequence !== loadSequence) return
      const matched = projects.find(
        (item) => item.projectCode === requestedProjectCode,
      )
      if (!matched?.id) {
        projectMissing.value = true
        return
      }
      const [{ data: detail }, { data: instanceRows }] =
        await Promise.all([
          getScanProjectDetail(matched.id),
          listRegistryProjectInstances(
            matched.projectCode || requestedProjectCode,
          ),
        ])
      if (currentLoadSequence !== loadSequence) return
      project.value = detail
      instances.value = instanceRows
      const [, manifest] = await Promise.all([
        loadOnboardingTask(detail.id).catch(() => undefined),
        getAiOnboardingManifest(detail.id)
          .then(({ data }) => data)
          .catch(() => null),
      ])
      if (currentLoadSequence !== loadSequence) return
      aiOnboardingManifest.value = manifest
    } catch (error) {
      if (currentLoadSequence !== loadSequence) return
      clearWorkbenchData()
      const message = (error as Error).message
      loadError.value = message
        ? `无法读取当前项目的接入数据：${message}`
        : '无法读取当前项目的接入数据，请检查服务状态后重新加载。'
    } finally {
      if (currentLoadSequence === loadSequence) {
        loading.value = false
      }
    }
  }

  async function runCheck() {
    const currentProject = project.value
    if (!currentProject?.id) return
    const currentCheckSequence = ++selfCheckSequence
    checking.value = true
    try {
      const [{ data }, { data: currentInstances }] = await Promise.all([
        runSdkAccessCheck(currentProject.id),
        listRegistryProjectInstances(currentProject.projectCode || projectCode.value),
      ])
      if (
        currentCheckSequence !== selfCheckSequence
        || project.value?.id !== currentProject.id
      ) return
      checkResult.value = data
      instances.value = currentInstances
      instanceRefreshFailed.value = false
      ElMessage.info('已读取平台自检结果')
      await loadOnboardingTask(currentProject.id).catch(() => undefined)
    } catch (error) {
      if (currentCheckSequence !== selfCheckSequence) return
      checkResult.value = null
      ElMessage.error((error as Error).message || '平台接入自检失败')
    } finally {
      if (currentCheckSequence === selfCheckSequence) {
        checking.value = false
      }
    }
  }

  async function refreshOnboardingTask() {
    const projectId = project.value?.id
    if (!projectId) {
      throw new Error('当前项目尚未加载')
    }
    await loadOnboardingTask(projectId)
    return onboardingTaskDetail.value
  }

  async function refreshOnboardingTaskInBackground(canApply: () => boolean) {
    const currentProject = project.value
    if (!currentProject?.id || currentProject.projectCode !== projectCode.value) return
    const currentRefreshSequence = ++backgroundRefreshSequence
    const stillCurrent = () => canApply()
      && currentRefreshSequence === backgroundRefreshSequence
      && project.value?.id === currentProject.id
      && projectCode.value === currentProject.projectCode
      && !loading.value && !checking.value && !taskKernel.loading.value && !taskKernel.detailLoading.value
    const [taskResult, instanceResult] = await Promise.allSettled([
      taskKernel.refreshTasksInBackground(
        { projectId: currentProject.id, taskKind: 'PROJECT_ONBOARDING', limit: 20 },
        preferredOnboardingTask,
        stillCurrent,
      ),
      listRegistryProjectInstances(currentProject.projectCode),
    ])
    if (!stillCurrent()) return
    if (instanceResult.status === 'fulfilled') {
      instances.value = instanceResult.value.data
      instanceRefreshFailed.value = false
    } else {
      instanceRefreshFailed.value = true
    }
    if (taskResult.status === 'fulfilled' && taskResult.value) {
      aiTaskLoadError.value = ''
      taskLastUpdatedAt.value = new Date()
      taskRefreshFailed.value = false
    } else if (taskResult.status === 'rejected') {
      taskRefreshFailed.value = true
    }
    if (taskResult.status === 'rejected') throw taskResult.reason
  }

  async function retryTaskRefresh() {
    await refreshOnboardingTaskInBackground(() => true)
  }

  async function retryOnboardingTaskLoad() {
    const projectId = project.value?.id
    if (!projectId) {
      aiTaskLoadError.value = '当前项目尚未加载，无法读取 AI Coding 任务状态。'
      return
    }
    await loadOnboardingTask(projectId).catch(() => undefined)
  }

  async function answerOnboardingQuestion(
    taskId: string,
    questionId: string,
    answer: string,
  ) {
    await taskKernel.answerQuestion(taskId, questionId, answer)
    return refreshOnboardingTask()
  }

  async function finishOnboardingAcceptance(
    taskId: string,
    passed: boolean,
    message: string,
  ) {
    await taskKernel.finishAcceptance(taskId, passed, message)
    return refreshOnboardingTask()
  }

  async function verifyOnboardingAcceptanceReadiness(taskId: string) {
    const verification = await taskKernel.verifyAcceptanceReadiness(taskId)
    await refreshOnboardingTask()
    return verification
  }

  async function cancelOnboardingTask(taskId: string) {
    await taskKernel.cancelTask(taskId)
    return refreshOnboardingTask()
  }

  return {
    projectCode,
    project,
    instances,
    loading,
    projectMissing,
    loadError,
    aiTaskLoadError,
    taskLastUpdatedAt,
    taskRefreshError,
    checking,
    aiOnboardingManifest,
    onboardingTask,
    detectedSdkLanguage,
    effectiveSdkLanguage,
    onboardingTaskDetail,
    currentOnboardingTaskDetail,
    latestTaskEvent,
    openTaskQuestions,
    taskGuidance,
    aiReportAvailable,
    selfCheckReadiness,
    selfCheckSource,
    selfCheckVerified,
    aiDisplaySteps,
    aiAccessCompletedSteps,
    aiAccessTotalSteps,
    taskLoading,
    checkResult,
    isSdkBackedProject,
    onlineInstanceCount,
    loadAll,
    runCheck,
    refreshOnboardingTask,
    refreshOnboardingTaskInBackground,
    retryTaskRefresh,
    retryOnboardingTaskLoad,
    answerOnboardingQuestion,
    verifyOnboardingAcceptanceReadiness,
    finishOnboardingAcceptance,
    cancelOnboardingTask,
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {}
}

function normalizeStepStatus(
  value: unknown,
): AiOnboardingDisplayStep['status'] {
  const status = String(value || '').toUpperCase()
  return ['TODO', 'RUNNING', 'PASS', 'WARN', 'FAIL', 'SKIPPED'].includes(
    status,
  )
    ? (status as AiOnboardingDisplayStep['status'])
    : 'TODO'
}
