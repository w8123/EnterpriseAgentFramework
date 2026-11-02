import { describe, expect, it, vi } from 'vitest'
import {
  EAF_PAGE_NAVIGATE_ACTION,
  createEafPageBridge,
  type PageActionRequest,
} from './eafPageBridge'

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
  it('does not claim or run an action replaced while confirmation is open', async () => {
    let decide!: (accepted: boolean) => void
    const decision = new Promise<boolean>((resolve) => { decide = resolve })
    const oldHandler = vi.fn()
    const newHandler = vi.fn()
    const beforeExecute = vi.fn(async () => true)
    const bridge = createEafPageBridge({ confirmAction: () => decision })
    bridge.registerAction('page.search.applyFilters', oldHandler)
    const pending = bridge.handleEvent(request({ confirm: true }), { beforeExecute })
    bridge.registerAction('page.search.applyFilters', newHandler)
    decide(true)
    await expect(pending).resolves.toMatchObject({ status: 'ACTION_NOT_FOUND' })
    expect(beforeExecute).not.toHaveBeenCalled()
    expect(oldHandler).not.toHaveBeenCalled()
    expect(newHandler).not.toHaveBeenCalled()
  })

  it('does not run an unregistered action after its server claim completes', async () => {
    let finishClaim!: (claimed: boolean) => void
    let signalClaim!: () => void
    const started = new Promise<void>((resolve) => { signalClaim = resolve })
    const claim = new Promise<boolean>((resolve) => { finishClaim = resolve })
    const handler = vi.fn()
    const bridge = createEafPageBridge()
    const unregister = bridge.registerAction('page.search.applyFilters', handler)
    const pending = bridge.handleEvent(request(), { beforeExecute: () => { signalClaim(); return claim } })
    await started
    unregister()
    finishClaim(true)
    await expect(pending).resolves.toMatchObject({ status: 'ACTION_NOT_FOUND' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('does not run a retained old-page handler after the bound page changes', async () => {
    let finishClaim!: (claimed: boolean) => void
    let signalClaim!: () => void
    let currentPage = true
    const started = new Promise<void>((resolve) => { signalClaim = resolve })
    const claim = new Promise<boolean>((resolve) => { finishClaim = resolve })
    const handler = vi.fn()
    const bridge = createEafPageBridge()
    bridge.registerAction('page.search.applyFilters', handler)
    const pending = bridge.handleEvent(request(), {
      beforeExecute: () => { signalClaim(); return claim }, isCurrentPage: () => currentPage,
    })
    await started
    currentPage = false
    finishClaim(true)
    await expect(pending).resolves.toMatchObject({ status: 'ACTION_NOT_FOUND' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('keeps a replacement action when the earlier registration is disposed', async () => {
    const bridge = createEafPageBridge()
    const handler = vi.fn(async () => ({ ok: true }))
    const disposeOld = bridge.registerAction('page.search.applyFilters', handler, { title: 'Old' })
    const disposeNew = bridge.registerAction('page.search.applyFilters', handler, { title: 'New' })

    disposeOld()
    expect(bridge.actionDefinitions).toMatchObject([{ actionKey: 'page.search.applyFilters', title: 'New' }])
    expect(await bridge.handleEvent(request())).toMatchObject({ status: 'SUCCESS' })
    disposeNew()
    expect(bridge.registeredActions).toEqual([])
  })

  it('skips confirm when confirm=false and runs handler once', async () => {
    const confirmAction = vi.fn(async () => false)
    const handler = vi.fn(async () => ({ ok: true }))
    const bridge = createEafPageBridge({ confirmAction })
    bridge.registerAction('page.search.applyFilters', handler)

    const result = await bridge.handleEvent(request({ confirm: false }))
    expect(confirmAction).not.toHaveBeenCalled()
    expect(handler).toHaveBeenCalledTimes(1)
    expect(result).toMatchObject({ status: 'SUCCESS' })
    expect(result?.userConfirmed).toBeUndefined()
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
    expect(result?.status).toBe('USER_CANCELLED')
    expect(result?.message).toContain('cancelled')
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

  it('uses a non-blocking accessible dialog for the default confirmation', async () => {
    const nativeConfirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const handler = vi.fn(async () => ({ ok: true }))
    const bridge = createEafPageBridge()
    bridge.registerAction('page.search.applyFilters', handler)

    const pending = bridge.handleEvent(request({ confirm: true, title: 'Apply filters' }))
    const dialog = document.querySelector<HTMLElement>('[role="alertdialog"]')
    expect(dialog?.textContent).toContain('Apply filters')
    expect(dialog?.getAttribute('aria-modal')).toBe('true')
    expect(nativeConfirm).not.toHaveBeenCalled()

    const confirmButton = Array.from(dialog?.querySelectorAll('button') || [])
      .find(button => button.textContent === '确认执行') as HTMLButtonElement
    confirmButton.click()

    await expect(pending).resolves.toMatchObject({ status: 'SUCCESS', userConfirmed: true })
    expect(handler).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[role="alertdialog"]')).toBeNull()
    nativeConfirm.mockRestore()
  })

  it('returns USER_CANCELLED when the default confirmation is cancelled', async () => {
    const handler = vi.fn(async () => ({ ok: true }))
    const bridge = createEafPageBridge()
    bridge.registerAction('page.search.applyFilters', handler)

    const pending = bridge.handleEvent(request({ confirm: true }))
    const cancelButton = Array.from(document.querySelectorAll('[role="alertdialog"] button'))
      .find(button => button.textContent === '取消') as HTMLButtonElement
    cancelButton.click()

    await expect(pending).resolves.toMatchObject({ status: 'USER_CANCELLED' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('closes the default confirmation when the client is disposed', async () => {
    const lifetime = new AbortController()
    const handler = vi.fn()
    const bridge = createEafPageBridge()
    bridge.registerAction('page.search.applyFilters', handler)
    const pending = bridge.handleEvent(request({ confirm: true }), { signal: lifetime.signal, isActive: () => !lifetime.signal.aborted })
    expect(document.querySelector('[role="alertdialog"]')).not.toBeNull()
    lifetime.abort()
    await expect(pending).resolves.toBeNull()
    expect(document.querySelector('[role="alertdialog"]')).toBeNull()
    expect(handler).not.toHaveBeenCalled()
  })

  it('settles the previous request when a new confirmation replaces its dialog', async () => {
    const bridge = createEafPageBridge()
    const handler = vi.fn()
    bridge.registerAction('page.search.applyFilters', handler)
    const previous = bridge.handleEvent(request({ confirm: true, requestId: 'old' }))
    const next = bridge.handleEvent(request({ confirm: true, requestId: 'new' }))
    await expect(previous).resolves.toMatchObject({ status: 'USER_CANCELLED' })
    const cancel = Array.from(document.querySelectorAll('[role="alertdialog"] button'))
      .find(button => button.textContent === '取消') as HTMLButtonElement
    cancel.click()
    await expect(next).resolves.toMatchObject({ status: 'USER_CANCELLED' })
    expect(handler).not.toHaveBeenCalled()
  })

  it('claims execution after confirmation and before invoking the business handler', async () => {
    const order: string[] = []
    const bridge = createEafPageBridge({
      confirmAction: async () => {
        order.push('confirm')
        return true
      },
    })
    bridge.registerAction('page.search.applyFilters', async () => {
      order.push('handler')
      return { ok: true }
    })

    const result = await bridge.handleEvent(request({ confirm: true }), {
      beforeExecute: async () => {
        order.push('claim')
        return true
      },
    })

    expect(order).toEqual(['confirm', 'claim', 'handler'])
    expect(result?.status).toBe('SUCCESS')
  })

  it('does not invoke the business handler when the server lease already expired', async () => {
    const handler = vi.fn(async () => ({ ok: true }))
    const bridge = createEafPageBridge({ confirmAction: async () => true })
    bridge.registerAction('page.search.applyFilters', handler)

    const result = await bridge.handleEvent(request({ confirm: true }), {
      beforeExecute: async () => false,
    })

    expect(handler).not.toHaveBeenCalled()
    expect(result).toMatchObject({
      status: 'TIMEOUT',
      error: 'Page action request expired before execution',
    })
  })

  it('emits cancelled result through onResult and does not leave pending handler work', async () => {
    const confirmAction = vi.fn(async () => false)
    const handler = vi.fn(async () => ({ ok: true }))
    const onResult = vi.fn()
    const bridge = createEafPageBridge({ confirmAction })
    bridge.registerAction('page.search.applyFilters', handler)
    const off = bridge.onResult(onResult)

    const result = await bridge.handleEvent(request({ confirm: true }))
    expect(result?.status).toBe('USER_CANCELLED')
    expect(onResult).toHaveBeenCalledTimes(1)
    expect(onResult.mock.calls[0]?.[0]?.status).toBe('USER_CANCELLED')
    expect(handler).toHaveBeenCalledTimes(0)
    off()
  })

  it('keeps a handler business-terminal outcome instead of converting it to success', async () => {
    const bridge = createEafPageBridge()
    bridge.registerAction('page.search.applyFilters', async () => ({
      status: 'NO_DATA' as const,
      message: 'No matching teams',
      data: { total: 0 },
    }))

    const result = await bridge.handleEvent(request())

    expect(result).toMatchObject({
      status: 'NO_DATA',
      message: 'No matching teams',
      data: { total: 0 },
    })
  })

  it('uses the formal navigation adapter without requiring a business action registration', async () => {
    const onNavigate = vi.fn(async () => undefined)
    const bridge = createEafPageBridge({ onNavigate })

    const result = await bridge.handleEvent(request({
      actionKey: EAF_PAGE_NAVIGATE_ACTION,
      metadata: {
        pageKey: 'qmssmp.team-build.cycle-audit',
        route: '/team-build/cycle-management/audit/depart-1/cycle-1',
      },
    }))

    expect(onNavigate).toHaveBeenCalledWith(expect.objectContaining({
      requestId: 'req-1',
      targetPageKey: 'qmssmp.team-build.cycle-audit',
      route: '/team-build/cycle-management/audit/depart-1/cycle-1',
    }))
    expect(result).toMatchObject({
      status: 'SUCCESS',
      data: {
        pageKey: 'qmssmp.team-build.cycle-audit',
        route: '/team-build/cycle-management/audit/depart-1/cycle-1',
        ready: false,
      },
    })
    expect((result?.data as Record<string, unknown>).pageInstanceId).toBeUndefined()
  })

  it('returns a discoverable failure when a cross-route adapter is missing', async () => {
    const bridge = createEafPageBridge()

    const result = await bridge.handleEvent(request({
      actionKey: EAF_PAGE_NAVIGATE_ACTION,
      metadata: { pageKey: 'orders.audit', route: '/orders/audit' },
    }))

    expect(result?.status).toBe('ACTION_NOT_FOUND')
    expect(result?.error).toContain('onNavigate')
  })
})
