import { describe, expect, it } from 'vitest'
import { shouldHandlePlatformSessionFailure } from '@/api/request'

describe('platform authentication failure classification', () => {
  it('only treats Control explicit invalid-session 401 as a console logout', () => {
    expect(shouldHandlePlatformSessionFailure({
      config: {},
      response: { headers: { 'x-reachai-auth-failure': 'PLATFORM_SESSION_INVALID' } },
    })).toBe(true)
    expect(shouldHandlePlatformSessionFailure({
      config: {},
      response: { headers: {} },
    })).toBe(false)
  })

  it('honours explicit request classification', () => {
    expect(shouldHandlePlatformSessionFailure({
      config: { platformAuthFailure: 'handle' },
    })).toBe(true)
    expect(shouldHandlePlatformSessionFailure({
      config: { platformAuthFailure: 'ignore' },
      response: { headers: { 'x-reachai-auth-failure': 'PLATFORM_SESSION_INVALID' } },
    })).toBe(false)
  })
})
