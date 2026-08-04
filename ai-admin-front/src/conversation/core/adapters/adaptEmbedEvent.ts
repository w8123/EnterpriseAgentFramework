import type { ConversationEventEnvelope } from '../conversationEvents'
import { createEvent } from '../conversationEvents'
import { isBlockingUiRequest } from '../normalizeUiRequest'

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}

function textFromDelta(data: unknown): string {
  const record = asRecord(data)
  if (record && 'text' in record) return String(record.text ?? '')
  if (typeof data === 'string') return data
  return ''
}

/**
 * 后端 completion 顶层为 { sessionId, answer, intentType, toolCalls, metadata, uiRequest }。
 * 公开 metadata 只能取内层 metadata，绝不能把整个 record 当作 metadata。
 */
export function extractEmbedPublicMetadata(
  completion: Record<string, unknown> | null | undefined,
): Record<string, unknown> | undefined {
  if (!completion) return undefined
  const nested = asRecord(completion.metadata)
  if (nested) return { ...nested }
  // 兼容：若调用方已传入纯 metadata 对象（无 answer/uiRequest 信封）
  if (
    !('answer' in completion)
    && !('uiRequest' in completion)
    && !('sessionId' in completion)
    && !('toolCalls' in completion)
  ) {
    return { ...completion }
  }
  return undefined
}

function completionPayload(
  record: Record<string, unknown> | null,
  data: unknown,
  sessionId: string | undefined,
  answer: string | undefined,
) {
  const publicMetadata = extractEmbedPublicMetadata(record || undefined)
  return {
    answer,
    sessionId,
    result: data,
    metadata: publicMetadata,
    intentType: record?.intentType,
    toolCalls: record?.toolCalls,
    uiRequest: record?.uiRequest,
  }
}

/**
 * 将 Embed SSE 事件映射为公共事件（默认不暴露 supervisor.step / reasoning）。
 */
export function adaptEmbedEvent(
  eventName: string,
  data: unknown,
  extras: { sessionId?: string } = {},
): ConversationEventEnvelope | ConversationEventEnvelope[] | null {
  const record = asRecord(data)
  const sessionId = extras.sessionId
    || (record?.sessionId ? String(record.sessionId) : undefined)

  switch (eventName) {
    case 'message.delta': {
      const text = textFromDelta(data)
      if (!text) return null
      return createEvent('message.delta', { text }, { sessionId })
    }
    case 'ui.requested':
      return createEvent('ui.requested', {
        uiRequest: record?.uiRequest ?? data,
      }, { sessionId })
    case 'page.action.requested':
      return createEvent('page.action.requested', data, { sessionId })
    case 'message.completed': {
      const answer = record?.answer != null ? String(record.answer) : undefined
      const uiRequest = record?.uiRequest
      const payload = completionPayload(record, data, sessionId, answer)
      if (uiRequest) {
        return [
          createEvent('ui.requested', { uiRequest }, { sessionId }),
          createEvent(isBlockingUiRequest(uiRequest) ? 'turn.waiting' : 'turn.completed', payload, { sessionId }),
        ]
      }
      return createEvent('turn.completed', payload, { sessionId })
    }
    case 'error':
    case 'execution.error': {
      const message = record?.message != null
        ? String(record.message)
        : (typeof data === 'string' ? data : 'Embed 对话失败')
      return createEvent('turn.failed', { message, error: message }, { sessionId })
    }
    case 'execution.started':
      return createEvent('turn.started', record || {}, { sessionId })
    case 'execution.completed': {
      return adaptEmbedEvent('message.completed', data, extras)
    }
    case 'supervisor.step':
    case 'reasoning.delta':
      return null
    default:
      return null
  }
}

export function* expandEmbedAdapted(
  adapted: ConversationEventEnvelope | ConversationEventEnvelope[] | null,
): Generator<ConversationEventEnvelope> {
  if (!adapted) return
  if (Array.isArray(adapted)) {
    for (const item of adapted) yield item
    return
  }
  yield adapted
}
