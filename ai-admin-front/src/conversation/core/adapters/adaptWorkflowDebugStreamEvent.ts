import type { ConversationEventEnvelope } from '../conversationEvents'
import { createEvent } from '../conversationEvents'

function asRecord(value: unknown): Record<string, unknown> | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  return value as Record<string, unknown>
}

/**
 * 将 Workflow debug session SSE 事件映射为公共 / 调试事件。
 */
export function adaptWorkflowDebugStreamEvent(
  eventName: string,
  data: unknown,
  extras: { sessionId?: string; traceId?: string } = {},
): ConversationEventEnvelope | ConversationEventEnvelope[] | null {
  const record = asRecord(data)
  const sessionId = extras.sessionId
    || (record?.sessionId ? String(record.sessionId) : undefined)
  const traceId = extras.traceId
    || (record?.traceId ? String(record.traceId) : undefined)

  switch (eventName) {
    case 'session.created':
      return record?.sessionId
        ? createEvent('session.created', record, { sessionId: String(record.sessionId), traceId })
        : null

    case 'runtime.execution.v1':
      return createEvent('debug.workflow.runtime.event', record || {}, {
        sessionId,
        traceId,
        sequence: typeof record?.sequence === 'number' ? record.sequence : undefined,
      })
    case 'node.started':
      return createEvent('debug.workflow.node.started', record || {}, { sessionId, traceId })
    case 'node.waiting':
      return createEvent('debug.workflow.node.waiting', record || {}, { sessionId, traceId })
    case 'node.failed':
      return createEvent('debug.workflow.node.failed', record || {}, { sessionId, traceId })
    case 'node.completed':
      return createEvent('debug.workflow.node.completed', record || {}, { sessionId, traceId })
    case 'node.output.delta':
    case 'debug.workflow.node.delta':
      // 节点轨迹专用。公共 message.delta 只由后端显式 message.delta 事件提供，
      // 禁止根据 publicUserOutput 再次推导，否则会与 Runtime live sink 重复追加。
      return createEvent('debug.workflow.node.delta', record || {}, { sessionId, traceId })

    case 'message.delta': {
      const text = record?.text != null
        ? String(record.text)
        : (typeof data === 'string' ? data : '')
      return text ? createEvent('message.delta', { text }, { sessionId, traceId }) : null
    }

    case 'ui.requested':
      return createEvent('ui.requested', {
        uiRequest: record?.uiRequest ?? data,
      }, { sessionId, traceId })

    case 'turn.waiting': {
      const uiRequest = record?.uiRequest
      const answer = record?.answer != null ? String(record.answer) : undefined
      const events: ConversationEventEnvelope[] = []
      if (uiRequest) {
        events.push(createEvent('ui.requested', { uiRequest }, { sessionId, traceId }))
      }
      events.push(createEvent('turn.waiting', {
        answer,
        uiRequest,
        sessionId,
        traceId,
        result: data,
      }, { sessionId, traceId }))
      return events
    }

    case 'turn.completed':
      return createEvent('turn.completed', {
        answer: record?.answer != null ? String(record.answer) : undefined,
        sessionId,
        traceId,
        result: data,
      }, { sessionId, traceId })

    case 'turn.failed':
      return createEvent('turn.failed', {
        message: record?.message != null
          ? String(record.message)
          : (record?.answer != null ? String(record.answer) : 'Workflow 调试失败'),
        error: record?.message ?? record?.answer,
        sessionId,
        status: record?.status,
      }, { sessionId, traceId })

    case 'turn.cancelled':
      return createEvent('turn.cancelled', {
        sessionId,
        status: record?.status != null ? String(record.status) : 'CANCELLED',
        answer: record?.answer != null ? String(record.answer) : undefined,
        message: record?.message != null ? String(record.message) : undefined,
      }, { sessionId, traceId })

    default:
      return null
  }
}

export function expandWorkflowDebugAdapted(
  adapted: ConversationEventEnvelope | ConversationEventEnvelope[] | null,
): ConversationEventEnvelope[] {
  if (!adapted) return []
  return Array.isArray(adapted) ? adapted : [adapted]
}
