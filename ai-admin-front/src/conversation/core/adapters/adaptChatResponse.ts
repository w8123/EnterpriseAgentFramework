import type { ConversationEventEnvelope } from '../conversationEvents'
import { createEvent } from '../conversationEvents'
import type { ConversationSnapshot, ConversationMessage } from '../conversationTypes'
import { createId, createEmptySnapshot, nowIso } from '../conversationTypes'
import { normalizeUiRequest } from '../normalizeUiRequest'

/**
 * 将旧 ChatResponse（answer + uiRequest）转为 ConversationSnapshot。
 */
export function adaptChatResponseToSnapshot(
  response: {
    answer?: string
    sessionId?: string
    uiRequest?: unknown
    metadata?: Record<string, unknown>
    toolCalls?: string[]
    reasoningSteps?: string[]
  },
  options: { userMessage?: string; sessionId?: string } = {},
): ConversationSnapshot {
  const snapshot = createEmptySnapshot(response.sessionId || options.sessionId)
  if (options.userMessage) {
    snapshot.messages.push({
      id: createId('msg'),
      role: 'user',
      status: 'completed',
      blocks: [{ id: createId('blk'), type: 'text', text: options.userMessage, status: 'completed' }],
      createdAt: nowIso(),
    })
  }

  const blocks: ConversationMessage['blocks'] = []
  if (response.answer) {
    blocks.push({
      id: createId('blk'),
      type: 'text',
      text: response.answer,
      status: 'completed',
    })
  }
  const ui = normalizeUiRequest(response.uiRequest)
  if (ui) {
    blocks.push({
      id: createId('blk'),
      type: 'interaction',
      request: ui,
      state: 'waiting',
    })
  }

  if (blocks.length) {
    snapshot.messages.push({
      id: createId('msg'),
      role: 'assistant',
      status: 'completed',
      blocks,
      createdAt: nowIso(),
      metadata: {
        ...(response.metadata || {}),
        ...(response.toolCalls ? { toolCalls: response.toolCalls } : {}),
        ...(response.reasoningSteps ? { reasoningSteps: response.reasoningSteps } : {}),
      },
    })
  }

  snapshot.turnStatus = ui ? 'waiting' : 'completed'
  snapshot.metadata = response.metadata
  return snapshot
}

export function* adaptChatResponseToEvents(
  response: {
    answer?: string
    sessionId?: string
    uiRequest?: unknown
    metadata?: Record<string, unknown>
  },
): Generator<ConversationEventEnvelope> {
  if (response.sessionId) {
    yield createEvent('session.created', { sessionId: response.sessionId }, { sessionId: response.sessionId })
  }
  yield createEvent('turn.started', {})
  if (response.answer) {
    yield createEvent('message.delta', { text: response.answer }, { sessionId: response.sessionId })
  }
  if (response.uiRequest) {
    yield createEvent('ui.requested', { uiRequest: response.uiRequest }, { sessionId: response.sessionId })
    yield createEvent('turn.waiting', {
      answer: response.answer,
      uiRequest: response.uiRequest,
      sessionId: response.sessionId,
      metadata: response.metadata,
    }, { sessionId: response.sessionId })
  } else {
    yield createEvent('turn.completed', {
      answer: response.answer,
      sessionId: response.sessionId,
      metadata: response.metadata,
    }, { sessionId: response.sessionId })
  }
}
