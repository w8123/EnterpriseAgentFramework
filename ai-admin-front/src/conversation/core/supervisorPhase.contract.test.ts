import { describe, expect, it } from 'vitest'
import { createConversationController } from './createConversationController'
import { createEvent } from './conversationEvents'
import type { ConversationTransport } from '../transports/transportTypes'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}

function baseTransport(
  overrides: Partial<ConversationTransport> & Pick<ConversationTransport, 'startTurn'>,
): ConversationTransport {
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
    dispose() {},
    ...overrides,
  }
}

describe('supervisor phase + placeholder semantics', () => {
  it('A: creates pending assistant before fetch and keeps local connecting semantics', async () => {
    const gate = deferred<void>()
    const transport = baseTransport({
      async *startTurn() {
        await gate.promise
        yield createEvent('turn.started', {})
        yield createEvent('message.delta', { text: '答' })
        yield createEvent('turn.completed', { answer: '答' })
      },
    })
    const controller = createConversationController({ transport })
    const pending = controller.send('你好')
    await Promise.resolve()
    const mid = controller.getState().messages
    expect(mid.some((m) => m.role === 'assistant' && m.status === 'pending')).toBe(true)
    expect(mid.some((m) => m.role === 'assistant' && m.localPlaceholder)).toBe(true)
    gate.resolve()
    await pending
  })

  it('B: upserts same stepId started/completed into one phase', async () => {
    const steps: Array<Record<string, unknown>> = []
    function upsert(raw: Record<string, unknown>) {
      const stepId = raw.stepId ? String(raw.stepId) : undefined
      if (stepId) {
        const index = steps.findIndex((s) => s.stepId === stepId)
        if (index >= 0) {
          steps[index] = { ...steps[index], ...raw }
          return
        }
      }
      steps.push(raw)
    }
    const transport = baseTransport({
      async *startTurn() {
        yield createEvent('turn.started', {})
        yield createEvent('debug.supervisor.step', {
          stepId: 'final-answer',
          name: 'final_answer',
          state: 'started',
          source: 'runtime_fallback',
          title: '生成最终回答',
        })
        yield createEvent('message.delta', { text: '第一' })
        yield createEvent('message.delta', { text: '段' })
        yield createEvent('message.delta', { text: '答案' })
        yield createEvent('debug.supervisor.step', {
          stepId: 'final-answer',
          name: 'final_answer',
          state: 'completed',
          source: 'runtime_fallback',
          title: '生成最终回答',
        })
        yield createEvent('turn.completed', { answer: '第一段答案' })
      },
    })
    const texts: string[] = []
    const controller = createConversationController({
      transport,
      onDebugEvent(event) {
        if (event.type === 'debug.supervisor.step') {
          upsert(event.data as Record<string, unknown>)
        }
      },
      onChange(state) {
        const last = [...state.messages].reverse().find((m) => m.role === 'assistant')
        const text = last?.blocks
          .filter((b) => b.type === 'text')
          .map((b) => (b as { text?: string }).text || '')
          .join('') || ''
        if (text) texts.push(text)
      },
    })
    await controller.send('流式')
    expect(steps).toHaveLength(1)
    expect(steps[0].state).toBe('completed')
    expect(steps[0].source).toBe('runtime_fallback')
    expect(texts.length).toBeGreaterThanOrEqual(3)
    expect(texts[texts.length - 1]).toBe('第一段答案')
    expect(texts.some((t) => t === '第一')).toBe(true)
    expect(texts.some((t) => t === '第一段')).toBe(true)
  })

  it('B: legacy supervisor.step without stepId still appends', async () => {
    const steps: Array<Record<string, unknown>> = []
    function upsert(raw: Record<string, unknown>) {
      const stepId = raw.stepId ? String(raw.stepId) : undefined
      if (stepId) {
        const index = steps.findIndex((s) => s.stepId === stepId)
        if (index >= 0) {
          steps[index] = { ...steps[index], ...raw }
          return
        }
      }
      steps.push(raw)
    }
    const transport = baseTransport({
      async *startTurn() {
        yield createEvent('debug.supervisor.step', { name: 'plan', detail: 'a' })
        yield createEvent('debug.supervisor.step', { name: 'plan', detail: 'b' })
        yield createEvent('turn.completed', { answer: 'ok' })
      },
    })
    const controller = createConversationController({
      transport,
      onDebugEvent(event) {
        if (event.type === 'debug.supervisor.step') upsert(event.data as Record<string, unknown>)
      },
    })
    await controller.send('兼容')
    expect(steps).toHaveLength(2)
  })
})
