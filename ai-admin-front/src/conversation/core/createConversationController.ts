import type { ConversationEventEnvelope } from './conversationEvents'
import { createEvent, isDebugEventType } from './conversationEvents'
import { conversationReducer, initialConversationState, type ConversationAction } from './conversationReducer'
import type {
  ConversationTransport,
  ConversationTurnInput,
} from '../transports/transportTypes'
import type { ConversationSnapshot as Snapshot } from './conversationTypes'
import { createId } from './conversationTypes'
import { isAbortError } from './parseSseStream'

export interface ConversationControllerOptions {
  transport: ConversationTransport
  onDebugEvent?: (event: ConversationEventEnvelope) => void
  /** 公共对话事件出口（不含 supervisor.step / reasoning 等内部事件） */
  onPublicEvent?: (event: ConversationEventEnvelope) => void
  onChange?: (state: Snapshot) => void
  initialSessionId?: string
}

export interface ConversationController {
  getState(): Snapshot
  subscribe(listener: (state: Snapshot) => void): () => void
  send(input: string | ConversationTurnInput): Promise<void>
  submitInteraction(interactionId: string, action: string, values?: Record<string, unknown>): Promise<void>
  cancel(): Promise<void>
  clearSession(): Promise<void>
  restore(): Promise<Snapshot | null>
  dispose(): void
}

const PUBLIC_EVENT_TYPES = new Set([
  'message.delta',
  'ui.requested',
  'page.action.requested',
  'turn.started',
  'turn.progress',
  'turn.waiting',
  'turn.completed',
  'turn.failed',
  'turn.cancelled',
  'interaction.submitted',
  'interaction.cancelled',
])

export function createConversationController(options: ConversationControllerOptions): ConversationController {
  const { transport, onDebugEvent, onPublicEvent } = options
  let state: Snapshot = initialConversationState(options.initialSessionId)
  const listeners = new Set<(state: Snapshot) => void>()
  const submittingInteractions = new Set<string>()
  let disposed = false
  let activeAbort: AbortController | null = null
  let turnGeneration = 0
  let currentTurnId: string | undefined
  /** 仅对“同一 turn 内重复终态 / 同 interaction 重复 ui”去重；message.delta 不去重 */
  const emittedTerminalKeys = new Set<string>()
  const emittedUiKeys = new Set<string>()
  const emittedPageActionKeys = new Set<string>()

  function emit() {
    options.onChange?.(state)
    for (const listener of listeners) listener(state)
  }

  function dispatch(action: ConversationAction) {
    state = conversationReducer(state, action)
    emit()
  }

  function withTurnId(event: ConversationEventEnvelope): ConversationEventEnvelope {
    if (event.turnId || !currentTurnId) return event
    return { ...event, turnId: currentTurnId }
  }

  function shouldEmitPublic(event: ConversationEventEnvelope): boolean {
    if (!PUBLIC_EVENT_TYPES.has(event.type)) return false
    const data = (event.data && typeof event.data === 'object')
      ? event.data as Record<string, unknown>
      : {}
    const turnId = event.turnId || currentTurnId || ''

    if (event.type === 'message.delta') {
      // 相同文本的连续 delta 合法，绝不按 text 去重
      return true
    }
    if (event.type === 'page.action.requested') {
      const requestId = String(data.requestId || '')
      if (!requestId) return true
      const key = `page.action.requested:${requestId}`
      if (emittedPageActionKeys.has(key)) return false
      emittedPageActionKeys.add(key)
      return true
    }
    if (event.type === 'ui.requested') {
      const ui = data.uiRequest as { interactionId?: string } | undefined
      const key = `ui.requested:${turnId}:${ui?.interactionId || JSON.stringify(data.uiRequest || '')}`
      if (emittedUiKeys.has(key)) return false
      emittedUiKeys.add(key)
      return true
    }
    if (
      event.type === 'turn.completed'
      || event.type === 'turn.waiting'
      || event.type === 'turn.failed'
      || event.type === 'turn.cancelled'
    ) {
      // sessionId 可能在 envelope / data / state 任一处；须取稳定值，避免同 turn 第二次终态因 state 已写入而换 key
      const sessionId = event.sessionId
        || (data.sessionId != null ? String(data.sessionId) : '')
        || state.sessionId
        || ''
      const key = `${event.type}:${sessionId}:${turnId}`
      if (emittedTerminalKeys.has(key)) return false
      emittedTerminalKeys.add(key)
      return true
    }
    if (event.type === 'interaction.submitted' || event.type === 'interaction.cancelled') {
      const key = `${event.type}:${turnId}:${String(data.interactionId || '')}:${String(data.action || '')}`
      if (emittedTerminalKeys.has(key)) return false
      emittedTerminalKeys.add(key)
      return true
    }
    return true
  }

  function emitPublic(event: ConversationEventEnvelope) {
    const enriched = withTurnId(event)
    if (!shouldEmitPublic(enriched)) return
    onPublicEvent?.(enriched)
  }

  function applyEnvelope(event: ConversationEventEnvelope) {
    const enriched = withTurnId(event)
    if (isDebugEventType(enriched.type)) {
      onDebugEvent?.(enriched)
      return
    }
    // 同 requestId 的 page.action：公开与 UI block 均只保留一次
    if (enriched.type === 'page.action.requested') {
      const data = (enriched.data && typeof enriched.data === 'object')
        ? enriched.data as Record<string, unknown>
        : {}
      const requestId = String(data.requestId || '')
      if (requestId) {
        const key = `page.action.requested:${requestId}`
        if (emittedPageActionKeys.has(key)) {
          return
        }
      }
    }
    emitPublic(enriched)
    dispatch({ type: 'apply_event', event: enriched })
  }

  async function consume(events: AsyncIterable<ConversationEventEnvelope>, generation: number) {
    for await (const event of events) {
      if (disposed || generation !== turnGeneration) break
      applyEnvelope(event)
    }
  }

  async function runTurn(input: ConversationTurnInput, mode: 'start' | 'resume') {
    if (disposed) return
    const generation = ++turnGeneration
    currentTurnId = createId('turn')
    // 交互取消提交不得被“停止生成”的 abort 立即打断
    if (mode === 'start' || input.uiSubmit?.action !== 'cancel') {
      activeAbort?.abort()
      activeAbort = new AbortController()
    } else if (!activeAbort || activeAbort.signal.aborted) {
      activeAbort = new AbortController()
    }
    const signal = activeAbort.signal

    dispatch({ type: 'set_turn_status', status: 'sending' })
    dispatch({ type: 'set_error', error: null })

    if (mode === 'start' && input.message) {
      dispatch({ type: 'append_user_message', text: input.message })
    }

    // 本地占位必须在 fetch / consume 之前创建，不依赖 turn.started / message.delta。
    // interaction cancel 不得新增思考卡片。
    const shouldCreatePlaceholder = mode === 'start' || input.uiSubmit?.action !== 'cancel'
    if (shouldCreatePlaceholder) {
      dispatch({ type: 'begin_assistant_placeholder', turnId: currentTurnId })
    }

    try {
      const iterable = mode === 'resume' && transport.resumeInteraction && input.interactionId
        ? transport.resumeInteraction(
            input.interactionId,
            input.uiSubmit?.action || 'submit',
            input.uiSubmit?.values || input.values || {},
            signal,
          )
        : transport.startTurn(input, signal)

      await consume(iterable, generation)

      if (generation !== turnGeneration) return
      if (state.turnStatus === 'sending' || state.turnStatus === 'streaming') {
        dispatch({ type: 'set_turn_status', status: 'completed' })
      }
    } catch (error) {
      if (generation !== turnGeneration) return
      if (isAbortError(error) || signal.aborted) {
        dispatch({ type: 'cancel_turn' })
        emitPublic(createEvent('turn.cancelled', {}, { sessionId: state.sessionId, turnId: currentTurnId }))
        return
      }
      const message = error instanceof Error ? error.message : String(error)
      dispatch({ type: 'fail_turn', message })
      emitPublic(createEvent('turn.failed', { message, error: message }, {
        sessionId: state.sessionId,
        turnId: currentTurnId,
      }))
    }
  }

  return {
    getState() {
      return state
    },

    subscribe(listener) {
      listeners.add(listener)
      listener(state)
      return () => listeners.delete(listener)
    },

    async send(input) {
      const turnInput: ConversationTurnInput = typeof input === 'string'
        ? { message: input }
        : input
      if (!turnInput.message && !turnInput.values && !turnInput.uiSubmit) return
      await runTurn(turnInput, 'start')
    },

    async submitInteraction(interactionId, action, values = {}) {
      if (!interactionId) return
      if (submittingInteractions.has(interactionId)) return
      submittingInteractions.add(interactionId)
      const isCancelAction = action === 'cancel'
      dispatch({
        type: 'update_interaction_state',
        interactionId,
        state: 'submitting',
        result: { action, values },
      })
      try {
        await runTurn(
          {
            interactionId,
            uiSubmit: { action, values },
            values,
            message: undefined,
          },
          'resume',
        )
        // cancel 成功优先按 cancelled；失败才 failed（不可伪装成功）
        if (isCancelAction && state.turnStatus === 'cancelled') {
          dispatch({
            type: 'update_interaction_state',
            interactionId,
            state: 'cancelled',
            result: { action, values },
          })
          emitPublic(createEvent(
            'interaction.cancelled',
            { interactionId, action, values },
            { sessionId: state.sessionId, turnId: currentTurnId },
          ))
        } else if (state.turnStatus === 'failed') {
          dispatch({
            type: 'update_interaction_state',
            interactionId,
            state: 'failed',
            errorMessage: state.error || '提交失败',
          })
        } else if (isCancelAction) {
          // 服务端以非 failed 终态确认取消（例如 completed+空 answer 的兼容路径）
          dispatch({
            type: 'update_interaction_state',
            interactionId,
            state: 'cancelled',
            result: { action, values },
          })
          emitPublic(createEvent(
            'interaction.cancelled',
            { interactionId, action, values },
            { sessionId: state.sessionId, turnId: currentTurnId },
          ))
        } else if (state.turnStatus === 'cancelled') {
          dispatch({
            type: 'update_interaction_state',
            interactionId,
            state: 'cancelled',
            result: { action, values },
          })
        } else {
          dispatch({
            type: 'update_interaction_state',
            interactionId,
            state: 'resolved',
            result: { action, values },
          })
          emitPublic(createEvent(
            'interaction.submitted',
            { interactionId, action, values },
            { sessionId: state.sessionId, turnId: currentTurnId },
          ))
        }
      } catch (error) {
        if (isAbortError(error) || state.turnStatus === 'cancelled') {
          dispatch({
            type: 'update_interaction_state',
            interactionId,
            state: 'failed',
            errorMessage: '交互提交被中断',
          })
        } else {
          dispatch({
            type: 'update_interaction_state',
            interactionId,
            state: 'failed',
            errorMessage: error instanceof Error ? error.message : String(error),
          })
        }
      } finally {
        submittingInteractions.delete(interactionId)
      }
    },

    async cancel() {
      turnGeneration += 1
      currentTurnId = createId('turn')
      activeAbort?.abort()
      activeAbort = null
      try {
        await transport.cancelTurn?.()
      } catch {
        // ignore cancel transport errors
      }
      dispatch({ type: 'cancel_turn' })
      emitPublic(createEvent('turn.cancelled', {}, { sessionId: state.sessionId, turnId: currentTurnId }))
    },

    async clearSession() {
      turnGeneration += 1
      currentTurnId = undefined
      activeAbort?.abort()
      activeAbort = null
      await transport.clearSession?.()
      emittedTerminalKeys.clear()
      emittedUiKeys.clear()
      emittedPageActionKeys.clear()
      dispatch({ type: 'reset', sessionId: undefined })
    },

    async restore() {
      const snapshot = await transport.restoreSession?.()
      if (snapshot) {
        dispatch({ type: 'restore', snapshot })
        return snapshot
      }
      return null
    },

    dispose() {
      disposed = true
      turnGeneration += 1
      currentTurnId = undefined
      activeAbort?.abort()
      activeAbort = null
      listeners.clear()
      emittedTerminalKeys.clear()
      emittedUiKeys.clear()
      emittedPageActionKeys.clear()
      transport.dispose()
    },
  }
}
