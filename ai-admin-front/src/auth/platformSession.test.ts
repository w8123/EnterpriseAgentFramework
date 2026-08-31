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
  getPlatformSessionId,
  setPlatformSessionExpiresAt,
  setPlatformSessionId,
  setPlatformUser,
  PLATFORM_SESSION_EVENT_KEY,
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

function persistSessionMetadata(view = sessionView) {
  setPlatformSessionId(view.sessionId)
  setPlatformSessionExpiresAt(view.expiresAt)
  setPlatformUser(view.principal)
  resetPlatformSessionForTest()
}

describe('platform session bootstrap', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    window.history.replaceState({}, '', '/login')
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
    resetPlatformSessionForTest()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('asks Control about the HttpOnly cookie even when this tab has no metadata', async () => {
    fetchMock.mockResolvedValue(response(401, undefined, {
      [PLATFORM_SESSION_INVALID_HEADER]: PLATFORM_SESSION_INVALID_VALUE,
    }))

    await expect(bootstrapPlatformSession()).resolves.toBe('ANONYMOUS')

    expect(fetchMock).toHaveBeenCalledWith('/api/platform/auth/me', expect.objectContaining({
      credentials: 'same-origin',
      signal: expect.any(AbortSignal),
    }))
    expect(isPlatformAuthenticated.value).toBe(false)
  })

  it('authenticates a fresh tab after Control accepts the shared cookie', async () => {
    fetchMock.mockResolvedValue(response(200, sessionView))

    await expect(bootstrapPlatformSession()).resolves.toBe('AUTHENTICATED')

    const request = fetchMock.mock.calls[0][1] as RequestInit
    expect(request.headers).toBeUndefined()
    expect(platformSessionUser.value).toEqual(profile)
    expect(platformSessionId.value).toBe('pls_7a')
    expect(isPlatformAuthenticated.value).toBe(true)
    expect(requiresPlatformExplorationAcknowledgement.value).toBe(true)
    expect(isPlatformWorkspaceReady.value).toBe(false)
  })

  it('shares one /me request across concurrent protected-route navigations', async () => {
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

  it('unlocks the target workspace only after the server session notice is acknowledged', () => {
    markPlatformSessionAuthenticated(sessionView)

    expect(acknowledgePlatformExplorationNotice()).toBe(true)

    expect(requiresPlatformExplorationAcknowledgement.value).toBe(false)
    expect(isPlatformWorkspaceReady.value).toBe(true)
    expect(localStorage.getItem('reachai.platform.explorationNotice.ack.v4.pls_7a')).toBe('true')
  })

  it('clears local metadata when Control explicitly rejects the cookie session', async () => {
    persistSessionMetadata()
    fetchMock.mockResolvedValue(response(401, undefined, {
      [PLATFORM_SESSION_INVALID_HEADER]: PLATFORM_SESSION_INVALID_VALUE,
    }))

    await expect(bootstrapPlatformSession()).resolves.toBe('ANONYMOUS')

    expect(getPlatformSessionId()).toBe('')
    expect(platformSessionState.value).toBe('ANONYMOUS')
  })

  it('does not mistake an unclassified 401 for a confirmed logout', async () => {
    persistSessionMetadata()
    fetchMock.mockResolvedValue(response(401))

    await expect(bootstrapPlatformSession()).resolves.toBe('UNAVAILABLE')

    expect(getPlatformSessionId()).toBe('pls_7a')
  })

  it('does not mistake an unavailable Control service for logout', async () => {
    persistSessionMetadata()
    fetchMock.mockRejectedValue(new Error('offline'))

    await expect(bootstrapPlatformSession()).resolves.toBe('UNAVAILABLE')

    expect(getPlatformSessionId()).toBe('pls_7a')
  })

  it('keeps local metadata when Control returns 403', async () => {
    persistSessionMetadata()
    fetchMock.mockResolvedValue(response(403))

    await expect(bootstrapPlatformSession()).resolves.toBe('UNAVAILABLE')

    expect(getPlatformSessionId()).toBe('pls_7a')
  })

  it('times out a hanging /me request without deleting local metadata', async () => {
    vi.useFakeTimers()
    persistSessionMetadata()
    fetchMock.mockImplementation((_url: string, init: RequestInit) => new Promise((_, reject) => {
      init.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))

    const result = bootstrapPlatformSession()
    await vi.advanceTimersByTimeAsync(8_000)

    await expect(result).resolves.toBe('UNAVAILABLE')
    expect(getPlatformSessionId()).toBe('pls_7a')
  })

  it('lets Control replace stale tab metadata because the cookie is authoritative', async () => {
    persistSessionMetadata({ ...sessionView, expiresAt: '2020-01-01T00:00:00.000Z' })
    fetchMock.mockResolvedValue(response(200, {
      ...sessionView,
      sessionId: 'pls_fresh',
    }))

    await expect(bootstrapPlatformSession()).resolves.toBe('AUTHENTICATED')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(getPlatformSessionId()).toBe('pls_fresh')
  })

  it('deletes legacy script-readable tokens before checking the cookie', async () => {
    localStorage.setItem('reachai.platform.accessToken', 'pat_legacy_local')
    sessionStorage.setItem('reachai.platform.accessToken', 'pat_legacy_tab')
    fetchMock.mockResolvedValue(response(401, undefined, {
      [PLATFORM_SESSION_INVALID_HEADER]: PLATFORM_SESSION_INVALID_VALUE,
    }))

    await expect(bootstrapPlatformSession()).resolves.toBe('ANONYMOUS')

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('reachai.platform.accessToken')).toBeNull()
    expect(sessionStorage.getItem('reachai.platform.accessToken')).toBeNull()
  })

  it('keeps a successful login authenticated without another bootstrap request', async () => {
    markPlatformSessionAuthenticated(sessionView)

    await expect(bootstrapPlatformSession()).resolves.toBe('AUTHENTICATED')

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('clears an authenticated tab when another tab publishes logout', () => {
    markPlatformSessionAuthenticated(sessionView)

    window.dispatchEvent(new StorageEvent('storage', {
      key: PLATFORM_SESSION_EVENT_KEY,
      newValue: JSON.stringify({ type: 'LOGOUT', at: Date.now() }),
    }))

    expect(platformSessionState.value).toBe('ANONYMOUS')
    expect(getPlatformSessionId()).toBe('')
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
