import { describe, expect, it, vi } from 'vitest'
import { createEafPageBridge } from './eafPageBridge'
import {
  dispatchPageActionExactlyOnce,
  pollPendingPageActions,
} from './embedPageActions'

describe('dispatchPageActionExactlyOnce', () => {
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
    expect(fetchImpl).toHaveBeenCalledTimes(1)
    expect(pendingPageActionResults.has('request-1')).toBe(true)

    await dispatchPageActionExactlyOnce(options)

    expect(handler).toHaveBeenCalledTimes(1)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
    expect(pendingPageActionResults.has('request-1')).toBe(false)
    expect(onError).toHaveBeenCalledTimes(1)
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
