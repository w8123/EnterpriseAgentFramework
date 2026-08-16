import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  acknowledgePlatformExplorationNotice,
  bootstrapPlatformSession,
  isPlatformAuthenticated,
  isPlatformWorkspaceReady,
  markPlatformSessionAuthenticated,
  platformSessionId,
  platformSessionState,
  platformSessionUser,
  PLATFORM_SESSION_INVALID_HEADER,
  PLATFORM_SESSION_INVALID_VALUE,
  resetPlatformSessionForTest,
  requiresPlatformExplorationAcknowledgement,
  sanitizePlatformRedirect,
} from './platformSession'
import {
  getPlatformToken,
  setPlatformToken,
  type PlatformUserProfile,
} from '@/utils/platformAuth'

const profile: PlatformUserProfile = {
  userId: 7,
  username: 'admin',
  displayName: '管理员',
  roles: ['PLATFORM_ADMIN'],
  permissions: ['*'],
}

const sessionView = {
  sessionId: 'pls_7a',
  expiresAt: '2030-01-01T00:00:00.000Z',
  principal: profile,
}

function response(status: number, body?: unknown, headers: Record<string, string> = {}) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: (name: string) => headers[name] || null },
    json: async () => body,
  }
}

describe('platform session bootstrap', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
    resetPlatformSessionForTest()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('does not unlock a protected route when no saved token exists', async () => {
    await expect(bootstrapPlatformSession()).resolves.toBe('ANONYMOUS')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(isPlatformAuthenticated.value).toBe(false)
  })

  it('accepts a window token only after Control accepts the session view from /me', async () => {
    setPlatformToken('pat_valid', sessionView.expiresAt)
    fetchMock.mockResolvedValue(response(200, sessionView))

    await expect(bootstrapPlatformSession()).resolves.toBe('AUTHENTICATED')

    expect(fetchMock).toHaveBeenCalledWith('/api/platform/auth/me', expect.objectContaining({
      headers: { Authorization: 'Bearer pat_valid' },
      signal: expect.any(AbortSignal),
    }))
    expect(platformSessionUser.value).toEqual(profile)
    expect(platformSessionId.value).toBe('pls_7a')
    expect(isPlatformAuthenticated.value).toBe(true)
    expect(requiresPlatformExplorationAcknowledgement.value).toBe(true)
    expect(isPlatformWorkspaceReady.value).toBe(false)
  })

  it('shares one /me request across concurrent protected-route navigations', async () => {
    setPlatformToken('pat_valid', sessionView.expiresAt)
    let completeRequest: ((value: ReturnType<typeof response>) => void) | undefined
    fetchMock.mockImplementation(() => new Promise((resolve) => {
      completeRequest = resolve
    }))

    const firstNavigation = bootstrapPlatformSession()
    const secondNavigation = bootstrapPlatformSession()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    completeRequest?.(response(200, sessionView))
    await expect(Promise.all([firstNavigation, secondNavigation])).resolves.toEqual([
      'AUTHENTICATED',
      'AUTHENTICATED',
    ])
  })

  it('unlocks the target workspace only after the authenticated session acknowledges the notice', async () => {
    markPlatformSessionAuthenticated(sessionView)

    expect(acknowledgePlatformExplorationNotice()).toBe(true)

    expect(requiresPlatformExplorationAcknowledgement.value).toBe(false)
    expect(isPlatformWorkspaceReady.value).toBe(true)
  })

  it('clears an explicitly invalid platform session before any console page can render', async () => {
    setPlatformToken('pat_revoked', sessionView.expiresAt)
    fetchMock.mockResolvedValue(response(401, undefined, {
      [PLATFORM_SESSION_INVALID_HEADER]: PLATFORM_SESSION_INVALID_VALUE,
    }))

    await expect(bootstrapPlatformSession()).resolves.toBe('ANONYMOUS')

    expect(getPlatformToken()).toBe('')
    expect(platformSessionState.value).toBe('ANONYMOUS')
  })

  it('does not clear a token when a 401 lacks the explicit platform-session signal', async () => {
    setPlatformToken('pat_valid', sessionView.expiresAt)
    fetchMock.mockResolvedValue(response(401))

    await expect(bootstrapPlatformSession()).resolves.toBe('UNAVAILABLE')

    expect(getPlatformToken()).toBe('pat_valid')
  })

  it('does not mistake an unavailable Control service for logout', async () => {
    setPlatformToken('pat_valid', sessionView.expiresAt)
    fetchMock.mockRejectedValue(new Error('offline'))

    await expect(bootstrapPlatformSession()).resolves.toBe('UNAVAILABLE')

    expect(getPlatformToken()).toBe('pat_valid')
  })

  it('keeps the current window token when Control returns 403', async () => {
    setPlatformToken('pat_valid', sessionView.expiresAt)
    fetchMock.mockResolvedValue(response(403))

    await expect(bootstrapPlatformSession()).resolves.toBe('UNAVAILABLE')

    expect(getPlatformToken()).toBe('pat_valid')
  })

  it('times out a hanging /me request without clearing the current window token', async () => {
    vi.useFakeTimers()
    setPlatformToken('pat_valid', sessionView.expiresAt)
    fetchMock.mockImplementation((_url: string, init: RequestInit) => new Promise((_, reject) => {
      init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))

    const result = bootstrapPlatformSession()
    await vi.advanceTimersByTimeAsync(8_000)

    await expect(result).resolves.toBe('UNAVAILABLE')
    expect(getPlatformToken()).toBe('pat_valid')
  })

  it('does not call Control for a locally expired session', async () => {
    setPlatformToken('pat_expired', '2020-01-01T00:00:00.000Z')

    await expect(bootstrapPlatformSession()).resolves.toBe('ANONYMOUS')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(getPlatformToken()).toBe('')
  })

  it('clears legacy localStorage credentials rather than accepting them in a new window', async () => {
    localStorage.setItem('reachai.platform.accessToken', 'pat_legacy')
    localStorage.setItem('reachai.platform.expiresAt', sessionView.expiresAt)

    await expect(bootstrapPlatformSession()).resolves.toBe('ANONYMOUS')

    expect(fetchMock).not.toHaveBeenCalled()
    expect(localStorage.getItem('reachai.platform.accessToken')).toBeNull()
  })

  it('keeps a successful login authenticated without another bootstrap request', async () => {
    markPlatformSessionAuthenticated(sessionView)

    await expect(bootstrapPlatformSession()).resolves.toBe('AUTHENTICATED')

    expect(fetchMock).not.toHaveBeenCalled()
  })
})

describe('sanitizePlatformRedirect', () => {
  it('allows only same-origin router paths', () => {
    expect(sanitizePlatformRedirect('/registry/projects/demo/sdk-access')).toBe(
      '/registry/projects/demo/sdk-access',
    )
    expect(sanitizePlatformRedirect('//evil.example')).toBe('/dashboard')
    expect(sanitizePlatformRedirect('https://evil.example')).toBe('/dashboard')
    expect(sanitizePlatformRedirect('dashboard')).toBe('/dashboard')
  })
})
