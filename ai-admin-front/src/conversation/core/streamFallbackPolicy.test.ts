import { describe, expect, it } from 'vitest'
import {
  canFallbackFromStreamFailure,
  isAbortLikeError,
  isSafeStreamUnsupportedStatus,
} from './streamFallbackPolicy'

describe('streamFallbackPolicy', () => {
  it('allows fallback only for 404/405/415/501', () => {
    expect(isSafeStreamUnsupportedStatus(404)).toBe(true)
    expect(isSafeStreamUnsupportedStatus(405)).toBe(true)
    expect(isSafeStreamUnsupportedStatus(415)).toBe(true)
    expect(isSafeStreamUnsupportedStatus(501)).toBe(true)
    expect(isSafeStreamUnsupportedStatus(500)).toBe(false)
    expect(isSafeStreamUnsupportedStatus(200)).toBe(false)
    expect(isSafeStreamUnsupportedStatus(401)).toBe(false)
  })

  it('detects AbortError', () => {
    expect(isAbortLikeError(new DOMException('aborted', 'AbortError'))).toBe(true)
    expect(isAbortLikeError(new Error('network'))).toBe(false)
  })

  it('forbids fallback when bytes/events already consumed', () => {
    expect(canFallbackFromStreamFailure({
      status: 404,
      bytesOrEventsConsumed: true,
    })).toBe(false)
  })

  it('forbids fallback on abort even with 404', () => {
    expect(canFallbackFromStreamFailure({
      status: 404,
      bytesOrEventsConsumed: false,
      aborted: true,
    })).toBe(false)
    expect(canFallbackFromStreamFailure({
      status: 404,
      bytesOrEventsConsumed: false,
      error: new DOMException('aborted', 'AbortError'),
    })).toBe(false)
  })

  it('allows fallback for unsupported stream before any body consumed', () => {
    expect(canFallbackFromStreamFailure({
      status: 404,
      bytesOrEventsConsumed: false,
    })).toBe(true)
    expect(canFallbackFromStreamFailure({
      status: 501,
      bytesOrEventsConsumed: false,
    })).toBe(true)
  })

  it('forbids fallback when status missing (mid-stream disconnect)', () => {
    expect(canFallbackFromStreamFailure({
      bytesOrEventsConsumed: false,
      error: new Error('network reset'),
    })).toBe(false)
  })
})
