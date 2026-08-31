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
  AiCodingManagedExecutionDetail,
  ManagedApprovalDecisionRequest,
  ManagedExecutionArtifact,
  ManagedExecutionStartRequest,
  ManagedExecutionProgressEvent,
} from '@/types/aiCodingTask'
import { handlePlatformSessionFailure } from '@/auth/platformSession'

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

export function startManagedAiCodingExecution(
  taskId: string,
  data: ManagedExecutionStartRequest = {},
) {
  return controlRequest.post<AiCodingManagedExecutionDetail>(
    `${ROOT}/${encodeURIComponent(taskId)}/managed-execution`,
    data,
  )
}

export function getManagedAiCodingExecution(taskId: string) {
  return controlRequest.get<AiCodingManagedExecutionDetail>(
    `${ROOT}/${encodeURIComponent(taskId)}/managed-execution`,
  )
}

/**
 * Authenticated SSE transport using the shared HttpOnly platform session.
 * Fetch streaming is retained for bounded parsing and abort support.
 */
export async function streamManagedAiCodingExecution(
  taskId: string,
  onSnapshot: (snapshot: ManagedExecutionProgressEvent) => void,
  signal: AbortSignal,
) {
  const response = await fetch(
    `${ROOT}/${encodeURIComponent(taskId)}/managed-execution/events`,
    {
      method: 'GET',
      headers: {
        Accept: 'text/event-stream',
      },
      cache: 'no-store',
      credentials: 'same-origin',
      signal,
    },
  )
  if (response.status === 401
    && response.headers.get('x-reachai-auth-failure') === 'PLATFORM_SESSION_INVALID') {
    handlePlatformSessionFailure()
  }
  if (!response.ok) {
    throw new Error(`隔离执行进度流连接失败（HTTP ${response.status}）`)
  }
  if (!response.headers.get('content-type')?.toLowerCase().includes('text/event-stream')) {
    throw new Error('隔离执行进度流返回了无效媒体类型')
  }
  if (!response.body) throw new Error('浏览器不支持隔离执行进度流')

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = ''
  let eventName = 'message'
  let dataLines: string[] = []
  const dispatch = () => {
    if (eventName === 'managed.snapshot' && dataLines.length) {
      const raw = dataLines.join('\n')
      if (raw.length > 64 * 1024) throw new Error('隔离执行进度事件超过安全上限')
      const parsed = JSON.parse(raw) as Partial<ManagedExecutionProgressEvent>
      if (
        parsed.schema !== 'reachai.ai-coding.managed-progress.v1'
        || parsed.taskId !== taskId
        || typeof parsed.executionId !== 'string'
        || typeof parsed.status !== 'string'
        || typeof parsed.lastEventSequence !== 'number'
      ) {
        throw new Error('隔离执行进度事件契约无效')
      }
      onSnapshot(parsed as ManagedExecutionProgressEvent)
    } else if (eventName === 'managed.error') {
      throw new Error('隔离执行进度流暂时不可用')
    }
    eventName = 'message'
    dataLines = []
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      if (buffer.length > 128 * 1024) {
        throw new Error('隔离执行进度缓冲超过安全上限')
      }
      let newline = buffer.indexOf('\n')
      while (newline >= 0) {
        let line = buffer.slice(0, newline)
        buffer = buffer.slice(newline + 1)
        if (line.endsWith('\r')) line = line.slice(0, -1)
        if (!line) {
          dispatch()
        } else if (!line.startsWith(':')) {
          const delimiter = line.indexOf(':')
          const field = delimiter < 0 ? line : line.slice(0, delimiter)
          const valueText = delimiter < 0
            ? ''
            : line.slice(delimiter + 1).replace(/^ /, '')
          if (field === 'event') eventName = valueText
          if (field === 'data') dataLines.push(valueText)
        }
        newline = buffer.indexOf('\n')
      }
      if (done) {
        if (buffer.trim()) {
          if (buffer.startsWith('data:')) dataLines.push(buffer.slice(5).trimStart())
        }
        if (dataLines.length) dispatch()
        return
      }
    }
  } finally {
    try {
      await reader.cancel()
    } catch {
      // The stream may already be closed or aborted.
    }
    try {
      reader.releaseLock()
    } catch {
      // Ignore an already released reader.
    }
  }
}

export function resolveManagedAiCodingApproval(
  taskId: string,
  interactionId: string,
  data: ManagedApprovalDecisionRequest,
) {
  return controlRequest.post<AiCodingManagedExecutionDetail>(
    `${ROOT}/${encodeURIComponent(taskId)}/managed-execution/approvals/${encodeURIComponent(interactionId)}:resolve`,
    data,
  )
}

export function listManagedAiCodingArtifacts(taskId: string) {
  return controlRequest.get<ManagedExecutionArtifact[]>(
    `${ROOT}/${encodeURIComponent(taskId)}/managed-execution/artifacts`,
  )
}

export function downloadManagedAiCodingArtifact(
  taskId: string,
  artifactId: string,
) {
  return controlRequest.get<Blob>(
    `${ROOT}/${encodeURIComponent(taskId)}/managed-execution/artifacts/${encodeURIComponent(artifactId)}`,
    { responseType: 'blob' },
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
