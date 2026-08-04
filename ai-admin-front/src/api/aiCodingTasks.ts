import { controlRequest } from './request'
import type {
  AiCodingAcceptanceVerification,
  AiCodingAcceptanceRequest,
  AiCodingCredentialPolicy,
  AiCodingCredentialPolicyUpdateRequest,
  AiCodingHandoffPackage,
  AiCodingTask,
  AiCodingTaskCreateRequest,
  AiCodingTaskDetail,
  AiCodingTaskQuestion,
} from '@/types/aiCodingTask'

const ROOT = '/api/ai-coding-console/tasks'
const PROJECT_ROOT = '/api/ai-coding-console/projects'

export function createAiCodingTask(data: AiCodingTaskCreateRequest) {
  return controlRequest.post<AiCodingTask>(ROOT, data)
}

export function listAiCodingTasks(params: {
  projectId?: number
  projectCode?: string
  taskKind?: string
  executionStatus?: string
  limit?: number
}) {
  return controlRequest.get<AiCodingTask[]>(ROOT, { params })
}

export function getAiCodingTask(taskId: string) {
  return controlRequest.get<AiCodingTaskDetail>(
    `${ROOT}/${encodeURIComponent(taskId)}`,
  )
}

export function issueAiCodingHandoff(taskId: string, issuedBy?: string) {
  return controlRequest.post<AiCodingHandoffPackage>(
    `${ROOT}/${encodeURIComponent(taskId)}/handoffs`,
    issuedBy ? { issuedBy } : {},
  )
}

export function answerAiCodingQuestion(
  taskId: string,
  questionId: string,
  answer: string,
  answeredBy?: string,
) {
  return controlRequest.post<AiCodingTaskQuestion>(
    `${ROOT}/${encodeURIComponent(taskId)}/questions/${encodeURIComponent(questionId)}/answer`,
    { answer, answeredBy },
  )
}

export function cancelAiCodingTask(taskId: string, actor?: string) {
  return controlRequest.post<AiCodingTask>(
    `${ROOT}/${encodeURIComponent(taskId)}/cancel`,
    actor ? { actor } : {},
  )
}

export function finishAiCodingAcceptance(
  taskId: string,
  data: AiCodingAcceptanceRequest,
) {
  return controlRequest.post<AiCodingTask>(
    `${ROOT}/${encodeURIComponent(taskId)}/acceptance`,
    data,
  )
}

export function verifyAiCodingAcceptanceReadiness(taskId: string) {
  return controlRequest.post<AiCodingAcceptanceVerification>(
    `${ROOT}/${encodeURIComponent(taskId)}/acceptance-verification`,
  )
}

export function getAiCodingCredentialPolicy(projectId: number) {
  return controlRequest.get<AiCodingCredentialPolicy>(
    `${PROJECT_ROOT}/${projectId}/credential-policy`,
  )
}

export function updateAiCodingCredentialPolicy(
  projectId: number,
  data: AiCodingCredentialPolicyUpdateRequest,
) {
  return controlRequest.put<AiCodingCredentialPolicy>(
    `${PROJECT_ROOT}/${projectId}/credential-policy`,
    data,
  )
}
