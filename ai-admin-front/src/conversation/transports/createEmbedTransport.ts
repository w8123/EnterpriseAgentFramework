import { adaptEmbedEvent, expandEmbedAdapted } from '../core/adapters/adaptEmbedEvent'
import type { ConversationEventEnvelope } from '../core/conversationEvents'
import { createEvent } from '../core/conversationEvents'
import type { ConversationTurnInput } from '../core/conversationTypes'
import { parseSseStream, isAbortError } from '../core/parseSseStream'
import { createAttemptIdempotencyKey } from '../core/buildInteractionIdempotencyKey'
import { canFallbackFromStreamFailure } from '../core/streamFallbackPolicy'
import { isBlockingUiRequest, isCardOnlyUiRequest } from '../core/normalizeUiRequest'
import type { ConversationTransport } from './transportTypes'

export interface EmbedTransportOptions {
  apiBase: string
  tokenProvider: () => string
  getSessionId: () => string | undefined
  setSessionId: (sessionId: string | undefined) => void
  getContext?: () => Record<string, unknown> | undefined
  createSessionPayload?: () => Record<string, unknown>
  onUnauthorized?: () => Promise<string | void>
  /** false 时走 JSON /messages，不走 SSE */
  preferStream?: boolean
  messagesPath?: (sessionId: string) => string
  messagesJsonPath?: (sessionId: string) => string
  interactionSubmitPath?: (sessionId: string, interactionId: string) => string
  interactionSubmitStreamPath?: (sessionId: string, interactionId: string) => string
  sessionsPath?: string
  fetchImpl?: typeof fetch
}

export function createEmbedTransport(options: EmbedTransportOptions): ConversationTransport {
  const fetchImpl = options.fetchImpl || fetch
  const preferStream = options.preferStream !== false
  let disposed = false
  const abortControllers = new Set<AbortController>()
  let ensureSessionPromise: Promise<string> | null = null

  function resolveUrl(path: string): string {
    const base = options.apiBase.replace(/\/$/, '')
    if (path.startsWith('http')) return path
    return `${base}${path.startsWith('/') ? path : `/${path}`}`
  }

  async function authorizedFetch(url: string, init: RequestInit, signal?: AbortSignal): Promise<Response> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(init.headers as Record<string, string> | undefined),
    }
    const token = options.tokenProvider()
    if (token) headers.Authorization = `Bearer ${token}`

    let response = await fetchImpl(url, { ...init, headers, signal })
    if (response.status === 401 && options.onUnauthorized) {
      const refreshed = await options.onUnauthorized()
      if (typeof refreshed === 'string' && refreshed) {
        headers.Authorization = `Bearer ${refreshed}`
      } else {
        const next = options.tokenProvider()
        if (next) headers.Authorization = `Bearer ${next}`
      }
      response = await fetchImpl(url, { ...init, headers, signal })
    }
    return response
  }

  function httpError(message: string, status: number): Error & { status: number } {
    const error = new Error(`${message}: HTTP ${status}`) as Error & { status: number }
    error.status = status
    return error
  }

  /**
   * 会话唯一所有者入口：并发调用共享同一个 create Promise。
   */
  async function ensureSession(signal?: AbortSignal): Promise<string> {
    if (disposed) throw new Error('Embed transport disposed')
    const existing = options.getSessionId()
    if (existing) return existing

    if (!ensureSessionPromise) {
      ensureSessionPromise = (async () => {
        const sessionsPath = options.sessionsPath || '/chat/sessions'
        const response = await authorizedFetch(resolveUrl(sessionsPath), {
          method: 'POST',
          body: JSON.stringify(options.createSessionPayload?.() || { context: options.getContext?.() }),
        }, signal)

        if (!response.ok) {
          throw httpError('Embed session create failed', response.status)
        }
        const payload = await response.json().catch(() => ({})) as Record<string, unknown>
        const data = (payload.data && typeof payload.data === 'object' ? payload.data : payload) as Record<string, unknown>
        const sessionId = data.sessionId ? String(data.sessionId) : (data.id ? String(data.id) : undefined)
        if (!sessionId) throw new Error('Embed session create returned no sessionId')
        options.setSessionId(sessionId)
        return sessionId
      })().finally(() => {
        ensureSessionPromise = null
      })
    }

    return ensureSessionPromise
  }

  async function recreateSession(signal?: AbortSignal): Promise<string> {
    options.setSessionId(undefined)
    return ensureSession(signal)
  }

  async function* streamFromResponse(
    response: Response,
    sessionId: string,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope> {
    if (!response.body) throw new Error('Embed stream has no body')
    for await (const frame of parseSseStream(response.body, { signal })) {
      const adapted = adaptEmbedEvent(frame.event, frame.data, { sessionId })
      for (const event of expandEmbedAdapted(adapted)) {
        yield event
      }
    }
  }

  function* eventsFromJsonPayload(
    data: Record<string, unknown>,
    sessionId: string,
  ): Generator<ConversationEventEnvelope> {
    yield createEvent('turn.started', {}, { sessionId })
    if (data.answer && !isCardOnlyUiRequest(data.uiRequest)) {
      yield createEvent('message.delta', { text: String(data.answer) }, { sessionId })
    }
    const publicMetadata = data.metadata && typeof data.metadata === 'object' && !Array.isArray(data.metadata)
      ? { ...(data.metadata as Record<string, unknown>) }
      : undefined
    if (data.uiRequest) {
      yield createEvent('ui.requested', { uiRequest: data.uiRequest }, { sessionId })
      yield createEvent(isBlockingUiRequest(data.uiRequest) ? 'turn.waiting' : 'turn.completed', {
        answer: data.answer,
        uiRequest: data.uiRequest,
        sessionId,
        result: data,
        metadata: publicMetadata,
        intentType: data.intentType,
        toolCalls: data.toolCalls,
      }, { sessionId })
    } else {
      yield createEvent('turn.completed', {
        answer: data.answer,
        sessionId,
        result: data,
        metadata: publicMetadata,
        intentType: data.intentType,
        toolCalls: data.toolCalls,
      }, { sessionId })
    }
  }

  async function* startTurnJson(
    sessionId: string,
    input: ConversationTurnInput,
    signal?: AbortSignal,
    allowSessionRecovery = true,
  ): AsyncIterable<ConversationEventEnvelope> {
    const path = options.messagesJsonPath?.(sessionId)
      || `/chat/sessions/${encodeURIComponent(sessionId)}/messages`
    const response = await authorizedFetch(resolveUrl(path), {
      method: 'POST',
      body: JSON.stringify({
        message: input.message,
        context: options.getContext?.(),
        interactionId: input.interactionId,
        uiSubmit: input.uiSubmit,
      }),
    }, signal)
    if (response.status === 401 && allowSessionRecovery) {
      const nextSessionId = await recreateSession(signal)
      yield* startTurnJson(nextSessionId, input, signal, false)
      return
    }
    if (!response.ok) {
      throw httpError('Embed message failed', response.status)
    }
    const payload = await response.json().catch(() => ({})) as Record<string, unknown>
    const data = (payload.data && typeof payload.data === 'object' ? payload.data : payload) as Record<string, unknown>
    yield* eventsFromJsonPayload(data, sessionId)
  }

  async function* startTurnStream(
    sessionId: string,
    input: ConversationTurnInput,
    signal?: AbortSignal,
    allowSessionRecovery = true,
  ): AsyncIterable<ConversationEventEnvelope> {
    const path = options.messagesPath?.(sessionId)
      || `/chat/sessions/${encodeURIComponent(sessionId)}/messages/stream`
    const response = await authorizedFetch(resolveUrl(path), {
      method: 'POST',
      headers: { Accept: 'text/event-stream' },
      body: JSON.stringify({
        message: input.message,
        context: options.getContext?.(),
        interactionId: input.interactionId,
        uiSubmit: input.uiSubmit,
      }),
    }, signal)

    if (response.status === 401 && allowSessionRecovery) {
      const nextSessionId = await recreateSession(signal)
      yield* startTurnStream(nextSessionId, input, signal, false)
      return
    }

    const contentType = response.headers.get('content-type') || ''
    if (!response.ok || !response.body || !contentType.includes('text/event-stream')) {
      if (canFallbackFromStreamFailure({
        status: response.status,
        bytesOrEventsConsumed: false,
        aborted: signal?.aborted,
      })) {
        yield* startTurnJson(sessionId, input, signal, allowSessionRecovery)
        return
      }
      throw httpError('Embed stream failed', response.status)
    }

    yield createEvent('turn.started', {}, { sessionId })
    yield* streamFromResponse(response, sessionId, signal)
  }

  async function* startTurn(
    input: ConversationTurnInput,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope> {
    if (disposed) return

    const controller = new AbortController()
    abortControllers.add(controller)
    const onParentAbort = () => controller.abort()
    signal?.addEventListener('abort', onParentAbort)

    try {
      const sessionId = await ensureSession(controller.signal)

      if (!preferStream) {
        yield* startTurnJson(sessionId, input, controller.signal)
        return
      }
      yield* startTurnStream(sessionId, input, controller.signal)
    } catch (error) {
      if (isAbortError(error) || controller.signal.aborted) {
        yield createEvent('turn.cancelled', {}, { sessionId: options.getSessionId() })
        return
      }
      throw error
    } finally {
      signal?.removeEventListener('abort', onParentAbort)
      abortControllers.delete(controller)
    }
  }

  async function* resumeInteractionJson(
    sessionId: string,
    interactionId: string,
    action: string,
    values: Record<string, unknown>,
    idempotencyKey: string,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope> {
    const jsonPath = options.interactionSubmitPath?.(sessionId, interactionId)
      || `/chat/sessions/${encodeURIComponent(sessionId)}/interactions/${encodeURIComponent(interactionId)}/submit`
    const response = await authorizedFetch(resolveUrl(jsonPath), {
      method: 'POST',
      body: JSON.stringify({
        action,
        values,
        idempotencyKey,
        context: options.getContext?.(),
      }),
    }, signal)

    if (!response.ok) {
      throw httpError('Embed interaction submit failed', response.status)
    }

    const payload = await response.json().catch(() => ({})) as Record<string, unknown>
    const data = (payload.data && typeof payload.data === 'object' ? payload.data : payload) as Record<string, unknown>
    yield* eventsFromJsonPayload(data, sessionId)
  }

  async function* resumeInteraction(
    interactionId: string,
    action: string,
    values: Record<string, unknown>,
    signal?: AbortSignal,
  ): AsyncIterable<ConversationEventEnvelope> {
    if (disposed) return
    const sessionId = options.getSessionId()
    if (!sessionId) throw new Error('No embed session')

    // One attempt key per user click; reuse across stream → JSON fallback / network retry.
    const idempotencyKey = createAttemptIdempotencyKey('embed', interactionId)

    if (!preferStream) {
      yield* resumeInteractionJson(sessionId, interactionId, action, values, idempotencyKey, signal)
      return
    }

    const streamPath = options.interactionSubmitStreamPath?.(sessionId, interactionId)
      || `/chat/sessions/${encodeURIComponent(sessionId)}/interactions/${encodeURIComponent(interactionId)}/submit/stream`

    let eventsConsumed = false
    try {
      const response = await authorizedFetch(resolveUrl(streamPath), {
        method: 'POST',
        headers: { Accept: 'text/event-stream' },
        body: JSON.stringify({
          action,
          values,
          idempotencyKey,
          context: options.getContext?.(),
        }),
      }, signal)

      const contentType = response.headers.get('content-type') || ''
      if (response.ok && response.body && contentType.includes('text/event-stream')) {
        yield createEvent('turn.started', {}, { sessionId })
        eventsConsumed = true
        yield* streamFromResponse(response, sessionId, signal)
        return
      }

      if (canFallbackFromStreamFailure({
        status: response.status,
        bytesOrEventsConsumed: false,
        aborted: signal?.aborted,
      })) {
        yield* resumeInteractionJson(sessionId, interactionId, action, values, idempotencyKey, signal)
        return
      }

      throw httpError('Embed interaction stream failed', response.status)
    } catch (error) {
      if (isAbortError(error) || signal?.aborted) {
        yield createEvent('turn.cancelled', {}, { sessionId })
        return
      }
      if (canFallbackFromStreamFailure({
        status: (error as { status?: number })?.status,
        bytesOrEventsConsumed: eventsConsumed,
        aborted: signal?.aborted,
        error,
      })) {
        yield* resumeInteractionJson(sessionId, interactionId, action, values, idempotencyKey, signal)
        return
      }
      throw error
    }
  }

  return {
    kind: 'embed',
    capabilities: {
      eventStreaming: preferStream,
      tokenStreaming: true,
      abort: true,
      restore: false,
      structuredInitialInput: false,
      pageActions: true,
    },
    startTurn,
    resumeInteraction,
    /** 供 facade / 测试显式预热会话（带去重） */
    ensureSession,
    async cancelTurn() {
      for (const controller of abortControllers) controller.abort()
      abortControllers.clear()
    },
    dispose() {
      disposed = true
      ensureSessionPromise = null
      for (const controller of abortControllers) controller.abort()
      abortControllers.clear()
    },
  } as ConversationTransport & { ensureSession: typeof ensureSession }
}
