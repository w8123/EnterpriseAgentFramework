import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createWorkflowWorkingCopyTransport } from './createWorkflowWorkingCopyTransport'
import { createConversationController } from '../core/createConversationController'
import { adaptWorkflowSessionViewToEvents, adaptWorkflowSessionViewToSnapshot } from '../core/adapters/adaptWorkflowSessionView'
import type { ConversationEventEnvelope } from '../core/conversationEvents'

const api = vi.hoisted(() => ({ create: vi.fn(), submit: vi.fn(), get: vi.fn(), byCreation: vi.fn(), cancel: vi.fn() }))
vi.mock('@/api/workflow', () => ({
  createWorkflowDebugSession: api.create,
  submitWorkflowDebugSession: api.submit,
  getWorkflowDebugSession: api.get,
  getWorkflowDebugSessionByCreationKey: api.byCreation,
  cancelWorkflowDebugSession: api.cancel,
}))
vi.mock('@/utils/platformAuth', () => ({ platformCsrfHeaders: (headers: HeadersInit) => headers }))

function view(status = 'COMPLETED') {
  return {
    sessionId: 'admitted-1', runId: 'run-1', traceId: 'trace-1', targetType: 'WORKFLOW_WORKING_COPY',
    status, success: true, answer: status === 'COMPLETED' ? '已完成' : '',
    messages: [], steps: [], stateSnapshot: {},
  }
}

const admission = () => `event: session.created\ndata: ${JSON.stringify(view('RUNNING'))}\n\n`
const terminal = () => `event: turn.completed\ndata: ${JSON.stringify(view())}\n\n`

describe('Workflow debug terminal recovery', () => {
  const timeout = '调试执行已超时，结果无法确认。请核对运行记录和业务系统，勿直接重复调试。'

  it.each(['EXPIRED', 'FAILED'])('shows the stored result summary for %s in snapshots and events', (status) => {
    const restored = { ...view(status), success: false, answer: timeout }
    expect(adaptWorkflowSessionViewToSnapshot(restored).error).toBe(timeout)
    const failed = [...adaptWorkflowSessionViewToEvents(restored)].find((event) => event.type === 'turn.failed')
    expect(failed?.data).toEqual(expect.objectContaining({ message: timeout }))
  })

  it.each(['EXPIRED', 'FAILED', 'CANCELLED', 'COMPLETED'])('never reopens a historical interaction after %s', (status) => {
    const ui = { type: 'CONFIRM', interactionId: 'old-interaction', prompt: '确认执行？', blocking: true }
    const restored = {
      ...view(status), answer: timeout,
      messages: [{ role: 'assistant', content: '确认执行？', uiRequest: ui }],
    }
    const snapshot = adaptWorkflowSessionViewToSnapshot(restored)
    const blocks = snapshot.messages.flatMap((message) => message.blocks).filter((block) => block.type === 'interaction')
    expect(blocks).toHaveLength(1)
    expect(blocks.every((block) => block.type === 'interaction' && block.state === 'resolved')).toBe(true)
  })
})

function response(chunks: string[], cut = false) {
  let offset = 0
  return new Response(new ReadableStream<Uint8Array>({
    pull(controller) {
      if (offset < chunks.length) controller.enqueue(new TextEncoder().encode(chunks[offset++]))
      else if (cut) controller.error(new TypeError('connection cut'))
      else controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
}

async function collect(stream: AsyncIterable<ConversationEventEnvelope>) {
  const events: ConversationEventEnvelope[] = []
  for await (const event of stream) events.push(event)
  return events
}

function fixture(stream: Response) {
  let sessionId: string | undefined
  const onSessionView = vi.fn()
  const fetchImpl = vi.fn().mockResolvedValue(stream)
  const options = {
    tryStream: true, fetchImpl,
    getSessionId: () => sessionId,
    setSessionId: (id: string | undefined) => { sessionId = id },
    getCreateRequest: () => ({ targetType: 'WORKFLOW_WORKING_COPY', workingCopyDefinition: { graphSpecJson: '{}' } }),
    onSessionView,
  }
  return { transport: createWorkflowWorkingCopyTransport(options), options, onSessionView, fetchImpl, sessionId: () => sessionId }
}

describe('Workflow debug admission and interrupted-stream recovery', () => {
  beforeEach(() => vi.resetAllMocks())

  it('saves creation identity before dispatch and queries it after losing the entire reply', async () => {
    let key: string | undefined
    const f = fixture(response([], true))
    const options = {
      ...f.options,
      getCreationKey: () => key,
      setCreationKey: (value: string | undefined) => { key = value },
    }
    f.fetchImpl.mockImplementation(async (_path, init) => {
      expect(key).toMatch(/^[A-Za-z0-9_-]{1,128}$/)
      expect(JSON.parse(init.body).idempotencyKey).toBe(key)
      throw new TypeError('reply lost')
    })
    const transport = createWorkflowWorkingCopyTransport(options)
    await expect(collect(transport.startTurn({ message: 'hello' }))).rejects.toThrow()
    const originalKey = key
    transport.dispose?.()
    expect(key).toBe(originalKey)
    const reopened = createWorkflowWorkingCopyTransport(options)
    api.byCreation.mockRejectedValueOnce(Object.assign(new Error('not admitted yet'), { status: 404 }))
    await expect(reopened.restoreSession?.()).rejects.toThrow('not admitted yet')
    expect(key).toBe(originalKey)
    api.byCreation.mockResolvedValue({ data: view() })
    expect((await reopened.restoreSession?.())?.turnStatus).toBe('completed')
    expect(api.byCreation).toHaveBeenNthCalledWith(1, originalKey)
    expect(api.byCreation).toHaveBeenNthCalledWith(2, originalKey)
    expect(key).toBeUndefined()
    expect(f.fetchImpl).toHaveBeenCalledTimes(1)
    expect(api.create).not.toHaveBeenCalled()
    expect(api.submit).not.toHaveBeenCalled()
  })

  it('ignores a lookup response delivered after transport disposal', async () => {
    let resolve!: (value: { data: ReturnType<typeof view> }) => void
    api.byCreation.mockReturnValue(new Promise(value => { resolve = value }))
    const onSessionView = vi.fn()
    const setCreationKey = vi.fn()
    const transport = createWorkflowWorkingCopyTransport({
      getCreationKey: () => 'old-attempt', setCreationKey, onSessionView,
    })
    const pending = transport.restoreSession?.()
    transport.dispose?.()
    resolve({ data: view() })
    expect(await pending).toBeNull()
    expect(onSessionView).not.toHaveBeenCalled()
    expect(setCreationKey).not.toHaveBeenCalled()
  })

  it('remembers admission before yielding it, even when the consumer stops immediately', async () => {
    const f = fixture(response([admission()]))
    const iterator = f.transport.startTurn({ message: 'hello' })[Symbol.asyncIterator]()
    await iterator.next() // local turn.started
    const next = await iterator.next()
    expect(next.value.type).toBe('session.created')
    expect(f.sessionId()).toBe('admitted-1')
    expect(f.onSessionView).toHaveBeenCalledWith(expect.objectContaining({ status: 'RUNNING' }))
    await iterator.return?.()
    expect(api.create).not.toHaveBeenCalled()
    expect(api.get).not.toHaveBeenCalled()
  })

  it('retains the admitted session after a network cut and restores it in a new transport', async () => {
    const f = fixture(response([admission()], true))
    await expect(collect(f.transport.startTurn({ message: 'hello' }))).rejects.toThrow()
    expect(f.sessionId()).toBe('admitted-1')
    api.get.mockResolvedValue({ data: view() })
    const restored = await createWorkflowWorkingCopyTransport(f.options).restoreSession?.()
    expect(restored?.sessionId).toBe('admitted-1')
    expect(restored?.turnStatus).toBe('completed')
    expect(api.get).toHaveBeenCalledWith('admitted-1')
    expect(api.create).not.toHaveBeenCalled()
    expect(api.submit).not.toHaveBeenCalled()
  })

  it('keeps the admitted id in the shared conversation controller after a cut', async () => {
    const f = fixture(response([admission()], true))
    const controller = createConversationController({ transport: f.transport })
    await controller.send({ message: 'hello' })
    expect(controller.getState().sessionId).toBe('admitted-1')
    expect(controller.getState().turnStatus).toBe('failed')
    expect(api.create).not.toHaveBeenCalled()
  })

  it('does not turn a truncated stream and still-running session into success', async () => {
    const f = fixture(response([admission()]))
    api.get.mockResolvedValue({ data: view('RUNNING') })
    const controller = createConversationController({ transport: f.transport })
    await controller.send({ message: 'hello' })
    expect(controller.getState().turnStatus).toBe('failed')
    expect(controller.getState().error).toContain('尚未确认')
    expect(f.sessionId()).toBe('admitted-1')
    expect(api.create).not.toHaveBeenCalled()
  })

  it('recovers a committed result through GET when EOF loses the terminal event', async () => {
    const f = fixture(response([admission()]))
    api.get.mockResolvedValue({ data: view() })
    const events = await collect(f.transport.startTurn({ message: 'hello' }))
    expect(events.some(event => event.type === 'session.restored')).toBe(true)
    expect(api.get).toHaveBeenCalledWith('admitted-1')
    expect(api.create).not.toHaveBeenCalled()
  })

  it('never retries creation when the post-stream GET returns 404', async () => {
    const f = fixture(response([admission(), terminal()]))
    api.get.mockRejectedValue(Object.assign(new Error('view missing'), { status: 404 }))
    api.create.mockResolvedValue({ data: view() })
    await expect(collect(f.transport.startTurn({ message: 'hello' }))).rejects.toThrow()
    expect(api.create).not.toHaveBeenCalled()
    expect(f.fetchImpl).toHaveBeenCalledTimes(1)
    expect(f.sessionId()).toBe('admitted-1')
  })

  it('never accepts an empty SSE response as a completed turn', async () => {
    const f = fixture(response([]))
    await expect(collect(f.transport.startTurn({ message: 'hello' }))).rejects.toThrow()
    expect(api.create).not.toHaveBeenCalled()
  })

  it.each(['RUNNING', 'RESUMING'])('restoring %s reports an unconfirmed result without reopening its old interaction', (status) => {
    const data = { ...view(status), uiRequest: { interactionId: 'old', type: 'form', fields: [] } }
    const snapshot = adaptWorkflowSessionViewToSnapshot(data)
    expect(snapshot.turnStatus).toBe('failed')
    expect(snapshot.error).toContain('尚未确认')
    expect(snapshot.messages.flatMap(message => message.blocks).some(block => block.type === 'interaction')).toBe(false)
    expect([...adaptWorkflowSessionViewToEvents(data)].map(event => event.type)).not.toContain('turn.completed')
  })
})
