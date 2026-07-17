import { getPlatformToken } from '@/utils/platformAuth'
import type { ConversationEventEnvelope } from '../core/conversationEvents'
import { createEvent } from '../core/conversationEvents'
import { adaptAgentStreamEvent, expandAgentAdapted } from '../core/adapters/adaptAgentStreamEvent'
import { parseSseStream, isAbortError } from '../core/parseSseStream'
import type { ConversationTransport, ConversationTurnInput } from './transportTypes'

export interface AgentDebugTransportOptions {
  agentId: string
  getSessionId: () => string | undefined
  setSessionId: (sessionId: string | undefined) => void
  entryType?: 'DEBUG' | 'EMBED' | 'GATEWAY' | 'API' | 'EVAL' | 'REPLAY' | 'WORKFLOW_STUDIO' | 'A2A'
  userId?: string
  fetchImpl?: typeof fetch
}

export function createAgentDebugTransport(options: AgentDebugTransportOptions): ConversationTransport {
  const fetchImpl = options.fetchImpl || fetch
  let disposed = false
  let activeAbort: AbortController | null = null

  async function* streamExecute(body: Record<string, unknown>, signal?: AbortSignal) {
    if (disposed) return
    const token = getPlatformToken()
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    }
    if (token) headers.Authorization = `Bearer ${token}`

    const localAbort = new AbortController()
    activeAbort = localAbort
    const onAbort = () => localAbort.abort()
    signal?.addEventListener('abort', onAbort)

    try {
      const response = await fetchImpl('/api/runtime/agents/execute/stream', {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: localAbort.signal,
      })
      if (!response.ok || !response.body) {
        throw new Error(`Agent 流式执行失败：HTTP ${response.status}`)
      }
      const contentType = response.headers.get('content-type') || ''
      if (!contentType.toLowerCase().includes('text/event-stream')) {
        const rawBody = await response.text()
        let detail = rawBody.trim()
        try {
          const payload = JSON.parse(rawBody) as { message?: unknown }
          if (payload?.message) detail = String(payload.message)
        } catch {
          // keep raw
        }
        throw new Error(detail || `Agent 流式执行返回了非 SSE 响应：${contentType || 'unknown'}`)
      }

      yield createEvent('turn.started', {}, { sessionId: options.getSessionId() })

      for await (const frame of parseSseStream(response.body, { signal: localAbort.signal })) {
        const adapted = adaptAgentStreamEvent(frame.event, frame.data, {
          sessionId: options.getSessionId(),
        })
        for (const event of expandAgentAdapted(adapted)) {
          const data = event.data as Record<string, unknown> | undefined
          if (data && typeof data === 'object' && data.sessionId) {
            options.setSessionId(String(data.sessionId))
          }
          if (event.sessionId) options.setSessionId(event.sessionId)
          // execution.completed result often carries sessionId
          if (frame.event === 'execution.completed') {
            const record = frame.data && typeof frame.data === 'object'
              ? frame.data as Record<string, unknown>
              : null
            if (record?.sessionId) options.setSessionId(String(record.sessionId))
          }
          yield event
        }
      }
    } catch (error) {
      if (isAbortError(error) || localAbort.signal.aborted) {
        yield createEvent('turn.cancelled', {}, { sessionId: options.getSessionId() })
        return
      }
      throw error
    } finally {
      signal?.removeEventListener('abort', onAbort)
      if (activeAbort === localAbort) activeAbort = null
    }
  }

  return {
    kind: 'agent-debug',
    capabilities: {
      eventStreaming: true,
      tokenStreaming: true,
      abort: true,
      restore: false,
      structuredInitialInput: false,
      pageActions: false,
    },

    startTurn(input: ConversationTurnInput, signal?: AbortSignal): AsyncIterable<ConversationEventEnvelope> {
      return streamExecute({
        message: input.message,
        sessionId: options.getSessionId(),
        agentId: options.agentId,
        entryType: options.entryType || 'DEBUG',
        userId: options.userId,
        interactionId: input.interactionId,
        uiSubmit: input.uiSubmit,
        ...(input.values ? { values: input.values } : {}),
      }, signal)
    },

    resumeInteraction(interactionId, action, values, signal) {
      return streamExecute({
        sessionId: options.getSessionId(),
        agentId: options.agentId,
        entryType: options.entryType || 'DEBUG',
        userId: options.userId,
        interactionId,
        uiSubmit: { action, values },
      }, signal)
    },

    async clearSession() {
      const sessionId = options.getSessionId()
      if (!sessionId) return
      const token = getPlatformToken()
      const headers: Record<string, string> = {}
      if (token) headers.Authorization = `Bearer ${token}`
      await fetchImpl(`/api/runtime/agents/sessions/${encodeURIComponent(sessionId)}`, {
        method: 'DELETE',
        headers,
      })
      options.setSessionId(undefined)
    },

    async cancelTurn() {
      activeAbort?.abort()
    },

    dispose() {
      disposed = true
      activeAbort?.abort()
      activeAbort = null
    },
  }
}
