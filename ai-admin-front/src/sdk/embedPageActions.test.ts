import { afterEach, describe, expect, it, vi } from 'vitest'
import { createEafPageBridge } from './eafPageBridge'
import {
  dispatchPageActionExactlyOnce,
  pollPendingPageActions,
} from './embedPageActions'

describe('dispatchPageActionExactlyOnce', () => {
  afterEach(() => { delete (window as any).__REACHAI_PAGE_BRIDGE__ })

  it('reports a removed action without claiming or running the old handler', async () => {
    let finish!: (accepted: boolean) => void
    const decision = new Promise<boolean>((resolve) => { finish = resolve })
    const handler = vi.fn()
    const bridge = createEafPageBridge({ confirmAction: () => decision })
    const unregister = bridge.registerAction('change', handler)
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify({ code: 200, data: {} }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    }))
    const pending = dispatchPageActionExactlyOnce({
      request: { type: 'page.action.requested', requestId: 'stale-action', actionKey: 'change', confirm: true },
      bridge, sessionId: 'session-1', apiBase: '/api/embed', token: 'token',
      handledPageActions: new Set(), inFlightPageActions: new Map(), fetchImpl,
    })
    unregister()
    finish(true)
    await pending
    expect(handler).not.toHaveBeenCalled()
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(String(fetchImpl.mock.calls[0][0])).toContain('/result')
    expect(JSON.parse(String(fetchImpl.mock.calls[0][1]?.body))).toMatchObject({ status: 'ACTION_NOT_FOUND' })
  })

  it('reports a stale page after claim without invoking its retained handler', async () => {
    let finishClaim!: (response: Response) => void
    let notifyClaim!: () => void
    let currentPage = true
    const claimStarted = new Promise<void>((resolve) => { notifyClaim = resolve })
    const claimResponse = new Promise<Response>((resolve) => { finishClaim = resolve })
    const ok = (data: unknown) => new Response(JSON.stringify({ code: 200, data }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })
    const fetchImpl = vi.fn(async (url: RequestInfo | URL, _init?: RequestInit) => {
      if (String(url).endsWith('/claim')) { notifyClaim(); return claimResponse }
      return ok({})
    })
    const handler = vi.fn()
    const bridge = createEafPageBridge()
    bridge.registerAction('change', handler)
    const pending = dispatchPageActionExactlyOnce({
      request: { type: 'page.action.requested', requestId: 'old-page', actionKey: 'change' },
      bridge, sessionId: 'session-1', apiBase: '/api/embed', token: 'token',
      handledPageActions: new Set(), inFlightPageActions: new Map(), fetchImpl,
      isCurrentPage: () => currentPage,
    })
    await claimStarted
    currentPage = false
    finishClaim(ok({ claimed: true }))
    await pending
    expect(handler).not.toHaveBeenCalled()
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(JSON.parse(String(fetchImpl.mock.calls[1][1]?.body))).toMatchObject({ status: 'ACTION_NOT_FOUND' })
  })

  it.each([false, true])('requires confirmation before the global fallback (accepted: %s)', async (accepted) => {
    const execute = vi.fn(async () => ({ status: 'SUCCESS', data: { changed: true } }))
    ;(window as any).__REACHAI_PAGE_BRIDGE__ = { execute }
    const confirmAction = vi.fn(async () => accepted)
    const bridge = createEafPageBridge({ pageInstanceId: 'page-1', confirmAction })
    const ok = (data: unknown) => new Response(JSON.stringify({ code: 200, data }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })
    const fetchImpl = vi.fn().mockImplementation(async (url: string) => ok(url.endsWith('/claim') ? { claimed: true } : {}))
    await dispatchPageActionExactlyOnce({
      request: { type: 'page.action.requested', requestId: 'fallback-confirm', actionKey: 'change',
        confirm: true, target: { pageInstanceId: 'page-1' }, metadata: { pageKey: 'orders' } },
      bridge, sessionId: 'session-1', apiBase: '/api/embed', token: 'token',
      handledPageActions: new Set(), inFlightPageActions: new Map(), fetchImpl,
    })
    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(execute).toHaveBeenCalledTimes(accepted ? 1 : 0)
    expect(fetchImpl.mock.calls.filter(([url]) => String(url).endsWith('/claim'))).toHaveLength(accepted ? 1 : 0)
    if (accepted) {
      expect(execute).toHaveBeenCalledWith('orders', 'change', {}, expect.objectContaining({ confirmed: true }))
      expect(confirmAction.mock.invocationCallOrder[0]).toBeLessThan(fetchImpl.mock.invocationCallOrder[0])
      expect(fetchImpl.mock.invocationCallOrder[0]).toBeLessThan(execute.mock.invocationCallOrder[0])
    }
    const resultCall = fetchImpl.mock.calls.find(([url]) => String(url).endsWith('/result'))
    expect(JSON.parse(String(resultCall?.[1]?.body))).toMatchObject({
      status: accepted ? 'SUCCESS' : 'USER_CANCELLED',
      ...(accepted ? { userConfirmed: true } : {}),
    })
  })

  it('never invokes the global fallback for another page instance', async () => {
    const execute = vi.fn()
    ;(window as any).__REACHAI_PAGE_BRIDGE__ = { execute }
    const confirmAction = vi.fn(async () => true)
    const fetchImpl = vi.fn()
    await dispatchPageActionExactlyOnce({
      request: { type: 'page.action.requested', requestId: 'wrong-page', actionKey: 'change', confirm: true,
        target: { pageInstanceId: 'other-page' }, metadata: { pageKey: 'orders' } },
      bridge: createEafPageBridge({ pageInstanceId: 'page-1', confirmAction }),
      sessionId: 'session-1', apiBase: '/api/embed', token: 'token',
      handledPageActions: new Set(), inFlightPageActions: new Map(), fetchImpl,
    })
    expect(confirmAction).not.toHaveBeenCalled()
    expect(fetchImpl).not.toHaveBeenCalled()
    expect(execute).not.toHaveBeenCalled()
  })
  it('does not claim or execute when the host is destroyed during confirmation', async () => {
    let finishConfirmation!: (accepted: boolean) => void
    let active = true
    const handler = vi.fn()
    const resultListener = vi.fn()
    const bridge = createEafPageBridge({ confirmAction: () => new Promise<boolean>((resolve) => { finishConfirmation = resolve }) })
    bridge.registerAction('change', handler)
    bridge.onResult(resultListener)
    const fetchImpl = vi.fn()
    const pending = dispatchPageActionExactlyOnce({
      request: { type: 'page.action.requested', requestId: 'destroy-during-confirm', actionKey: 'change', confirm: true },
      bridge, sessionId: 'session-1', apiBase: '/api/embed', token: 'token',
      handledPageActions: new Set(), inFlightPageActions: new Map(), fetchImpl, isActive: () => active,
    })
    await vi.waitFor(() => expect(finishConfirmation).toBeTypeOf('function'))
    active = false
    finishConfirmation(true)
    await pending
    expect(fetchImpl).not.toHaveBeenCalled()
    expect(handler).not.toHaveBeenCalled()
    expect(resultListener).not.toHaveBeenCalled()
  })

  it('settles an unresponsive custom confirmation when the host is destroyed', async () => {
    const lifetime = new AbortController()
    const confirmAction = vi.fn(() => new Promise<boolean>(() => {}))
    const handler = vi.fn()
    const bridge = createEafPageBridge({ confirmAction })
    bridge.registerAction('change', handler)
    const fetchImpl = vi.fn()
    const pending = dispatchPageActionExactlyOnce({
      request: { type: 'page.action.requested', requestId: 'destroy-unresponsive', actionKey: 'change', confirm: true },
      bridge, sessionId: 'session-1', apiBase: '/api/embed', token: 'token',
      handledPageActions: new Set(), inFlightPageActions: new Map(), fetchImpl,
      isActive: () => !lifetime.signal.aborted, signal: lifetime.signal,
    })
    await vi.waitFor(() => expect(confirmAction).toHaveBeenCalledTimes(1))
    lifetime.abort()
    await pending
    expect(fetchImpl).not.toHaveBeenCalled()
    expect(handler).not.toHaveBeenCalled()
  })

  it('posts a business-terminal action result without treating it as a transport error', async () => {
    const bridge = createEafPageBridge({ pageInstanceId: 'page-instance-1' })
    bridge.registerAction('search', async () => ({
      status: 'NO_DATA' as const,
      message: 'No matching rows',
      data: { total: 0 },
    }))
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ code: 200, data: { claimed: true } }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ))
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ code: 200, data: {} }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ))
    const onError = vi.fn()

    await dispatchPageActionExactlyOnce({
      request: {
        type: 'page.action.requested',
        requestId: 'request-terminal',
        actionKey: 'search',
        target: { pageInstanceId: 'page-instance-1' },
      },
      bridge,
      sessionId: 'session-1',
      apiBase: '/api/embed',
      token: 'embed-token',
      handledPageActions: new Set(),
      inFlightPageActions: new Map(),
      onError,
      fetchImpl,
    })

    expect(onError).not.toHaveBeenCalled()
    expect(String(fetchImpl.mock.calls[0]?.[0])).toContain('/claim')
    expect(String(fetchImpl.mock.calls[1]?.[0])).toContain('/result')
    const body = JSON.parse(String(fetchImpl.mock.calls[1]?.[1]?.body))
    expect(body).toMatchObject({
      status: 'NO_DATA',
      message: 'No matching rows',
      data: { total: 0 },
    })
  })

  it('retries result delivery without executing the page bridge twice', async () => {
    const handler = vi.fn(async () => ({ refreshed: true }))
    const bridge = createEafPageBridge({
      pageInstanceId: 'page-instance-1',
    })
    bridge.registerAction('refresh', handler)
    const handledPageActions = new Set<string>()
    const inFlightPageActions = new Map<string, Promise<void>>()
    const pendingPageActionResults = new Map()
    const onError = vi.fn()
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ code: 200, data: { claimed: true } }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ))
      .mockRejectedValueOnce(new TypeError('temporary network failure'))
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ code: 200, data: {} }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ))
    const options = {
      request: {
        type: 'page.action.requested' as const,
        requestId: 'request-1',
        actionKey: 'refresh',
        target: { pageInstanceId: 'page-instance-1' },
      },
      bridge,
      sessionId: 'session-1',
      apiBase: '/api/embed',
      token: 'embed-token',
      handledPageActions,
      inFlightPageActions,
      pendingPageActionResults,
      onError,
      fetchImpl,
    }

    await dispatchPageActionExactlyOnce(options)
    expect(handler).toHaveBeenCalledTimes(1)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(pendingPageActionResults.has('request-1')).toBe(true)

    await dispatchPageActionExactlyOnce(options)

    expect(handler).toHaveBeenCalledTimes(1)
    expect(fetchImpl).toHaveBeenCalledTimes(3)
    expect(pendingPageActionResults.has('request-1')).toBe(false)
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it('does not execute or post a result when the claim endpoint reports an expired request', async () => {
    const handler = vi.fn(async () => ({ changed: true }))
    const bridge = createEafPageBridge({ confirmAction: async () => true })
    bridge.registerAction('setEnabled', handler)
    const onError = vi.fn()
    const fetchImpl = vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: 409, message: 'page action request is no longer active' }),
      { status: 409, headers: { 'Content-Type': 'application/json' } },
    ))

    await dispatchPageActionExactlyOnce({
      request: {
        type: 'page.action.requested',
        requestId: 'request-expired',
        actionKey: 'setEnabled',
        confirm: true,
      },
      bridge,
      sessionId: 'session-1',
      apiBase: '/api/embed',
      token: 'embed-token',
      handledPageActions: new Set(),
      inFlightPageActions: new Map(),
      onError,
      fetchImpl,
    })

    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(String(fetchImpl.mock.calls[0]?.[0])).toContain('/claim')
    expect(handler).not.toHaveBeenCalled()
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('expired before execution'),
    }))
  })

  it.each([false, true])('retries an uncertain claim without duplicate execution (server already claimed: %s)', async (serverClaimed) => {
    const handler = vi.fn(async () => ({ changed: true }))
    const confirmAction = vi.fn(async () => true)
    const bridge = createEafPageBridge({ confirmAction })
    bridge.registerAction('change', handler)
    const handledPageActions = new Set<string>()
    const fetchImpl = vi.fn()
      .mockRejectedValueOnce(new TypeError('claim response lost'))
      .mockResolvedValueOnce(new Response(
        JSON.stringify(serverClaimed ? { code: 409, message: 'already claimed' } : { code: 200, data: { claimed: true } }),
        { status: serverClaimed ? 409 : 200, headers: { 'Content-Type': 'application/json' } },
      ))
    if (!serverClaimed) {
      fetchImpl.mockResolvedValueOnce(new Response(JSON.stringify({ code: 200, data: {} }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      }))
    }
    const options = {
      request: { type: 'page.action.requested' as const, requestId: 'uncertain-claim', actionKey: 'change', confirm: true },
      bridge, sessionId: 'session-1', apiBase: '/api/embed', token: 'token',
      handledPageActions, inFlightPageActions: new Map<string, Promise<void>>(), fetchImpl,
    }

    await dispatchPageActionExactlyOnce(options)
    expect(handler).not.toHaveBeenCalled()
    expect(handledPageActions.has('uncertain-claim')).toBe(false)
    await dispatchPageActionExactlyOnce(options)
    expect(confirmAction).toHaveBeenCalledTimes(2)
    expect(handler).toHaveBeenCalledTimes(serverClaimed ? 0 : 1)
    expect(fetchImpl).toHaveBeenCalledTimes(serverClaimed ? 2 : 3)
  })

  it('recovers an unclaimed action on the next pending poll after a network failure', async () => {
    const handler = vi.fn(async () => ({ refreshed: true }))
    const bridge = createEafPageBridge()
    bridge.registerAction('refresh', handler)
    const request = { type: 'page.action.requested', requestId: 'retry-poll', actionKey: 'refresh' }
    const json = (data: unknown) => new Response(JSON.stringify({ code: 200, data }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(json([request]))
      .mockRejectedValueOnce(new TypeError('network unavailable'))
      .mockResolvedValueOnce(json([request]))
      .mockResolvedValueOnce(json({ claimed: true }))
      .mockResolvedValueOnce(json({}))
    const options = {
      apiBase: '/api/embed', token: 'token', sessionId: 'session-1', bridge,
      handledPageActions: new Set<string>(), pendingPageActions: new Set<string>(),
      inFlightPageActions: new Map<string, Promise<void>>(), fetchImpl,
    }

    await pollPendingPageActions(options)
    expect(handler).not.toHaveBeenCalled()
    await pollPendingPageActions(options)
    expect(handler).toHaveBeenCalledTimes(1)
    expect(fetchImpl).toHaveBeenCalledTimes(5)
  })

  it('retries an ambiguously delivered cached result even when server no longer lists it', async () => {
    const bridge = createEafPageBridge({
      pageInstanceId: 'page-instance-1',
    })
    const handler = vi.fn()
    bridge.registerAction('refresh', handler)
    const result = {
      protocolVersion: '1.0',
      type: 'page.action.result' as const,
      requestId: 'request-1',
      actionKey: 'refresh',
      status: 'SUCCESS' as const,
      data: { refreshed: true },
    }
    const pendingPageActionResults = new Map([['request-1', result]])
    const fetchImpl = vi.fn()
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ code: 200, data: [] }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ))
      .mockResolvedValueOnce(new Response(
        JSON.stringify({ code: 200, data: {} }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ))

    await pollPendingPageActions({
      apiBase: '/api/embed',
      token: 'embed-token',
      sessionId: 'session-1',
      bridge,
      handledPageActions: new Set(['request-1']),
      pendingPageActions: new Set(),
      pendingPageActionResults,
      fetchImpl,
    })

    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(handler).not.toHaveBeenCalled()
    expect(pendingPageActionResults.has('request-1')).toBe(false)
  })
})
