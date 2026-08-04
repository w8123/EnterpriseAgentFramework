import { describe, expect, it, vi } from 'vitest'
import { createConversationController } from './createConversationController'
import type { ConversationTransport } from '../transports/transportTypes'
import { createEvent } from './conversationEvents'
import { textContentOf } from './conversationTypes'

function delayedWaitingTransport(): ConversationTransport {
  return {
    kind: 'embed',
    capabilities: {
      eventStreaming: true,
      tokenStreaming: false,
      abort: true,
      restore: false,
      structuredInitialInput: false,
      pageActions: false,
    },
    async *startTurn() {
      yield createEvent('turn.started', {})
      yield createEvent('ui.requested', {
        uiRequest: {
          schemaVersion: '1.0',
          interactionId: 'ix-1',
          component: 'form',
          fields: [{ key: 'a', label: 'A', type: 'string' }],
        },
      })
      yield createEvent('turn.waiting', {})
    },
    async *resumeInteraction(_id, action) {
      yield createEvent('turn.started', {})
      await new Promise((resolve) => setTimeout(resolve, 20))
      if (action === 'cancel') {
        yield createEvent('turn.cancelled', { status: 'CANCELLED' })
        return
      }
      yield createEvent('turn.completed', { answer: 'done' })
    },
    dispose() {},
  }
}

describe('createConversationController interaction cancel', () => {
  it('marks card cancelled after action=cancel succeeds without aborting', async () => {
    const publicEvents: string[] = []
    const controller = createConversationController({
      transport: delayedWaitingTransport(),
      onPublicEvent: (event) => publicEvents.push(event.type),
    })
    await controller.send('hi')
    expect(controller.getState().turnStatus).toBe('waiting')

    await controller.submitInteraction('ix-1', 'cancel', {})
    const interaction = controller.getState().messages
      .flatMap((m) => m.blocks)
      .find((b) => b.type === 'interaction' && b.request.interactionId === 'ix-1')
    expect(interaction && interaction.type === 'interaction' ? interaction.state : null).toBe('cancelled')
    expect(publicEvents).toContain('interaction.cancelled')
    expect(publicEvents.filter((t) => t === 'interaction.cancelled')).toHaveLength(1)
    controller.dispose()
  })

  it('marks card failed when cancel submit fails', async () => {
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: {
        eventStreaming: true,
        tokenStreaming: false,
        abort: true,
        restore: false,
        structuredInitialInput: false,
        pageActions: false,
      },
      async *startTurn() {
        yield createEvent('ui.requested', {
          uiRequest: {
            schemaVersion: '1.0',
            interactionId: 'ix-2',
            component: 'confirm',
          },
        })
        yield createEvent('turn.waiting', {})
      },
      async *resumeInteraction() {
        throw new Error('backend reject cancel')
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    await controller.send('hi')
    await controller.submitInteraction('ix-2', 'cancel', {})
    const interaction = controller.getState().messages
      .flatMap((m) => m.blocks)
      .find((b) => b.type === 'interaction' && b.request.interactionId === 'ix-2')
    expect(interaction && interaction.type === 'interaction' ? interaction.state : null).toBe('failed')
    controller.dispose()
  })

  it('forwards public events once via onPublicEvent', async () => {
    const events: string[] = []
    const controller = createConversationController({
      transport: delayedWaitingTransport(),
      onPublicEvent: (event) => events.push(event.type),
    })
    await controller.send('hi')
    expect(events.filter((t) => t === 'ui.requested')).toHaveLength(1)
    expect(events.filter((t) => t === 'turn.waiting')).toHaveLength(1)
    controller.dispose()
  })

  it('dedupes identical page.action.requested by requestId in public events and blocks', async () => {
    const publicEvents: string[] = []
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: {
        eventStreaming: true,
        tokenStreaming: false,
        abort: true,
        restore: false,
        structuredInitialInput: false,
        pageActions: true,
      },
      async *startTurn() {
        yield createEvent('page.action.requested', {
          type: 'page.action.requested',
          requestId: 'pa-dup',
          actionKey: 'refresh',
        })
        yield createEvent('page.action.requested', {
          type: 'page.action.requested',
          requestId: 'pa-dup',
          actionKey: 'refresh',
        })
        yield createEvent('page.action.requested', {
          type: 'page.action.requested',
          requestId: 'pa-other',
          actionKey: 'refresh',
        })
        yield createEvent('turn.completed', { answer: 'done', sessionId: 's1' })
      },
      dispose() {},
    }
    const controller = createConversationController({
      transport,
      onPublicEvent: (event) => publicEvents.push(event.type),
    })
    await controller.send('pa')
    expect(publicEvents.filter((t) => t === 'page.action.requested')).toHaveLength(2)
    const pageBlocks = controller.getState().messages
      .flatMap((m) => m.blocks)
      .filter((b) => b.type === 'page_action')
    expect(pageBlocks).toHaveLength(2)
    controller.dispose()
  })

  it('keeps two message.completed turns when answers are identical', async () => {
    const completed: Array<{ turnId?: string; answer?: string }> = []
    const deltas: string[] = []
    let round = 0
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: {
        eventStreaming: true,
        tokenStreaming: false,
        abort: true,
        restore: false,
        structuredInitialInput: false,
        pageActions: false,
      },
      async *startTurn() {
        round += 1
        yield createEvent('message.delta', { text: '操作成功' })
        yield createEvent('message.delta', { text: '操作成功' })
        yield createEvent('turn.completed', { answer: '操作成功', sessionId: 's1' })
      },
      dispose() {},
    }
    const controller = createConversationController({
      transport,
      onPublicEvent: (event) => {
        if (event.type === 'message.delta') {
          deltas.push(String((event.data as { text?: string }).text || ''))
        }
        if (event.type === 'turn.completed') {
          const data = event.data as Record<string, unknown>
          completed.push({ turnId: event.turnId, answer: String(data.answer || '') })
        }
      },
    })
    await controller.send('a')
    await controller.send('b')
    expect(round).toBe(2)
    expect(completed).toHaveLength(2)
    expect(completed[0].answer).toBe('操作成功')
    expect(completed[1].answer).toBe('操作成功')
    expect(completed[0].turnId).toBeTruthy()
    expect(completed[1].turnId).toBeTruthy()
    expect(completed[0].turnId).not.toBe(completed[1].turnId)
    expect(deltas).toEqual(['操作成功', '操作成功', '操作成功', '操作成功'])
    controller.dispose()
  })

  it('dedupes duplicate terminal events within the same turn', async () => {
    const completed: string[] = []
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: {
        eventStreaming: true,
        tokenStreaming: false,
        abort: true,
        restore: false,
        structuredInitialInput: false,
        pageActions: false,
      },
      async *startTurn() {
        yield createEvent('turn.completed', { answer: 'same', sessionId: 's1' })
        yield createEvent('turn.completed', { answer: 'same', sessionId: 's1' })
      },
      dispose() {},
    }
    const controller = createConversationController({
      transport,
      onPublicEvent: (event) => {
        if (event.type === 'turn.completed') completed.push(event.type)
      },
    })
    await controller.send('once')
    expect(completed).toHaveLength(1)
    controller.dispose()
  })

  it('maps Workflow SSE turn.cancelled to cancelled card without failed', async () => {
    const publicEvents: string[] = []
    const transport: ConversationTransport = {
      kind: 'workflow-working-copy',
      capabilities: {
        eventStreaming: true,
        tokenStreaming: false,
        abort: true,
        restore: true,
        structuredInitialInput: true,
        pageActions: false,
      },
      async *startTurn() {
        yield createEvent('ui.requested', {
          uiRequest: {
            schemaVersion: '1.0',
            interactionId: 'ix-wf',
            component: 'confirm',
            message: '?',
          },
        })
        yield createEvent('turn.waiting', { sessionId: 'wf-1' }, { sessionId: 'wf-1' })
      },
      async *resumeInteraction(_id, action) {
        if (action === 'cancel') {
          yield createEvent('turn.cancelled', {
            sessionId: 'wf-1',
            status: 'CANCELLED',
          }, { sessionId: 'wf-1' })
          return
        }
        yield createEvent('turn.completed', { answer: 'ok' })
      },
      dispose() {},
    }
    const controller = createConversationController({
      transport,
      onPublicEvent: (event) => publicEvents.push(event.type),
    })
    await controller.send('hi')
    await controller.submitInteraction('ix-wf', 'cancel', {})
    expect(controller.getState().turnStatus).toBe('cancelled')
    const interaction = controller.getState().messages
      .flatMap((m) => m.blocks)
      .find((b) => b.type === 'interaction' && b.request.interactionId === 'ix-wf')
    expect(interaction && interaction.type === 'interaction' ? interaction.state : null).toBe('cancelled')
    expect(publicEvents).toContain('turn.cancelled')
    expect(publicEvents).toContain('interaction.cancelled')
    expect(publicEvents).not.toContain('turn.failed')
    controller.dispose()
  })
})

describe('createConversationController assistant placeholder lifecycle', () => {
  function baseCaps(overrides: Partial<ConversationTransport['capabilities']> = {}) {
    return {
      eventStreaming: true,
      tokenStreaming: false,
      abort: true,
      restore: false,
      structuredInitialInput: false,
      pageActions: false,
      ...overrides,
    }
  }

  it('A: creates pending assistant placeholder before any transport event', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        await gate
        yield createEvent('turn.started', {})
        yield createEvent('turn.completed', { answer: '最终回答' })
      },
      dispose() {},
    }
    const publicEvents: string[] = []
    const controller = createConversationController({
      transport,
      onPublicEvent: (event) => publicEvents.push(event.type),
    })
    const pending = controller.send('你好')
    await Promise.resolve()
    await Promise.resolve()

    const state = controller.getState()
    expect(state.turnStatus).toBe('sending')
    expect(state.messages.map((m) => m.role)).toEqual(['user', 'assistant'])
    const assistant = state.messages[1]
    expect(assistant.status).toBe('pending')
    expect(assistant.blocks).toHaveLength(0)
    expect(assistant.localPlaceholder).toBe(true)
    expect(publicEvents).not.toContain('message.started')

    release()
    await pending
    expect(controller.getState().messages.filter((m) => m.role === 'assistant')).toHaveLength(1)
    expect(controller.getState().messages[1].status).toBe('completed')
    controller.dispose()
  })

  it('B: completion-only reuses placeholder without message.started/delta', async () => {
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        yield createEvent('turn.completed', { answer: '完成' })
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    await controller.send('hi')
    const assistants = controller.getState().messages.filter((m) => m.role === 'assistant')
    expect(assistants).toHaveLength(1)
    expect(assistants[0].status).toBe('completed')
    expect(textContentOf(assistants[0])).toBe('完成')
    expect(assistants[0].blocks.some((b) => b.type === 'text' && !b.text)).toBe(false)
    controller.dispose()
  })

  it('C: message.started + messageId binds to the same placeholder', async () => {
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        yield createEvent('turn.started', {})
        yield createEvent('message.started', { messageId: 'server-m1' })
        yield createEvent('message.delta', { messageId: 'server-m1', text: '你好' })
        yield createEvent('turn.completed', { answer: '你好' })
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    await controller.send('hi')
    const assistants = controller.getState().messages.filter((m) => m.role === 'assistant')
    expect(assistants).toHaveLength(1)
    expect(assistants[0].transportMessageId).toBe('server-m1')
    expect(textContentOf(assistants[0])).toBe('你好')
    expect(assistants[0].status).toBe('completed')
    controller.dispose()
  })

  it('D: direct message.delta + messageId reuses placeholder', async () => {
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        yield createEvent('message.delta', { messageId: 'server-m2', text: '直接' })
        yield createEvent('turn.completed', { answer: '直接' })
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    await controller.send('hi')
    const assistants = controller.getState().messages.filter((m) => m.role === 'assistant')
    expect(assistants).toHaveLength(1)
    expect(assistants[0].transportMessageId).toBe('server-m2')
    expect(textContentOf(assistants[0])).toBe('直接')
    controller.dispose()
  })

  it('E: id-less delta then id delta appends without duplicating text', async () => {
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        yield createEvent('message.delta', { text: 'A' })
        yield createEvent('message.delta', { messageId: 'server-m1', text: 'B' })
        yield createEvent('turn.completed', { answer: 'AB' })
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    await controller.send('hi')
    const assistants = controller.getState().messages.filter((m) => m.role === 'assistant')
    expect(assistants).toHaveLength(1)
    expect(textContentOf(assistants[0])).toBe('AB')
    expect(assistants[0].transportMessageId).toBe('server-m1')
    controller.dispose()
  })

  it('F: transport failure before first event marks placeholder failed', async () => {
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        throw new Error('network down')
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    await controller.send('hi')
    const assistants = controller.getState().messages.filter((m) => m.role === 'assistant')
    expect(assistants).toHaveLength(1)
    expect(assistants[0].status).toBe('failed')
    expect(assistants[0].blocks.some((b) => b.type === 'error')).toBe(true)
    expect(controller.getState().messages.some((m) => m.status === 'pending')).toBe(false)
    controller.dispose()
  })

  it('G: cancel turns active placeholder cancelled without extra cards', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn(_input, signal) {
        await gate
        if (signal?.aborted) {
          const err = new Error('aborted')
          err.name = 'AbortError'
          throw err
        }
        yield createEvent('turn.completed', { answer: 'late' })
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    const pending = controller.send('hi')
    await Promise.resolve()
    await Promise.resolve()
    expect(controller.getState().messages.filter((m) => m.role === 'assistant')).toHaveLength(1)

    await controller.cancel()
    release()
    await pending.catch(() => undefined)

    const assistants = controller.getState().messages.filter((m) => m.role === 'assistant')
    expect(assistants).toHaveLength(1)
    expect(assistants[0].status).toBe('cancelled')
    expect(controller.getState().turnStatus).toBe('cancelled')
    controller.dispose()
  })

  it('H: two consecutive turns keep two assistant messages even with same answer', async () => {
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        yield createEvent('turn.completed', { answer: '相同答案' })
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    await controller.send('a')
    const firstId = controller.getState().messages.find((m) => m.role === 'assistant')?.id
    await controller.send('b')
    const assistants = controller.getState().messages.filter((m) => m.role === 'assistant')
    const users = controller.getState().messages.filter((m) => m.role === 'user')
    expect(users).toHaveLength(2)
    expect(assistants).toHaveLength(2)
    expect(assistants[0].id).toBe(firstId)
    expect(assistants[1].id).not.toBe(firstId)
    expect(assistants.every((m) => m.status === 'completed')).toBe(true)
    expect(assistants.every((m) => textContentOf(m) === '相同答案')).toBe(true)
    controller.dispose()
  })

  it('I: interaction submit creates a new thinking placeholder', async () => {
    let submitCount = 0
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        yield createEvent('ui.requested', {
          uiRequest: {
            schemaVersion: '1.0',
            interactionId: 'ix-submit',
            component: 'confirm',
          },
        })
        yield createEvent('turn.waiting', {})
      },
      async *resumeInteraction(_id, action) {
        submitCount += 1
        yield createEvent('turn.started', {})
        yield createEvent('turn.completed', { answer: `resumed:${action}` })
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    await controller.send('hi')
    const before = controller.getState().messages.filter((m) => m.role === 'assistant').length

    const pending = controller.submitInteraction('ix-submit', 'confirm', { confirm: true })
    await Promise.resolve()
    await Promise.resolve()
    const mid = controller.getState().messages.filter((m) => m.role === 'assistant')
    expect(mid.length).toBe(before + 1)
    expect(mid[mid.length - 1].status).toBe('pending')
    await pending

    const after = controller.getState().messages.filter((m) => m.role === 'assistant')
    expect(after).toHaveLength(before + 1)
    expect(textContentOf(after[after.length - 1])).toBe('resumed:confirm')
    expect(submitCount).toBe(1)
    const interaction = controller.getState().messages
      .flatMap((m) => m.blocks)
      .find((b) => b.type === 'interaction' && b.request.interactionId === 'ix-submit')
    expect(interaction && interaction.type === 'interaction' ? interaction.state : null).toBe('resolved')
    controller.dispose()
  })

  it('J: interaction cancel does not create a thinking placeholder', async () => {
    let submitCount = 0
    let abortCalled = false
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        yield createEvent('ui.requested', {
          uiRequest: {
            schemaVersion: '1.0',
            interactionId: 'ix-cancel',
            component: 'confirm',
          },
        })
        yield createEvent('turn.waiting', {})
      },
      async *resumeInteraction(_id, action) {
        submitCount += 1
        expect(action).toBe('cancel')
        yield createEvent('turn.cancelled', { status: 'CANCELLED' })
      },
      async cancelTurn() {
        abortCalled = true
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    await controller.send('hi')
    const beforeAssistants = controller.getState().messages.filter((m) => m.role === 'assistant')
    const beforeCount = beforeAssistants.length
    const beforePending = beforeAssistants.filter((m) => m.status === 'pending').length

    await controller.submitInteraction('ix-cancel', 'cancel', {})
    const afterAssistants = controller.getState().messages.filter((m) => m.role === 'assistant')
    expect(afterAssistants).toHaveLength(beforeCount)
    expect(afterAssistants.filter((m) => m.status === 'pending')).toHaveLength(beforePending)
    expect(submitCount).toBe(1)
    expect(abortCalled).toBe(false)
    const interaction = controller.getState().messages
      .flatMap((m) => m.blocks)
      .find((b) => b.type === 'interaction' && b.request.interactionId === 'ix-cancel')
    expect(interaction && interaction.type === 'interaction' ? interaction.state : null).toBe('cancelled')
    controller.dispose()
  })

  it('K: workflow resume without session fails without orphan pending', async () => {
    let createCalls = 0
    let submitCalls = 0
    const transport: ConversationTransport = {
      kind: 'workflow-working-copy',
      capabilities: baseCaps({ structuredInitialInput: true, restore: true }),
      async *startTurn() {
        createCalls += 1
        yield createEvent('turn.completed', { answer: 'ok' })
      },
      async *resumeInteraction() {
        submitCalls += 1
        throw new Error('Workflow interaction resume requires an active debug session')
      },
      dispose() {},
    }
    const controller = createConversationController({ transport })
    // 模拟已有 interaction 卡片（无 session 场景）
    await controller.send('seed')
    // 清掉 session 语义：直接 resume 失败路径
    createCalls = 0
    submitCalls = 0
    // 注入一条 waiting interaction，便于 submitInteraction 找到块
    // 直接走 resume：controller 会创建 placeholder，transport 抛错后转 failed
    await controller.submitInteraction('ix-orphan', 'submit', { a: 1 })
    expect(createCalls).toBe(0)
    expect(submitCalls).toBe(1)
    expect(controller.getState().turnStatus).toBe('failed')
    expect(controller.getState().messages.some((m) => m.status === 'pending')).toBe(false)
    const failed = controller.getState().messages.filter((m) => m.role === 'assistant' && m.status === 'failed')
    expect(failed.length).toBeGreaterThanOrEqual(1)
    expect(failed.some((m) => m.blocks.some((b) => b.type === 'error'))).toBe(true)
    controller.dispose()
  })

  it('L: local placeholder does not emit public message.started', async () => {
    const publicEvents: string[] = []
    const transport: ConversationTransport = {
      kind: 'embed',
      capabilities: baseCaps(),
      async *startTurn() {
        yield createEvent('message.delta', { text: 'Hi' })
        yield createEvent('turn.completed', { answer: 'Hi', sessionId: 's1' })
      },
      dispose() {},
    }
    const controller = createConversationController({
      transport,
      onPublicEvent: (event) => publicEvents.push(event.type),
    })
    await controller.send('hello')
    expect(publicEvents).not.toContain('message.started')
    expect(publicEvents.filter((t) => t === 'message.delta')).toHaveLength(1)
    expect(publicEvents.filter((t) => t === 'turn.completed')).toHaveLength(1)
    controller.dispose()
  })
})
