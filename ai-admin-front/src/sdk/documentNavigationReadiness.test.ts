import { describe, expect, it, vi } from 'vitest'
import { createDocumentNavigationReadiness } from './documentNavigationReadiness'

const trace = 'a'.repeat(32)
const waiting = { metadata: { code: 'RUNTIME_GRAPH_INTERACTION_WAITING', traceId: trace }, uiRequest: {
  interactionId: `wfi_${'b'.repeat(32)}`,
  extension: { documentNavigation: { version: 1, navigationRequestId: 'nav-1' } },
} }
const delivered = { requestId: 'nav-1', actionKey: '__reachai.navigate', status: 'SUCCESS', userConfirmed: true } as const

describe('document navigation readiness', () => {
  it.each(['waiting-first', 'claim-first'])('requires matching persisted waiting and confirmed ACK: %s', order => {
    const ready = vi.fn()
    const state = createDocumentNavigationReadiness(ready)
    state.started('s', 'turn')
    if (order === 'waiting-first') state.waiting('s', 'turn', waiting)
    state.claimed('s', 'nav-1', '__reachai.navigate', trace)
    state.delivered('s', delivered)
    if (order === 'claim-first') {
      expect(ready).not.toHaveBeenCalled()
      state.waiting('s', 'turn', waiting)
    }
    expect(ready).toHaveBeenCalledTimes(1)
    expect(ready).toHaveBeenCalledWith({ sessionId: 's', requestId: 'nav-1', traceId: trace, turnId: 'turn' })
    state.delivered('s', delivered)
    expect(ready).toHaveBeenCalledTimes(1)
  })
  it('rejects cancellation, wrong trace, wrong session, and stale turns', () => {
    const ready = vi.fn()
    const state = createDocumentNavigationReadiness(ready)
    state.started('s', 'turn')
    state.claimed('s', 'nav-1', '__reachai.navigate', trace)
    state.waiting('s', 'turn', waiting)
    state.delivered('s', { ...delivered, userConfirmed: false })
    state.delivered('other', delivered)
    state.started('s', 'new-turn')
    state.delivered('s', delivered)
    state.waiting('s', 'new-turn', { ...waiting, metadata: { ...waiting.metadata, traceId: 'c'.repeat(32) } })
    expect(ready).not.toHaveBeenCalled()
  })
  it.each(['waiting-first', 'cancel-first'])('resolves a cancellation only for its waiting session: %s', order => {
    const ready = vi.fn()
    const cancelled = vi.fn()
    const state = createDocumentNavigationReadiness(ready, cancelled)
    state.started('s', 'turn')
    if (order === 'waiting-first') state.waiting('s', 'turn', waiting)
    state.delivered('other', { ...delivered, status: 'USER_CANCELLED', userConfirmed: false })
    expect(cancelled).not.toHaveBeenCalled()
    state.delivered('s', { ...delivered, status: 'USER_CANCELLED', userConfirmed: false })
    if (order === 'cancel-first') state.waiting('s', 'turn', waiting)
    expect(cancelled).toHaveBeenCalledOnce()
    expect(cancelled).toHaveBeenCalledWith({ sessionId: 's', requestId: 'nav-1', traceId: trace, turnId: 'turn' })
    state.delivered('s', { ...delivered, status: 'USER_CANCELLED', userConfirmed: false })
    expect(cancelled).toHaveBeenCalledOnce()
    expect(ready).not.toHaveBeenCalled()
  })
  it('does not cancel a stale or unacknowledged waiting turn', () => {
    const cancelled = vi.fn()
    const state = createDocumentNavigationReadiness(vi.fn(), cancelled)
    state.started('s', 'old')
    state.waiting('s', 'old', waiting)
    state.started('s', 'new')
    state.delivered('s', { ...delivered, status: 'USER_CANCELLED', userConfirmed: false })
    expect(cancelled).not.toHaveBeenCalled()
  })
})
