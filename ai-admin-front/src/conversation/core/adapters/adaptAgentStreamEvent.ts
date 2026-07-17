import type { ConversationEventEnvelope } from '../conversationEvents'
import { createEvent } from '../conversationEvents'

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

function asString(value: unknown): string | undefined {
  if (value == null) return undefined
  const text = String(value)
  return text && text !== 'null' ? text : undefined
}

function nestedRecord(value: unknown): Record<string, unknown> | null {
  return asRecord(value)
}

/**
 * 从 Agent 完成/失败载荷提取公开 metadata，保留 success/code/traceId，避免重复嵌套。
 */
export function extractAgentPublicMetadata(
  record: Record<string, unknown> | null,
): Record<string, unknown> {
  if (!record) return {}
  const inner = nestedRecord(record.metadata)
  const publicMeta: Record<string, unknown> = inner ? { ...inner } : {}

  // 顶层业务字段优先，防止解包丢失
  if ('success' in record) publicMeta.success = record.success
  const code = asString(record.code) ?? asString(publicMeta.code)
  if (code) publicMeta.code = code
  const traceId = asString(record.traceId)
    ?? asString(publicMeta.traceId)
    ?? asString(nestedRecord(publicMeta.model)?.traceId)
  if (traceId) publicMeta.traceId = traceId
  const sessionId = asString(record.sessionId) ?? asString(publicMeta.sessionId)
  if (sessionId) publicMeta.sessionId = sessionId
  const finishReason = asString(record.finishReason)
    ?? asString(publicMeta.finishReason)
    ?? asString(nestedRecord(publicMeta.model)?.finishReason)
  if (finishReason) publicMeta.finishReason = finishReason
  const modelInstanceId = asString(record.modelInstanceId)
    ?? asString(publicMeta.modelInstanceId)
    ?? asString(nestedRecord(publicMeta.model)?.modelInstanceId)
  if (modelInstanceId) publicMeta.modelInstanceId = modelInstanceId

  if (record.usage && typeof record.usage === 'object') {
    publicMeta.usage = record.usage
  }
  if (record.eventCounts && typeof record.eventCounts === 'object') {
    publicMeta.eventCounts = record.eventCounts
  }

  // 去掉再次嵌套的 completion 信封
  if (publicMeta.metadata && typeof publicMeta.metadata === 'object') {
    delete publicMeta.metadata
  }
  return publicMeta
}

function isExplicitFailure(record: Record<string, unknown> | null): boolean {
  if (!record) return false
  if (record.success === false) return true
  if (String(record.success).toLowerCase() === 'false') return true
  return false
}

function toFailedEvent(
  record: Record<string, unknown> | null,
  data: unknown,
  extras: { sessionId?: string; turnId?: string },
  fallbackMessage: string,
): ConversationEventEnvelope {
  const publicMeta = extractAgentPublicMetadata(record)
  const message = asString(record?.message)
    || asString(record?.answer)
    || asString(publicMeta.message)
    || fallbackMessage
  const sessionId = extras.sessionId
    || asString(record?.sessionId)
    || asString(publicMeta.sessionId)
  const traceId = asString(publicMeta.traceId) || asString(record?.traceId)
  return createEvent('turn.failed', {
    message,
    error: message,
    code: publicMeta.code,
    sessionId,
    traceId,
    result: data,
    metadata: {
      ...publicMeta,
      success: false,
      ...(traceId ? { traceId } : {}),
      ...(sessionId ? { sessionId } : {}),
    },
  }, { sessionId, turnId: extras.turnId, traceId })
}

/**
 * 将 Runtime Agent SSE 原始事件映射为公共 / 调试事件。
 */
export function adaptAgentStreamEvent(
  eventName: string,
  data: unknown,
  extras: { sessionId?: string; turnId?: string } = {},
): ConversationEventEnvelope | ConversationEventEnvelope[] | null {
  const record = asRecord(data)
  const sessionId = extras.sessionId
    || (record?.sessionId ? String(record.sessionId) : undefined)

  switch (eventName) {
    case 'execution.started':
      return createEvent('turn.started', record || {}, { sessionId, turnId: extras.turnId })

    case 'supervisor.step':
      return createEvent('debug.supervisor.step', data, { sessionId, turnId: extras.turnId })

    case 'message.delta': {
      const text = textFromDelta(data)
      if (!text) return null
      return createEvent('message.delta', { text }, { sessionId, turnId: extras.turnId })
    }

    case 'ui.requested':
      return createEvent('ui.requested', {
        uiRequest: record?.uiRequest ?? data,
      }, { sessionId, turnId: extras.turnId })

    case 'execution.completed': {
      // 兼容旧后端：success=false 仍发 completed → 必须转为 turn.failed
      if (isExplicitFailure(record)) {
        return toFailedEvent(record, data, extras, 'Agent 执行失败')
      }
      const answer = record?.answer != null ? String(record.answer) : undefined
      const uiRequest = record?.uiRequest
      const publicMeta = extractAgentPublicMetadata(record)
      const traceId = asString(publicMeta.traceId) || asString(record?.traceId)
      if (uiRequest) {
        return [
          createEvent('ui.requested', { uiRequest }, { sessionId, turnId: extras.turnId, traceId }),
          createEvent('turn.waiting', {
            answer,
            uiRequest,
            sessionId,
            traceId,
            result: data,
            metadata: publicMeta,
          }, { sessionId, turnId: extras.turnId, traceId }),
        ]
      }
      return createEvent('turn.completed', {
        answer,
        sessionId,
        traceId,
        result: data,
        metadata: publicMeta,
      }, { sessionId, turnId: extras.turnId, traceId })
    }

    case 'execution.error':
      return toFailedEvent(record, data, extras, 'Agent 执行失败')

    default:
      return null
  }
}

export function* expandAgentAdapted(
  adapted: ConversationEventEnvelope | ConversationEventEnvelope[] | null,
): Generator<ConversationEventEnvelope> {
  if (!adapted) return
  if (Array.isArray(adapted)) {
    for (const item of adapted) yield item
    return
  }
  yield adapted
}
