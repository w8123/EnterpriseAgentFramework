import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { createEafChat } from './eafChat'
import { createEafPageBridge } from './eafPageBridge'

function sse(chunks: string[]) {
  const encoder = new TextEncoder()
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  }), {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

function sessionResponse(sessionId = 'sess-1') {
  return new Response(JSON.stringify({ data: { sessionId } }), { status: 200 })
}

async function flushMicrotasks(count = 20) {
  for (let index = 0; index < count; index += 1) await Promise.resolve()
}

describe('createEafChat facade', () => {
  const originalFetch = globalThis.fetch
  let mountEl: HTMLElement

  beforeEach(() => {
    vi.useFakeTimers()
    mountEl = document.createElement('div')
    document.body.appendChild(mountEl)
    globalThis.fetch = vi.fn()
  })

  afterEach(() => {
    vi.useRealTimers()
    mountEl.remove()
    globalThis.fetch = originalFetch
  })

  it('mounts the launcher before the initial tokenProvider settles', async () => {
    let resolveToken: ((token: string) => void) | undefined
    const tokenProvider = vi.fn(() => new Promise<string>((resolve) => {
      resolveToken = resolve
    }))

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider,
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      position: 'bottom-right',
      initialOpen: false,
    })

    await flushMicrotasks()
    const root = mountEl.querySelector('.eaf-chat') as HTMLElement
    const status = root.querySelector('.eaf-chat__connection-status') as HTMLElement
    expect(root).toBeTruthy()
    expect(root.dataset.authState).toBe('loading')
    expect(status.hidden).toBe(false)
    expect(globalThis.fetch).not.toHaveBeenCalled()

    resolveToken?.('tok')
    await flushMicrotasks()
    expect(root.dataset.authState).toBe('ready')
    expect(status.hidden).toBe(true)
    chat.destroy()
  })

  it.each([
    ['empty token', () => '', 'TOKEN_PROVIDER_EMPTY'],
    ['broker 401', () => Promise.reject(Object.assign(new Error('broker unauthorized'), { status: 401 })), 'TOKEN_PROVIDER_UNAUTHORIZED'],
    ['broker 502', () => Promise.reject(Object.assign(new Error('bad gateway'), { status: 502 })), 'TOKEN_PROVIDER_UNAVAILABLE'],
    ['network failure', () => Promise.reject(new TypeError('Failed to fetch')), 'TOKEN_PROVIDER_NETWORK_ERROR'],
  ])('keeps the launcher visible and recovers after %s', async (_label, firstResult, expectedCode) => {
    const errors: string[] = []
    const provider = vi.fn()
      .mockImplementationOnce(firstResult)
      .mockResolvedValueOnce('tok-recovered')

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: provider,
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      onError: (error) => errors.push(error.code || ''),
    })
    await flushMicrotasks()

    const root = mountEl.querySelector('.eaf-chat') as HTMLElement
    expect(root).toBeTruthy()
    expect(root.dataset.authState).toBe('error')
    expect(errors).toContain(expectedCode)
    const retry = root.querySelector<HTMLButtonElement>('.eaf-chat-auth button')!
    expect(retry).toBeTruthy()
    retry.click()
    await flushMicrotasks()

    expect(provider).toHaveBeenCalledTimes(2)
    expect(root.dataset.authState).toBe('ready')
    expect(root.querySelector('.eaf-chat-auth')).toBeNull()
    chat.destroy()
  })

  it('times out tokenProvider without hiding the launcher', async () => {
    const errors: string[] = []
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => new Promise<string>(() => undefined),
      tokenTimeoutMs: 100,
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      onError: (error) => errors.push(error.code || ''),
    })
    await flushMicrotasks()

    const root = mountEl.querySelector('.eaf-chat') as HTMLElement
    expect(root.dataset.authState).toBe('loading')
    await vi.advanceTimersByTimeAsync(101)
    await flushMicrotasks()
    expect(root.dataset.authState).toBe('error')
    expect(errors).toContain('TOKEN_PROVIDER_TIMEOUT')
    chat.destroy()
  })

  it('refreshes a near-expiry token before creating the first session', async () => {
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(sessionResponse('sess-expiring'))
      .mockResolvedValueOnce(sse([
        'event: message.delta\ndata: {"text":"ok"}\n\n',
        'event: message.completed\ndata: {"sessionId":"sess-expiring","answer":"ok"}\n\n',
      ]))
    const reasons: string[] = []
    const provider = vi.fn((context?: { reason?: string }) => {
      reasons.push(context?.reason || '')
      return reasons.length === 1
        ? { token: 'tok-near-expiry', expiresIn: 10 }
        : { token: 'tok-fresh', expiresIn: 600 }
    })
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: provider,
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
    })
    await flushMicrotasks()

    await chat.send('hello')

    expect(reasons).toEqual(['initial', 'expiring'])
    const sessionInit = (globalThis.fetch as any).mock.calls[0][1] as RequestInit
    expect((sessionInit.headers as Record<string, string>).Authorization).toBe('Bearer tok-fresh')
    chat.destroy()
  })

  it('does not create session while idle for 2 seconds', async () => {
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
    })
    await vi.advanceTimersByTimeAsync(2000)
    expect(globalThis.fetch).not.toHaveBeenCalled()
    chat.destroy()
  })

  it('chat.send executes metadata.pageActionQueue exactly once', async () => {
    const events: Array<{ type: string; data?: unknown }> = []
    let bridgeCalls = 0
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(sessionResponse('sess-1'))
      .mockResolvedValueOnce(sse([
        'event: message.delta\ndata: {"text":"Hi"}\n\n',
        'event: message.completed\ndata: {"sessionId":"sess-1","answer":"Hi","intentType":"CHAT","metadata":{"pageActionQueue":[{"type":"page.action.requested","requestId":"pa-1","actionKey":"refresh"}]},"uiRequest":null}\n\n',
      ]))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { ok: true } }), { status: 200 }))

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => {
      bridgeCalls += 1
      return { status: 'SUCCESS', data: { refreshed: true } }
    }, { title: 'Refresh' })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      stream: true,
      onEvent: (event) => events.push({ type: event.type, data: event.data }),
    })

    const response = await chat.send('hello')
    expect(response.sessionId).toBe('sess-1')
    expect(response.answer).toBe('Hi')
    expect(response.metadata).toEqual({
      pageActionQueue: [{ type: 'page.action.requested', requestId: 'pa-1', actionKey: 'refresh' }],
    })
    expect((response.metadata as any)?.metadata).toBeUndefined()

    expect(events.filter((e) => e.type === 'page.action.requested')).toHaveLength(1)
    expect(bridgeCalls).toBe(1)
    const resultPosts = (globalThis.fetch as any).mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/page-actions/pa-1/result'),
    )
    expect(resultPosts).toHaveLength(1)
    expect(String(resultPosts[0][0])).toContain('/chat/sessions/sess-1/')
    chat.destroy()
  })

  it('UI composer send executes pageActionQueue without calling chat.send', async () => {
    const events: Array<{ type: string }> = []
    let bridgeCalls = 0
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(sessionResponse('sess-ui-pa'))
      .mockResolvedValueOnce(sse([
        'event: message.delta\ndata: {"text":"UI"}\n\n',
        'event: message.completed\ndata: {"sessionId":"sess-ui-pa","answer":"UI","metadata":{"pageActionQueue":[{"type":"page.action.requested","requestId":"pa-ui","actionKey":"refresh"}]}}\n\n',
      ]))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { ok: true } }), { status: 200 }))

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => {
      bridgeCalls += 1
      return { status: 'SUCCESS', data: {} }
    }, { title: 'Refresh' })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      onEvent: (event) => events.push({ type: event.type }),
    })

    const sendSpy = vi.spyOn(chat, 'send')
    const textarea = mountEl.querySelector('textarea') as HTMLTextAreaElement
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set
    setter?.call(textarea, 'from-ui')
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    const form = mountEl.querySelector('form.reachai-composer') as HTMLFormElement
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await vi.advanceTimersByTimeAsync(100)
    for (let i = 0; i < 20; i += 1) await Promise.resolve()
    await vi.advanceTimersByTimeAsync(50)

    expect(sendSpy).not.toHaveBeenCalled()
    expect(events.filter((e) => e.type === 'message.completed')).toHaveLength(1)
    expect(events.filter((e) => e.type === 'page.action.requested')).toHaveLength(1)
    expect(bridgeCalls).toBe(1)
    const resultPosts = (globalThis.fetch as any).mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/page-actions/pa-ui/result'),
    )
    expect(resultPosts).toHaveLength(1)
    sendSpy.mockRestore()
    chat.destroy()
  })

  it('wires the visible conversation retry button to the failed UI message', async () => {
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(new Response(null, { status: 502 }))
      .mockResolvedValueOnce(sessionResponse('sess-retry-ui'))
      .mockResolvedValueOnce(sse([
        'event: message.delta\ndata: {"text":"Recovered"}\n\n',
        'event: message.completed\ndata: {"sessionId":"sess-retry-ui","answer":"Recovered"}\n\n',
      ]))

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
    })
    await flushMicrotasks()

    const textarea = mountEl.querySelector('textarea') as HTMLTextAreaElement
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set
    setter?.call(textarea, 'retry-me')
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    const form = mountEl.querySelector('form.reachai-composer') as HTMLFormElement
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushMicrotasks(40)

    const retry = mountEl.querySelector<HTMLButtonElement>('.reachai-conversation__error button')!
    expect(retry).toBeTruthy()
    retry.click()
    await flushMicrotasks(50)

    expect(globalThis.fetch).toHaveBeenCalledTimes(3)
    expect(mountEl.querySelector('.reachai-conversation__error')).toBeNull()
    expect(mountEl.textContent).toContain('Recovered')
    chat.destroy()
  })

  it('completion and pending poll share requestId exactly-once semantics', async () => {
    const events: string[] = []
    let bridgeCalls = 0
    const pageAction = {
      type: 'page.action.requested',
      requestId: 'pa-race',
      actionKey: 'refresh',
    }
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        return sessionResponse('sess-race')
      }
      if (path.includes('/messages/stream')) {
        return sse([
          `event: message.completed\ndata: ${JSON.stringify({
            sessionId: 'sess-race',
            answer: 'ok',
            metadata: { pageActionQueue: [pageAction] },
          })}\n\n`,
        ])
      }
      if (path.includes('/page-actions/pending')) {
        return new Response(JSON.stringify({ data: [pageAction] }), { status: 200 })
      }
      if (path.includes('/page-actions/pa-race/result')) {
        return new Response(JSON.stringify({ data: { ok: true } }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => {
      bridgeCalls += 1
      await new Promise((r) => setTimeout(r, 40))
      return { status: 'SUCCESS', data: {} }
    }, { title: 'Refresh' })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      onEvent: (event) => events.push(event.type),
    })

    const sendPromise = chat.send('race')
    await vi.advanceTimersByTimeAsync(500)
    for (let i = 0; i < 30; i += 1) await Promise.resolve()
    await sendPromise
    await vi.advanceTimersByTimeAsync(600)
    for (let i = 0; i < 20; i += 1) await Promise.resolve()

    expect(events.filter((t) => t === 'page.action.requested')).toHaveLength(1)
    expect(bridgeCalls).toBe(1)
    const resultPosts = (globalThis.fetch as any).mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/page-actions/pa-race/result'),
    )
    expect(resultPosts).toHaveLength(1)
    chat.destroy()
  })

  it('page action failure reports onError without unhandled rejection', async () => {
    const errors: string[] = []
    const unhandled: unknown[] = []
    const onUnhandled = (event: PromiseRejectionEvent) => {
      unhandled.push(event.reason)
      event.preventDefault()
    }
    window.addEventListener('unhandledrejection', onUnhandled)

    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(sessionResponse('sess-err'))
      .mockResolvedValueOnce(sse([
        'event: message.completed\ndata: {"sessionId":"sess-err","answer":"x","metadata":{"pageActionQueue":[{"type":"page.action.requested","requestId":"pa-err","actionKey":"boom"}]}}\n\n',
      ]))
      .mockResolvedValueOnce(new Response(JSON.stringify({ data: { ok: true } }), { status: 200 }))

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('boom', async () => {
      throw new Error('bridge exploded')
    }, { title: 'Boom' })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      onError: (error) => errors.push(error.message),
    })

    await chat.send('err')
    await vi.advanceTimersByTimeAsync(50)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()

    expect(errors.some((m) => m.includes('bridge exploded'))).toBe(true)
    expect(unhandled).toHaveLength(0)
    window.removeEventListener('unhandledrejection', onUnhandled)
    chat.destroy()
  })

  it('UI composer send and chat.send share the same full public event sequence', async () => {
    type PublicEvent = { type: string; data?: unknown }
    const uiRequest = {
      schemaVersion: '1.0',
      interactionId: 'ix-full',
      component: 'confirm',
      message: '确认？',
    }
    const pageAction = {
      type: 'page.action.requested',
      requestId: 'pa-full',
      actionKey: 'refresh',
    }

    function normalizeSequence(events: PublicEvent[]) {
      return events.map((event) => {
        if (event.type === 'message.delta') {
          return { type: 'message.delta', text: String((event.data as { text?: string })?.text || '') }
        }
        if (event.type === 'ui.requested') {
          const ui = (event.data as { uiRequest?: { interactionId?: string } })?.uiRequest
          return { type: 'ui.requested', interactionId: ui?.interactionId }
        }
        if (event.type === 'page.action.requested') {
          const data = event.data as { requestId?: string; actionKey?: string }
          return {
            type: 'page.action.requested',
            requestId: data.requestId,
            actionKey: data.actionKey,
          }
        }
        if (event.type === 'message.completed') {
          const data = event.data as { answer?: string }
          return { type: 'message.completed', answer: data.answer }
        }
        return { type: event.type }
      })
    }

    function mockFullScenario(sessionId: string) {
      ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
        const path = String(url)
        if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
          return sessionResponse(sessionId)
        }
        if (path.includes('/messages/stream')) {
          return sse([
            'event: message.delta\ndata: {"text":"请确认"}\n\n',
            `event: ui.requested\ndata: ${JSON.stringify({ uiRequest })}\n\n`,
            `event: message.completed\ndata: ${JSON.stringify({
              sessionId,
              answer: '请确认',
              metadata: { pageActionQueue: [pageAction] },
            })}\n\n`,
          ])
        }
        if (path.includes('/page-actions/pending')) {
          return new Response(JSON.stringify({ data: [] }), { status: 200 })
        }
        if (path.includes('/page-actions/pa-full/result')) {
          return new Response(JSON.stringify({ data: { ok: true } }), { status: 200 })
        }
        return new Response(JSON.stringify({ data: {} }), { status: 200 })
      })
    }

    const expected = [
      { type: 'message.delta', text: '请确认' },
      { type: 'ui.requested', interactionId: 'ix-full' },
      { type: 'page.action.requested', requestId: 'pa-full', actionKey: 'refresh' },
      { type: 'message.completed', answer: '请确认' },
    ]

    const apiEvents: PublicEvent[] = []
    mockFullScenario('sess-full-api')
    const bridgeApi = createEafPageBridge({ route: '/' })
    bridgeApi.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    const chatApi = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: bridgeApi,
      onEvent: (event) => apiEvents.push({ type: event.type, data: event.data }),
    })
    await chatApi.send('from-api')
    const apiSequence = normalizeSequence(apiEvents)
    expect(apiSequence).toEqual(expected)
    expect(apiSequence.filter((e) => e.type === 'page.action.requested')).toHaveLength(1)
    expect(apiSequence.filter((e) => e.type === 'message.completed')).toHaveLength(1)
    const paIdx = apiSequence.findIndex((e) => e.type === 'page.action.requested')
    const doneIdx = apiSequence.findIndex((e) => e.type === 'message.completed')
    expect(paIdx).toBeGreaterThan(-1)
    expect(paIdx).toBeLessThan(doneIdx)
    chatApi.destroy()

    const uiEvents: PublicEvent[] = []
    const uiMount = document.createElement('div')
    document.body.appendChild(uiMount)
    mockFullScenario('sess-full-ui')
    const bridgeUi = createEafPageBridge({ route: '/' })
    bridgeUi.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    const chatUi = await createEafChat({
      agentId: 'agent-1',
      mount: uiMount,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: bridgeUi,
      onEvent: (event) => uiEvents.push({ type: event.type, data: event.data }),
    })
    const textarea = uiMount.querySelector('textarea') as HTMLTextAreaElement
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set
    setter?.call(textarea, 'from-ui')
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await Promise.resolve()
    const form = uiMount.querySelector('form.reachai-composer') as HTMLFormElement
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await vi.advanceTimersByTimeAsync(100)
    for (let i = 0; i < 30; i += 1) await Promise.resolve()

    const uiSequence = normalizeSequence(uiEvents)
    expect(uiSequence).toEqual(expected)
    expect(uiSequence).toEqual(apiSequence)

    chatUi.destroy()
    uiMount.remove()
  })

  it('same requestId from SSE, completion queue and pending poll fires once', async () => {
    const events: string[] = []
    let bridgeCalls = 0
    let bridgeTriggeredBy: string | null = null
    const pageAction = {
      type: 'page.action.requested',
      requestId: 'pa-triple',
      actionKey: 'refresh',
    }
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        return sessionResponse('sess-triple')
      }
      if (path.includes('/messages/stream')) {
        return sse([
          `event: page.action.requested\ndata: ${JSON.stringify(pageAction)}\n\n`,
          `event: message.completed\ndata: ${JSON.stringify({
            sessionId: 'sess-triple',
            answer: 'ok',
            metadata: { pageActionQueue: [pageAction] },
          })}\n\n`,
        ])
      }
      if (path.includes('/page-actions/pending')) {
        return new Response(JSON.stringify({ data: [pageAction] }), { status: 200 })
      }
      if (path.includes('/page-actions/pa-triple/result')) {
        return new Response(JSON.stringify({ data: { ok: true } }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => {
      bridgeCalls += 1
      if (!bridgeTriggeredBy) bridgeTriggeredBy = 'first-bridge-call'
      await new Promise((r) => setTimeout(r, 20))
      return { status: 'SUCCESS', data: {} }
    }, { title: 'Refresh' })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      onEvent: (event) => events.push(event.type),
    })
    await chat.send('triple')
    await vi.advanceTimersByTimeAsync(600)
    for (let i = 0; i < 20; i += 1) await Promise.resolve()

    expect(events.filter((t) => t === 'page.action.requested')).toHaveLength(1)
    expect(bridgeCalls).toBe(1)
    expect(bridgeTriggeredBy).toBe('first-bridge-call')
    const resultPosts = (globalThis.fetch as any).mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/page-actions/pa-triple/result'),
    )
    expect(resultPosts).toHaveLength(1)
    const paIdx = events.indexOf('page.action.requested')
    const doneIdx = events.indexOf('message.completed')
    expect(paIdx).toBeGreaterThan(-1)
    expect(paIdx).toBeLessThan(doneIdx)
    chat.destroy()
  })

  it('SSE-only page.action executes Bridge without completion queue or pending', async () => {
    const events: string[] = []
    let bridgeCalls = 0
    const pageAction = {
      type: 'page.action.requested',
      requestId: 'pa-sse-only',
      actionKey: 'refresh',
    }
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        return sessionResponse('sess-sse-only')
      }
      if (path.includes('/messages/stream')) {
        return sse([
          'event: message.delta\ndata: {"text":"正在刷新"}\n\n',
          `event: page.action.requested\ndata: ${JSON.stringify(pageAction)}\n\n`,
          'event: message.completed\ndata: {"sessionId":"sess-sse-only","answer":"已刷新","metadata":{}}\n\n',
        ])
      }
      if (path.includes('/page-actions/pending')) {
        return new Response(JSON.stringify({ data: [] }), { status: 200 })
      }
      if (path.includes('/page-actions/pa-sse-only/result')) {
        return new Response(JSON.stringify({ data: { ok: true } }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => {
      bridgeCalls += 1
      return { status: 'SUCCESS', data: { refreshed: true } }
    }, { title: 'Refresh' })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      onEvent: (event) => events.push(event.type),
    })
    await chat.send('sse-only')
    for (let i = 0; i < 15; i += 1) await Promise.resolve()

    expect(events.filter((t) => t === 'page.action.requested')).toHaveLength(1)
    expect(bridgeCalls).toBe(1)
    const resultPosts = (globalThis.fetch as any).mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/page-actions/pa-sse-only/result'),
    )
    expect(resultPosts).toHaveLength(1)
    expect(events.indexOf('page.action.requested')).toBeLessThan(events.indexOf('message.completed'))
    expect(events).toContain('message.delta')
    chat.destroy()
  })

  it('completion-only pageActionQueue executes without independent SSE page.action', async () => {
    const events: string[] = []
    let bridgeCalls = 0
    const pageAction = {
      type: 'page.action.requested',
      requestId: 'pa-completion-only',
      actionKey: 'refresh',
    }
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        return sessionResponse('sess-co')
      }
      if (path.includes('/messages/stream')) {
        return sse([
          'event: message.delta\ndata: {"text":"ok"}\n\n',
          `event: message.completed\ndata: ${JSON.stringify({
            sessionId: 'sess-co',
            answer: 'ok',
            metadata: { pageActionQueue: [pageAction] },
          })}\n\n`,
        ])
      }
      if (path.includes('/page-actions/pending')) {
        return new Response(JSON.stringify({ data: [] }), { status: 200 })
      }
      if (path.includes('/page-actions/pa-completion-only/result')) {
        return new Response(JSON.stringify({ data: { ok: true } }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => {
      bridgeCalls += 1
      return { status: 'SUCCESS', data: {} }
    }, { title: 'Refresh' })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      onEvent: (event) => events.push(event.type),
    })
    await chat.send('completion-only')
    for (let i = 0; i < 15; i += 1) await Promise.resolve()

    expect(events.filter((t) => t === 'page.action.requested')).toHaveLength(1)
    expect(bridgeCalls).toBe(1)
    expect(events.indexOf('page.action.requested')).toBeLessThan(events.indexOf('message.completed'))
    expect((globalThis.fetch as any).mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/page-actions/pa-completion-only/result'),
    )).toHaveLength(1)
    chat.destroy()
  })

  it('pending-only page action executes without SSE or completion queue', async () => {
    const events: string[] = []
    let bridgeCalls = 0
    const pageAction = {
      type: 'page.action.requested',
      requestId: 'pa-pending-only',
      actionKey: 'refresh',
    }
    let sessionCreated = 0
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        sessionCreated += 1
        return sessionResponse('sess-pending')
      }
      if (path.includes('/messages/stream')) {
        return sse([
          'event: message.delta\ndata: {"text":"hi"}\n\n',
          'event: message.completed\ndata: {"sessionId":"sess-pending","answer":"hi","metadata":{}}\n\n',
        ])
      }
      if (path.includes('/page-actions/pending')) {
        return new Response(JSON.stringify({ data: [pageAction] }), { status: 200 })
      }
      if (path.includes('/page-actions/pa-pending-only/result')) {
        return new Response(JSON.stringify({ data: { ok: true } }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => {
      bridgeCalls += 1
      return { status: 'SUCCESS', data: {} }
    }, { title: 'Refresh' })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      onEvent: (event) => events.push(event.type),
    })
    await chat.send('pending-only')
    const createsAfterSend = sessionCreated
    await vi.advanceTimersByTimeAsync(600)
    for (let i = 0; i < 20; i += 1) await Promise.resolve()

    expect(events.filter((t) => t === 'page.action.requested')).toHaveLength(1)
    expect(bridgeCalls).toBe(1)
    expect(sessionCreated).toBe(createsAfterSend)
    expect((globalThis.fetch as any).mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/page-actions/pa-pending-only/result'),
    )).toHaveLength(1)
    chat.destroy()
  })

  it('SSE page.action without sessionId reports onError and does not create session or call Bridge', async () => {
    const errors: string[] = []
    let bridgeCalls = 0
    let sessionCreates = 0
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      if (String(url).includes('/chat/sessions') && !String(url).includes('/messages') && !String(url).includes('/page-actions')) {
        sessionCreates += 1
        return sessionResponse('should-not')
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => {
      bridgeCalls += 1
      return { status: 'SUCCESS', data: {} }
    }, { title: 'Refresh' })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      onError: (error) => errors.push(error.message),
    }) as Awaited<ReturnType<typeof createEafChat>> & {
      __emitPublicEventForTests?: (event: { type: string; data: unknown; sessionId?: string }) => void
    }

    expect(chat.__emitPublicEventForTests).toBeTypeOf('function')
    chat.__emitPublicEventForTests?.({
      type: 'page.action.requested',
      data: {
        type: 'page.action.requested',
        requestId: 'pa-no-sess',
        actionKey: 'refresh',
      },
      // 故意不提供 sessionId，且尚未创建会话
    })
    for (let i = 0; i < 10; i += 1) await Promise.resolve()

    expect(errors.some((m) => m.includes('active embed session'))).toBe(true)
    expect(bridgeCalls).toBe(0)
    expect(sessionCreates).toBe(0)
    expect((globalThis.fetch as any).mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/page-actions/'),
    )).toHaveLength(0)
    chat.destroy()
  })

  it('emits two message.completed for two turns with identical answers', async () => {
    const completed: Array<{ answer?: string; turnId?: string }> = []
    let streamRounds = 0
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        return sessionResponse('sess-dup')
      }
      if (path.includes('/messages/stream')) {
        streamRounds += 1
        return sse([
          'event: message.delta\ndata: {"text":"操作成功"}\n\n',
          'event: message.completed\ndata: {"sessionId":"sess-dup","answer":"操作成功","metadata":{}}\n\n',
        ])
      }
      if (path.includes('/page-actions/pending')) {
        return new Response(JSON.stringify({ data: [] }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      onEvent: (event) => {
        if (event.type === 'message.completed') {
          const data = event.data as { answer?: string; turnId?: string }
          completed.push({ answer: data.answer, turnId: data.turnId })
        }
      },
    })

    const first = await chat.send('first')
    const second = await chat.send('second')
    expect(streamRounds).toBe(2)
    expect(first.answer).toBe('操作成功')
    expect(second.answer).toBe('操作成功')
    expect(completed).toHaveLength(2)
    expect(completed[0].answer).toBe('操作成功')
    expect(completed[1].answer).toBe('操作成功')
    expect(completed[0].turnId).toBeTruthy()
    expect(completed[1].turnId).toBeTruthy()
    expect(completed[0].turnId).not.toBe(completed[1].turnId)
    chat.destroy()
  })

  it('keeps two identical message.delta fragments in one turn', async () => {
    const deltas: string[] = []
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(sessionResponse('sess-delta'))
      .mockResolvedValueOnce(sse([
        'event: message.delta\ndata: {"text":"同"}\n\n',
        'event: message.delta\ndata: {"text":"同"}\n\n',
        'event: message.completed\ndata: {"sessionId":"sess-delta","answer":"同同","metadata":{}}\n\n',
      ]))

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      onEvent: (event) => {
        if (event.type === 'message.delta') {
          deltas.push(String((event.data as { text?: string }).text || ''))
        }
      },
    })
    await chat.send('d')
    expect(deltas).toEqual(['同', '同'])
    chat.destroy()
  })

  it('interaction cancel posts action=cancel without session cancel; card becomes cancelled', async () => {
    const fetchMock = globalThis.fetch as any
    fetchMock
      .mockResolvedValueOnce(sessionResponse('sess-c'))
      .mockResolvedValueOnce(sse([
        'event: ui.requested\ndata: {"uiRequest":{"schemaVersion":"1.0","interactionId":"ix-c","component":"confirm","message":"?"}}\n\n',
        'event: message.completed\ndata: {"sessionId":"sess-c","answer":"need","uiRequest":{"schemaVersion":"1.0","interactionId":"ix-c","component":"confirm","message":"?"},"metadata":{}}\n\n',
      ]))

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
    })
    await chat.send('need confirm')

    let abortSeen = false
    let cancelPosts = 0
    fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
      const path = String(url)
      if (init?.signal) {
        init.signal.addEventListener('abort', () => { abortSeen = true })
      }
      if (path.includes('/interactions/') && path.includes('/submit')) {
        cancelPosts += 1
        const body = JSON.parse(String(init?.body || '{}'))
        expect(body.action).toBe('cancel')
        await new Promise((r) => setTimeout(r, 30))
        expect(abortSeen).toBe(false)
        if (path.includes('/stream')) {
          return sse([
            'event: message.completed\ndata: {"sessionId":"sess-c","answer":"cancelled","metadata":{}}\n\n',
          ])
        }
        return new Response(JSON.stringify({
          data: { sessionId: 'sess-c', answer: 'cancelled', metadata: {} },
        }), { status: 200 })
      }
      if (path.includes('/cancel') || (init?.method === 'DELETE' && path.includes('/sessions/'))) {
        throw new Error('session cancel must not be called for interaction cancel')
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const cancelBtn = Array.from(mountEl.querySelectorAll('button')).find((b) =>
      (b.textContent || '').includes('取消'),
    ) as HTMLButtonElement
    expect(cancelBtn).toBeTruthy()
    cancelBtn.click()
    await vi.advanceTimersByTimeAsync(80)
    for (let i = 0; i < 15; i += 1) await Promise.resolve()

    expect(abortSeen).toBe(false)
    expect(cancelPosts).toBe(1)
    const card = mountEl.querySelector('.reachai-interaction--cancelled')
    expect(card).toBeTruthy()

    chat.destroy()
  })

  it('interaction cancel failure marks card failed', async () => {
    const fetchMock = globalThis.fetch as any
    fetchMock
      .mockResolvedValueOnce(sessionResponse('sess-cf'))
      .mockResolvedValueOnce(sse([
        'event: ui.requested\ndata: {"uiRequest":{"schemaVersion":"1.0","interactionId":"ix-cf","component":"confirm","message":"?"}}\n\n',
        'event: message.completed\ndata: {"sessionId":"sess-cf","answer":"need","uiRequest":{"schemaVersion":"1.0","interactionId":"ix-cf","component":"confirm","message":"?"},"metadata":{}}\n\n',
      ]))

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
    })
    await chat.send('need confirm')

    fetchMock.mockImplementation(async (url: string) => {
      if (String(url).includes('/interactions/') && String(url).includes('/submit')) {
        return new Response(JSON.stringify({ message: 'reject cancel' }), { status: 500 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const cancelBtn = Array.from(mountEl.querySelectorAll('button')).find((b) =>
      (b.textContent || '').includes('取消'),
    ) as HTMLButtonElement
    cancelBtn.click()
    await vi.advanceTimersByTimeAsync(80)
    for (let i = 0; i < 15; i += 1) await Promise.resolve()

    expect(mountEl.querySelector('.reachai-interaction--failed')).toBeTruthy()
    chat.destroy()
  })

  it('destroy rejects in-flight send with AbortError and clears poller', async () => {
    const events: string[] = []
    let streamResolve: ((value: Response) => void) | undefined
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(sessionResponse('sess-abort'))
      .mockImplementationOnce(() => new Promise<Response>((resolve) => {
        streamResolve = resolve
      }))

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      onEvent: (event) => events.push(event.type),
    })

    const pending = chat.send('hanging')
    await vi.advanceTimersByTimeAsync(20)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()

    chat.destroy()
    await expect(pending).rejects.toMatchObject({ name: 'AbortError' })

    const callsAfterDestroy = (globalThis.fetch as any).mock.calls.length
    if (streamResolve) {
      streamResolve(sse([
        'event: message.completed\ndata: {"sessionId":"sess-abort","answer":"late","metadata":{}}\n\n',
      ]))
    }
    await vi.advanceTimersByTimeAsync(2000)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
    expect((globalThis.fetch as any).mock.calls.length).toBe(callsAfterDestroy)
    expect(events.filter((t) => t === 'message.completed')).toHaveLength(0)
  })

  it('local thinking placeholder does not emit synthetic public message.started', async () => {
    const events: string[] = []
    let releaseStream!: (value: Response) => void
    const streamGate = new Promise<Response>((resolve) => {
      releaseStream = resolve
    })
    ;(globalThis.fetch as any)
      .mockResolvedValueOnce(sessionResponse('sess-think'))
      .mockImplementationOnce(() => streamGate)

    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      stream: true,
      onEvent: (event) => events.push(event.type),
    })

    const pending = chat.send('hello')
    await vi.advanceTimersByTimeAsync(20)
    for (let i = 0; i < 20; i += 1) await Promise.resolve()

    const thinking = mountEl.querySelector('.reachai-message--thinking')
    expect(thinking).toBeTruthy()
    expect(thinking?.textContent || '').toContain('思考中')
    expect(events).not.toContain('message.started')

    releaseStream(sse([
      'event: message.delta\ndata: {"text":"Hi"}\n\n',
      'event: message.completed\ndata: {"sessionId":"sess-think","answer":"Hi","metadata":{}}\n\n',
    ]))
    const response = await pending
    expect(response.answer).toBe('Hi')
    expect(events).not.toContain('message.started')
    expect(events.filter((t) => t === 'message.delta')).toHaveLength(1)
    expect(events.filter((t) => t === 'message.completed')).toHaveLength(1)
    expect(mountEl.querySelectorAll('.reachai-message--assistant').length).toBe(1)
    chat.destroy()
  })

  it('maps theme.primaryColor onto shared reachai chat tokens without nesting', async () => {
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      theme: { primaryColor: '#7c3aed', brandName: 'ReachAI' },
    })
    const root = mountEl.querySelector('.eaf-chat') as HTMLElement
    expect(root).toBeTruthy()
    expect(root.style.getPropertyValue('--reachai-chat-primary')).toBe('#7c3aed')
    expect(root.style.getPropertyValue('--reachai-chat-spectrum-anchor')).toBe('#7c3aed')
    expect(root.style.getPropertyValue('--reachai-chat-primary-rgb')).toBe('124 58 237')
    expect(root.getAttribute('style') || '').not.toMatch(/--reachai-chat-primary--/)
    expect(root.querySelector('.eaf-chat__brand')?.textContent).toBe('ReachAI')
    chat.destroy()
  })

  it('applies official theme.preset and lets primaryColor override it', async () => {
    const presetChat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      theme: { preset: 'metro-green', brandName: 'ReachAI' },
    })
    const presetRoot = mountEl.querySelector('.eaf-chat') as HTMLElement
    expect(presetRoot.style.getPropertyValue('--reachai-chat-primary')).toBe('#0b7a59')
    expect(presetRoot.style.getPropertyValue('--reachai-chat-primary-rgb')).toBe('11 122 89')
    expect(presetRoot.dataset.chatPreset).toBe('metro-green')
    presetChat.destroy()
    mountEl.innerHTML = ''

    const overrideChat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
      theme: { preset: 'metro-green', primaryColor: '#1d4ed8', brandName: 'ReachAI' },
    })
    const overrideRoot = mountEl.querySelector('.eaf-chat') as HTMLElement
    expect(overrideRoot.style.getPropertyValue('--reachai-chat-primary')).toBe('#1d4ed8')
    expect(overrideRoot.style.getPropertyValue('--reachai-chat-primary-rgb')).toBe('29 78 216')
    overrideChat.destroy()
  })

  it('defaults to tech-purple when theme is omitted', async () => {
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
    })
    const root = mountEl.querySelector('.eaf-chat') as HTMLElement
    expect(root.style.getPropertyValue('--reachai-chat-primary')).toBe('#6366f1')
    expect(root.dataset.chatPreset).toBe('tech-purple')
    chat.destroy()
  })

  function pendingFetchCalls() {
    return (globalThis.fetch as any).mock.calls.filter((c: unknown[]) =>
      String(c[0]).includes('/page-actions/pending'),
    )
  }

  function mockEmptyPendingTransport(sessionId: string) {
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        return sessionResponse(sessionId)
      }
      if (path.includes('/messages/stream')) {
        return sse([
          'event: message.delta\ndata: {"text":"ok"}\n\n',
          `event: message.completed\ndata: {"sessionId":"${sessionId}","answer":"ok","metadata":{}}\n\n`,
        ])
      }
      if (path.includes('/page-actions/pending')) {
        return new Response(JSON.stringify({ data: [] }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })
  }

  function setDocumentVisibility(state: 'visible' | 'hidden') {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => state,
    })
    document.dispatchEvent(new Event('visibilitychange'))
  }

  it('starts pending polling during the first UI turn as soon as its session exists', async () => {
    const encoder = new TextEncoder()
    let streamController: ReadableStreamDefaultController<Uint8Array> | undefined
    let streamCompleted = false
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        return sessionResponse('sess-first-ui-turn')
      }
      if (path.includes('/messages/stream')) {
        return new Response(new ReadableStream<Uint8Array>({
          start(controller) {
            streamController = controller
          },
        }), {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        })
      }
      if (path.includes('/page-actions/pending')) {
        expect(streamCompleted).toBe(false)
        return new Response(JSON.stringify({ data: [] }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })

    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
    })
    await flushMicrotasks()

    const textarea = mountEl.querySelector('textarea') as HTMLTextAreaElement
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set
    setter?.call(textarea, 'first UI turn')
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
    await flushMicrotasks()
    const form = mountEl.querySelector('form.reachai-composer') as HTMLFormElement
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))

    await flushMicrotasks(40)
    await vi.advanceTimersByTimeAsync(0)
    await flushMicrotasks(20)
    expect(pendingFetchCalls().length).toBeGreaterThanOrEqual(1)
    expect(streamController).toBeTruthy()

    streamCompleted = true
    streamController?.enqueue(encoder.encode(
      'event: message.completed\ndata: {"sessionId":"sess-first-ui-turn","answer":"ok","metadata":{}}\n\n',
    ))
    streamController?.close()
    await flushMicrotasks(30)
    chat.destroy()
  })

  it('does not poll pending without a session for 60 seconds', async () => {
    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
    })
    await vi.advanceTimersByTimeAsync(60_000)
    expect(pendingFetchCalls()).toHaveLength(0)
    chat.destroy()
  })

  it('does not poll pending without registered page actions even after session', async () => {
    mockEmptyPendingTransport('sess-no-action')
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge: createEafPageBridge({ route: '/' }),
    })
    await chat.send('hello')
    await vi.advanceTimersByTimeAsync(60_000)
    expect(pendingFetchCalls()).toHaveLength(0)
    chat.destroy()
  })

  it('starts pending poll immediately after the first action is registered with a session', async () => {
    mockEmptyPendingTransport('sess-first-action')
    const bridge = createEafPageBridge({ route: '/' })
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
    })
    await chat.send('hello')
    expect(pendingFetchCalls()).toHaveLength(0)

    bridge.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    await vi.advanceTimersByTimeAsync(0)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
    expect(pendingFetchCalls().length).toBeGreaterThanOrEqual(1)
    chat.destroy()
  })

  it('stops pending poll after the last action is unregistered', async () => {
    mockEmptyPendingTransport('sess-unreg')
    const bridge = createEafPageBridge({ route: '/' })
    const unregister = bridge.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
    })
    await chat.send('hello')
    await vi.advanceTimersByTimeAsync(0)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
    const beforeStop = pendingFetchCalls().length
    expect(beforeStop).toBeGreaterThanOrEqual(1)

    unregister()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(pendingFetchCalls().length).toBe(beforeStop)
    chat.destroy()
  })

  it('caps visible idle pending polls to at most 12 in 60 seconds', async () => {
    mockEmptyPendingTransport('sess-cap')
    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
    })
    await chat.send('hello')
    await vi.advanceTimersByTimeAsync(60_000)
    for (let i = 0; i < 20; i += 1) await Promise.resolve()
    const count = pendingFetchCalls().length
    expect(count).toBeGreaterThan(0)
    expect(count).toBeLessThanOrEqual(12)
    expect(count).toBeLessThan(120)
    chat.destroy()
  })

  it('pauses pending polls while hidden and compensates on visible', async () => {
    mockEmptyPendingTransport('sess-vis')
    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    setDocumentVisibility('visible')
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
    })
    await chat.send('hello')
    await vi.advanceTimersByTimeAsync(0)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
    const afterActive = pendingFetchCalls().length
    expect(afterActive).toBeGreaterThanOrEqual(1)

    setDocumentVisibility('hidden')
    await vi.advanceTimersByTimeAsync(60_000)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
    expect(pendingFetchCalls().length).toBe(afterActive)

    setDocumentVisibility('visible')
    await vi.advanceTimersByTimeAsync(0)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
    expect(pendingFetchCalls().length).toBe(afterActive + 1)
    setDocumentVisibility('visible')
    chat.destroy()
  })

  it('backs off pending poll errors instead of retrying every 500ms', async () => {
    const errors: string[] = []
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        return sessionResponse('sess-backoff')
      }
      if (path.includes('/messages/stream')) {
        return sse([
          'event: message.completed\ndata: {"sessionId":"sess-backoff","answer":"ok","metadata":{}}\n\n',
        ])
      }
      if (path.includes('/page-actions/pending')) {
        return new Response(JSON.stringify({ message: 'pending down' }), { status: 500 })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })
    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
      onError: (error) => errors.push(error.message),
    })
    await chat.send('hello')
    await vi.advanceTimersByTimeAsync(60_000)
    for (let i = 0; i < 20; i += 1) await Promise.resolve()
    expect(pendingFetchCalls().length).toBeLessThanOrEqual(5)
    expect(errors.filter((m) => m.includes('pending down') || m.includes('500')).length).toBeLessThanOrEqual(5)
    chat.destroy()
  })

  it('never overlaps pending GETs and coalesces activity into one rerun', async () => {
    let releasePending!: (value: Response) => void
    let pendingStarts = 0
    ;(globalThis.fetch as any).mockImplementation(async (url: string) => {
      const path = String(url)
      if (path.includes('/chat/sessions') && !path.includes('/messages') && !path.includes('/page-actions')) {
        return sessionResponse('sess-overlap')
      }
      if (path.includes('/messages/stream')) {
        return sse([
          'event: message.completed\ndata: {"sessionId":"sess-overlap","answer":"ok","metadata":{}}\n\n',
        ])
      }
      if (path.includes('/page-actions/pending')) {
        pendingStarts += 1
        return new Promise<Response>((resolve) => {
          releasePending = resolve
        })
      }
      return new Response(JSON.stringify({ data: {} }), { status: 200 })
    })
    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
    })
    await chat.send('hello')
    await vi.advanceTimersByTimeAsync(0)
    for (let i = 0; i < 15; i += 1) await Promise.resolve()
    expect(pendingStarts).toBe(1)

    chat.open()
    chat.open()
    setDocumentVisibility('hidden')
    setDocumentVisibility('visible')
    await vi.advanceTimersByTimeAsync(3_000)
    expect(pendingStarts).toBe(1)

    releasePending(new Response(JSON.stringify({ data: [] }), { status: 200 }))
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(0)
    for (let i = 0; i < 15; i += 1) await Promise.resolve()
    expect(pendingStarts).toBe(2)
    chat.destroy()
  })

  it('destroy stops pending polls completely', async () => {
    mockEmptyPendingTransport('sess-destroy-poll')
    const bridge = createEafPageBridge({ route: '/' })
    bridge.registerAction('refresh', async () => ({ status: 'SUCCESS', data: {} }), { title: 'Refresh' })
    const chat = await createEafChat({
      agentId: 'agent-1',
      mount: mountEl,
      tokenProvider: () => 'tok',
      apiBase: 'http://localhost/embed',
      bridge,
    })
    await chat.send('hello')
    await vi.advanceTimersByTimeAsync(0)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
    const beforeDestroy = pendingFetchCalls().length
    chat.destroy()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(pendingFetchCalls().length).toBe(beforeDestroy)
  })
})
