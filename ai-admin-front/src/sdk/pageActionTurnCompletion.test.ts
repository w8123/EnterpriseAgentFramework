import { describe, expect, it, vi } from 'vitest'
import { createPageActionTurnCompletion } from './pageActionTurnCompletion'

const traceA = 'a'.repeat(32)
const traceB = 'b'.repeat(32)
const result = (requestId: string, status: 'SUCCESS' | 'USER_CANCELLED' = 'SUCCESS') => ({
  requestId, actionKey: 'search', status, userConfirmed: status === 'SUCCESS',
})

describe('page action and completed turn correlation', () => {
  it.each(['ack-first', 'turn-first'])('settles once in either order: %s', (order) => {
    const emit = vi.fn()
    const tracker = createPageActionTurnCompletion(emit)
    tracker.started('session-a', 'turn-a')
    tracker.claimed('session-a', 'req-a', 'search', traceA)
    if (order === 'ack-first') tracker.delivered('session-a', result('req-a'))
    else tracker.completed('session-a', traceA, 'turn-a')
    expect(emit).not.toHaveBeenCalled()
    if (order === 'ack-first') tracker.completed('session-a', traceA, 'turn-a')
    else tracker.delivered('session-a', result('req-a'))
    expect(emit).toHaveBeenCalledExactlyOnceWith({ ...result('req-a'), traceId: traceA, sessionId: 'session-a', turnId: 'turn-a' })
    tracker.delivered('session-a', result('req-a'))
    tracker.completed('session-a', traceA, 'turn-a')
    expect(emit).toHaveBeenCalledTimes(1)
  })

  it('does not correlate another session, trace or a missing attribution', () => {
    const emit = vi.fn()
    const tracker = createPageActionTurnCompletion(emit)
    tracker.started('session-a', 'turn-a')
    tracker.started('session-b', 'turn-b')
    tracker.claimed('session-a', 'req-a', 'search', traceA)
    tracker.delivered('session-b', result('req-a'))
    tracker.completed('session-a', traceB, 'turn-a')
    tracker.completed('session-b', traceA, 'turn-b')
    tracker.claimed('session-a', 'req-b', 'search', undefined)
    tracker.delivered('session-a', result('req-b'))
    tracker.completed('session-a', traceA, 'turn-a')
    expect(emit).not.toHaveBeenCalled()
  })

  it('does not bind an older request to a later turn or overwrite its identity', () => {
    const emit = vi.fn()
    const tracker = createPageActionTurnCompletion(emit)
    tracker.started('session-a', 'turn-a')
    tracker.claimed('session-a', 'req-a', 'search', traceA)
    tracker.claimed('session-a', 'req-a', 'search', traceB)
    tracker.delivered('session-a', result('req-a'))
    tracker.completed('session-a', traceB, 'turn-a')
    expect(emit).not.toHaveBeenCalled()
    tracker.completed('session-a', traceA, 'turn-a')
    expect(emit).toHaveBeenCalledTimes(1)
  })

  it('clears pending work on destruction', () => {
    const emit = vi.fn()
    const tracker = createPageActionTurnCompletion(emit)
    tracker.started('session-a', 'turn-a')
    tracker.claimed('session-a', 'req-a', 'search', traceA)
    tracker.delivered('session-a', result('req-a'))
    tracker.clear()
    tracker.completed('session-a', traceA, 'turn-a')
    expect(emit).not.toHaveBeenCalled()
  })

  it('does not authorize navigation for a cancelled result or an unconfirmed success', () => {
    const navigate = vi.fn()
    const tracker = createPageActionTurnCompletion((event) => {
      if (event.status === 'SUCCESS' && event.userConfirmed === true) navigate(event.requestId)
    })
    tracker.started('session-a', 'turn-a')
    tracker.claimed('session-a', 'cancelled', 'search', traceA)
    tracker.delivered('session-a', result('cancelled', 'USER_CANCELLED'))
    tracker.claimed('session-a', 'unconfirmed', 'search', traceA)
    tracker.delivered('session-a', { ...result('unconfirmed'), userConfirmed: false })
    tracker.completed('session-a', traceA, 'turn-a')
    expect(navigate).not.toHaveBeenCalled()
  })

  it('does not navigate an old acknowledged action after a newer turn starts', () => {
    const emit = vi.fn()
    const tracker = createPageActionTurnCompletion(emit)
    tracker.started('session-a', 'turn-a')
    tracker.claimed('session-a', 'req-a', 'search', traceA)
    tracker.completed('session-a', traceA, 'turn-a')
    tracker.started('session-a', 'turn-b')
    tracker.delivered('session-a', result('req-a'))
    tracker.completed('session-a', traceA, 'turn-a') // stale terminal must not resurrect it
    tracker.completed('session-a', traceB, 'turn-b')
    expect(emit).not.toHaveBeenCalled()
  })

  it('invalidates an old completion as soon as the next turn is prepared', () => {
    const emit = vi.fn()
    const tracker = createPageActionTurnCompletion(emit)
    tracker.started('session-a', 'turn-a')
    tracker.claimed('session-a', 'req-a', 'search', traceA)
    tracker.completed('session-a', traceA, 'turn-a')
    tracker.preparing('session-a') // the next network response has not arrived yet
    tracker.delivered('session-a', result('req-a'))
    expect(emit).not.toHaveBeenCalled()
    tracker.started('session-a', 'turn-b')
    tracker.completed('session-a', traceB, 'turn-b')
    expect(emit).not.toHaveBeenCalled()
  })
})
