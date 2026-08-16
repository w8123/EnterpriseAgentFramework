import { beforeEach, describe, expect, it } from 'vitest'
import {
  acknowledgeExplorationNotice,
  clearPlatformToken,
  discardLegacyPlatformPersistentSession,
  getPlatformSessionExpiresAt,
  getPlatformSessionId,
  getPlatformToken,
  hasAcknowledgedExplorationNotice,
  hasLocallyExpiredPlatformSession,
  setPlatformSessionId,
  setPlatformToken,
  setPlatformUser,
} from './platformAuth'

describe('platform auth browser storage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('stores the platform token and expiry in window-scoped session storage', () => {
    setPlatformToken('pat_1', '2030-01-01T00:00:00.000Z')
    setPlatformSessionId('pls_1')

    expect(getPlatformToken()).toBe('pat_1')
    expect(getPlatformSessionExpiresAt()).toBe('2030-01-01T00:00:00.000Z')
    expect(getPlatformSessionId()).toBe('pls_1')
    expect(localStorage.getItem('reachai.platform.accessToken')).toBeNull()
    expect(hasLocallyExpiredPlatformSession(Date.parse('2029-01-01T00:00:00.000Z'))).toBe(false)
    expect(hasLocallyExpiredPlatformSession(Date.parse('2031-01-01T00:00:00.000Z'))).toBe(true)
  })

  it('scopes exploration acknowledgement to the server session rather than the user', () => {
    acknowledgeExplorationNotice('pls_7a')

    expect(hasAcknowledgedExplorationNotice('pls_7a')).toBe(true)
    expect(hasAcknowledgedExplorationNotice('pls_7b')).toBe(false)
  })

  it('clears only the current sessions exploration acknowledgement on logout', () => {
    setPlatformUser({ userId: 7, username: 'admin' })
    setPlatformSessionId('pls_7a')
    acknowledgeExplorationNotice('pls_7a')
    acknowledgeExplorationNotice('pls_7b')

    clearPlatformToken()

    expect(hasAcknowledgedExplorationNotice('pls_7a')).toBe(false)
    expect(hasAcknowledgedExplorationNotice('pls_7b')).toBe(true)
  })

  it('deletes legacy persistent tokens instead of migrating them to this window', () => {
    localStorage.setItem('reachai.platform.accessToken', 'pat_legacy')
    localStorage.setItem('reachai.platform.user', JSON.stringify({ userId: 7, username: 'admin' }))
    localStorage.setItem('reachai.platform.expiresAt', '2030-01-01T00:00:00.000Z')
    sessionStorage.setItem('reachai.platform.explorationNotice.ack.v2.7', 'true')

    discardLegacyPlatformPersistentSession()

    expect(localStorage.getItem('reachai.platform.accessToken')).toBeNull()
    expect(localStorage.getItem('reachai.platform.user')).toBeNull()
    expect(localStorage.getItem('reachai.platform.expiresAt')).toBeNull()
    expect(sessionStorage.getItem('reachai.platform.explorationNotice.ack.v2.7')).toBeNull()
    expect(getPlatformToken()).toBe('')
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
      setPlatformToken('pat_memory_only', '2030-01-01T00:00:00.000Z')
      setPlatformSessionId('pls_memory_only')

      expect(getPlatformToken()).toBe('pat_memory_only')
      expect(getPlatformSessionId()).toBe('pls_memory_only')
      expect(localStorage.getItem('reachai.platform.accessToken')).toBeNull()
      clearPlatformToken()
      expect(getPlatformToken()).toBe('')
    } finally {
      if (originalSessionStorage) {
        Object.defineProperty(globalThis, 'sessionStorage', originalSessionStorage)
      } else {
        Reflect.deleteProperty(globalThis, 'sessionStorage')
      }
    }
  })
})
