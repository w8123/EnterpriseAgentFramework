import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import EmbedChatHost from './EmbedChatHost.vue'
import { createEafPageBridge } from './eafPageBridge'

describe('EmbedChatHost token lifecycle', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('adopts a resumed session only after the target token and navigation are verified', async () => {
    let completeRebind!: (response: Response) => void
    const request = vi.fn(() => new Promise<Response>(resolve => { completeRebind = resolve }))
    vi.stubGlobal('fetch', request)
    const bridge = createEafPageBridge({ route: '/target', pageInstanceId: 'target-instance' })
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: () => 'target-token', bridge,
      page: { pageKey: 'target' }, memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      rebindPage(targetBridge: typeof bridge, page: { pageKey: string }, requestId: string, sessionId: string): Promise<void>
      getExistingSessionId(): string | null
    }
    const resume = api.rebindPage(bridge, { pageKey: 'target' }, 'nav-1', 'session-1')
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(1))
    expect(api.getExistingSessionId()).toBeNull()
    const [url, init] = request.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toContain('/chat/sessions/session-1/page-bridge/rebind-v2')
    expect(init.headers).toMatchObject({ Authorization: 'Bearer target-token' })
    expect(JSON.parse(String(init.body))).toMatchObject({ navigationRequestId: 'nav-1', pageKey: 'target' })
    completeRebind(new Response(JSON.stringify({ data: {
      sessionId: 'session-1', pageKey: 'target', pageInstanceId: 'target-instance', route: '/target',
    } }), { status: 200 }))
    await resume
    expect(api.getExistingSessionId()).toBe('session-1')
    host.unmount()
  })

  it('does not adopt a resumed session if Control rejects the navigation', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 403 })))
    const bridge = createEafPageBridge({ route: '/target', pageInstanceId: 'target-instance' })
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: () => 'target-token', bridge,
      page: { pageKey: 'target' }, memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      rebindPage(targetBridge: typeof bridge, page: { pageKey: string }, requestId: string, sessionId: string): Promise<void>
      getExistingSessionId(): string | null
    }
    await expect(api.rebindPage(bridge, { pageKey: 'target' }, 'nav-1', 'session-1')).rejects.toBeTruthy()
    expect(api.getExistingSessionId()).toBeNull()
    host.unmount()
  })

  it('serializes continuation checks and permits retry only before a submission', async () => {
    let finishLookup!: (response: Response) => void
    let calls = 0
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (String(url).endsWith('/page-bridge/rebind-v2')) return Promise.resolve(new Response(JSON.stringify({ data: {
        sessionId: 'session-1', pageKey: 'target', pageInstanceId: 'target-instance', route: '/target',
      } }), { status: 200 }))
      calls += 1
      return new Promise<Response>(resolve => { finishLookup = resolve })
    }))
    const bridge = createEafPageBridge({ route: '/target', pageInstanceId: 'target-instance' })
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: () => 'target-token', bridge,
      page: { pageKey: 'target' }, memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      rebindPage(targetBridge: typeof bridge, page: { pageKey: string }, requestId: string, sessionId: string): Promise<void>
      continueDocumentNavigation(requestId: string): Promise<void>
      sendMessage(message: string): Promise<unknown>
    }
    await api.rebindPage(bridge, { pageKey: 'target' }, 'nav-1', 'session-1')
    const first = api.continueDocumentNavigation('nav-1')
    await vi.waitFor(() => expect(calls).toBe(1))
    await expect(api.continueDocumentNavigation('nav-1')).rejects.toThrow('already in progress')
    await expect(api.sendMessage('another turn')).rejects.toThrow('Another message is still in flight')
    finishLookup(new Response(null, { status: 409 }))
    await expect(first).rejects.toThrow('HTTP 409')
    const retry = api.continueDocumentNavigation('nav-1')
    await vi.waitFor(() => expect(calls).toBe(2))
    finishLookup(new Response(null, { status: 409 }))
    await expect(retry).rejects.toThrow('HTTP 409')
    host.unmount()
  })

  it('does not submit an interaction when the target host is destroyed during lookup', async () => {
    let finishLookup!: (response: Response) => void
    const request = vi.fn((url: string) => {
      if (String(url).endsWith('/page-bridge/rebind-v2')) return Promise.resolve(new Response(JSON.stringify({ data: {
        sessionId: 'session-1', pageKey: 'target', pageInstanceId: 'target-instance', route: '/target',
      } }), { status: 200 }))
      return new Promise<Response>(resolve => { finishLookup = resolve })
    })
    vi.stubGlobal('fetch', request)
    const bridge = createEafPageBridge({ route: '/target', pageInstanceId: 'target-instance' })
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: () => 'target-token', bridge,
      page: { pageKey: 'target' }, memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      rebindPage(targetBridge: typeof bridge, page: { pageKey: string }, requestId: string, sessionId: string): Promise<void>
      continueDocumentNavigation(requestId: string): Promise<void>
      disposeHost(): void
    }
    await api.rebindPage(bridge, { pageKey: 'target' }, 'nav-1', 'session-1')
    const continuation = api.continueDocumentNavigation('nav-1')
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(2))
    api.disposeHost()
    finishLookup(new Response(JSON.stringify({ data: {
      navigationRequestId: 'nav-1', interactionId: `wfi_${'b'.repeat(32)}`,
    } }), { status: 200 }))
    await expect(continuation).rejects.toMatchObject({ name: 'AbortError' })
    expect(request).toHaveBeenCalledTimes(2)
    host.unmount()
  })

  it('cancels a navigation interaction only after the source lookup and only once', async () => {
    const request = vi.fn(async (url: string, _init?: RequestInit) => {
      if (String(url).endsWith('/chat/sessions')) return new Response(JSON.stringify({ data: { sessionId: 'session-1' } }), { status: 200 })
      if (String(url).endsWith('/cancelled-interaction')) return new Response(JSON.stringify({ data: {
        navigationRequestId: 'nav-1', interactionId: `wfi_${'b'.repeat(32)}`,
      } }), { status: 200 })
      if (String(url).endsWith('/submit/stream')) return new Response(
        'event: turn.cancelled\ndata: {}\n\n', { status: 200, headers: { 'content-type': 'text/event-stream' } })
      return new Response('{}', { status: 404 })
    })
    vi.stubGlobal('fetch', request)
    const bridge = createEafPageBridge({ route: '/source', pageInstanceId: 'source-instance' })
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: () => 'source-token', bridge,
      page: { pageKey: 'source' }, memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      ensureHostSession(): Promise<string>
      continueDocumentNavigation(requestId: string, cancel?: boolean): Promise<void>
    }
    await api.ensureHostSession()
    await api.continueDocumentNavigation('nav-1', true)
    const submit = request.mock.calls.find(([url]) => String(url).endsWith('/submit/stream'))
    expect(submit).toBeDefined()
    expect(JSON.parse(String((submit as unknown as [string, RequestInit])[1].body))).toMatchObject({ action: 'cancel' })
    await expect(api.continueDocumentNavigation('nav-1', true)).rejects.toThrow('already submitted')
    expect(request.mock.calls.filter(([url]) => String(url).endsWith('/submit/stream'))).toHaveLength(1)
    host.unmount()
  })

  it('issues an ordinary transfer and adopts it only after target binding is verified', async () => {
    const ticket = 'A'.repeat(43)
    const request = vi.fn(async (url: string, _init?: RequestInit) => {
      if (String(url).endsWith('/chat/sessions')) return new Response(JSON.stringify({ data: { sessionId: 'session-1' } }), { status: 200 })
      if (String(url).endsWith('/page-transfers/claim')) return new Response(JSON.stringify({ data: {
        sessionId: 'session-1', pageKey: 'target', pageInstanceId: 'target-instance', route: '/target',
      } }), { status: 200 })
      return new Response(JSON.stringify({ data: { transferTicket: ticket, expiresInSeconds: 120 } }), { status: 200 })
    })
    vi.stubGlobal('fetch', request)
    const source = createEafPageBridge({ route: '/source', pageInstanceId: 'source-instance' })
    const first = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: () => 'source-token', bridge: source,
      page: { pageKey: 'source' }, memoryPanel: false, aiToolPanel: false,
    } })
    const sourceApi = first.vm as unknown as {
      ensureHostSession(): Promise<string>
      preparePageTransfer(): Promise<{ sessionId: string; transferTicket: string; expiresInSeconds: number }>
    }
    await sourceApi.ensureHostSession()
    const transfer = await sourceApi.preparePageTransfer()
    expect(transfer).toEqual({ sessionId: 'session-1', transferTicket: ticket, expiresInSeconds: 120 })
    first.unmount()
    const target = createEafPageBridge({ route: '/target', pageInstanceId: 'target-instance' })
    const second = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: () => 'target-token', bridge: target,
      page: { pageKey: 'target' }, memoryPanel: false, aiToolPanel: false,
    } })
    const targetApi = second.vm as unknown as {
      claimPageTransfer(value: typeof transfer): Promise<void>
      getExistingSessionId(): string | null
    }
    await targetApi.claimPageTransfer(transfer)
    expect(targetApi.getExistingSessionId()).toBe('session-1')
    const call = request.mock.calls.find(([url]) => String(url).endsWith('/page-transfers/claim'))!
    expect((call[1] as RequestInit).headers).toMatchObject({ Authorization: 'Bearer target-token' })
    await expect(targetApi.claimPageTransfer(transfer)).rejects.toThrow('cannot be claimed')
    second.unmount()
  })

  it('clears context only after confirmation and retains messages if the server refuses', async () => {
    let clearStatus = 409
    const onEvent = vi.fn()
    const request = vi.fn(async (url: string) => {
      if (String(url).endsWith('/context/clear')) return new Response(null, { status: clearStatus })
      if (String(url).endsWith('/chat/sessions')) {
        return new Response(JSON.stringify({ data: { sessionId: 'session-1' } }), { status: 200 })
      }
      return new Response(JSON.stringify({ data: { answer: '测试回答', sessionId: 'session-1' } }), { status: 200 })
    })
    vi.stubGlobal('fetch', request)
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: () => 'token', preferStream: false,
      bridge: createEafPageBridge({ route: '/source' }), memoryPanel: false, aiToolPanel: false, onEvent,
    } })
    const api = host.vm as unknown as { sendMessage(message: string): Promise<unknown> }
    await api.sendMessage('测试问题')
    await flushPromises()
    expect(host.text()).toContain('测试回答')
    await host.findAll('button').find(button => button.text() === '清除上下文')!.trigger('click')
    expect(host.text()).toContain('已保存的记忆和审计记录会保留')
    await host.findAll('button').find(button => button.text() === '清除')!.trigger('click')
    await flushPromises()
    expect(host.text()).toContain('对话正在处理中')
    expect(host.text()).toContain('测试回答')
    clearStatus = 204
    await host.findAll('button').find(button => button.text() === '清除')!.trigger('click')
    await flushPromises()
    expect(host.text()).not.toContain('测试回答')
    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'context.cleared', sessionId: 'session-1' }))
    expect(request.mock.calls.filter(([url]) => String(url).endsWith('/context/clear'))).toHaveLength(2)
    host.unmount()
  })

  it('blocks sending while a context clear is in flight', async () => {
    let complete!: (value: Response) => void
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).endsWith('/context/clear')) return new Promise<Response>(resolve => { complete = resolve })
      return new Response(JSON.stringify({ data: { sessionId: 'session-1' } }), { status: 200 })
    }))
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: () => 'token',
      bridge: createEafPageBridge({ route: '/source' }), memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      ensureHostSession(): Promise<string>
      clearContext(): Promise<void>
      sendMessage(message: string): Promise<unknown>
    }
    await api.ensureHostSession()
    const clear = api.clearContext()
    await flushPromises()
    await expect(api.sendMessage('不要并发发送')).rejects.toThrow('Another message is still in flight')
    complete(new Response(null, { status: 204 }))
    await clear
    host.unmount()
  })

  it('obtains a target-page token after an unrelated source-page refresh completes', async () => {
    let completeSource!: (value: string) => void
    const provider = vi.fn()
      .mockResolvedValueOnce('initial-token')
      .mockImplementationOnce(() => new Promise<string>((resolve) => { completeSource = resolve }))
      .mockResolvedValueOnce('target-token')
    const request = vi.fn(async (url: string, _init?: RequestInit) => new Response(JSON.stringify({
      data: String(url).endsWith('/chat/sessions') ? { sessionId: 'session-1' }
        : { sessionId: 'session-1', pageKey: 'target', pageInstanceId: 'target-instance', route: '/target' },
    }), { status: 200 }))
    vi.stubGlobal('fetch', request)
    const source = createEafPageBridge({ route: '/source', pageInstanceId: 'source-instance' })
    const target = createEafPageBridge({ route: '/target', pageInstanceId: 'target-instance' })
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: provider, bridge: source,
      page: { pageKey: 'source' }, memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      ensureHostSession(): Promise<string>
      refreshToken(reason: 'page-action'): Promise<string>
      rebindPage(bridge: typeof target, page: { pageKey: string }, id: string): Promise<void>
    }
    await api.ensureHostSession()
    const sourceRefresh = api.refreshToken('page-action')
    await vi.waitFor(() => expect(provider).toHaveBeenCalledTimes(2))
    const navigation = api.rebindPage(target, { pageKey: 'target' }, 'nav-1')
    completeSource('source-refresh-token')
    await sourceRefresh
    await navigation
    expect(provider).toHaveBeenCalledTimes(3)
    expect(provider.mock.calls[2][0]).toMatchObject({ reason: 'page-navigation', pageKey: 'target', pageInstanceId: 'target-instance' })
    const rebind = request.mock.calls.find(([url]) => String(url).endsWith('/page-bridge/rebind-v2'))!
    expect((rebind[1] as RequestInit).headers).toMatchObject({ Authorization: 'Bearer target-token' })
    host.unmount()
  })

  it('does not publish a target token until rebind succeeds and restores the source after failure', async () => {
    let finishRebind!: (response: Response) => void
    const provider = vi.fn().mockResolvedValueOnce('source-token').mockResolvedValueOnce('target-token').mockResolvedValueOnce('source-again')
    const changed = vi.fn()
    vi.stubGlobal('fetch', vi.fn((url: string) => String(url).endsWith('/page-bridge/rebind-v2')
      ? new Promise<Response>((resolve) => { finishRebind = resolve })
      : Promise.resolve(new Response(JSON.stringify({ data: { sessionId: 'session-1' } }), { status: 200 }))))
    const source = createEafPageBridge({ route: '/source', pageInstanceId: 'source-instance' })
    const target = createEafPageBridge({ route: '/target', pageInstanceId: 'target-instance' })
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: provider, bridge: source, page: { pageKey: 'source' },
      onTokenChanged: changed, memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      ensureHostSession(): Promise<string>
      refreshToken(reason: 'page-action'): Promise<string>
      rebindPage(bridge: typeof target, page: { pageKey: string }, id: string): Promise<void>
    }
    await api.ensureHostSession()
    const navigation = api.rebindPage(target, { pageKey: 'target' }, 'nav-1')
    const rejected = expect(navigation).rejects.toMatchObject({ code: 'PAGE_NAVIGATION_REBIND_FAILED' })
    await vi.waitFor(() => expect(finishRebind).toBeTypeOf('function'))
    expect(changed.mock.calls.map(([token]) => token)).not.toContain('target-token')
    await expect(api.refreshToken('page-action')).rejects.toMatchObject({ code: 'PAGE_NAVIGATION_IN_PROGRESS' })
    finishRebind(new Response('{}', { status: 403 }))
    await rejected
    expect(await api.refreshToken('page-action')).toBe('source-again')
    expect(provider.mock.calls[2][0]).toMatchObject({ pageKey: 'source', pageInstanceId: 'source-instance' })
    host.unmount()
  })

  it.each(['lost-response', 'gateway-error'])('retries the same v2 rebind once after %s', async (failure) => {
    const provider = vi.fn().mockResolvedValueOnce('source-token').mockResolvedValueOnce('target-token')
    const changed = vi.fn()
    const calls: Array<{ url: string; init?: RequestInit }> = []
    const request = vi.fn(async (url: string, init?: RequestInit) => {
      if (!String(url).endsWith('/page-bridge/rebind-v2')) {
        return new Response(JSON.stringify({ data: { sessionId: 'session-1' } }), { status: 200 })
      }
      calls.push({ url: String(url), init })
      if (calls.length === 1) {
        if (failure === 'lost-response') throw new TypeError('response lost after commit')
        return new Response('{}', { status: 503 })
      }
      return new Response(JSON.stringify({ code: 200, data: {
        sessionId: 'session-1', pageKey: 'target', pageInstanceId: 'target-instance', route: '/target',
      } }), { status: 200 })
    })
    vi.stubGlobal('fetch', request)
    const source = createEafPageBridge({ route: '/source', pageInstanceId: 'source-instance' })
    const target = createEafPageBridge({ route: '/target', pageInstanceId: 'target-instance' })
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: provider, bridge: source, page: { pageKey: 'source' },
      onTokenChanged: changed, memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      ensureHostSession(): Promise<string>
      rebindPage(bridge: typeof target, page: { pageKey: string }, id: string): Promise<void>
    }
    await api.ensureHostSession()
    await api.rebindPage(target, { pageKey: 'target' }, 'nav-1')
    expect(calls).toHaveLength(2)
    expect(calls[0].url).toBe(calls[1].url)
    expect(calls[0].init?.body).toBe(calls[1].init?.body)
    expect(calls[0].init?.headers).toEqual(calls[1].init?.headers)
    expect(changed.mock.calls.map(([token]) => token)).toContain('target-token')
    host.unmount()
  })

  it('does not retry indefinitely when both rebind attempts fail', async () => {
    const provider = vi.fn().mockResolvedValueOnce('source-token').mockResolvedValueOnce('target-token')
    const request = vi.fn(async (url: string) => {
      if (!String(url).endsWith('/page-bridge/rebind-v2')) {
        return new Response(JSON.stringify({ data: { sessionId: 'session-1' } }), { status: 200 })
      }
      if (request.mock.calls.filter(([value]) => String(value).endsWith('/page-bridge/rebind-v2')).length === 1) {
        return new Response('{}', { status: 503 })
      }
      throw new TypeError('network unavailable')
    })
    vi.stubGlobal('fetch', request)
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: provider,
      bridge: createEafPageBridge({ route: '/source' }), page: { pageKey: 'source' },
      memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      ensureHostSession(): Promise<string>
      rebindPage(bridge: ReturnType<typeof createEafPageBridge>, page: { pageKey: string }, id: string): Promise<void>
    }
    await api.ensureHostSession()
    await expect(api.rebindPage(createEafPageBridge({ route: '/target' }), { pageKey: 'target' }, 'nav-1')).rejects.toThrow('network unavailable')
    expect(request.mock.calls.filter(([url]) => String(url).endsWith('/page-bridge/rebind-v2'))).toHaveLength(2)
    host.unmount()
  })

  it('rejects a successful HTTP response that does not confirm the target binding', async () => {
    const provider = vi.fn().mockResolvedValueOnce('source-token').mockResolvedValueOnce('target-token')
    const changed = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async (url: string) => String(url).endsWith('/page-bridge/rebind-v2')
      ? new Response('<html>gateway fallback</html>', { status: 200 })
      : new Response(JSON.stringify({ data: { sessionId: 'session-1' } }), { status: 200 })))
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: provider, onTokenChanged: changed,
      bridge: createEafPageBridge({ route: '/source' }), page: { pageKey: 'source' },
      memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as {
      ensureHostSession(): Promise<string>
      rebindPage(bridge: ReturnType<typeof createEafPageBridge>, page: { pageKey: string }, id: string): Promise<void>
    }
    await api.ensureHostSession()
    await expect(api.rebindPage(createEafPageBridge({ route: '/target' }), { pageKey: 'target' }, 'nav-1'))
      .rejects.toMatchObject({ code: 'PAGE_NAVIGATION_REBIND_INVALID_RESPONSE' })
    expect(changed.mock.calls.map(([token]) => token)).not.toContain('target-token')
    host.unmount()
  })

  it('settles a pending refresh immediately when disposed even if the provider ignores abort', async () => {
    const provider = vi.fn().mockResolvedValueOnce('initial-token').mockImplementationOnce(() => new Promise(() => {}))
    const host = mount(EmbedChatHost, { props: {
      apiBase: '/api/embed', tokenProvider: provider, bridge: createEafPageBridge({ route: '/' }),
      memoryPanel: false, aiToolPanel: false,
    } })
    const api = host.vm as unknown as { refreshToken(reason: 'page-action'): Promise<string>; disposeHost(): void }
    await vi.waitFor(() => expect(provider).toHaveBeenCalledTimes(1))
    const pending = api.refreshToken('page-action')
    const rejected = expect(pending).rejects.toMatchObject({ name: 'AbortError' })
    await vi.waitFor(() => expect(provider).toHaveBeenCalledTimes(2))
    api.disposeHost()
    await rejected
    host.unmount()
  })
})
