import { beforeEach, describe, expect, it } from 'vitest'
import {
  acknowledgeExplorationNotice,
  clearPlatformSessionMetadata,
  discardLegacyPlatformPersistentSession,
  getPlatformSessionExpiresAt,
  getPlatformSessionId,
  hasAcknowledgedExplorationNotice,
  isPlatformLogoutEvent,
  platformCsrfHeaders,
  publishPlatformLogoutEvent,
  setPlatformSessionExpiresAt,
  setPlatformSessionId,
  setPlatformUser,
  PLATFORM_CSRF_HEADER,
  PLATFORM_SESSION_EVENT_KEY,
} from './platformAuth'

describe('platform auth browser storage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('stores only non-secret session metadata in window-scoped storage', () => {
    setPlatformSessionExpiresAt('2030-01-01T00:00:00.000Z')
    setPlatformSessionId('pls_1')
    setPlatformUser({ userId: 7, username: 'admin' })

    expect(getPlatformSessionExpiresAt()).toBe('2030-01-01T00:00:00.000Z')
    expect(getPlatformSessionId()).toBe('pls_1')
    expect(sessionStorage.getItem('reachai.platform.accessToken')).toBeNull()
    expect(localStorage.getItem('reachai.platform.accessToken')).toBeNull()
  })

  it('derives the synchronizer CSRF header from the current server session id', () => {
    setPlatformSessionId('pls_1')

    const headers = platformCsrfHeaders({ Accept: 'application/json' })

    expect(headers.get(PLATFORM_CSRF_HEADER)).toBe('pls_1')
    expect(headers.get('Accept')).toBe('application/json')
  })

  it('shares exploration acknowledgement by server session rather than by user or tab', () => {
    acknowledgeExplorationNotice('pls_7a')

    expect(hasAcknowledgedExplorationNotice('pls_7a')).toBe(true)
    expect(hasAcknowledgedExplorationNotice('pls_7b')).toBe(false)
    expect(localStorage.getItem('reachai.platform.explorationNotice.ack.v4.pls_7a')).toBe('true')
  })

  it('clears only the current sessions exploration acknowledgement on logout', () => {
    setPlatformUser({ userId: 7, username: 'admin' })
    setPlatformSessionId('pls_7a')
    acknowledgeExplorationNotice('pls_7a')
    acknowledgeExplorationNotice('pls_7b')

    clearPlatformSessionMetadata()

    expect(hasAcknowledgedExplorationNotice('pls_7a')).toBe(false)
    expect(hasAcknowledgedExplorationNotice('pls_7b')).toBe(true)
  })

  it('deletes legacy persistent and per-tab tokens instead of migrating them', () => {
    localStorage.setItem('reachai.platform.accessToken', 'pat_legacy')
    localStorage.setItem('reachai.platform.user', JSON.stringify({ userId: 7, username: 'admin' }))
    localStorage.setItem('reachai.platform.expiresAt', '2030-01-01T00:00:00.000Z')
    sessionStorage.setItem('reachai.platform.accessToken', 'pat_tab')
    sessionStorage.setItem('reachai.platform.explorationNotice.ack.v3.pls_7', 'true')

    discardLegacyPlatformPersistentSession()

    expect(localStorage.getItem('reachai.platform.accessToken')).toBeNull()
    expect(localStorage.getItem('reachai.platform.user')).toBeNull()
    expect(localStorage.getItem('reachai.platform.expiresAt')).toBeNull()
    expect(sessionStorage.getItem('reachai.platform.accessToken')).toBeNull()
    expect(sessionStorage.getItem('reachai.platform.explorationNotice.ack.v3.pls_7')).toBeNull()
  })

  it('publishes a non-secret logout event for other tabs', () => {
    publishPlatformLogoutEvent()

    const value = localStorage.getItem(PLATFORM_SESSION_EVENT_KEY)
    expect(value).not.toContain('accessToken')
    expect(isPlatformLogoutEvent(new StorageEvent('storage', {
      key: PLATFORM_SESSION_EVENT_KEY,
      newValue: value,
    }))).toBe(true)
  })

  it('falls back only to memory when session storage is unavailable', () => {
    const originalSessionStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
    Object.defineProperty(globalThis, 'sessionStorage', {
      configurable: true,
      get: () => {
        throw new Error('session storage is unavailable')
      },
    })

    try {
      setPlatformSessionExpiresAt('2030-01-01T00:00:00.000Z')
      setPlatformSessionId('pls_memory_only')

      expect(getPlatformSessionExpiresAt()).toBe('2030-01-01T00:00:00.000Z')
      expect(getPlatformSessionId()).toBe('pls_memory_only')
      clearPlatformSessionMetadata()
      expect(getPlatformSessionId()).toBe('')
    } finally {
      if (originalSessionStorage) {
        Object.defineProperty(globalThis, 'sessionStorage', originalSessionStorage)
      } else {
        Reflect.deleteProperty(globalThis, 'sessionStorage')
      }
    }
  })
})
