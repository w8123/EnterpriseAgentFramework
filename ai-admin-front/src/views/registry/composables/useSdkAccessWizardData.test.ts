import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useSdkAccessWizardData } from './useSdkAccessWizardData'

const mocks = vi.hoisted(() => ({
  getAiOnboardingManifest: vi.fn(),
  getScanProjectDetail: vi.fn(),
  getScanProjectTools: vi.fn(),
  getScanProjects: vi.fn(),
  createAiCodingTask: vi.fn(),
  getAiCodingTask: vi.fn(),
  issueAiCodingHandoff: vi.fn(),
  runSdkAccessCheck: vi.fn(),
  listRegistryProjectInstances: vi.fn(),
  listAiCodingTasks: vi.fn(),
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
  },
}))

function createData(projectCode = ref('demo-project')) {
  return useSdkAccessWizardData({ projectCode })
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

  it('uses the task language in AI mode without overwriting the manual selection', async () => {
    const manualLanguage = ref<'java' | 'csharp' | 'python' | 'node' | 'go'>('python')
    const accessMode = ref<'manual' | 'ai-coding'>('ai-coding')
    const task = {
      taskId: 'csharp-task', executionStatus: 'RUNNING', executorProvider: 'CODEX',
      connection: { status: 'ACTIVE' },
      targets: [{ targetRole: 'PRIMARY', snapshot: { sdkLanguage: 'csharp' } }],
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: { task, readiness: [{ key: 'CODE_READY', status: 'PASS' }], events: [], questions: [], artifacts: [] } })
    const data = useSdkAccessWizardData({ sdkLanguage: manualLanguage, accessMode, projectCode: ref('demo-project') })

    expect(data.detectedSdkLanguage.value).toBeNull()
    await data.loadAll()
    expect(data.detectedSdkLanguage.value).toBe('csharp')
    expect(data.effectiveSdkLanguage.value).toBe('csharp')
    expect(data.selfCheckSource.value).toBe('TASK')
    expect(manualLanguage.value).toBe('python')

    accessMode.value = 'manual'
    expect(data.effectiveSdkLanguage.value).toBe('python')
    expect(data.selfCheckSource.value).not.toBe('TASK')
    accessMode.value = 'ai-coding'
    expect(data.effectiveSdkLanguage.value).toBe('csharp')
  })

  it('uses Java for legacy task snapshots and does not infer unsupported languages', async () => {
    const accessMode = ref<'manual' | 'ai-coding'>('ai-coding')
    const task = { taskId: 'legacy', executionStatus: 'RUNNING', executorProvider: 'CODEX', connection: { status: 'ACTIVE' } }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: { task, readiness: [], events: [], questions: [], artifacts: [] } })
    const data = useSdkAccessWizardData({ accessMode, projectCode: ref('demo-project') })
    await data.loadAll()
    expect(data.detectedSdkLanguage.value).toBe('java')
    const unsupported = { ...task, targets: [{ targetRole: 'PRIMARY', snapshot: { sdkLanguage: 'rust' } }] }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [unsupported] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: { task: unsupported, readiness: [{ key: 'CODE_READY', status: 'PASS' }], events: [], questions: [], artifacts: [] } })
    await data.refreshOnboardingTask()
    expect(data.detectedSdkLanguage.value).toBeNull()
    expect(data.selfCheckSource.value).not.toBe('TASK')
  })





  it('does not mark project self-check as verified in AI mode without a known task language', async () => {
    const accessMode = ref<'manual' | 'ai-coding'>('ai-coding')
    const data = useSdkAccessWizardData({ sdkLanguage: ref('java' as const), accessMode, projectCode: ref('demo-project') })
    await data.loadAll()
    data.checkResult.value = {
      projectId: 27, projectCode: 'demo-project', overallStatus: 'PASS', checks: [],
      readiness: [{ key: 'CODE_READY', label: '代码接入', status: 'PASS', message: '已检查' }],
    }
    expect(data.detectedSdkLanguage.value).toBeNull()
    expect(data.selfCheckSource.value).toBe('MANUAL')
    expect(data.selfCheckVerified.value).toBe(false)

    accessMode.value = 'manual'
    expect(data.selfCheckVerified.value).toBe(true)
    accessMode.value = 'ai-coding'

    const task = {
      taskId: 'unknown-task', executionStatus: 'RUNNING', executorProvider: 'CODEX',
      connection: { status: 'ACTIVE' },
      targets: [{ targetRole: 'PRIMARY', snapshot: { sdkLanguage: 'rust' } }],
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: { task, readiness: [], events: [], questions: [], artifacts: [] } })
    await data.refreshOnboardingTask()
    expect(data.detectedSdkLanguage.value).toBeNull()
    expect(data.selfCheckSource.value).toBe('MANUAL')
    expect(data.selfCheckVerified.value).toBe(false)
  })

  it('discards a delayed task detail after switching projects', async () => {
    let resolveOld: ((value: unknown) => void) | undefined
    const task = {
      taskId: 'old-csharp', executionStatus: 'RUNNING', executorProvider: 'CODEX',
      connection: { status: 'ACTIVE' },
      targets: [{ targetRole: 'PRIMARY', snapshot: { sdkLanguage: 'csharp' } }],
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve }))
    const projectCode = ref('demo-project')
    const data = useSdkAccessWizardData({ accessMode: ref<'manual' | 'ai-coding'>('ai-coding'), projectCode })
    const oldLoad = data.loadAll()
    await vi.waitFor(() => expect(data.detectedSdkLanguage.value).toBe('csharp'))
    projectCode.value = 'missing-project'
    await data.loadAll()
    resolveOld?.({ data: { task, readiness: [{ key: 'CODE_READY', status: 'PASS' }], events: [], questions: [], artifacts: [] } })
    await oldLoad
    expect(data.detectedSdkLanguage.value).toBeNull()
    expect(data.selfCheckSource.value).toBe('NONE')
  })

  it('refreshes the task and instance heartbeat in the background without discarding confirmed state on failure', async () => {
    const data = useSdkAccessWizardData({ accessMode: ref<'manual' | 'ai-coding'>('ai-coding'), projectCode: ref('demo-project') })
    await data.loadAll()
    const task = {
      taskId: 'python-task', executionStatus: 'RUNNING', executorProvider: 'CODEX',
      connection: { status: 'ACTIVE' },
      targets: [{ targetRole: 'PRIMARY', snapshot: { sdkLanguage: 'python' } }],
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: { task, readiness: [{ key: 'CODE_READY', status: 'PASS' }], events: [], questions: [], artifacts: [] } })
    mocks.listRegistryProjectInstances.mockResolvedValueOnce({ data: [{ id: 1, instanceId: 'worker', status: 'ONLINE' }] })
    await data.refreshOnboardingTaskInBackground(() => true)
    expect(data.detectedSdkLanguage.value).toBe('python')
    expect(data.selfCheckSource.value).toBe('TASK')
    expect(data.instances.value[0]?.status).toBe('ONLINE')
    expect(mocks.createAiCodingTask).not.toHaveBeenCalled()
    expect(mocks.issueAiCodingHandoff).not.toHaveBeenCalled()

    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockRejectedValueOnce(new Error('offline'))
    mocks.listRegistryProjectInstances.mockRejectedValueOnce(new Error('offline'))
    await expect(data.refreshOnboardingTaskInBackground(() => true)).rejects.toThrow('offline')
    expect(data.detectedSdkLanguage.value).toBe('python')
    expect(data.selfCheckSource.value).toBe('TASK')
    expect(data.instances.value[0]?.status).toBe('ONLINE')
  })

  it('keeps manual access available and retries when task state is unknown', async () => {
    mocks.listAiCodingTasks.mockRejectedValueOnce(new Error('task endpoint down'))
    const data = createData()

    await data.loadAll()

    expect(data.loadError.value).toBe('')
    expect(data.project.value?.projectCode).toBe('demo-project')
    expect(data.aiTaskLoadError.value).toContain('task endpoint down')

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

  it('does not infer a six-step report from a completed legacy task', async () => {
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

    expect(data.aiReportAvailable.value).toBe(false)
    expect(data.aiAccessCompletedSteps.value).toBe(0)
    expect(data.aiAccessTotalSteps.value).toBe(6)
    expect(data.aiDisplaySteps.value).toHaveLength(6)
    expect(data.aiDisplaySteps.value.every((step) => step.status === 'TODO')).toBe(true)
    expect(data.taskGuidance.value.title).toBe('接入已验收')
  })

  it('reports real activity and open questions without inventing per-step progress', async () => {
    const task = {
      taskId: 'waiting-task', executionStatus: 'WAITING_USER', executorProvider: 'CODEX',
      connection: { status: 'ACTIVE' },
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: {
      task, readiness: [], artifacts: [],
      events: [
        { id: 1, eventType: 'STARTED', message: '开始接入', createdAt: '2026-11-01T10:00:00' },
        { id: 2, eventType: 'PROGRESS', message: '已检查网关', createdAt: '2026-11-01T10:01:00' },
      ],
      questions: [
        { questionId: 'q1', title: '确认模块', status: 'OPEN' },
        { questionId: 'q2', title: '已回答', status: 'ANSWERED' },
      ],
    } })
    const data = createData()
    await data.loadAll()
    expect(data.latestTaskEvent.value?.message).toBe('已检查网关')
    expect(data.openTaskQuestions.value).toHaveLength(1)
    expect(data.taskGuidance.value.action).toBe('question')
    expect(data.aiReportAvailable.value).toBe(false)
    expect(data.aiDisplaySteps.value.every((step) => step.status === 'TODO')).toBe(true)
  })

  it.each([
    ['READY', 'WAITING_CONNECT', 'start'],
    ['RUNNING', 'ACTIVE', 'detail'],
    ['RUNNING', 'TIMED_OUT', 'start'],
    ['RESULT_SUBMITTED', 'ACTIVE', 'detail'],
    ['RESULT_APPLIED', 'ACTIVE', 'verify'],
    ['ACCEPTANCE_READY', 'CLOSED', 'acceptance'],
    ['FAILED', 'CLOSED', 'detail'],
    ['CANCELLED', 'CLOSED', 'start'],
  ])('offers %s task the appropriate action', async (executionStatus, connectionStatus, action) => {
    const task = {
      taskId: 'status-task', executionStatus, executorProvider: 'CODEX',
      connection: { status: connectionStatus },
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: { task, readiness: [], artifacts: [], events: [], questions: [] } })
    const data = createData()
    await data.loadAll()
    expect(data.taskGuidance.value.action).toBe(action)
  })

  it('directs a previously ready task with a failing check to verification', async () => {
    const task = {
      taskId: 'regressed-task', executionStatus: 'ACCEPTANCE_READY', executorProvider: 'CODEX',
      connection: { status: 'CLOSED' },
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: {
      task, readiness: [{ key: 'RUNTIME_READY', status: 'WARN' }], artifacts: [], events: [], questions: [],
    } })
    const data = createData()
    await data.loadAll()
    expect(data.taskGuidance.value.action).toBe('verify')
    expect(data.taskGuidance.value.tone).toBe('warning')
  })

  it('counts only passing report steps while retaining non-pass results', async () => {
    const task = {
      taskId: 'report-task', executionStatus: 'RESULT_APPLIED', executorProvider: 'CODEX',
      connection: { status: 'ACTIVE' },
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: {
      task, readiness: [], events: [], questions: [],
      artifacts: [{ contractKey: 'reachai.project-onboarding-report', processingStatus: 'APPLIED',
        applicationResult: { codingReport: { steps: [
          { stepKey: 'PROJECT', status: 'PASS' },
          { stepKey: 'GATEWAY', status: 'WARN' },
          { stepKey: 'FINAL_CHECK', status: 'SKIPPED' },
        ] } } }],
    } })
    const data = createData()
    await data.loadAll()
    expect(data.aiReportAvailable.value).toBe(true)
    expect(data.aiAccessCompletedSteps.value).toBe(1)
    expect(data.aiDisplaySteps.value.map((step) => step.status)).toEqual(['PASS', 'WARN', 'SKIPPED'])
    expect(data.taskGuidance.value.action).toBe('verify')
  })

  it('reports partial background refresh failures and retains the last successful task time', async () => {
    const data = createData()
    await data.loadAll()
    const lastTaskTime = data.taskLastUpdatedAt.value
    expect(lastTaskTime).toBeInstanceOf(Date)
    mocks.listAiCodingTasks.mockRejectedValueOnce(new Error('task offline'))
    mocks.listRegistryProjectInstances.mockResolvedValueOnce({ data: [] })
    await expect(data.refreshOnboardingTaskInBackground(() => true)).rejects.toThrow('task offline')
    expect(data.taskLastUpdatedAt.value).toBe(lastTaskTime)
    expect(data.taskRefreshError.value).toContain('任务状态刷新失败')
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [] })
    mocks.listRegistryProjectInstances.mockRejectedValueOnce(new Error('instances offline'))
    await data.refreshOnboardingTaskInBackground(() => true)
    expect(data.taskRefreshError.value).toContain('实例状态刷新失败')
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [] })
    await data.retryOnboardingTaskLoad()
    expect(data.taskRefreshError.value).toContain('实例状态刷新失败')
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [] })
    mocks.listRegistryProjectInstances.mockResolvedValueOnce({ data: [] })
    await data.retryTaskRefresh()
    expect(data.taskRefreshError.value).toBe('')
  })

  it('discards old project background responses after the project changes', async () => {
    const projectCode = ref('demo-project')
    const data = createData(projectCode)
    await data.loadAll()
    let resolveOldTasks!: (value: { data: never[] }) => void
    let resolveOldInstances!: (value: { data: Array<{ instanceId: string }> }) => void
    mocks.listAiCodingTasks.mockImplementationOnce(() => new Promise((resolve) => { resolveOldTasks = resolve }))
    mocks.listRegistryProjectInstances.mockImplementationOnce(() => new Promise((resolve) => { resolveOldInstances = resolve }))
    const oldRefresh = data.refreshOnboardingTaskInBackground(() => true)
    projectCode.value = 'other-project'
    mocks.getScanProjects.mockResolvedValueOnce({ data: [{ id: 28, projectCode: 'other-project', name: 'Other' }] })
    mocks.getScanProjectDetail.mockResolvedValueOnce({ data: { id: 28, projectCode: 'other-project', name: 'Other' } })
    mocks.listRegistryProjectInstances.mockResolvedValueOnce({ data: [{ instanceId: 'new-instance' }] })
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [] })
    await data.loadAll()
    const newTaskTime = data.taskLastUpdatedAt.value
    resolveOldTasks({ data: [] })
    resolveOldInstances({ data: [{ instanceId: 'old-instance' }] })
    await oldRefresh
    expect(data.project.value?.projectCode).toBe('other-project')
    expect(data.instances.value[0]?.instanceId).toBe('new-instance')
    expect(data.taskLastUpdatedAt.value).toBe(newTaskTime)
    expect(data.taskRefreshError.value).toBe('')
  })

  it('discards a slower background failure after a newer refresh succeeds', async () => {
    const data = createData()
    await data.loadAll()
    let rejectOldTasks!: (error: Error) => void
    mocks.listAiCodingTasks.mockImplementationOnce(() => new Promise((_, reject) => { rejectOldTasks = reject }))
    mocks.listRegistryProjectInstances.mockResolvedValueOnce({ data: [] })
    const oldRefresh = data.refreshOnboardingTaskInBackground(() => true)
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [] })
    mocks.listRegistryProjectInstances.mockResolvedValueOnce({ data: [] })
    await data.retryTaskRefresh()
    const newTaskTime = data.taskLastUpdatedAt.value
    rejectOldTasks(new Error('outdated failure'))
    await oldRefresh
    expect(data.taskLastUpdatedAt.value).toBe(newTaskTime)
    expect(data.taskRefreshError.value).toBe('')
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
  it('does not treat a project-level passing check as Python acceptance even with a Python heartbeat', () => {
    const data = useSdkAccessWizardData({
      sdkLanguage: ref('python' as const),
      projectCode: ref('demo-project'),
    })
    data.instances.value = [{ id: 1, projectId: 27, projectCode: 'demo-project', instanceId: 'python',
      status: 'ONLINE', metadataJson: '{"sdkLanguage":"python"}' }]
    data.checkResult.value = { projectId: 27, projectCode: 'demo-project', overallStatus: 'PASS', checks: [],
      readiness: [{ key: 'SDK_CALLBACK_READY', label: '回调', status: 'PASS', message: '项目级证据' }] }
    expect(data.selfCheckVerified.value).toBe(false)
  })

  it('does not treat a project-level passing check as Node acceptance even with a Node heartbeat', () => {
    const data = useSdkAccessWizardData({
      sdkLanguage: ref('node' as const),
      projectCode: ref('demo-project'),
    })
    data.instances.value = [{ id: 1, projectId: 27, projectCode: 'demo-project', instanceId: 'node',
      status: 'ONLINE', metadataJson: '{"sdkLanguage":"node"}' }]
    data.checkResult.value = { projectId: 27, projectCode: 'demo-project', overallStatus: 'PASS', checks: [],
      readiness: [{ key: 'SDK_CALLBACK_READY', label: '回调', status: 'PASS', message: '项目级证据' }] }
    expect(data.selfCheckVerified.value).toBe(false)
  })

  it('does not treat a project-level passing check as Go acceptance even with a Go heartbeat', () => {
    const data = useSdkAccessWizardData({
      sdkLanguage: ref('go' as const),
      projectCode: ref('demo-project'),
    })
    data.instances.value = [{ id: 1, projectId: 27, projectCode: 'demo-project', instanceId: 'go',
      status: 'ONLINE', metadataJson: '{"sdkLanguage":"go"}' }]
    data.checkResult.value = { projectId: 27, projectCode: 'demo-project', overallStatus: 'PASS', checks: [],
      readiness: [{ key: 'SDK_CALLBACK_READY', label: '回调', status: 'PASS', message: '项目级证据' }] }
    expect(data.selfCheckVerified.value).toBe(false)
  })

  it('does not show Java task readiness for C# manual access', async () => {
    const task = {
      taskId: 'java-task', executionStatus: 'RUNNING', executorProvider: 'CODEX',
      connection: { status: 'WAITING_CONNECT' },
      targets: [{ targetRole: 'PRIMARY', snapshot: { sdkLanguage: 'java' } }],
    }
    mocks.listAiCodingTasks.mockResolvedValueOnce({ data: [task] })
    mocks.getAiCodingTask.mockResolvedValueOnce({ data: { task, readiness: [
      { key: 'CODE_READY', label: '代码接入', status: 'PASS', message: 'Java task' },
    ], events: [], questions: [], artifacts: [] } })
    const data = useSdkAccessWizardData({
      sdkLanguage: ref('csharp' as const),
      projectCode: ref('demo-project'),
    })
    await data.loadAll()
    expect(data.selfCheckVerified.value).toBe(false)
    expect(data.selfCheckSource.value).not.toBe('TASK')
  })
})
