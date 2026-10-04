import { computed, ref, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listRegistryProjectInstances } from '@/api/registry'
import {
  getAiOnboardingManifest,
  getScanProjectDetail,
  getScanProjects,
  provisionProjectAgent,
  runSdkAccessCheck,
} from '@/api/scanProject'
import { useAiCodingTask } from '@/composables/useAiCodingTask'
import type { ProjectInstance } from '@/types/registry'
import type {
  AiCodingExecutorProvider,
  AiCodingTask,
} from '@/types/aiCodingTask'
import type {
  AiOnboardingManifest,
  ScanProject,
  SdkAccessCheckResponse,
} from '@/types/scanProject'
import type { SdkAccessAiPromptTool } from './useSdkAccessWizardUiState'
import {
  aiCodingExecutionAcceptsClientAccess,
  aiCodingExecutionIsTerminal,
  aiCodingProviderLabel,
} from '@/utils/aiCodingPresentation'

export interface AiOnboardingDisplayStep {
  stepKey: string
  title: string
  status: 'TODO' | 'RUNNING' | 'PASS' | 'WARN' | 'FAIL' | 'SKIPPED'
  message?: string
}

export interface UseSdkAccessWizardDataDeps {
  aiPromptTool: Ref<SdkAccessAiPromptTool>
  projectCode?: Ref<string>
}

const STEP_TITLES: Record<string, string> = {
  PROJECT: '项目识别',
  STARTER: '后端 Starter',
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
  const checking = ref(false)
  const aiPromptLoading = ref(false)
  const aiPromptDialogVisible = ref(false)
  const aiOnboardingManifest = ref<AiOnboardingManifest | null>(null)
  const checkResult = ref<SdkAccessCheckResponse | null>(null)
  const taskKernel = useAiCodingTask()
  let loadSequence = 0
  let onboardingTaskLoadSequence = 0
  let onboardingActionSequence = 0
  let selfCheckSequence = 0

  const onboardingTask = computed<AiCodingTask | null>(
    () => preferredOnboardingTask(taskKernel.tasks.value),
  )
  const onboardingTaskDetail = taskKernel.selectedTaskDetail
  const handoffPackage = taskKernel.latestHandoff
  const taskPlatformReadiness = computed(
    () => onboardingTaskDetail.value?.readiness || [],
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
      selfCheckReadiness.value.length > 0
      && selfCheckReadiness.value.every((item) => item.status === 'PASS'),
  )

  const selectedExecutorProvider = computed<AiCodingExecutorProvider>(
    () => deps.aiPromptTool.value,
  )
  const aiOnboardingPromptLoading = computed(
    () => loading.value || aiPromptLoading.value || taskKernel.loading.value,
  )
  const aiOnboardingPromptReady = computed(() => {
    const handoff = handoffPackage.value
    return Boolean(
      handoff?.prompt
        && onboardingTask.value?.connection.status === 'WAITING_CONNECT'
        && aiCodingExecutionAcceptsClientAccess(
          onboardingTask.value?.executionStatus,
        )
        && onboardingTask.value?.executorProvider === selectedExecutorProvider.value
        && handoff.executorProvider === selectedExecutorProvider.value
        && new Date(handoff.activationExpiresAt).getTime() > Date.now(),
    )
  })
  const aiOnboardingPrompt = computed(
    () => handoffPackage.value?.prompt || '',
  )
  const aiOnboardingPromptUnavailableReason = computed(() => {
    if (aiOnboardingPromptLoading.value) {
      return '正在生成项目任务交接包，请稍候…'
    }
    if (aiTaskLoadError.value) {
      return aiTaskLoadError.value
    }
    if (!project.value?.id) {
      return '项目数据尚未加载，请刷新后重试。'
    }
    if (onboardingTask.value?.executionStatus === 'ACCEPTANCE_READY') {
      return '当前接入任务已通过平台验证，正在等待人工验收，无需再次交接。'
    }
    if (onboardingTask.value?.connection.status === 'ACTIVE') {
      return '当前 AI Coding 客户端已对接；终端重置时请从任务详情复制恢复命令。'
    }
    if (!handoffPackage.value) {
      return onboardingTask.value?.connection.status === 'WAITING_CONNECT'
        ? '出于安全原因，页面刷新后不会恢复一次性交接码；请重新生成交接包。'
        : '请先生成一次性交接包。'
    }
    if (
      onboardingTask.value?.executorProvider !== selectedExecutorProvider.value
      || handoffPackage.value.executorProvider !== selectedExecutorProvider.value
    ) {
      return `当前交接包不属于所选的 ${
        aiCodingProviderLabel(selectedExecutorProvider.value)
      }，请重新生成。`
    }
    if (onboardingTask.value?.connection.status === 'CLOSED') {
      return '当前 AI Coding 交接已关闭；如任务仍需继续，请重新生成交接包。'
    }
    return '当前交接包的激活码已过期，请重新生成。'
  })

  const aiDisplaySteps = computed<AiOnboardingDisplayStep[]>(() => {
    const applied = onboardingTaskDetail.value?.artifacts.find(
      (artifact) =>
        artifact.contractKey === 'reachai.project-onboarding-report'
        && artifact.processingStatus === 'APPLIED',
    )
    const application = asRecord(applied?.applicationResult)
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
    const execution =
      onboardingTaskDetail.value?.task.executionStatus
      || onboardingTask.value?.executionStatus
    if (execution === 'COMPLETED') {
      return Object.entries(STEP_TITLES).map(([stepKey, title]) => ({
        stepKey,
        title,
        status: 'PASS',
        message: '该项目接入任务已由用户在 ReachAI 验收通过。',
      }))
    }
    return Object.entries(STEP_TITLES).map(([stepKey, title], index) => ({
      stepKey,
      title,
      status:
        index === 0 && execution === 'RUNNING'
          ? 'RUNNING'
          : 'TODO',
    }))
  })

  const aiAccessCompletedSteps = computed(
    () => aiDisplaySteps.value.filter((step) => step.status === 'PASS').length,
  )
  const aiAccessTotalSteps = computed(() => aiDisplaySteps.value.length)

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
      }
    } catch (error) {
      if (currentLoadSequence !== onboardingTaskLoadSequence) return
      const message = (error as Error).message
      aiTaskLoadError.value = message
        ? `无法读取当前项目的 AI Coding 任务状态：${message}`
        : '无法读取当前项目的 AI Coding 任务状态，请重新加载后再生成交接包。'
      throw error
    }
  }

  function clearWorkbenchData() {
    onboardingActionSequence += 1
    selfCheckSequence += 1
    onboardingTaskLoadSequence += 1
    project.value = null
    instances.value = []
    aiOnboardingManifest.value = null
    checkResult.value = null
    checking.value = false
    aiPromptLoading.value = false
    aiPromptDialogVisible.value = false
    aiTaskLoadError.value = ''
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

  async function prepareAiOnboardingTask() {
    if (aiTaskLoadError.value) {
      ElMessage.warning('请先重新加载当前项目的 AI Coding 任务状态')
      return
    }
    const currentProject = project.value
    if (!currentProject?.id) {
      ElMessage.warning('请先加载 SDK 接入项目')
      return
    }
    const currentActionSequence = ++onboardingActionSequence
    const actionIsCurrent = () =>
      currentActionSequence === onboardingActionSequence
      && project.value?.id === currentProject.id
    aiPromptLoading.value = true
    try {
      const executorProvider = selectedExecutorProvider.value
      try {
        await provisionProjectAgent(
          currentProject.id,
          aiCodingProviderLabel(executorProvider),
        )
      } catch (error) {
        if (actionIsCurrent()) {
          ElMessage.warning(
            (error as Error).message
              || '项目 Copilot 尚未就绪；交接包仍会生成，请在验收前配置可用模型并重试',
          )
        }
      }
      if (!actionIsCurrent()) return
      const existing = onboardingTask.value
      if (existing?.executionStatus === 'ACCEPTANCE_READY') {
        await taskKernel.refreshTask(existing.taskId)
        if (!actionIsCurrent()) return
        ElMessage.info('当前接入任务正在等待人工验收，无需再次生成交接包')
        return
      }
      if (
        existing
        && aiCodingExecutionAcceptsClientAccess(existing.executionStatus)
        && existing.executorProvider !== executorProvider
      ) {
        await taskKernel.refreshTask(existing.taskId)
        if (!actionIsCurrent()) return
        ElMessage.warning(
          `当前 ${aiCodingProviderLabel(existing.executorProvider)} 任务尚未结束；请先在任务详情中取消，再切换 AI Coding 工具。`,
        )
        return
      }
      if (
        existing?.executorProvider === executorProvider
        && existing.connection.status === 'ACTIVE'
      ) {
        await taskKernel.refreshTask(existing.taskId)
        if (!actionIsCurrent()) return
        ElMessage.info(
          '当前 AI Coding 客户端已对接；终端重置时请在任务详情复制恢复命令',
        )
        return
      }
      const reusable =
        existing
        && aiCodingExecutionAcceptsClientAccess(existing.executionStatus)
        && existing.executorProvider === executorProvider
      if (reusable) {
        await taskKernel.reissueHandoff(existing.taskId)
      } else {
        await taskKernel.createTask({
          projectId: currentProject.id,
          projectCode: currentProject.projectCode || projectCode.value,
          taskKind: 'PROJECT_ONBOARDING',
          executorProvider,
          title: `接入 ReachAI：${currentProject.name || projectCode.value}`,
          objective:
            '在当前业务仓库完成 ReachAI Starter、网关、业务能力、Embed Token 和真实端到端验证。',
          targets: [
            {
              targetType: 'PROJECT',
              targetKey: currentProject.projectCode || projectCode.value,
              targetRole: 'PRIMARY',
              accessMode: 'READ_WRITE',
              snapshot: {
                projectId: currentProject.id,
                name: currentProject.name,
                projectKind: currentProject.projectKind,
              },
            },
          ],
        })
      }
      if (!actionIsCurrent()) return
      const task = onboardingTask.value
      if (task) {
        await taskKernel.refreshTask(task.taskId)
        if (!actionIsCurrent()) return
      }
      aiPromptDialogVisible.value = true
    } catch (error) {
      if (actionIsCurrent()) {
        ElMessage.error((error as Error).message || '生成 AI Coding 交接包失败')
      }
    } finally {
      if (currentActionSequence === onboardingActionSequence) {
        aiPromptLoading.value = false
      }
    }
  }

  async function runCheck() {
    const currentProject = project.value
    if (!currentProject?.id) return
    const currentCheckSequence = ++selfCheckSequence
    checking.value = true
    try {
      const { data } = await runSdkAccessCheck(currentProject.id)
      if (
        currentCheckSequence !== selfCheckSequence
        || project.value?.id !== currentProject.id
      ) return
      checkResult.value = data
      ElMessage[data.overallStatus === 'PASS' ? 'success' : 'warning'](
        '平台接入自检已完成',
      )
      await loadOnboardingTask(currentProject.id).catch(() => undefined)
    } catch (error) {
      if (currentCheckSequence !== selfCheckSequence) return
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

  async function retryOnboardingTaskLoad() {
    const projectId = project.value?.id
    if (!projectId) {
      aiTaskLoadError.value = '当前项目尚未加载，无法读取 AI Coding 任务状态。'
      return
    }
    await loadOnboardingTask(projectId).catch(() => undefined)
  }

  async function reissueOnboardingHandoff(taskId: string) {
    const handoff = await taskKernel.reissueHandoff(taskId)
    await refreshOnboardingTask()
    return handoff
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
    checking,
    aiPromptLoading,
    aiPromptDialogVisible,
    aiOnboardingManifest,
    onboardingTask,
    onboardingTaskDetail,
    handoffPackage,
    selfCheckReadiness,
    selfCheckSource,
    selfCheckVerified,
    aiDisplaySteps,
    aiAccessCompletedSteps,
    aiAccessTotalSteps,
    aiOnboardingPrompt,
    aiOnboardingPromptLoading,
    aiOnboardingPromptReady,
    aiOnboardingPromptUnavailableReason,
    checkResult,
    isSdkBackedProject,
    onlineInstanceCount,
    loadAll,
    prepareAiOnboardingTask,
    runCheck,
    refreshOnboardingTask,
    retryOnboardingTaskLoad,
    reissueOnboardingHandoff,
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
