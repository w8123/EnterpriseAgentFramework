import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSdkAccessWizardData } from './useSdkAccessWizardData'
import type { SdkAccessAiPromptTool } from './useSdkAccessWizardUiState'

const mocks = vi.hoisted(() => ({
  getAiOnboardingManifest: vi.fn(),
  getScanProjectDetail: vi.fn(),
  getScanProjectTools: vi.fn(),
  getScanProjects: vi.fn(),
  createAiCodingTask: vi.fn(),
  getAiCodingTask: vi.fn(),
  issueAiCodingHandoff: vi.fn(),
  provisionProjectAgent: vi.fn(),
  runSdkAccessCheck: vi.fn(),
  listRegistryProjectInstances: vi.fn(),
  listAiCodingTasks: vi.fn(),
  warning: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: {},
  }),
}))

vi.mock('@/api/registry', () => ({
  listRegistryProjectInstances: mocks.listRegistryProjectInstances,
}))

vi.mock('@/api/scanProject', () => ({
  getAiOnboardingManifest: mocks.getAiOnboardingManifest,
  getScanProjectDetail: mocks.getScanProjectDetail,
  getScanProjectTools: mocks.getScanProjectTools,
  getScanProjects: mocks.getScanProjects,
  provisionProjectAgent: mocks.provisionProjectAgent,
  runSdkAccessCheck: mocks.runSdkAccessCheck,
}))

vi.mock('@/api/aiCodingTasks', () => ({
  answerAiCodingQuestion: vi.fn(),
  cancelAiCodingTask: vi.fn(),
  createAiCodingTask: mocks.createAiCodingTask,
  finishAiCodingAcceptance: vi.fn(),
  getAiCodingTask: mocks.getAiCodingTask,
  issueAiCodingHandoff: mocks.issueAiCodingHandoff,
  listAiCodingTasks: mocks.listAiCodingTasks,
  verifyAiCodingAcceptanceReadiness: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn(),
    info: vi.fn(),
    success: vi.fn(),
    warning: mocks.warning,
  },
}))

function createData(projectCode = ref('demo-project')) {
  return useSdkAccessWizardData({
    aiPromptTool: ref<SdkAccessAiPromptTool>('CODEX'),
    projectCode,
  })
}

describe('useSdkAccessWizardData', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.getScanProjects.mockResolvedValue({
      data: [{
        id: 27,
        projectCode: 'demo-project',
        name: 'Demo Project',
        projectKind: 'REGISTERED',
      }],
    })
    mocks.getScanProjectDetail.mockResolvedValue({
      data: {
        id: 27,
        projectCode: 'demo-project',
        name: 'Demo Project',
        projectKind: 'REGISTERED',
      },
    })
    mocks.getScanProjectTools.mockResolvedValue({ data: [] })
    mocks.listRegistryProjectInstances.mockResolvedValue({ data: [] })
    mocks.getAiOnboardingManifest.mockResolvedValue({ data: {} })
    mocks.listAiCodingTasks.mockResolvedValue({ data: [] })
    mocks.createAiCodingTask.mockResolvedValue({
      data: {
        taskId: 'old-project-task',
        executionStatus: 'CREATED',
        executorProvider: 'CODEX',
        connection: { status: 'WAITING_CONNECT' },
      },
    })
    mocks.issueAiCodingHandoff.mockResolvedValue({
      data: {
        taskId: 'old-project-task',
        prompt: 'prompt',
      },
    })
    mocks.getAiCodingTask.mockResolvedValue({
      data: {
        task: {
          taskId: 'old-project-task',
          executionStatus: 'CREATED',
          executorProvider: 'CODEX',
          connection: { status: 'WAITING_CONNECT' },
        },
        readiness: [],
        events: [],
        questions: [],
        artifacts: [],
      },
    })
  })

  it('keeps manual access available but blocks task creation when task state is unknown', async () => {
    mocks.listAiCodingTasks.mockRejectedValueOnce(new Error('task endpoint down'))
    const data = createData()

    await data.loadAll()

    expect(data.loadError.value).toBe('')
    expect(data.project.value?.projectCode).toBe('demo-project')
    expect(data.aiTaskLoadError.value).toContain('task endpoint down')

    await data.prepareAiOnboardingTask()
    expect(mocks.provisionProjectAgent).not.toHaveBeenCalled()
    expect(mocks.warning).toHaveBeenCalledWith(
      '请先重新加载当前项目的 AI Coding 任务状态',
    )

    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [] })
    await data.retryOnboardingTaskLoad()
    expect(data.aiTaskLoadError.value).toBe('')
  })

  it('does not restore an older task error after a successful retry', async () => {
    let rejectOlder: ((reason?: unknown) => void) | undefined
    mocks.listAiCodingTasks
      .mockImplementationOnce(() => new Promise((_, reject) => {
        rejectOlder = reject
      }))
      .mockResolvedValueOnce({ data: [] })
    const data = createData()

    const initialLoad = data.loadAll()
    await vi.waitFor(() => {
      expect(data.project.value?.id).toBe(27)
    })
    await data.retryOnboardingTaskLoad()
    rejectOlder?.(new Error('older task endpoint outage'))
    await initialLoad

    expect(data.aiTaskLoadError.value).toBe('')
    expect(data.project.value?.projectCode).toBe('demo-project')
  })

  it('runs the SDK self-check without sending ignored browser or API invocation fields', async () => {
    mocks.runSdkAccessCheck.mockResolvedValueOnce({
      data: {
        projectId: 27,
        projectCode: 'demo-project',
        overallStatus: 'PASS',
        readiness: [{
          key: 'SDK_CALLBACK_READY',
          label: 'SDK 回调闭环',
          status: 'PASS',
          message: 'signed callback observed',
        }],
        checks: [],
      },
    })
    const data = createData()
    await data.loadAll()

    await data.runCheck()

    expect(mocks.runSdkAccessCheck).toHaveBeenCalledWith(27)
    expect(data.checkResult.value?.readiness[0]?.key).toBe('SDK_CALLBACK_READY')
  })

  it('uses the current task platform gates before a manual self-check is run', async () => {
    const task = {
      taskId: 'acceptance-ready-task',
      executionStatus: 'ACCEPTANCE_READY',
      executorProvider: 'CODEX',
      connection: { status: 'CLOSED' },
    }
    const readiness = [
      ['CODE_READY', '代码接入'],
      ['RUNTIME_READY', '实例在线'],
      ['SDK_CALLBACK_READY', 'SDK 回调闭环'],
      ['E2E_READY', '端到端验收'],
    ].map(([key, label]) => ({
      key,
      label,
      status: 'PASS',
      message: `${label}已通过`,
    }))
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({
      data: {
        task,
        readiness,
        events: [],
        questions: [],
        artifacts: [],
      },
    })
    const data = createData()

    await data.loadAll()

    expect(data.checkResult.value).toBeNull()
    expect(data.selfCheckSource.value).toBe('TASK')
    expect(data.selfCheckReadiness.value).toHaveLength(4)
    expect(data.selfCheckVerified.value).toBe(true)
  })

  it('keeps all six reported steps complete after the task is accepted', async () => {
    const task = {
      taskId: 'completed-project-task',
      executionStatus: 'COMPLETED',
      executorProvider: 'CODEX',
      connection: { status: 'CLOSED' },
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({
      data: {
        task,
        readiness: [],
        events: [],
        questions: [],
        artifacts: [],
      },
    })
    const data = createData()

    await data.loadAll()

    expect(data.aiAccessCompletedSteps.value).toBe(6)
    expect(data.aiAccessTotalSteps.value).toBe(6)
    expect(data.aiDisplaySteps.value).toHaveLength(6)
    expect(data.aiDisplaySteps.value.every((step) => step.status === 'PASS')).toBe(true)
  })

  it('does not publish a self-check result after the workbench changed projects', async () => {
    let resolvePending:
      | ((value: {
          data: {
            projectId: number
            projectCode: string
            overallStatus: string
            readiness: never[]
            checks: never[]
          }
        }) => void)
      | undefined
    mocks.runSdkAccessCheck.mockImplementationOnce(() => new Promise((resolve) => {
      resolvePending = resolve
    }))
    const projectCode = ref('demo-project')
    const data = createData(projectCode)
    await data.loadAll()

    const pendingCheck = data.runCheck()
    projectCode.value = 'missing-project'
    await data.loadAll()
    resolvePending?.({
      data: {
        projectId: 27,
        projectCode: 'demo-project',
        overallStatus: 'PASS',
        readiness: [],
        checks: [],
      },
    })
    await pendingCheck

    expect(data.projectMissing.value).toBe(true)
    expect(data.checkResult.value).toBeNull()
  })

  it('does not create an onboarding task after its workbench changed projects', async () => {
    let resolveProvision: (() => void) | undefined
    mocks.provisionProjectAgent.mockImplementationOnce(() => new Promise<void>((resolve) => {
      resolveProvision = resolve
    }))
    const projectCode = ref('demo-project')
    const data = createData(projectCode)
    await data.loadAll()

    const pendingPrepare = data.prepareAiOnboardingTask()
    projectCode.value = 'missing-project'
    await data.loadAll()
    resolveProvision?.()
    await pendingPrepare

    expect(data.projectMissing.value).toBe(true)
    expect(mocks.createAiCodingTask).not.toHaveBeenCalled()
    expect(data.aiPromptDialogVisible.value).toBe(false)
  })
})
