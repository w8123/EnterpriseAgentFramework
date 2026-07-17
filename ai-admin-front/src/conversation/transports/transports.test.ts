import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { createAgentDebugTransport } from './createAgentDebugTransport'
import { createEmbedTransport } from './createEmbedTransport'
import {
  createWorkflowDraftTransport,
  WORKFLOW_INITIAL_INPUT_ID,
} from './createWorkflowDraftTransport'
import { StreamFallbackForbiddenError } from '../core/streamFallbackPolicy'

vi.mock('@/utils/platformAuth', () => ({
  getPlatformToken: () => 'platform-token',
}))

const createWorkflowDebugSession = vi.fn()
const submitWorkflowDebugSession = vi.fn()
const getWorkflowDebugSession = vi.fn()
const cancelWorkflowDebugSession = vi.fn()

vi.mock('@/api/workflow', () => ({
  createWorkflowDebugSession: (...args: unknown[]) => createWorkflowDebugSession(...args),
  submitWorkflowDebugSession: (...args: unknown[]) => submitWorkflowDebugSession(...args),
  getWorkflowDebugSession: (...args: unknown[]) => getWorkflowDebugSession(...args),
  cancelWorkflowDebugSession: (...args: unknown[]) => cancelWorkflowDebugSession(...args),
}))

function sseResponse(chunks: string[], status = 200) {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  })
  return new Response(stream, {
    status,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

function sessionView(overrides: Record<string, unknown> = {}) {
  return {
    sessionId: 'wf-1',
    status: 'COMPLETED',
    success: true,
    messages: [],
    steps: [],
    ...overrides,
  }
}

describe('createAgentDebugTransport', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    globalThis.fetch = vi.fn()
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('streams agent events through adapter', async () => {
    ;(globalThis.fetch as any).mockResolvedValue(
      sseResponse([
        'event: execution.started\ndata: {}\n\n',
        'event: message.delta\ndata: {"text":"Hi"}\n\n',
        'event: execution.completed\ndata: {"answer":"Hi","sessionId":"sess-1"}\n\n',
      ]),
    )

    let sessionId: string | undefined
    const transport = createAgentDebugTransport({
      agentId: 'agent-1',
      getSessionId: () => sessionId,
      setSessionId: (id) => { sessionId = id },
      fetchImpl: globalThis.fetch,
    })

    const events = []
    for await (const event of transport.startTurn({ message: 'hello' })) {
      events.push(event.type)
    }
    expect(events).toContain('message.delta')
    expect(events).toContain('turn.completed')
    expect(sessionId).toBe('sess-1')
    transport.dispose()
  })
})

describe('createEmbedTransport', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    globalThis.fetch = vi.fn()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    globalThis.fetch = originalFetch
  })

  it('creates session then streams messages', async () => {
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { sessionId: 'emb-1' } }), { status: 200 }))
      .mockResolvedValueOnce(
        sseResponse([
          'event: message.delta\ndata: {"text":"A"}\n\n',
          'event: message.completed\ndata: {"answer":"A"}\n\n',
        ]),
      )

    let sessionId: string | undefined
    const transport = createEmbedTransport({
      apiBase: 'http://localhost/api/embed',
      tokenProvider: () => 'tok',
      getSessionId: () => sessionId,
      setSessionId: (id) => { sessionId = id },
      fetchImpl: globalThis.fetch,
    })

    const types = []
    for await (const event of transport.startTurn({ message: 'hi' })) {
      types.push(event.type)
    }
    expect(sessionId).toBe('emb-1')
    expect(types).toContain('message.delta')
    expect(types).toContain('turn.completed')
    transport.dispose()
  })

  it('dedupes concurrent ensureSession into a single POST /chat/sessions', async () => {
    let resolveCreate: ((value: Response) => void) | undefined
    const createPromise = new Promise<Response>((resolve) => {
      resolveCreate = resolve
    })
    ;(globalThis.fetch as any).mockImplementationOnce(() => createPromise)

    let sessionId: string | undefined
    const transport = createEmbedTransport({
      apiBase: 'http://localhost/api/embed',
      tokenProvider: () => 'tok',
      getSessionId: () => sessionId,
      setSessionId: (id) => { sessionId = id },
      fetchImpl: globalThis.fetch,
    }) as ReturnType<typeof createEmbedTransport> & {
      ensureSession: (signal?: AbortSignal) => Promise<string>
    }

    const p1 = transport.ensureSession()
    const p2 = transport.ensureSession()
    expect(globalThis.fetch).toHaveBeenCalledTimes(1)
    resolveCreate?.(new Response(JSON.stringify({ data: { sessionId: 'emb-once' } }), { status: 200 }))
    await expect(Promise.all([p1, p2])).resolves.toEqual(['emb-once', 'emb-once'])
    expect(sessionId).toBe('emb-once')
    transport.dispose()
  })

  it('does not create a session when idle without ensureSession', async () => {
    let sessionId: string | undefined
    const transport = createEmbedTransport({
      apiBase: 'http://localhost/api/embed',
      tokenProvider: () => 'tok',
      getSessionId: () => sessionId,
      setSessionId: (id) => { sessionId = id },
      fetchImpl: globalThis.fetch,
    })
    await vi.advanceTimersByTimeAsync(2000)
    expect(globalThis.fetch).not.toHaveBeenCalled()
    expect(sessionId).toBeUndefined()
    transport.dispose()
  })

  it('uses JSON messages path when preferStream is false', async () => {
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { sessionId: 'emb-json' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        data: { answer: 'ok', sessionId: 'emb-json' },
      }), { status: 200 }))

    let sessionId: string | undefined
    const transport = createEmbedTransport({
      apiBase: 'http://localhost/api/embed',
      tokenProvider: () => 'tok',
      getSessionId: () => sessionId,
      setSessionId: (id) => { sessionId = id },
      preferStream: false,
      fetchImpl: globalThis.fetch,
    })

    const types: string[] = []
    for await (const event of transport.startTurn({ message: 'hi' })) {
      types.push(event.type)
    }
    expect(types).toContain('turn.completed')
    const urls = (globalThis.fetch as any).mock.calls.map((c: unknown[]) => String(c[0]))
    expect(urls.some((u: string) => u.includes('/messages') && !u.includes('/stream'))).toBe(true)
    transport.dispose()
  })

  it('falls back to JSON only on 404 before consuming stream body', async () => {
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { sessionId: 'emb-fb' } }), { status: 200 }))
      .mockResolvedValueOnce(new Response('not found', { status: 404 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        data: { answer: 'fallback', sessionId: 'emb-fb' },
      }), { status: 200 }))

    let sessionId: string | undefined
    const transport = createEmbedTransport({
      apiBase: 'http://localhost/api/embed',
      tokenProvider: () => 'tok',
      getSessionId: () => sessionId,
      setSessionId: (id) => { sessionId = id },
      fetchImpl: globalThis.fetch,
    })

    const types: string[] = []
    for await (const event of transport.startTurn({ message: 'hi' })) {
      types.push(event.type)
    }
    expect(types).toContain('turn.completed')
    expect(globalThis.fetch).toHaveBeenCalledTimes(3)
    transport.dispose()
  })

  it('does not REST-fallback when interaction stream aborts after events', async () => {
    const abort = new AbortController()
    ;(globalThis.fetch as any).mockResolvedValueOnce(
      sseResponse([
        'event: message.delta\ndata: {"text":"partial"}\n\n',
      ]),
    )

    let sessionId: string | undefined = 'emb-abort'
    const transport = createEmbedTransport({
      apiBase: 'http://localhost/api/embed',
      tokenProvider: () => 'tok',
      getSessionId: () => sessionId,
      setSessionId: (id) => { sessionId = id },
      fetchImpl: globalThis.fetch,
    })

    const resume = transport.resumeInteraction
    expect(resume).toBeTypeOf('function')
    const iter = resume!('ix-1', 'submit', { a: 1 }, abort.signal)[Symbol.asyncIterator]()
    const first = await iter.next()
    expect(first.value?.type).toBe('turn.started')
    abort.abort()
    try {
      while (true) {
        const step = await iter.next()
        if (step.done) break
      }
    } catch {
      // abort path
    }
    const postCalls = (globalThis.fetch as any).mock.calls.filter((c: unknown[]) => {
      const init = c[1] as RequestInit | undefined
      return init?.method === 'POST'
    })
    // only the stream attempt — no JSON submit fallback
    expect(postCalls.length).toBe(1)
    transport.dispose()
  })

  it('stops issuing requests after dispose', async () => {
    let sessionId: string | undefined
    const transport = createEmbedTransport({
      apiBase: 'http://localhost/api/embed',
      tokenProvider: () => 'tok',
      getSessionId: () => sessionId,
      setSessionId: (id) => { sessionId = id },
      fetchImpl: globalThis.fetch,
    }) as ReturnType<typeof createEmbedTransport> & {
      ensureSession: (signal?: AbortSignal) => Promise<string>
    }
    transport.dispose()
    await expect(transport.ensureSession()).rejects.toThrow(/disposed/i)
    expect(globalThis.fetch).not.toHaveBeenCalled()
  })
})

describe('createWorkflowDraftTransport', () => {
  const originalFetch = globalThis.fetch

  beforeEach(() => {
    globalThis.fetch = vi.fn()
    createWorkflowDebugSession.mockReset()
    submitWorkflowDebugSession.mockReset()
    getWorkflowDebugSession.mockReset()
    cancelWorkflowDebugSession.mockReset()
  })

  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('creates on first message and creates again after completed (never submits stale session)', async () => {
    createWorkflowDebugSession
      .mockResolvedValueOnce({ data: sessionView({ sessionId: 'wf-a', status: 'COMPLETED' }) })
      .mockResolvedValueOnce({ data: sessionView({ sessionId: 'wf-b', status: 'COMPLETED' }) })

    let hostSessionId: string | undefined
    const transport = createWorkflowDraftTransport({
      tryStream: false,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
    })

    for await (const _ of transport.startTurn({ message: 'first' })) {
      // drain
    }
    expect(hostSessionId).toBe('wf-a')
    expect(createWorkflowDebugSession).toHaveBeenCalledTimes(1)
    expect(submitWorkflowDebugSession).not.toHaveBeenCalled()

    // Simulate beginDraftDebugTurn clearing host session then sending again
    hostSessionId = undefined
    await transport.clearSession?.()

    for await (const _ of transport.startTurn({ message: 'second' })) {
      // drain
    }
    expect(hostSessionId).toBe('wf-b')
    expect(createWorkflowDebugSession).toHaveBeenCalledTimes(2)
    expect(submitWorkflowDebugSession).not.toHaveBeenCalled()
    transport.dispose()
  })

  it('submits only for interaction resume on existing session', async () => {
    createWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-wait', status: 'WAITING' }),
    })
    submitWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-wait', status: 'COMPLETED' }),
    })

    let hostSessionId: string | undefined
    const transport = createWorkflowDraftTransport({
      tryStream: false,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
    })

    for await (const _ of transport.startTurn({
      interactionId: WORKFLOW_INITIAL_INPUT_ID,
      values: { question: 'q' },
      message: 'q',
    })) {
      // drain
    }
    expect(hostSessionId).toBe('wf-wait')

    for await (const _ of transport.startTurn({
      interactionId: 'ui-1',
      uiSubmit: { action: 'submit', values: { x: 1 } },
      values: { x: 1 },
    })) {
      // drain
    }
    expect(submitWorkflowDebugSession).toHaveBeenCalledTimes(1)
    expect(createWorkflowDebugSession).toHaveBeenCalledTimes(1)
    transport.dispose()
  })

  it('rejects interaction resume without session and never creates', async () => {
    const transport = createWorkflowDraftTransport({
      tryStream: false,
      getSessionId: () => undefined,
      setSessionId: () => {},
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
    })

    await expect(async () => {
      for await (const _ of transport.resumeInteraction!('ix-orphan', 'submit', { a: 1 })) {
        // drain
      }
    }).rejects.toThrow(/requires an active debug session/i)

    expect(createWorkflowDebugSession).not.toHaveBeenCalled()
    expect(submitWorkflowDebugSession).not.toHaveBeenCalled()
    expect(globalThis.fetch).not.toHaveBeenCalled()
    transport.dispose()
  })

  it('rejects resume after clearSession and never creates', async () => {
    createWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-clear', status: 'WAITING' }),
    })
    let hostSessionId: string | undefined
    const transport = createWorkflowDraftTransport({
      tryStream: false,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
    })

    for await (const _ of transport.startTurn({
      interactionId: WORKFLOW_INITIAL_INPUT_ID,
      values: { q: 1 },
    })) {
      // drain
    }
    expect(hostSessionId).toBe('wf-clear')
    await transport.clearSession?.()
    expect(hostSessionId).toBeUndefined()
    createWorkflowDebugSession.mockClear()
    submitWorkflowDebugSession.mockClear()

    await expect(async () => {
      for await (const _ of transport.resumeInteraction!('ix-old', 'submit', {})) {
        // drain
      }
    }).rejects.toThrow(/requires an active debug session/i)
    expect(createWorkflowDebugSession).not.toHaveBeenCalled()
    expect(submitWorkflowDebugSession).not.toHaveBeenCalled()
    transport.dispose()
  })

  it('action=cancel with sessionId submits once and never creates or session-cancels', async () => {
    submitWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-cxl', status: 'CANCELLED', uiRequest: null, currentNodeId: null }),
    })
    let hostSessionId: string | undefined = 'wf-cxl'
    const transport = createWorkflowDraftTransport({
      tryStream: false,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
    })

    const types: string[] = []
    for await (const event of transport.resumeInteraction!('ix-1', 'cancel', {})) {
      types.push(event.type)
    }
    expect(submitWorkflowDebugSession).toHaveBeenCalledTimes(1)
    expect(submitWorkflowDebugSession.mock.calls[0][1]).toMatchObject({ action: 'cancel' })
    expect(createWorkflowDebugSession).not.toHaveBeenCalled()
    expect(cancelWorkflowDebugSession).not.toHaveBeenCalled()
    expect(types).toContain('turn.cancelled')
    expect(types).not.toContain('turn.failed')
    transport.dispose()
  })

  it('WORKFLOW_INITIAL_INPUT_ID still creates a new session', async () => {
    createWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-init', status: 'WAITING' }),
    })
    let hostSessionId: string | undefined
    const transport = createWorkflowDraftTransport({
      tryStream: false,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
    })
    for await (const _ of transport.startTurn({
      interactionId: WORKFLOW_INITIAL_INPUT_ID,
      values: { foo: 'bar' },
    })) {
      // drain
    }
    expect(createWorkflowDebugSession).toHaveBeenCalledTimes(1)
    expect(submitWorkflowDebugSession).not.toHaveBeenCalled()
    expect(hostSessionId).toBe('wf-init')
    transport.dispose()
  })

  it('creates a new session after failed/cancelled free-text turns', async () => {
    createWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-new', status: 'COMPLETED' }),
    })

    let hostSessionId: string | undefined = 'wf-fail'
    const transport = createWorkflowDraftTransport({
      tryStream: false,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
    })

    for await (const _ of transport.startTurn({ message: 'retry after fail' })) {
      // drain
    }
    expect(createWorkflowDebugSession).toHaveBeenCalledTimes(1)
    expect(submitWorkflowDebugSession).not.toHaveBeenCalled()
    expect(hostSessionId).toBe('wf-new')
    transport.dispose()
  })

  it('honors setSessionId(undefined) via clearSession', async () => {
    let hostSessionId: string | undefined = 'stale'
    const transport = createWorkflowDraftTransport({
      tryStream: false,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
    })
    await transport.clearSession?.()
    expect(hostSessionId).toBeUndefined()
    transport.dispose()
  })

  it('falls back to REST on 404 SSE unsupported without prior events', async () => {
    ;(globalThis.fetch as any).mockResolvedValueOnce(new Response('missing', { status: 404 }))
    createWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-rest', status: 'COMPLETED' }),
    })

    let hostSessionId: string | undefined
    const transport = createWorkflowDraftTransport({
      tryStream: true,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
      fetchImpl: globalThis.fetch,
    })

    for await (const _ of transport.startTurn({ message: 'hello' })) {
      // drain
    }
    expect(createWorkflowDebugSession).toHaveBeenCalledTimes(1)
    transport.dispose()
  })

  it('forbids REST fallback after SSE body/events were consumed', async () => {
    const encoder = new TextEncoder()
    let pulls = 0
    const brokenStream = new ReadableStream<Uint8Array>({
      pull(controller) {
        pulls += 1
        if (pulls === 1) {
          controller.enqueue(encoder.encode('event: turn.failed\ndata: {"message":"cut"}\n\n'))
          return
        }
        controller.error(new TypeError('stream cut after events'))
      },
    })
    ;(globalThis.fetch as any).mockResolvedValueOnce(new Response(brokenStream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }))

    let hostSessionId: string | undefined
    const transport = createWorkflowDraftTransport({
      tryStream: true,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
      fetchImpl: globalThis.fetch,
    })

    let caught: unknown
    try {
      for await (const _ of transport.startTurn({ message: 'hello' })) {
        // drain until failure
      }
    } catch (error) {
      caught = error
    }
    expect(caught).toBeInstanceOf(StreamFallbackForbiddenError)
    expect(createWorkflowDebugSession).not.toHaveBeenCalled()
    transport.dispose()
  })

  it('SSE turn.cancelled maps to cancelled without failed; REST CANCELLED matches', async () => {
    const encoder = new TextEncoder()
    ;(globalThis.fetch as any).mockResolvedValueOnce(new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode(
          'event: turn.started\ndata: {"phase":"submit"}\n\n'
          + 'event: turn.cancelled\ndata: {"sessionId":"wf-c","status":"CANCELLED","answer":"","message":"debug session cancelled"}\n\n'
          + 'event: session.completed\ndata: {"sessionId":"wf-c","status":"CANCELLED"}\n\n',
        ))
        controller.close()
      },
    }), {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }))
    getWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-c', status: 'CANCELLED', uiRequest: null, currentNodeId: null }),
    })

    let hostSessionId: string | undefined = 'wf-c'
    const transport = createWorkflowDraftTransport({
      tryStream: true,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
      fetchImpl: globalThis.fetch,
    })

    const sseTypes: string[] = []
    const sseResume = transport.resumeInteraction
    expect(sseResume).toBeTypeOf('function')
    for await (const event of sseResume!('ix-1', 'cancel', {})) {
      sseTypes.push(event.type)
    }
    expect(sseTypes).toContain('turn.cancelled')
    expect(sseTypes).not.toContain('turn.failed')

    submitWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-c2', status: 'CANCELLED', uiRequest: null, currentNodeId: null }),
    })
    const restTransport = createWorkflowDraftTransport({
      tryStream: false,
      getSessionId: () => 'wf-c2',
      setSessionId: () => {},
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
    })
    const restTypes: string[] = []
    const restResume = restTransport.resumeInteraction
    expect(restResume).toBeTypeOf('function')
    for await (const event of restResume!('ix-1', 'cancel', {})) {
      restTypes.push(event.type)
    }
    expect(restTypes).toContain('turn.cancelled')
    expect(restTypes).not.toContain('turn.failed')
    transport.dispose()
    restTransport.dispose()
  })

  it('safe final output sequence produces message.delta once (no hellohello)', async () => {
    const encoder = new TextEncoder()
    ;(globalThis.fetch as any).mockResolvedValueOnce(new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode(
          'event: turn.started\ndata: {"phase":"create"}\n\n'
          + 'event: node.output.delta\ndata: {"nodeId":"llm-final","text":"hello","publicUserOutput":true}\n\n'
          + 'event: message.delta\ndata: {"text":"hello"}\n\n'
          + 'event: turn.completed\ndata: {"sessionId":"wf-hello","status":"SUCCESS","answer":"hello"}\n\n'
          + 'event: session.completed\ndata: {"sessionId":"wf-hello","status":"SUCCESS","answer":"hello"}\n\n',
        ))
        controller.close()
      },
    }), {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }))
    getWorkflowDebugSession.mockResolvedValueOnce({
      data: sessionView({ sessionId: 'wf-hello', status: 'SUCCESS', answer: 'hello' }),
    })

    let hostSessionId: string | undefined
    const transport = createWorkflowDraftTransport({
      tryStream: true,
      getSessionId: () => hostSessionId,
      setSessionId: (id) => { hostSessionId = id },
      getCreateRequest: () => ({
        targetType: 'WORKFLOW_DRAFT',
        draftDefinition: { nodes: [], edges: [] },
      }),
      fetchImpl: globalThis.fetch,
    })

    const types: string[] = []
    const texts: string[] = []
    for await (const event of transport.startTurn({ message: 'q' })) {
      types.push(event.type)
      if (event.type === 'message.delta') {
        texts.push(String((event.data as { text?: string })?.text || ''))
      }
    }
    expect(types.filter((t) => t === 'debug.workflow.node.delta')).toHaveLength(1)
    expect(types.filter((t) => t === 'message.delta')).toHaveLength(1)
    expect(types.filter((t) => t === 'turn.completed')).toHaveLength(1)
    expect(texts.join('')).toBe('hello')
    expect(texts.join('')).not.toBe('hellohello')
    transport.dispose()
  })
})
