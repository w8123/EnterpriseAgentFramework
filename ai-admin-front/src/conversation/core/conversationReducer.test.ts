import { describe, expect, it } from 'vitest'
import { conversationReducer, initialConversationState } from './conversationReducer'
import { createEvent } from './conversationEvents'

describe('conversationReducer', () => {
  it('merges text deltas into the same text block', () => {
    let state = initialConversationState()
    state = conversationReducer(state, { type: 'start_assistant_message', id: 'a1' })
    state = conversationReducer(state, { type: 'append_text_delta', text: 'Hel', messageId: 'a1' })
    state = conversationReducer(state, { type: 'append_text_delta', text: 'lo', messageId: 'a1' })
    const textBlocks = state.messages[0].blocks.filter((b) => b.type === 'text')
    expect(textBlocks).toHaveLength(1)
    expect(textBlocks[0].type === 'text' && textBlocks[0].text).toBe('Hello')
  })

  it('keeps sanitized Embed lifecycle progress on the current assistant placeholder', () => {
    let state = initialConversationState('s1')
    state = conversationReducer(state, { type: 'begin_assistant_placeholder', turnId: 't1' })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.progress', {
        phase: 'workflow',
        state: 'started',
        message: '正在调用业务能力',
      }, { sessionId: 's1', turnId: 't1' }),
    })

    expect(state.turnStatus).toBe('streaming')
    expect(state.messages).toHaveLength(1)
    expect(state.messages[0].metadata?.publicProgress).toEqual({
      phase: 'workflow',
      state: 'started',
      message: '正在调用业务能力',
    })
  })

  it('does not duplicate messages on repeated completion', () => {
    let state = initialConversationState('s1')
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('message.delta', { text: 'done' }, { sessionId: 's1' }),
    })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.completed', { answer: 'done', sessionId: 's1' }, { sessionId: 's1' }),
    })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.completed', { answer: 'done', sessionId: 's1' }, { sessionId: 's1' }),
    })
    expect(state.messages.filter((m) => m.role === 'assistant')).toHaveLength(1)
    const text = state.messages[0].blocks.filter((b) => b.type === 'text').map((b) => (b as { text: string }).text).join('')
    expect(text).toBe('done')
    expect(state.turnStatus).toBe('completed')
  })

  it('inserts ui request without prematurely marking turn waiting', () => {
    let state = initialConversationState()
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('ui.requested', {
        uiRequest: { component: 'FORM', interactionId: 'i1', title: 'Fill' },
      }),
    })
    // ui.requested 只挂卡片；turnStatus 须等 turn.waiting / complete_turn
    expect(state.turnStatus).toBe('streaming')
    const interaction = state.messages[0].blocks.find((b) => b.type === 'interaction')
    expect(interaction?.type === 'interaction' && interaction.request.component).toBe('form')
    expect(interaction?.type === 'interaction' && interaction.state).toBe('waiting')

    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.waiting', { answer: 'need input' }),
    })
    expect(state.turnStatus).toBe('waiting')
  })

  it('completes a turn that contains a non-blocking output card', () => {
    const uiRequest = {
      component: 'list_card',
      interactionId: 'output-1',
      behavior: { blocking: false },
      presentation: { mode: 'card_only' },
    }
    let state = initialConversationState()
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('ui.requested', { uiRequest }),
    })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.completed', { answer: 'done', uiRequest }),
    })

    const interaction = state.messages[0].blocks.find((block) => block.type === 'interaction')
    expect(interaction?.type === 'interaction' && interaction.state).toBe('resolved')
    expect(state.messages[0].blocks.some((block) => block.type === 'text')).toBe(false)
    expect(state.turnStatus).toBe('completed')
  })

  it('removes already streamed text when a card-only presentation arrives', () => {
    const uiRequest = {
      component: 'list_card',
      interactionId: 'output-streamed',
      presentation: { mode: 'card_only' },
    }
    let state = initialConversationState()
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('message.delta', { text: 'duplicated details' }),
    })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('ui.requested', { uiRequest }),
    })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('message.delta', { text: 'late duplicate' }),
    })

    expect(state.messages[0].blocks.filter((block) => block.type === 'text')).toHaveLength(0)
    expect(state.messages[0].blocks.filter((block) => block.type === 'interaction')).toHaveLength(1)
  })

  it('keeps both blocks when presentation explicitly requests text and card', () => {
    const uiRequest = {
      component: 'list_card',
      interactionId: 'output-both',
      presentation: { mode: 'text_and_card' },
    }
    let state = initialConversationState()
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.completed', { answer: 'summary', uiRequest }),
    })

    expect(state.messages[0].blocks.some((block) => block.type === 'text')).toBe(true)
    expect(state.messages[0].blocks.some((block) => block.type === 'interaction')).toBe(true)
  })

  it('keeps only fallback text when presentation explicitly requests text only', () => {
    const uiRequest = {
      component: 'list_card',
      interactionId: 'output-text-only',
      presentation: { mode: 'text_only' },
    }
    let state = initialConversationState()
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.completed', { answer: 'plain summary', uiRequest }),
    })

    expect(state.messages[0].blocks.some((block) => block.type === 'text')).toBe(true)
    expect(state.messages[0].blocks.some((block) => block.type === 'interaction')).toBe(false)
  })

  it('marks cancel distinctly from fail', () => {
    let state = initialConversationState()
    state = conversationReducer(state, { type: 'start_assistant_message' })
    state = conversationReducer(state, { type: 'append_text_delta', text: 'partial' })
    state = conversationReducer(state, { type: 'cancel_turn' })
    expect(state.turnStatus).toBe('cancelled')
    expect(state.messages[0].status).toBe('cancelled')
    expect(state.error).toBeNull()

    state = conversationReducer(state, { type: 'fail_turn', message: 'network' })
    expect(state.turnStatus).toBe('failed')
    expect(state.error).toBe('network')
  })

  it('preserves traceId/code on fail_turn and turn.failed event', () => {
    let state = initialConversationState('s1')
    state = conversationReducer(state, { type: 'begin_assistant_placeholder', turnId: 'turn-1' })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.failed', {
        message: '模型在生成最终答案前达到最大输出长度，请提高最大输出或关闭思考模式后重试。',
        code: 'MODEL_OUTPUT_TOKEN_LIMIT',
        metadata: {
          finishReason: 'length',
          modelInstanceId: 'seed-deepseek-v4-flash',
        },
      }, { sessionId: 's1', turnId: 'turn-1', traceId: 't-fail' }),
    })
    expect(state.turnStatus).toBe('failed')
    const assistant = state.messages.find((m) => m.role === 'assistant')
    expect(assistant?.status).toBe('failed')
    expect(assistant?.metadata?.traceId).toBe('t-fail')
    expect(assistant?.metadata?.code).toBe('MODEL_OUTPUT_TOKEN_LIMIT')
    expect(assistant?.metadata?.success).toBe(false)
    expect(assistant?.metadata?.finishReason).toBe('length')
    expect(assistant?.blocks.some((b) => b.type === 'error')).toBe(true)
  })

  it('keeps success completion path and does not nest metadata', () => {
    let state = initialConversationState('s1')
    state = conversationReducer(state, { type: 'begin_assistant_placeholder' })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.completed', {
        answer: 'done',
        result: {
          success: true,
          answer: 'done',
          metadata: { code: 'SUPERVISOR_COMPLETED', traceId: 't-ok', planCount: 0 },
        },
        metadata: { code: 'SUPERVISOR_COMPLETED', traceId: 't-ok', planCount: 0, success: true },
      }, { sessionId: 's1' }),
    })
    expect(state.turnStatus).toBe('completed')
    const assistant = state.messages.find((m) => m.role === 'assistant')
    expect(assistant?.status).toBe('completed')
    expect(assistant?.metadata?.traceId).toBe('t-ok')
    expect(assistant?.metadata?.success).toBe(true)
    expect(assistant?.metadata?.metadata).toBeUndefined()
  })

  it('updates interaction submit states', () => {
    let state = initialConversationState()
    state = conversationReducer(state, {
      type: 'add_interaction',
      request: { component: 'confirm', interactionId: 'c1', message: 'ok?' },
    })
    state = conversationReducer(state, {
      type: 'update_interaction_state',
      interactionId: 'c1',
      state: 'submitting',
    })
    state = conversationReducer(state, {
      type: 'update_interaction_state',
      interactionId: 'c1',
      state: 'resolved',
      result: { action: 'confirm', values: { confirm: true } },
    })
    const block = state.messages[0].blocks.find((b) => b.type === 'interaction')
    expect(block?.type === 'interaction' && block.state).toBe('resolved')
  })

  it('dedupes page_action blocks by requestId only', () => {
    let state = initialConversationState()
    state = conversationReducer(state, {
      type: 'add_page_action',
      requestId: 'pa-1',
      actionKey: 'refresh',
    })
    state = conversationReducer(state, {
      type: 'add_page_action',
      requestId: 'pa-1',
      actionKey: 'refresh',
    })
    state = conversationReducer(state, {
      type: 'add_page_action',
      requestId: 'pa-2',
      actionKey: 'refresh',
    })
    const pageBlocks = state.messages[0].blocks.filter((b) => b.type === 'page_action')
    expect(pageBlocks).toHaveLength(2)
    expect(pageBlocks.map((b) => (b.type === 'page_action' ? b.requestId : ''))).toEqual(['pa-1', 'pa-2'])
  })

  it('begin_assistant_placeholder creates pending empty assistant for current turn', () => {
    let state = initialConversationState()
    state = conversationReducer(state, { type: 'set_turn_status', status: 'sending' })
    state = conversationReducer(state, { type: 'begin_assistant_placeholder', turnId: 'turn-1' })
    expect(state.turnId).toBe('turn-1')
    expect(state.turnStatus).toBe('sending')
    expect(state.messages).toHaveLength(1)
    expect(state.messages[0].role).toBe('assistant')
    expect(state.messages[0].status).toBe('pending')
    expect(state.messages[0].blocks).toHaveLength(0)
    expect(state.messages[0].localPlaceholder).toBe(true)
    expect(state.messages[0].turnId).toBe('turn-1')
  })

  it('binds server messageId onto local placeholder without creating a second card', () => {
    let state = initialConversationState()
    state = conversationReducer(state, { type: 'begin_assistant_placeholder', turnId: 't1' })
    const localId = state.messages[0].id
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('message.started', { messageId: 'server-m1' }),
    })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('message.delta', { messageId: 'server-m1', text: '你好' }),
    })
    expect(state.messages.filter((m) => m.role === 'assistant')).toHaveLength(1)
    expect(state.messages[0].id).toBe(localId)
    expect(state.messages[0].transportMessageId).toBe('server-m1')
    expect(state.messages[0].status).toBe('streaming')
    const text = state.messages[0].blocks.find((b) => b.type === 'text')
    expect(text && text.type === 'text' ? text.text : '').toBe('你好')
  })

  it('does not reuse a completed assistant for the next turn placeholder', () => {
    let state = initialConversationState()
    state = conversationReducer(state, { type: 'begin_assistant_placeholder', turnId: 't1' })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.completed', { answer: '第一轮' }),
    })
    expect(state.messages).toHaveLength(1)
    expect(state.messages[0].status).toBe('completed')

    state = conversationReducer(state, { type: 'set_turn_status', status: 'sending' })
    state = conversationReducer(state, { type: 'begin_assistant_placeholder', turnId: 't2' })
    expect(state.messages).toHaveLength(2)
    expect(state.messages[1].status).toBe('pending')
    expect(state.messages[1].turnId).toBe('t2')
    expect(state.messages[1].id).not.toBe(state.messages[0].id)

    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.completed', { answer: '第一轮' }),
    })
    expect(state.messages).toHaveLength(2)
    expect(state.messages[1].status).toBe('completed')
  })

  it('workflow safe final sequence yields hello once (no hellohello)', () => {
    let state = initialConversationState('wf1')
    state = conversationReducer(state, { type: 'begin_assistant_placeholder', turnId: 't1' })
    // debug node delta must not append public text
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('debug.workflow.node.delta', {
        nodeId: 'llm-final',
        text: 'hello',
        publicUserOutput: true,
      }, { sessionId: 'wf1' }),
    })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('message.delta', { text: 'hello' }, { sessionId: 'wf1' }),
    })
    state = conversationReducer(state, {
      type: 'apply_event',
      event: createEvent('turn.completed', { answer: 'hello', sessionId: 'wf1' }, { sessionId: 'wf1' }),
    })
    const assistants = state.messages.filter((m) => m.role === 'assistant')
    expect(assistants).toHaveLength(1)
    const text = assistants[0].blocks
      .filter((b) => b.type === 'text')
      .map((b) => (b as { text: string }).text)
      .join('')
    expect(text).toBe('hello')
    expect(text).not.toBe('hellohello')
    expect(state.turnStatus).toBe('completed')
  })
})
