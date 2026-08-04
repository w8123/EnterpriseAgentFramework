import { ref } from 'vue'
import {
  answerAiCodingQuestion,
  cancelAiCodingTask,
  createAiCodingTask,
  finishAiCodingAcceptance,
  getAiCodingTask,
  issueAiCodingHandoff,
  listAiCodingTasks,
  verifyAiCodingAcceptanceReadiness,
} from '@/api/aiCodingTasks'
import type {
  AiCodingHandoffPackage,
  AiCodingTask,
  AiCodingTaskCreateRequest,
  AiCodingTaskDetail,
} from '@/types/aiCodingTask'

export function useAiCodingTask() {
  const tasks = ref<AiCodingTask[]>([])
  const selectedTaskDetail = ref<AiCodingTaskDetail | null>(null)
  const latestHandoff = ref<AiCodingHandoffPackage | null>(null)
  const loading = ref(false)
  let taskListLoadSequence = 0
  let taskDetailLoadSequence = 0
  let handoffIssueSequence = 0
  let stateGeneration = 0
  let taskMutationSequence = 0

  async function loadTasks(params: Parameters<typeof listAiCodingTasks>[0]) {
    const currentLoadSequence = ++taskListLoadSequence
    const currentMutationSequence = taskMutationSequence
    loading.value = true
    try {
      const response = await listAiCodingTasks(params)
      const rows = response.data || []
      if (
        currentLoadSequence !== taskListLoadSequence
        || currentMutationSequence !== taskMutationSequence
      ) {
        return tasks.value
      }
      tasks.value = rows
      if (
        selectedTaskDetail.value
        && !tasks.value.some(
          (task) => task.taskId === selectedTaskDetail.value?.task.taskId,
        )
      ) {
        selectedTaskDetail.value = null
      }
      return tasks.value
    } finally {
      if (currentLoadSequence === taskListLoadSequence) {
        loading.value = false
      }
    }
  }

  async function createTask(data: AiCodingTaskCreateRequest, issuedBy?: string) {
    const currentStateGeneration = stateGeneration
    const created = (await createAiCodingTask(data)).data
    if (currentStateGeneration === stateGeneration) {
      upsertTask(created)
    }
    const currentIssueSequence = ++handoffIssueSequence
    if (currentStateGeneration === stateGeneration) {
      latestHandoff.value = null
    }
    const handoff = (
      await issueAiCodingHandoff(created.taskId, issuedBy)
    ).data
    if (
      currentStateGeneration === stateGeneration
      && currentIssueSequence === handoffIssueSequence
    ) {
      latestHandoff.value = handoff
    }
    return {
      task: created,
      handoff,
    }
  }

  async function reissueHandoff(taskId: string, issuedBy?: string) {
    const currentStateGeneration = stateGeneration
    const currentIssueSequence = ++handoffIssueSequence
    latestHandoff.value = null
    const handoff = (
      await issueAiCodingHandoff(taskId, issuedBy)
    ).data
    if (
      currentStateGeneration !== stateGeneration
      || currentIssueSequence !== handoffIssueSequence
    ) {
      return handoff
    }
    latestHandoff.value = handoff
    await refreshTask(taskId, currentStateGeneration)
    return handoff
  }

  async function refreshTask(
    taskId: string,
    expectedStateGeneration = stateGeneration,
  ) {
    const currentLoadSequence = ++taskDetailLoadSequence
    const detail = (await getAiCodingTask(taskId)).data
    if (
      expectedStateGeneration !== stateGeneration
      || currentLoadSequence !== taskDetailLoadSequence
    ) {
      return detail
    }
    selectedTaskDetail.value = detail
    upsertTask(detail.task)
    return detail
  }

  async function answerQuestion(
    taskId: string,
    questionId: string,
    answer: string,
    answeredBy?: string,
  ) {
    const currentStateGeneration = stateGeneration
    await answerAiCodingQuestion(
      taskId,
      questionId,
      answer,
      answeredBy,
    )
    return refreshTask(taskId, currentStateGeneration)
  }

  async function finishAcceptance(
    taskId: string,
    passed: boolean,
    message: string,
    actor?: string,
  ) {
    const currentStateGeneration = stateGeneration
    const task = (
      await finishAiCodingAcceptance(taskId, {
        passed,
        message,
        actor,
      })
    ).data
    if (currentStateGeneration === stateGeneration) {
      upsertTask(task)
    }
    return refreshTask(taskId, currentStateGeneration)
  }

  async function verifyAcceptanceReadiness(taskId: string) {
    const currentStateGeneration = stateGeneration
    const verification = (
      await verifyAiCodingAcceptanceReadiness(taskId)
    ).data
    if (currentStateGeneration === stateGeneration) {
      upsertTask(verification.task)
    }
    await refreshTask(taskId, currentStateGeneration)
    return verification
  }

  async function cancelTask(taskId: string, actor?: string) {
    const currentStateGeneration = stateGeneration
    const task = (await cancelAiCodingTask(taskId, actor)).data
    if (currentStateGeneration === stateGeneration) {
      upsertTask(task)
    }
    return refreshTask(taskId, currentStateGeneration)
  }

  function upsertTask(task: AiCodingTask) {
    taskMutationSequence += 1
    const index = tasks.value.findIndex((item) => item.taskId === task.taskId)
    if (index >= 0) tasks.value[index] = task
    else tasks.value.unshift(task)
  }

  function reset() {
    stateGeneration += 1
    taskMutationSequence += 1
    taskListLoadSequence += 1
    taskDetailLoadSequence += 1
    handoffIssueSequence += 1
    tasks.value = []
    selectedTaskDetail.value = null
    latestHandoff.value = null
    loading.value = false
  }

  return {
    tasks,
    selectedTaskDetail,
    latestHandoff,
    loading,
    loadTasks,
    createTask,
    reissueHandoff,
    refreshTask,
    answerQuestion,
    verifyAcceptanceReadiness,
    finishAcceptance,
    cancelTask,
    upsertTask,
    reset,
  }
}
