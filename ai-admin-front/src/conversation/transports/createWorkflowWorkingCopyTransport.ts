import {
  cancelWorkflowDebugSession,
  createWorkflowDebugSession,
  getWorkflowDebugSession,
  getWorkflowDebugSessionByCreationKey,
  submitWorkflowDebugSession,
} from '@/api/workflow'
import type {
  WorkflowDebugSessionCreateRequest,
  WorkflowDebugSessionView,
} from '@/types/workflow'
import { platformCsrfHeaders } from '@/utils/platformAuth'
import {
  adaptWorkflowDebugStreamEvent,
  expandWorkflowDebugAdapted,
} from '../core/adapters/adaptWorkflowDebugStreamEvent'
import {
  adaptWorkflowSessionViewToEvents,
  adaptWorkflowSessionViewToSnapshot,
  isWorkflowSessionInFlight,
  WORKFLOW_DEBUG_UNCONFIRMED_MESSAGE,
} from '../core/adapters/adaptWorkflowSessionView'
import type { ConversationEventEnvelope } from '../core/conversationEvents'
import { createEvent } from '../core/conversationEvents'
import { parseSseStream } from '../core/parseSseStream'
import {
  canFallbackFromStreamFailure,
  StreamFallbackForbiddenError,
} from '../core/streamFallbackPolicy'
import type { ConversationSnapshot, ConversationTurnInput } from '../core/conversationTypes'
import { buildDebugInteractionIdempotencyKey, createAttemptIdempotencyKey } from '../core/buildInteractionIdempotencyKey'
import type { ConversationTransport } from './transportTypes'

export const WORKFLOW_INITIAL_INPUT_ID = 'local:workflow-initial-input'

export interface WorkflowWorkingCopyTransportOptions {
  getSessionId?: () => string | undefined
  setSessionId?: (sessionId: string | undefined) => void
  getCreationKey?: () => string | undefined
  setCreationKey?: (key: string | undefined) => void
  /** 构造 create 请求（工作副本 GraphSpec / Canvas 由 Shell 提供） */
  getCreateRequest?: () => Omit<WorkflowDebugSessionCreateRequest, 'message' | 'inputParams'> & {
    message?: string
    inputParams?: Record<string, unknown>
  }
  /** REST / SSE 每次返回完整 session 视图时回调 Shell 同步轨迹与高亮 */
  onSessionView?: (view: WorkflowDebugSessionView) => void
  /** 优先尝试 SSE；仅在明确不支持且未消费响应时回退 REST */
  tryStream?: boolean
  fetchImpl?: typeof fetch
}

async function unwrapView(
  promise: Promise<{ data: WorkflowDebugSessionView } | WorkflowDebugSessionView>,
): Promise<WorkflowDebugSessionView> {
  const result = await promise
  if (result && typeof result === 'object' && 'data' in result) {
    return (result as { data: WorkflowDebugSessionView }).data
  }
  return result as WorkflowDebugSessionView
}

export function createWorkflowWorkingCopyTransport(
  options: WorkflowWorkingCopyTransportOptions = {},
): ConversationTransport {
  const {
    getSessionId,
    setSessionId,
    getCreationKey,
    setCreationKey,
    getCreateRequest,
    onSessionView,
    tryStream = false,
    fetchImpl = fetch,
  } = options

  let disposed = false
  let activeSessionId: string | undefined
  let activeCreationKey: string | undefined
  let previousStepCount = 0

  function currentSessionId(): string | undefined {
    // When a host provides getSessionId, it is the source of truth (including undefined).
    if (getSessionId) return getSessionId() ?? undefined
    return activeSessionId
  }

  function rememberSessionId(sessionId: string) {
    if (disposed) return
    activeSessionId = sessionId
    setSessionId?.(sessionId)
  }

  function remember(view: WorkflowDebugSessionView) {
    if (disposed) return
    rememberSessionId(view.sessionId)
    previousStepCount = view.steps?.length || 0
    onSessionView?.(view)
    rememberCreationKey(undefined)
  }

  function rememberCreationKey(key: string | undefined) {
    activeCreationKey = key
    setCreationKey?.(key)
  }

  function beginCreation(): string {
    const key = createAttemptIdempotencyKey('wf-debug', 'create')
    rememberCreationKey(key)
    return key
  }

  function clearLocalSession() {
    activeSessionId = undefined
    previousStepCount = 0
    setSessionId?.(undefined)
  }

  async function* eventsFromView(view: WorkflowDebugSessionView): AsyncIterable<ConversationEventEnvelope> {
    const start = view.steps && view.steps.length >= previousStepCount ? previousStepCount : 0
    remember(view)
    yield* adaptWorkflowSessionViewToEvents(view, { previousStepCount: start })
  }

  async function* streamWorkflowTurn(
    path: string,
    body: Record<string, unknown>,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope> {
    const headers = platformCsrfHeaders({
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    })

    const response = await fetchImpl(path, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      credentials: 'same-origin',
      signal,
    })
    if (!response.ok || !response.body) {
      const err = new Error(`Workflow debug stream failed: HTTP ${response.status}`) as Error & {
        status?: number
        bytesOrEventsConsumed?: boolean
      }
      err.status = response.status
      err.bytesOrEventsConsumed = false
      throw err
    }
    const contentType = response.headers.get('content-type') || ''
    if (!contentType.toLowerCase().includes('text/event-stream')) {
      const err = new Error(`Workflow debug stream returned non-SSE response: ${contentType || 'unknown'}`) as Error & {
        status?: number
        bytesOrEventsConsumed?: boolean
      }
      err.status = response.status
      err.bytesOrEventsConsumed = false
      throw err
    }

    let streamSessionId = currentSessionId()
    let terminalReceived = false
    yield createEvent('turn.started', {}, { sessionId: streamSessionId })

    try {
      for await (const frame of parseSseStream(response.body, { signal })) {
        for (const event of expandWorkflowDebugAdapted(
          adaptWorkflowDebugStreamEvent(frame.event, frame.data, { sessionId: streamSessionId }),
        )) {
          if (event.sessionId) streamSessionId = event.sessionId
          const data = event.data as Record<string, unknown> | undefined
          if (data?.sessionId) streamSessionId = String(data.sessionId)
          if (streamSessionId) rememberSessionId(streamSessionId)
          if (event.type === 'session.created' && data && typeof data.status === 'string'
            && Array.isArray(data.messages) && Array.isArray(data.steps)) {
            remember(data as unknown as WorkflowDebugSessionView)
          }
          if (['turn.completed', 'turn.waiting', 'turn.failed', 'turn.cancelled'].includes(event.type)) {
            terminalReceived = true
          }
          yield event
        }
      }

      if (streamSessionId) {
        const view = await unwrapView(getWorkflowDebugSession(streamSessionId))
        remember(view)
        if (isWorkflowSessionInFlight(view.status)) throw new Error(WORKFLOW_DEBUG_UNCONFIRMED_MESSAGE)
        if (!terminalReceived) yield createWorkflowRestoredEvent(adaptWorkflowSessionViewToSnapshot(view))
      } else if (!terminalReceived) {
        throw new Error('调试连接已结束，未收到会话标识或执行结果。请检查服务状态。')
      }
    } catch (error) {
      const err = error as Error & { status?: number; bytesOrEventsConsumed?: boolean; aborted?: boolean }
      // A successful SSE response has already accepted the POST. Later read/GET
      // errors are never evidence that the original streaming endpoint is unsupported.
      err.bytesOrEventsConsumed = true
      err.aborted = signal?.aborted === true
      throw err
    }
  }

  async function* runRestTurn(
    mode: 'create' | 'submit',
    body: Record<string, unknown>,
    sessionId?: string,
  ): AsyncIterable<ConversationEventEnvelope> {
    if (mode === 'submit' && sessionId) {
      const view = await unwrapView(submitWorkflowDebugSession(sessionId, body))
      yield* eventsFromView(view)
      return
    }
    const view = await unwrapView(createWorkflowDebugSession(body as unknown as WorkflowDebugSessionCreateRequest))
    yield* eventsFromView(view)
  }

  async function* runTurn(
    mode: 'create' | 'submit',
    body: Record<string, unknown>,
    sessionId?: string,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope> {
    if (!tryStream) {
      yield* runRestTurn(mode, body, sessionId)
      return
    }

    const streamPath = mode === 'submit' && sessionId
      ? `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}/submit/stream`
      : '/api/runtime/debug-sessions/stream'

    try {
      yield* streamWorkflowTurn(streamPath, body, signal)
    } catch (error) {
      const err = error as Error & {
        status?: number
        bytesOrEventsConsumed?: boolean
        aborted?: boolean
      }
      const allowFallback = canFallbackFromStreamFailure({
        status: err.status,
        bytesOrEventsConsumed: err.bytesOrEventsConsumed === true,
        aborted: err.aborted === true || signal?.aborted === true,
        error,
      })
      if (!allowFallback) {
        throw new StreamFallbackForbiddenError(
          err.message || 'Workflow stream failed; REST fallback forbidden',
          error,
        )
      }
      yield* runRestTurn(mode, body, sessionId)
    }
  }

  async function* startTurn(
    input: ConversationTurnInput,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope> {
    if (disposed) return

    const existingSessionId = currentSessionId()
    const isLocalInitial = input.interactionId === WORKFLOW_INITIAL_INPUT_ID
    const hasResumeInteraction = !!input.interactionId && !isLocalInitial

    // 普通 interaction resume：有 session 只能 submit；无 session 必须失败，绝不能回退 create
    if (hasResumeInteraction) {
      if (!existingSessionId) {
        throw new Error('Workflow interaction resume requires an active debug session')
      }
      const action = input.uiSubmit?.action || 'submit'
      const values = (input.uiSubmit?.values || input.values || {}) as Record<string, unknown>
      const idempotencyKey = buildDebugInteractionIdempotencyKey(
        String(input.interactionId),
        action,
        values,
      )
      yield* runTurn('submit', {
        action,
        values,
        message: input.message,
        interactionId: input.interactionId,
        idempotencyKey,
      }, existingSessionId, signal)
      return
    }

    // 仅本地初始表单（WORKFLOW_INITIAL_INPUT_ID）或自由文本可 create
    if (isLocalInitial) {
      clearLocalSession()
      const createRequest = getCreateRequest?.()
      if (!createRequest) {
        throw new Error('WorkflowWorkingCopyTransport requires getCreateRequest for structured initial input')
      }
      yield* runTurn('create', {
        ...createRequest,
        idempotencyKey: beginCreation(),
        message: input.message,
        inputParams: input.values ?? createRequest.inputParams,
      }, undefined, signal)
      return
    }

    if (input.message) {
      // Free-text after completed/failed/cancelled (or unknown) must create a new session.
      // Do not submit a stale session id.
      clearLocalSession()
      const createRequest = getCreateRequest?.()
      if (!createRequest) {
        throw new Error('WorkflowWorkingCopyTransport requires getCreateRequest for new session')
      }
      yield* runTurn('create', {
        ...createRequest,
        idempotencyKey: beginCreation(),
        message: input.message,
      }, undefined, signal)
      return
    }

    throw new Error('WorkflowWorkingCopyTransport startTurn requires message or structured values')
  }

  return {
    kind: 'workflow-working-copy',
    capabilities: {
      eventStreaming: tryStream,
      tokenStreaming: false,
      abort: false,
      restore: true,
      structuredInitialInput: true,
      pageActions: false,
    },

    startTurn,

    resumeInteraction(interactionId, action, values, signal) {
      return startTurn({
        interactionId,
        uiSubmit: { action, values },
        values,
      }, signal)
    },

    async restoreSession(): Promise<ConversationSnapshot | null> {
      if (disposed) return null
      const sessionId = currentSessionId()
      const creationKey = getCreationKey ? getCreationKey() : activeCreationKey
      if (!sessionId && !creationKey) return null
      const view = await unwrapView(sessionId
        ? getWorkflowDebugSession(sessionId)
        : getWorkflowDebugSessionByCreationKey(creationKey!))
      if (disposed) return null
      remember(view)
      return adaptWorkflowSessionViewToSnapshot(view)
    },

    async cancelTurn() {
      const sessionId = currentSessionId()
      if (!sessionId) return
      await cancelWorkflowDebugSession(sessionId)
    },

    async clearSession() {
      clearLocalSession()
      rememberCreationKey(undefined)
    },

    dispose() {
      disposed = true
      clearLocalSession()
    },
  }
}

/** 将 debugInputFields 转为本地初始 form UiRequest */
export function buildWorkflowInitialFormRequest(fields: Array<{
  key: string
  label?: string
  type?: string
  required?: boolean
  placeholder?: string
  options?: Array<{ value: string | number | boolean; label: string }>
}> , prefilled?: Record<string, unknown>) {
  return {
    schemaVersion: '1.0' as const,
    interactionId: WORKFLOW_INITIAL_INPUT_ID,
    type: 'form',
    component: 'form',
    title: '调试输入',
    fields: fields.map((f) => ({
      key: f.key,
      label: f.label || f.key,
      type: f.type || 'string',
      required: f.required,
      placeholder: f.placeholder,
      options: f.options,
    })),
    prefilled,
  }
}

export function createWorkflowRestoredEvent(snapshot: ConversationSnapshot): ConversationEventEnvelope {
  return createEvent('session.restored', snapshot, { sessionId: snapshot.sessionId })
}
