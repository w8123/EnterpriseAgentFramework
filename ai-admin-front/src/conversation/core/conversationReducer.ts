import type {
  ConversationBlock,
  ConversationInteractionBlock,
  ConversationMessage,
  ConversationSnapshot,
  ConversationTextBlock,
  ConversationTurnStatus,
  InteractionRenderState,
  UiRequestV1,
} from './conversationTypes'
import { createEmptySnapshot, createId, nowIso } from './conversationTypes'
import {
  isBlockingUiRequest,
  isCardOnlyUiRequest,
  isTextOnlyUiRequest,
  normalizeUiRequest,
} from './normalizeUiRequest'
import type { ConversationEventEnvelope } from './conversationEvents'

export type ConversationAction =
  | { type: 'reset'; sessionId?: string }
  | { type: 'restore'; snapshot: ConversationSnapshot }
  | { type: 'set_session'; sessionId?: string }
  | { type: 'set_error'; error: string | null }
  | { type: 'append_user_message'; text: string; id?: string; metadata?: Record<string, unknown> }
  /** 本地 UI 占位：fetch 前立即创建 pending assistant，不对应服务端 message.started */
  | { type: 'begin_assistant_placeholder'; turnId?: string }
  | { type: 'start_assistant_message'; id?: string; metadata?: Record<string, unknown> }
  | { type: 'append_text_delta'; text: string; messageId?: string }
  | { type: 'add_interaction'; request: UiRequestV1 | unknown; messageId?: string; state?: InteractionRenderState }
  | { type: 'update_interaction_state'; interactionId: string; state: InteractionRenderState; result?: { action: string; values?: Record<string, unknown> }; errorMessage?: string }
  | { type: 'add_notice'; text: string; level?: 'info' | 'warning' | 'success'; messageId?: string }
  | { type: 'add_error_block'; text: string; retryable?: boolean; messageId?: string }
  | { type: 'add_page_action'; requestId: string; actionKey: string; title?: string; args?: Record<string, unknown>; messageId?: string }
  | { type: 'set_turn_status'; status: ConversationTurnStatus }
  | { type: 'complete_turn'; answer?: string; uiRequest?: unknown; sessionId?: string; metadata?: Record<string, unknown>; completion?: { intentType?: unknown; toolCalls?: unknown; result?: unknown } }
  | { type: 'fail_turn'; message: string; metadata?: Record<string, unknown> }
  | { type: 'cancel_turn' }
  | { type: 'apply_event'; event: ConversationEventEnvelope }

function cloneSnapshot(state: ConversationSnapshot): ConversationSnapshot {
  return {
    ...state,
    messages: state.messages.map((m) => ({
      ...m,
      blocks: m.blocks.map((b) => ({ ...b })),
      metadata: m.metadata ? { ...m.metadata } : undefined,
      completion: m.completion ? { ...m.completion } : undefined,
    })),
    metadata: state.metadata ? { ...state.metadata } : undefined,
  }
}

function matchesMessageId(message: ConversationMessage, messageId: string): boolean {
  return message.id === messageId || message.transportMessageId === messageId
}

function findActiveAssistant(state: ConversationSnapshot): ConversationMessage | undefined {
  for (let i = state.messages.length - 1; i >= 0; i -= 1) {
    const msg = state.messages[i]
    if (msg.role === 'assistant' && (msg.status === 'streaming' || msg.status === 'pending')) {
      return msg
    }
  }
  return undefined
}

function findMessage(state: ConversationSnapshot, messageId?: string): ConversationMessage | undefined {
  if (messageId) {
    const byId = state.messages.find((m) => matchesMessageId(m, messageId))
    if (byId && (byId.status === 'pending' || byId.status === 'streaming')) {
      return byId
    }
  }
  return findActiveAssistant(state)
}

function findLastAssistant(state: ConversationSnapshot): ConversationMessage | undefined {
  for (let i = state.messages.length - 1; i >= 0; i -= 1) {
    if (state.messages[i].role === 'assistant') return state.messages[i]
  }
  return undefined
}

/**
 * 确保当前 turn 只有一条 assistant 响应消息。
 * - 优先按 transportMessageId / id 命中活跃消息
 * - 否则绑定当前 pending/streaming placeholder
 * - 不得接管上一轮已 completed/failed/cancelled 的消息（除非 reuseCompleted 用于同轮终态去重）
 */
function ensureAssistantMessage(
  state: ConversationSnapshot,
  messageId?: string,
  metadata?: Record<string, unknown>,
  options: { reuseCompleted?: boolean } = {},
): ConversationMessage {
  if (messageId) {
    const byId = state.messages.find((m) => matchesMessageId(m, messageId))
    if (byId) {
      const active = byId.status === 'pending' || byId.status === 'streaming'
      if (active || options.reuseCompleted) {
        if (metadata) {
          byId.metadata = { ...(byId.metadata || {}), ...metadata }
        }
        return byId
      }
    }
  }

  const active = findActiveAssistant(state)
  if (active) {
    if (messageId && active.transportMessageId !== messageId) {
      active.transportMessageId = messageId
    }
    if (metadata) {
      active.metadata = { ...(active.metadata || {}), ...metadata }
    }
    return active
  }

  if (options.reuseCompleted) {
    const last = findLastAssistant(state)
    if (last) {
      if (metadata) {
        last.metadata = { ...(last.metadata || {}), ...metadata }
      }
      return last
    }
  }

  const message: ConversationMessage = {
    id: createId('msg'),
    role: 'assistant',
    status: 'streaming',
    blocks: [],
    createdAt: nowIso(),
    metadata,
    transportMessageId: messageId,
  }
  state.messages.push(message)
  return message
}

function findInteractionBlock(message: ConversationMessage, interactionId: string): ConversationInteractionBlock | undefined {
  return message.blocks.find(
    (b): b is ConversationInteractionBlock => b.type === 'interaction' && b.request.interactionId === interactionId,
  )
}

function appendTextDelta(message: ConversationMessage, text: string) {
  if (!text || message.blocks.some((block) => block.type === 'interaction' && isCardOnlyUiRequest(block.request))) return
  const last = message.blocks[message.blocks.length - 1]
  if (last && last.type === 'text' && last.status !== 'completed') {
    last.text += text
    last.status = 'streaming'
  } else {
    const block: ConversationTextBlock = {
      id: createId('blk'),
      type: 'text',
      text,
      status: 'streaming',
    }
    message.blocks.push(block)
  }
  message.status = 'streaming'
  message.updatedAt = nowIso()
}

function addInteraction(message: ConversationMessage, raw: unknown, state: InteractionRenderState = 'waiting') {
  const request = normalizeUiRequest(raw)
  if (!request) return
  if (isTextOnlyUiRequest(request)) {
    message.blocks = message.blocks.filter(
      (block) => block.type !== 'interaction' || block.request.interactionId !== request.interactionId,
    )
    message.updatedAt = nowIso()
    return
  }
  if (isCardOnlyUiRequest(request)) {
    message.blocks = message.blocks.filter((block) => block.type !== 'text')
  }
  const existing = findInteractionBlock(message, request.interactionId)
  if (existing) {
    existing.request = request
    if (existing.state === 'waiting' || existing.state === 'failed') {
      existing.state = state
    }
    message.updatedAt = nowIso()
    return
  }
  message.blocks.push({
    id: createId('blk'),
    type: 'interaction',
    request,
    state,
  })
  message.updatedAt = nowIso()
}

function finalizeTextBlocks(message: ConversationMessage) {
  for (const block of message.blocks) {
    if (block.type === 'text' && block.status === 'streaming') {
      block.status = 'completed'
    }
  }
}

export function conversationReducer(
  state: ConversationSnapshot,
  action: ConversationAction,
): ConversationSnapshot {
  switch (action.type) {
    case 'reset':
      return createEmptySnapshot(action.sessionId)

    case 'restore':
      return cloneSnapshot(action.snapshot)

    case 'set_session': {
      const next = cloneSnapshot(state)
      next.sessionId = action.sessionId
      return next
    }

    case 'set_error': {
      const next = cloneSnapshot(state)
      next.error = action.error
      return next
    }

    case 'append_user_message': {
      const next = cloneSnapshot(state)
      next.messages.push({
        id: action.id || createId('msg'),
        role: 'user',
        status: 'completed',
        blocks: [{ id: createId('blk'), type: 'text', text: action.text, status: 'completed' }],
        createdAt: nowIso(),
        metadata: action.metadata,
      })
      next.error = null
      return next
    }

    case 'begin_assistant_placeholder': {
      const next = cloneSnapshot(state)
      const active = findActiveAssistant(next)
      if (active && (!action.turnId || !active.turnId || active.turnId === action.turnId)) {
        if (action.turnId) {
          active.turnId = action.turnId
          next.turnId = action.turnId
        }
        return next
      }
      next.messages.push({
        id: createId('msg'),
        role: 'assistant',
        status: 'pending',
        blocks: [],
        createdAt: nowIso(),
        turnId: action.turnId,
        localPlaceholder: true,
      })
      if (action.turnId) next.turnId = action.turnId
      next.error = null
      return next
    }

    case 'start_assistant_message': {
      const next = cloneSnapshot(state)
      const message = ensureAssistantMessage(next, action.id, action.metadata)
      if (message.status === 'pending') {
        message.status = 'streaming'
      }
      message.updatedAt = nowIso()
      next.turnStatus = next.turnStatus === 'waiting' ? 'waiting' : 'streaming'
      next.error = null
      return next
    }

    case 'append_text_delta': {
      const next = cloneSnapshot(state)
      const message = ensureAssistantMessage(next, action.messageId)
      appendTextDelta(message, action.text)
      if (next.turnStatus === 'idle' || next.turnStatus === 'sending') {
        next.turnStatus = 'streaming'
      }
      return next
    }

    case 'add_interaction': {
      const next = cloneSnapshot(state)
      const message = ensureAssistantMessage(next, action.messageId)
      addInteraction(message, action.request, action.state || 'waiting')
      // 不在此将 turnStatus 置为 waiting：须等 turn.waiting / complete_turn，
      // 否则 Host settlePending 会在后续 message.completed 到达前过早 resolve。
      if (next.turnStatus === 'idle' || next.turnStatus === 'sending') {
        next.turnStatus = 'streaming'
      }
      return next
    }

    case 'update_interaction_state': {
      const next = cloneSnapshot(state)
      for (const message of next.messages) {
        const block = findInteractionBlock(message, action.interactionId)
        if (block) {
          block.state = action.state
          if (action.result) block.result = action.result
          if (action.errorMessage !== undefined) block.errorMessage = action.errorMessage
          message.updatedAt = nowIso()
          break
        }
      }
      return next
    }

    case 'add_notice': {
      const next = cloneSnapshot(state)
      const message = ensureAssistantMessage(next, action.messageId)
      message.blocks.push({
        id: createId('blk'),
        type: 'notice',
        level: action.level || 'info',
        text: action.text,
      })
      return next
    }

    case 'add_error_block': {
      const next = cloneSnapshot(state)
      const message = ensureAssistantMessage(next, action.messageId)
      message.blocks.push({
        id: createId('blk'),
        type: 'error',
        text: action.text,
        retryable: action.retryable,
      })
      message.status = 'failed'
      return next
    }

    case 'add_page_action': {
      const next = cloneSnapshot(state)
      const message = ensureAssistantMessage(next, action.messageId)
      const exists = message.blocks.some(
        (b) => b.type === 'page_action' && b.requestId === action.requestId,
      )
      if (exists || !action.requestId) {
        return next
      }
      message.blocks.push({
        id: createId('blk'),
        type: 'page_action',
        requestId: action.requestId,
        actionKey: action.actionKey,
        title: action.title,
        args: action.args,
        status: 'pending',
      })
      return next
    }

    case 'set_turn_status': {
      const next = cloneSnapshot(state)
      next.turnStatus = action.status
      return next
    }

    case 'complete_turn': {
      const next = cloneSnapshot(state)
      if (action.sessionId) next.sessionId = action.sessionId
      // 优先复用本轮 placeholder；仅当无活跃消息时复用最后一条（同轮终态去重）
      const message = ensureAssistantMessage(next, undefined, undefined, { reuseCompleted: true })
      if (action.answer) {
        const hasText = message.blocks.some((b) => b.type === 'text' && b.text)
        if (!hasText) {
          appendTextDelta(message, action.answer)
        }
      }
      if (action.uiRequest) {
        addInteraction(message, action.uiRequest, isBlockingUiRequest(action.uiRequest) ? 'waiting' : 'resolved')
      }
      finalizeTextBlocks(message)
      const waiting = message.blocks.some(
        (b): b is ConversationInteractionBlock => b.type === 'interaction' && b.state === 'waiting',
      )
      message.status = 'completed'
      message.updatedAt = nowIso()
      if (action.metadata) {
        // 仅合并公开 metadata；不得把 completion 信封整包写入
        message.metadata = { ...(message.metadata || {}), ...action.metadata }
        next.metadata = { ...(next.metadata || {}), ...action.metadata }
      }
      if (action.completion) {
        message.completion = {
          ...(message.completion || {}),
          ...action.completion,
        }
      }
      next.turnStatus = waiting ? 'waiting' : 'completed'
      next.error = null
      return next
    }

    case 'fail_turn': {
      const next = cloneSnapshot(state)
      const message = findMessage(next) || ensureAssistantMessage(next)
      finalizeTextBlocks(message)
      message.status = 'failed'
      message.blocks.push({
        id: createId('blk'),
        type: 'error',
        text: action.message,
        retryable: true,
      } satisfies ConversationBlock)
      if (action.metadata) {
        message.metadata = { ...(message.metadata || {}), ...action.metadata }
        next.metadata = { ...(next.metadata || {}), ...action.metadata }
      }
      message.updatedAt = nowIso()
      next.turnStatus = 'failed'
      next.error = action.message
      return next
    }

    case 'cancel_turn': {
      const next = cloneSnapshot(state)
      const message = findMessage(next)
      if (message) {
        finalizeTextBlocks(message)
        message.status = 'cancelled'
        message.updatedAt = nowIso()
      }
      next.turnStatus = 'cancelled'
      next.error = null
      return next
    }

    case 'apply_event':
      return applyEvent(state, action.event)

    default:
      return state
  }
}

function applyEvent(state: ConversationSnapshot, event: ConversationEventEnvelope): ConversationSnapshot {
  const type = event.type
  const data = (event.data && typeof event.data === 'object' ? event.data : {}) as Record<string, unknown>
  const withTurn = event.turnId && state.turnId !== event.turnId
    ? { ...state, turnId: event.turnId }
    : state

  switch (type) {
    case 'session.created':
    case 'session.restored': {
      if (type === 'session.restored' && event.data && typeof event.data === 'object' && 'messages' in (event.data as object)) {
        return conversationReducer(withTurn, { type: 'restore', snapshot: event.data as ConversationSnapshot })
      }
      return conversationReducer(withTurn, { type: 'set_session', sessionId: event.sessionId || asString(data.sessionId) })
    }
    case 'turn.started':
      return conversationReducer(withTurn, { type: 'set_turn_status', status: 'sending' })
    case 'turn.progress': {
      const next = cloneSnapshot(withTurn)
      const message = ensureAssistantMessage(next, asString(data.messageId))
      const progressMessage = typeof data.message === 'string' ? data.message.trim() : ''
      message.metadata = {
        ...(message.metadata || {}),
        publicProgress: {
          phase: asString(data.phase),
          state: asString(data.state),
          ...(progressMessage ? { message: progressMessage } : {}),
        },
      }
      if (message.status === 'pending') message.status = 'streaming'
      message.updatedAt = nowIso()
      if (next.turnStatus === 'idle' || next.turnStatus === 'sending') {
        next.turnStatus = 'streaming'
      }
      return next
    }
    case 'message.started':
      return conversationReducer(withTurn, {
        type: 'start_assistant_message',
        id: asString(data.messageId),
      })
    case 'message.delta': {
      const text = typeof data.text === 'string'
        ? data.text
        : typeof event.data === 'string'
          ? event.data
          : ''
      return conversationReducer(withTurn, {
        type: 'append_text_delta',
        text,
        messageId: asString(data.messageId),
      })
    }
    case 'ui.requested':
      return conversationReducer(withTurn, {
        type: 'add_interaction',
        request: data.uiRequest ?? event.data,
        state: isBlockingUiRequest(data.uiRequest ?? event.data) ? 'waiting' : 'resolved',
        messageId: asString(data.messageId),
      })
    case 'page.action.requested':
      return conversationReducer(withTurn, {
        type: 'add_page_action',
        requestId: String(data.requestId || ''),
        actionKey: String(data.actionKey || ''),
        title: asString(data.title),
        args: (data.args as Record<string, unknown>) || undefined,
        messageId: asString(data.messageId),
      })
    case 'turn.waiting':
      return conversationReducer(withTurn, {
        type: 'complete_turn',
        answer: asString(data.answer),
        uiRequest: data.uiRequest,
        sessionId: event.sessionId || asString(data.sessionId),
        metadata: normalizePublicMetadata(data.metadata, data.result),
        completion: {
          intentType: data.intentType,
          toolCalls: data.toolCalls,
          result: data.result,
        },
      })
    case 'turn.completed':
      return conversationReducer(withTurn, {
        type: 'complete_turn',
        answer: asString(data.answer),
        uiRequest: data.uiRequest,
        sessionId: event.sessionId || asString(data.sessionId),
        metadata: {
          ...normalizePublicMetadata(data.metadata, data.result),
          ...(event.traceId ? { traceId: event.traceId } : {}),
          ...(data.traceId ? { traceId: data.traceId } : {}),
        },
        completion: {
          intentType: data.intentType,
          toolCalls: data.toolCalls,
          result: data.result,
        },
      })
    case 'turn.cancelled':
      return conversationReducer(withTurn, { type: 'cancel_turn' })
    case 'turn.failed': {
      const failedMeta = normalizePublicMetadata(data.metadata, data.result)
      const traceId = event.traceId || asString(data.traceId) || asString(failedMeta.traceId)
      const code = asString(data.code) || asString(failedMeta.code)
      return conversationReducer(withTurn, {
        type: 'fail_turn',
        message: asString(data.message) || asString(data.error) || '对话失败',
        metadata: {
          ...failedMeta,
          success: false,
          ...(code ? { code } : {}),
          ...(traceId ? { traceId } : {}),
          ...(event.sessionId || asString(data.sessionId)
            ? { sessionId: event.sessionId || asString(data.sessionId) }
            : {}),
        },
      })
    }
    default:
      return withTurn
  }
}

function asString(value: unknown): string | undefined {
  if (value == null) return undefined
  const text = String(value)
  return text ? text : undefined
}

/**
 * 确保写入 snapshot 的是后端内层 metadata，而不是整份 completion。
 */
function normalizePublicMetadata(
  metadata: unknown,
  result: unknown,
): Record<string, unknown> {
  if (metadata && typeof metadata === 'object' && !Array.isArray(metadata)) {
    const record = metadata as Record<string, unknown>
    // 误把整份 completion 当 metadata 时解包，但保留顶层 success/code/traceId
    if (
      record.metadata
      && typeof record.metadata === 'object'
      && !Array.isArray(record.metadata)
      && ('answer' in record || 'uiRequest' in record || 'sessionId' in record)
    ) {
      const inner = { ...(record.metadata as Record<string, unknown>) }
      if ('success' in record) inner.success = record.success
      if (record.code != null) inner.code = record.code
      if (record.traceId != null) inner.traceId = record.traceId
      return inner
    }
    if (!('answer' in record) && !('uiRequest' in record)) {
      return { ...record }
    }
    // 仅有 answer 的信封：保留顶层诊断字段
    const preserved: Record<string, unknown> = {}
    if ('success' in record) preserved.success = record.success
    if (record.code != null) preserved.code = record.code
    if (record.traceId != null) preserved.traceId = record.traceId
    if (result && typeof result === 'object' && !Array.isArray(result)) {
      const nested = (result as Record<string, unknown>).metadata
      if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
        return { ...(nested as Record<string, unknown>), ...preserved }
      }
    }
    return preserved
  }
  if (result && typeof result === 'object' && !Array.isArray(result)) {
    const resultRecord = result as Record<string, unknown>
    const nested = resultRecord.metadata
    const preserved: Record<string, unknown> = {}
    if ('success' in resultRecord) preserved.success = resultRecord.success
    if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
      return { ...(nested as Record<string, unknown>), ...preserved }
    }
    return preserved
  }
  return {}
}

export function initialConversationState(sessionId?: string): ConversationSnapshot {
  return createEmptySnapshot(sessionId)
}
