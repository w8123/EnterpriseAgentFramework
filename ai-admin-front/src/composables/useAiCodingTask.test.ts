import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAiCodingTask } from './useAiCodingTask'

const mocks = vi.hoisted(() => ({
  answerAiCodingQuestion: vi.fn(),
  cancelAiCodingTask: vi.fn(),
  createAiCodingTask: vi.fn(),
  listAiCodingTasks: vi.fn(),
  getAiCodingTask: vi.fn(),
  issueAiCodingHandoff: vi.fn(),
  startManagedAiCodingExecution: vi.fn(),
}))

vi.mock('@/api/aiCodingTasks', () => ({
  answerAiCodingQuestion: mocks.answerAiCodingQuestion,
  cancelAiCodingTask: mocks.cancelAiCodingTask,
  createAiCodingTask: mocks.createAiCodingTask,
  finishAiCodingAcceptance: vi.fn(),
  getAiCodingTask: mocks.getAiCodingTask,
  issueAiCodingHandoff: mocks.issueAiCodingHandoff,
  listAiCodingTasks: mocks.listAiCodingTasks,
  startManagedAiCodingExecution: mocks.startManagedAiCodingExecution,
  verifyAiCodingAcceptanceReadiness: vi.fn(),
}))

function task(taskId: string) {
  return {
    taskId,
    executionStatus: 'CREATED',
    connection: {
      status: 'WAITING_CONNECT',
    },
  }
}

function handoff(taskId: string) {
  return {
    schema: 'reachai.ai-coding.handoff-package.v1',
    taskId,
    handoffId: `handoff-${taskId}`,
    protocolVersion: 'v1',
    executorProvider: 'CODEX',
    activationUrl: `http://localhost/activate/${taskId}`,
    activationCode: `code-${taskId}`,
    activationExpiresAt: '2099-01-01T00:00:00Z',
    prompt: `prompt-${taskId}`,
    promptCharacters: 20,
  }
}

describe('useAiCodingTask', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('does not let an older task-list response overwrite the latest project', async () => {
    let resolveOlder:
      | ((value: { data: ReturnType<typeof task>[] }) => void)
      | undefined
    mocks.listAiCodingTasks
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveOlder = resolve
      }))
      .mockResolvedValueOnce({
        data: [task('latest-project-task')],
      })
    const kernel = useAiCodingTask()

    const olderLoad = kernel.loadTasks({ projectCode: 'older-project' })
    await kernel.loadTasks({ projectCode: 'latest-project' })
    resolveOlder?.({
      data: [task('older-project-task')],
    })
    await olderLoad

    expect(kernel.tasks.value.map((item) => item.taskId)).toEqual([
      'latest-project-task',
    ])
    expect(kernel.loading.value).toBe(false)
  })

  it('does not let an older detail response replace the task opened last', async () => {
    let resolveOlder:
      | ((value: {
          data: {
            task: ReturnType<typeof task>
            readiness: never[]
            events: never[]
            questions: never[]
            artifacts: never[]
          }
        }) => void)
      | undefined
    mocks.getAiCodingTask
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveOlder = resolve
      }))
      .mockResolvedValueOnce({
        data: {
          task: task('latest-task'),
          readiness: [],
          events: [],
          questions: [],
          artifacts: [],
        },
      })
    const kernel = useAiCodingTask()

    const olderLoad = kernel.refreshTask('older-task')
    await kernel.refreshTask('latest-task')
    resolveOlder?.({
      data: {
        task: task('older-task'),
        readiness: [],
        events: [],
        questions: [],
        artifacts: [],
      },
    })
    await olderLoad

    expect(kernel.selectedTaskDetail.value?.task.taskId).toBe('latest-task')
    expect(kernel.tasks.value.map((item) => item.taskId)).toEqual([
      'latest-task',
    ])
  })

  it('does not let an older handoff response replace the handoff issued last', async () => {
    let resolveOlder:
      | ((value: { data: ReturnType<typeof handoff> }) => void)
      | undefined
    mocks.issueAiCodingHandoff
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveOlder = resolve
      }))
      .mockResolvedValueOnce({
        data: handoff('latest-task'),
      })
    mocks.getAiCodingTask.mockResolvedValueOnce({
      data: {
        task: task('latest-task'),
        readiness: [],
        events: [],
        questions: [],
        artifacts: [],
      },
    })
    const kernel = useAiCodingTask()

    const olderIssue = kernel.reissueHandoff('older-task')
    await kernel.reissueHandoff('latest-task')
    resolveOlder?.({
      data: handoff('older-task'),
    })
    await olderIssue

    expect(kernel.latestHandoff.value?.taskId).toBe('latest-task')
    expect(kernel.selectedTaskDetail.value?.task.taskId).toBe('latest-task')
    expect(mocks.getAiCodingTask).toHaveBeenCalledTimes(1)
    expect(mocks.getAiCodingTask).toHaveBeenCalledWith('latest-task')
  })

  it('invalidates in-flight requests when the owning workbench resets', async () => {
    let resolvePending:
      | ((value: { data: ReturnType<typeof task>[] }) => void)
      | undefined
    mocks.listAiCodingTasks.mockImplementationOnce(() => new Promise((resolve) => {
      resolvePending = resolve
    }))
    const kernel = useAiCodingTask()

    const pendingLoad = kernel.loadTasks({ projectCode: 'old-project' })
    kernel.reset()
    resolvePending?.({
      data: [task('old-project-task')],
    })
    await pendingLoad

    expect(kernel.tasks.value).toEqual([])
    expect(kernel.selectedTaskDetail.value).toBeNull()
    expect(kernel.latestHandoff.value).toBeNull()
    expect(kernel.loading.value).toBe(false)
  })

  it('does not publish a task created for a workbench that already reset', async () => {
    let resolvePending:
      | ((value: { data: ReturnType<typeof task> }) => void)
      | undefined
    mocks.createAiCodingTask.mockImplementationOnce(() => new Promise((resolve) => {
      resolvePending = resolve
    }))
    mocks.issueAiCodingHandoff.mockResolvedValueOnce({
      data: handoff('old-project-task'),
    })
    const kernel = useAiCodingTask()

    const pendingCreate = kernel.createTask({} as never)
    kernel.reset()
    resolvePending?.({
      data: task('old-project-task'),
    })
    await pendingCreate

    expect(kernel.tasks.value).toEqual([])
    expect(kernel.selectedTaskDetail.value).toBeNull()
    expect(kernel.latestHandoff.value).toBeNull()
  })

  it('does not publish a task refresh triggered by an action from a reset workbench', async () => {
    let resolvePending: (() => void) | undefined
    mocks.answerAiCodingQuestion.mockImplementationOnce(() => new Promise<void>((resolve) => {
      resolvePending = resolve
    }))
    mocks.getAiCodingTask.mockResolvedValueOnce({
      data: {
        task: task('old-project-task'),
        readiness: [],
        events: [],
        questions: [],
        artifacts: [],
      },
    })
    const kernel = useAiCodingTask()

    const pendingAnswer = kernel.answerQuestion(
      'old-project-task',
      'question-1',
      'answer',
    )
    kernel.reset()
    resolvePending?.()
    await pendingAnswer

    expect(kernel.tasks.value).toEqual([])
    expect(kernel.selectedTaskDetail.value).toBeNull()
  })

  it('does not upsert a task mutation completed after its workbench reset', async () => {
    let resolvePending:
      | ((value: { data: ReturnType<typeof task> }) => void)
      | undefined
    mocks.cancelAiCodingTask.mockImplementationOnce(() => new Promise((resolve) => {
      resolvePending = resolve
    }))
    mocks.getAiCodingTask.mockResolvedValueOnce({
      data: {
        task: task('old-project-task'),
        readiness: [],
        events: [],
        questions: [],
        artifacts: [],
      },
    })
    const kernel = useAiCodingTask()

    const pendingCancel = kernel.cancelTask('old-project-task')
    kernel.reset()
    resolvePending?.({
      data: task('old-project-task'),
    })
    await pendingCancel

    expect(kernel.tasks.value).toEqual([])
    expect(kernel.selectedTaskDetail.value).toBeNull()
  })

  it('does not let a list snapshot started before a local mutation erase it', async () => {
    let resolvePending:
      | ((value: { data: ReturnType<typeof task>[] }) => void)
      | undefined
    mocks.listAiCodingTasks.mockImplementationOnce(() => new Promise((resolve) => {
      resolvePending = resolve
    }))
    mocks.createAiCodingTask.mockResolvedValueOnce({
      data: task('new-task'),
    })
    mocks.issueAiCodingHandoff.mockResolvedValueOnce({
      data: handoff('new-task'),
    })
    const kernel = useAiCodingTask()

    const pendingLoad = kernel.loadTasks({ projectCode: 'demo' })
    await kernel.createTask({} as never)
    resolvePending?.({ data: [] })
    await pendingLoad

    expect(kernel.tasks.value.map((item) => item.taskId)).toEqual(['new-task'])
  })

  it('does not keep an older activation code when reissuing fails', async () => {
    mocks.issueAiCodingHandoff
      .mockResolvedValueOnce({
        data: handoff('current-task'),
      })
      .mockRejectedValueOnce(new Error('handoff endpoint down'))
    mocks.getAiCodingTask.mockResolvedValueOnce({
      data: {
        task: task('current-task'),
        readiness: [],
        events: [],
        questions: [],
        artifacts: [],
      },
    })
    const kernel = useAiCodingTask()

    await kernel.reissueHandoff('current-task')
    expect(kernel.latestHandoff.value?.taskId).toBe('current-task')

    await expect(kernel.reissueHandoff('current-task')).rejects.toThrow(
      'handoff endpoint down',
    )
    expect(kernel.latestHandoff.value).toBeNull()
  })

  it('starts managed mode explicitly without issuing an external handoff', async () => {
    const created = {
      ...task('managed-task'),
      executionMode: 'MANAGED_SANDBOX',
      sandboxProfile: 'WORKSPACE_PATCH',
    }
    const started = {
      ...created,
      managedExecutionId: 'mex_1',
      managedExecutionStatus: 'QUEUED',
    }
    mocks.createAiCodingTask.mockResolvedValueOnce({ data: created })
    mocks.startManagedAiCodingExecution.mockResolvedValueOnce({
      data: {
        schema: 'reachai.ai-coding.managed-execution.v1',
        task: started,
        execution: {
          executionId: 'mex_1',
          status: 'QUEUED',
        },
        artifacts: [],
      },
    })
    const kernel = useAiCodingTask()

    const result = await kernel.createManagedTask({
      projectId: 1,
      projectCode: 'DEMO',
      taskKind: 'CODE_IMPLEMENTATION',
      executorProvider: 'CODEX',
      executionMode: 'MANAGED_SANDBOX',
      sandboxProfile: 'WORKSPACE_PATCH',
      title: 'Managed task',
      objective: 'Implement it',
      targets: [],
    })

    expect(result.managedExecution.execution.executionId).toBe('mex_1')
    expect(mocks.startManagedAiCodingExecution).toHaveBeenCalledWith('managed-task')
    expect(mocks.issueAiCodingHandoff).not.toHaveBeenCalled()
    expect(kernel.tasks.value[0].managedExecutionId).toBe('mex_1')
    expect(kernel.latestHandoff.value).toBeNull()
  })
})
