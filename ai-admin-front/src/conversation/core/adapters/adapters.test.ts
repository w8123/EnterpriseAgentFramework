import { describe, expect, it } from 'vitest'
import { adaptAgentStreamEvent, expandAgentAdapted } from './adaptAgentStreamEvent'
import { adaptEmbedEvent, expandEmbedAdapted } from './adaptEmbedEvent'
import { adaptWorkflowDebugStreamEvent, expandWorkflowDebugAdapted } from './adaptWorkflowDebugStreamEvent'
import { adaptWorkflowSessionViewToEvents, adaptWorkflowSessionViewToSnapshot } from './adaptWorkflowSessionView'
import { adaptChatResponseToSnapshot } from './adaptChatResponse'

describe('adaptAgentStreamEvent', () => {
  it('maps supervisor.step to debug event', () => {
    const event = adaptAgentStreamEvent('supervisor.step', { thought: 'plan' })
    expect(event && !Array.isArray(event) && event.type).toBe('debug.supervisor.step')
  })

  it('maps completion without duplicating delta payload as separate message.delta', () => {
    const adapted = adaptAgentStreamEvent('execution.completed', {
      answer: 'final',
      sessionId: 's1',
    })
    const types = [...expandAgentAdapted(adapted)].map((e) => e.type)
    expect(types).toEqual(['turn.completed'])
  })

  it('maps execution.error to turn.failed with code/traceId metadata', () => {
    const adapted = adaptAgentStreamEvent('execution.error', {
      code: 'MODEL_OUTPUT_TOKEN_LIMIT',
      message: '模型在生成最终答案前达到最大输出长度，请提高最大输出或关闭思考模式后重试。',
      traceId: 't1',
      sessionId: 's1',
      metadata: {
        finishReason: 'length',
        modelInstanceId: 'seed-deepseek-v4-flash',
        usage: { completionTokens: 4096 },
        eventCounts: { reasoningDeltaCount: 2 },
      },
    })
    expect(adapted && !Array.isArray(adapted) && adapted.type).toBe('turn.failed')
    const data = (adapted as { data: Record<string, unknown> }).data
    expect(data.message).toContain('最大输出长度')
    expect(data.code).toBe('MODEL_OUTPUT_TOKEN_LIMIT')
    expect(data.traceId).toBe('t1')
    const meta = data.metadata as Record<string, unknown>
    expect(meta.success).toBe(false)
    expect(meta.traceId).toBe('t1')
    expect(meta.finishReason).toBe('length')
    expect(meta.metadata).toBeUndefined()
  })

  it('maps execution.completed with success=false to turn.failed', () => {
    const adapted = adaptAgentStreamEvent('execution.completed', {
      success: false,
      answer: 'ReachAI model stream returned empty content and no tool calls',
      sessionId: 's1',
      metadata: {
        code: 'MODEL_EMPTY_RESPONSE',
        traceId: 't2',
      },
    })
    const events = [...expandAgentAdapted(adapted)]
    expect(events.map((e) => e.type)).toEqual(['turn.failed'])
    const data = events[0].data as Record<string, unknown>
    expect((data.metadata as Record<string, unknown>).success).toBe(false)
    expect((data.metadata as Record<string, unknown>).traceId).toBe('t2')
    expect(data.code).toBe('MODEL_EMPTY_RESPONSE')
  })

  it('keeps success path as turn.completed without nested metadata envelope', () => {
    const adapted = adaptAgentStreamEvent('execution.completed', {
      success: true,
      answer: 'ok',
      sessionId: 's1',
      metadata: { code: 'SUPERVISOR_COMPLETED', traceId: 't3', planCount: 1 },
    })
    expect(adapted && !Array.isArray(adapted) && adapted.type).toBe('turn.completed')
    const data = (adapted as { data: Record<string, unknown> }).data
    const meta = data.metadata as Record<string, unknown>
    expect(meta.success).toBe(true)
    expect(meta.traceId).toBe('t3')
    expect(meta.planCount).toBe(1)
    expect(meta.metadata).toBeUndefined()
    expect(meta.answer).toBeUndefined()
  })
})

describe('adaptEmbedEvent', () => {
  it('hides supervisor.step', () => {
    expect(adaptEmbedEvent('supervisor.step', {})).toBeNull()
  })

  it('maps the sanitized Embed lifecycle progress without restoring supervisor details', () => {
    const event = adaptEmbedEvent('turn.progress', {
      phase: 'workflow',
      state: 'started',
      message: '正在调用业务能力',
    })
    expect(event && !Array.isArray(event) && event.type).toBe('turn.progress')
    expect(event && !Array.isArray(event) && event.data).toEqual({
      phase: 'workflow',
      state: 'started',
      message: '正在调用业务能力',
    })
  })

  it('maps message.completed to turn.completed with unwrapped metadata', () => {
    const adapted = adaptEmbedEvent('message.completed', {
      sessionId: 's1',
      answer: 'ok',
      intentType: 'CHAT',
      toolCalls: [{ name: 'x' }],
      metadata: { pageActionQueue: [{ type: 'page.action.requested', requestId: 'r1', actionKey: 'refresh' }] },
    })
    const events = [...expandEmbedAdapted(adapted)]
    expect(events.map((e) => e.type)).toEqual(['turn.completed'])
    const data = events[0].data as Record<string, unknown>
    expect(data.metadata).toEqual({
      pageActionQueue: [{ type: 'page.action.requested', requestId: 'r1', actionKey: 'refresh' }],
    })
    expect(data.intentType).toBe('CHAT')
    expect(data.toolCalls).toEqual([{ name: 'x' }])
    expect((data.metadata as Record<string, unknown>).metadata).toBeUndefined()
  })

  it('keeps readonly output cards non-blocking while retaining interactive waiting', () => {
    const readonly = [...expandEmbedAdapted(adaptEmbedEvent('message.completed', {
      sessionId: 's-readonly',
      answer: 'done',
      uiRequest: {
        interactionId: 'output-1',
        component: 'list_card',
        behavior: { blocking: false },
      },
    }))]
    const interactive = [...expandEmbedAdapted(adaptEmbedEvent('message.completed', {
      sessionId: 's-confirm',
      answer: 'confirm',
      uiRequest: { interactionId: 'confirm-1', component: 'confirm' },
    }))]

    expect(readonly.map((event) => event.type)).toEqual(['ui.requested', 'turn.completed'])
    expect(interactive.map((event) => event.type)).toEqual(['ui.requested', 'turn.waiting'])
  })
})

describe('adaptWorkflowDebugStreamEvent', () => {
  it('maps node events to debug.workflow.node.*', () => {
    const event = adaptWorkflowDebugStreamEvent('node.started', { nodeId: 'n1' }, { sessionId: 's1' })
    expect(event && !Array.isArray(event) && event.type).toBe('debug.workflow.node.started')
  })

  it('maps turn.waiting with ui.requested', () => {
    const types = [...expandWorkflowDebugAdapted(adaptWorkflowDebugStreamEvent('turn.waiting', {
      sessionId: 's1',
      uiRequest: { component: 'form', interactionId: 'i1' },
    }))].map((e) => e.type)
    expect(types).toContain('ui.requested')
    expect(types).toContain('turn.waiting')
  })

  it('maps turn.cancelled to Conversation turn.cancelled (not turn.failed)', () => {
    const adapted = adaptWorkflowDebugStreamEvent('turn.cancelled', {
      sessionId: 's1',
      status: 'CANCELLED',
      answer: 'debug session cancelled',
      message: 'debug session cancelled',
    }, { sessionId: 's1' })
    expect(adapted && !Array.isArray(adapted) && adapted.type).toBe('turn.cancelled')
    const data = (adapted as { data: Record<string, unknown> }).data
    expect(data.status).toBe('CANCELLED')
    expect(data.sessionId).toBe('s1')
  })

  it('maps node.output.delta only to debug.workflow.node.delta (never derives public message.delta)', () => {
    const adapted = adaptWorkflowDebugStreamEvent('node.output.delta', {
      nodeId: 'llm-final',
      text: 'hello',
      publicUserOutput: true,
    }, { sessionId: 's1' })
    const events = expandWorkflowDebugAdapted(adapted)
    expect(events.map((e) => e.type)).toEqual(['debug.workflow.node.delta'])
    expect(events.some((e) => e.type === 'message.delta')).toBe(false)
  })

  it('does not publish message.delta for internal node.output.delta', () => {
    const adapted = adaptWorkflowDebugStreamEvent('node.output.delta', {
      nodeId: 'llm-internal',
      text: 'secret-plan',
      publicUserOutput: false,
    }, { sessionId: 's1' })
    const events = expandWorkflowDebugAdapted(adapted)
    expect(events.map((e) => e.type)).toEqual(['debug.workflow.node.delta'])
  })

  it('backend message.delta remains the single public text ownership path', () => {
    const debugEvents = expandWorkflowDebugAdapted(adaptWorkflowDebugStreamEvent('node.output.delta', {
      nodeId: 'llm-final',
      text: 'hello',
      publicUserOutput: true,
    }, { sessionId: 's1' }))
    const publicEvents = expandWorkflowDebugAdapted(adaptWorkflowDebugStreamEvent('message.delta', {
      text: 'hello',
    }, { sessionId: 's1' }))
    const completed = expandWorkflowDebugAdapted(adaptWorkflowDebugStreamEvent('turn.completed', {
      answer: 'hello',
      sessionId: 's1',
    }, { sessionId: 's1' }))
    const all = [...debugEvents, ...publicEvents, ...completed]
    expect(all.filter((e) => e.type === 'debug.workflow.node.delta')).toHaveLength(1)
    expect(all.filter((e) => e.type === 'message.delta')).toHaveLength(1)
    expect(all.filter((e) => e.type === 'turn.completed')).toHaveLength(1)
  })
})

describe('adaptWorkflowSessionView', () => {
  it('builds snapshot and node debug events', () => {
    const view = {
      sessionId: 'ws1',
      status: 'WAITING',
      messages: [
        { id: 'm1', role: 'user', content: 'hi' },
        { id: 'm2', role: 'assistant', content: 'need input', uiRequest: { component: 'form', interactionId: 'i1', fields: [] } },
      ],
      steps: [{ nodeId: 'n1', status: 'WAITING', nodeName: 'Ask' }],
      uiRequest: { component: 'form', interactionId: 'i1' },
      traceId: 't1',
    }
    const snapshot = adaptWorkflowSessionViewToSnapshot(view)
    expect(snapshot.turnStatus).toBe('waiting')
    expect(snapshot.messages).toHaveLength(2)
    const events = [...adaptWorkflowSessionViewToEvents(view)]
    expect(events.some((e) => e.type === 'debug.workflow.node.waiting')).toBe(true)
    expect(events.some((e) => e.type === 'turn.waiting')).toBe(true)
  })

  it('maps CANCELLED REST view to turn.cancelled', () => {
    const view = {
      sessionId: 'ws-c',
      status: 'CANCELLED',
      messages: [],
      steps: [],
      uiRequest: null,
    }
    const snapshot = adaptWorkflowSessionViewToSnapshot(view)
    expect(snapshot.turnStatus).toBe('cancelled')
    const events = [...adaptWorkflowSessionViewToEvents(view)]
    expect(events.some((e) => e.type === 'turn.cancelled')).toBe(true)
    expect(events.some((e) => e.type === 'turn.failed')).toBe(false)
  })

  it('restores card-only output without reintroducing persisted fallback text', () => {
    const uiRequest = {
      component: 'list_card',
      interactionId: 'history-card-only',
      presentation: { mode: 'card_only' },
    }
    const view = {
      sessionId: 'ws-card-only',
      status: 'COMPLETED',
      answer: 'persisted fallback details',
      messages: [
        { id: 'm-card-only', role: 'assistant', content: 'persisted fallback details', uiRequest },
      ],
      uiRequest,
    }

    const snapshot = adaptWorkflowSessionViewToSnapshot(view)
    expect(snapshot.messages[0].blocks.some((block) => block.type === 'text')).toBe(false)
    expect(snapshot.messages[0].blocks.some((block) => block.type === 'interaction')).toBe(true)
    const events = [...adaptWorkflowSessionViewToEvents(view)]
    expect(events.some((event) => event.type === 'message.delta')).toBe(false)
    expect(events.some((event) => event.type === 'ui.requested')).toBe(true)
    expect(events.find((event) => event.type === 'turn.completed')?.data).toMatchObject({
      answer: 'persisted fallback details',
      uiRequest,
    })
  })
})

describe('adaptChatResponse', () => {
  it('converts answer + uiRequest to blocks', () => {
    const snapshot = adaptChatResponseToSnapshot({
      answer: 'hello',
      uiRequest: { component: 'confirm', interactionId: 'c1', message: '?' },
      sessionId: 's',
    }, { userMessage: 'q' })
    expect(snapshot.messages).toHaveLength(2)
    expect(snapshot.turnStatus).toBe('waiting')
  })

  it('keeps the answer as transport fallback but renders only a card in card-only snapshots', () => {
    const snapshot = adaptChatResponseToSnapshot({
      answer: 'duplicated details',
      uiRequest: {
        component: 'list_card',
        interactionId: 'output-card-only',
        presentation: { mode: 'card_only' },
      },
      sessionId: 's-card-only',
    })
    const assistant = snapshot.messages.find((message) => message.role === 'assistant')
    expect(assistant?.blocks.filter((block) => block.type === 'text')).toHaveLength(0)
    expect(assistant?.blocks.filter((block) => block.type === 'interaction')).toHaveLength(1)
    expect(snapshot.turnStatus).toBe('completed')
  })
})
