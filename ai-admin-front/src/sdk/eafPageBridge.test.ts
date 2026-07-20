import { describe, expect, it, vi } from 'vitest'
import { createEafPageBridge, type PageActionRequest } from './eafPageBridge'

function request(partial: Partial<PageActionRequest> = {}): PageActionRequest {
  return {
    protocolVersion: '1.0',
    type: 'page.action.requested',
    requestId: 'req-1',
    actionKey: 'page.search.applyFilters',
    title: 'Apply filters',
    confirm: false,
    args: { q: 'demo' },
    ...partial,
  }
}

describe('createEafPageBridge pre-execution confirm', () => {
  it('skips confirm when confirm=false and runs handler once', async () => {
    const confirmAction = vi.fn(async () => false)
    const handler = vi.fn(async () => ({ ok: true }))
    const bridge = createEafPageBridge({ confirmAction })
    bridge.registerAction('page.search.applyFilters', handler)

    const result = await bridge.handleEvent(request({ confirm: false }))
    expect(confirmAction).not.toHaveBeenCalled()
    expect(handler).toHaveBeenCalledTimes(1)
    expect(result?.status).toBe('SUCCESS')
    expect(result?.data).toEqual({ ok: true })
  })

  it('cancels before handler when confirm=true and confirm is rejected', async () => {
    const confirmAction = vi.fn(async () => false)
    const handler = vi.fn(async () => ({ ok: true }))
    const bridge = createEafPageBridge({ confirmAction })
    bridge.registerAction('page.search.applyFilters', handler)

    const result = await bridge.handleEvent(request({ confirm: true }))
    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(handler).toHaveBeenCalledTimes(0)
    expect(result?.status).toBe('CANCELLED')
    expect(result?.error).toContain('cancelled')
  })

  it('runs handler once when confirm=true and confirm is accepted', async () => {
    const confirmAction = vi.fn(async () => true)
    const handler = vi.fn(async () => ({ ok: true }))
    const bridge = createEafPageBridge({ confirmAction })
    bridge.registerAction('page.search.applyFilters', handler)

    const result = await bridge.handleEvent(request({ confirm: true }))
    expect(confirmAction).toHaveBeenCalledTimes(1)
    expect(handler).toHaveBeenCalledTimes(1)
    expect(result?.status).toBe('SUCCESS')
  })

  it('emits cancelled result through onResult and does not leave pending handler work', async () => {
    const confirmAction = vi.fn(async () => false)
    const handler = vi.fn(async () => ({ ok: true }))
    const onResult = vi.fn()
    const bridge = createEafPageBridge({ confirmAction })
    bridge.registerAction('page.search.applyFilters', handler)
    const off = bridge.onResult(onResult)

    const result = await bridge.handleEvent(request({ confirm: true }))
    expect(result?.status).toBe('CANCELLED')
    expect(onResult).toHaveBeenCalledTimes(1)
    expect(onResult.mock.calls[0]?.[0]?.status).toBe('CANCELLED')
    expect(handler).toHaveBeenCalledTimes(0)
    off()
  })
})
