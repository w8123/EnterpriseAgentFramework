import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import {
  createPendingPageActionPollScheduler,
  PENDING_POLL_DEFAULTS,
} from './pendingPageActionPollScheduler'

describe('createPendingPageActionPollScheduler', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('stays dormant without session or actions', async () => {
    const poll = vi.fn(async () => {})
    const scheduler = createPendingPageActionPollScheduler({
      getSessionId: () => null,
      hasRegisteredActions: () => true,
      isDestroyed: () => false,
      isDocumentHidden: () => false,
      poll,
    })
    scheduler.notifyActivity()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(poll).not.toHaveBeenCalled()
    scheduler.destroy()
  })

  it('polls immediately on activity then idles at most once per 10s', async () => {
    const poll = vi.fn(async () => {})
    const scheduler = createPendingPageActionPollScheduler({
      getSessionId: () => 'sess',
      hasRegisteredActions: () => true,
      isDestroyed: () => false,
      isDocumentHidden: () => false,
      poll,
    })
    scheduler.notifyActivity()
    await vi.advanceTimersByTimeAsync(0)
    expect(poll).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(60_000)
    // active ~5s @1s + idle @10s → well under old 120×500ms storm
    expect(poll.mock.calls.length).toBeLessThanOrEqual(12)
    expect(poll.mock.calls.length).toBeGreaterThanOrEqual(6)
    scheduler.destroy()
  })

  it('does not overlap polls and runs at most one coalesced rerun', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => { release = resolve })
    const poll = vi.fn(async () => gate)
    const scheduler = createPendingPageActionPollScheduler({
      getSessionId: () => 'sess',
      hasRegisteredActions: () => true,
      isDestroyed: () => false,
      isDocumentHidden: () => false,
      poll,
    })
    scheduler.notifyActivity()
    await vi.advanceTimersByTimeAsync(0)
    expect(poll).toHaveBeenCalledTimes(1)
    expect(scheduler.isInFlight()).toBe(true)

    scheduler.notifyActivity()
    scheduler.notifyActivity()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(poll).toHaveBeenCalledTimes(1)

    release()
    await Promise.resolve()
    await vi.advanceTimersByTimeAsync(0)
    for (let i = 0; i < 10; i += 1) await Promise.resolve()
    expect(poll).toHaveBeenCalledTimes(2)
    scheduler.destroy()
  })

  it('backs off on consecutive errors and resets after success', async () => {
    const errors: unknown[] = []
    let shouldFail = true
    const poll = vi.fn(async () => {
      if (shouldFail) throw new Error('pending failed')
    })
    const scheduler = createPendingPageActionPollScheduler({
      getSessionId: () => 'sess',
      hasRegisteredActions: () => true,
      isDestroyed: () => false,
      isDocumentHidden: () => false,
      poll,
      onError: (error) => errors.push(error),
    })
    scheduler.notifyActivity()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(poll.mock.calls.length).toBeLessThanOrEqual(5)
    expect(errors.length).toBe(poll.mock.calls.length)

    const failedCount = poll.mock.calls.length
    shouldFail = false
    // 退避中途的 activity 不应清零错误计数；等到退避结束后成功一次再清零
    await vi.advanceTimersByTimeAsync(60_000)
    expect(poll.mock.calls.length).toBeGreaterThan(failedCount)

    const afterSuccess = poll.mock.calls.length
    scheduler.notifyActivity()
    await vi.advanceTimersByTimeAsync(1_000)
    // 成功后应能按 active 1s 节奏继续，而不是卡在 60s 退避
    expect(poll.mock.calls.length).toBeGreaterThan(afterSuccess)
    scheduler.destroy()
  })

  it('stops while hidden and compensates immediately when visible again', async () => {
    let hidden = false
    const poll = vi.fn(async () => {})
    let visibilityListener: (() => void) | undefined
    const scheduler = createPendingPageActionPollScheduler({
      getSessionId: () => 'sess',
      hasRegisteredActions: () => true,
      isDestroyed: () => false,
      isDocumentHidden: () => hidden,
      addVisibilityListener: (listener) => {
        visibilityListener = listener
        return () => { visibilityListener = undefined }
      },
      poll,
    })
    scheduler.notifyActivity()
    await vi.advanceTimersByTimeAsync(0)
    expect(poll).toHaveBeenCalledTimes(1)

    hidden = true
    visibilityListener?.()
    const callsWhileHiddenStart = poll.mock.calls.length
    await vi.advanceTimersByTimeAsync(60_000)
    expect(poll.mock.calls.length).toBe(callsWhileHiddenStart)

    hidden = false
    visibilityListener?.()
    await vi.advanceTimersByTimeAsync(0)
    expect(poll.mock.calls.length).toBe(callsWhileHiddenStart + 1)
    scheduler.destroy()
  })

  it('starts when actions go 0→1 with session and stops when back to 0', async () => {
    let actionCount = 0
    const poll = vi.fn(async () => {})
    const scheduler = createPendingPageActionPollScheduler({
      getSessionId: () => 'sess',
      hasRegisteredActions: () => actionCount > 0,
      isDestroyed: () => false,
      isDocumentHidden: () => false,
      poll,
    })
    scheduler.notifyActionsChanged(0, 0)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(poll).not.toHaveBeenCalled()

    actionCount = 1
    scheduler.notifyActionsChanged(0, 1)
    await vi.advanceTimersByTimeAsync(0)
    expect(poll).toHaveBeenCalledTimes(1)

    actionCount = 0
    scheduler.notifyActionsChanged(1, 0)
    const afterStop = poll.mock.calls.length
    await vi.advanceTimersByTimeAsync(30_000)
    expect(poll.mock.calls.length).toBe(afterStop)
    scheduler.destroy()
  })

  it('destroy clears timers and ignores later activity', async () => {
    const poll = vi.fn(async () => {})
    const scheduler = createPendingPageActionPollScheduler({
      getSessionId: () => 'sess',
      hasRegisteredActions: () => true,
      isDestroyed: () => false,
      isDocumentHidden: () => false,
      poll,
    })
    scheduler.notifyActivity()
    scheduler.destroy()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(poll).not.toHaveBeenCalled()
    scheduler.notifyActivity()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(poll).not.toHaveBeenCalled()
  })

  it('uses safe default timings that cannot become a 500ms storm', () => {
    expect(PENDING_POLL_DEFAULTS.activeIntervalMs).toBeGreaterThanOrEqual(1_000)
    expect(PENDING_POLL_DEFAULTS.idleIntervalMs).toBeGreaterThanOrEqual(10_000)
    expect(PENDING_POLL_DEFAULTS.backoffMs[0]).toBeGreaterThanOrEqual(5_000)
  })
})
