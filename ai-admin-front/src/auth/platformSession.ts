import { computed, readonly, ref } from 'vue'
import {
  acknowledgeExplorationNotice,
  clearPlatformToken,
  discardLegacyPlatformPersistentSession,
  getPlatformSessionExpiresAt,
  getPlatformSessionId,
  getPlatformToken,
  getPlatformUser,
  hasAcknowledgedExplorationNotice,
  hasLocallyExpiredPlatformSession,
  setPlatformSessionExpiresAt,
  setPlatformSessionId,
  setPlatformUser,
  type PlatformUserProfile,
} from '@/utils/platformAuth'

export type PlatformSessionState =
  | 'BOOTSTRAPPING'
  | 'ANONYMOUS'
  | 'AUTHENTICATED'
  | 'UNAVAILABLE'

export interface PlatformSessionView {
  sessionId: string
  expiresAt: string
  principal: PlatformUserProfile
}

interface PlatformAuthMeResponse {
  data?: PlatformSessionView
  sessionId?: string
  expiresAt?: string
  principal?: PlatformUserProfile
}

export const PLATFORM_SESSION_BOOTSTRAP_TIMEOUT_MS = 8_000
export const PLATFORM_SESSION_INVALID_HEADER = 'X-ReachAI-Auth-Failure'
export const PLATFORM_SESSION_INVALID_VALUE = 'PLATFORM_SESSION_INVALID'

function persistedSession(): PlatformSessionView | null {
  const sessionId = getPlatformSessionId()
  const expiresAt = getPlatformSessionExpiresAt()
  const principal = getPlatformUser()
  return sessionId && expiresAt && principal
    ? { sessionId, expiresAt, principal }
    : null
}

const state = ref<PlatformSessionState>('BOOTSTRAPPING')
const session = ref<PlatformSessionView | null>(persistedSession())
const explorationAcknowledged = ref(
  hasAcknowledgedExplorationNotice(session.value?.sessionId),
)
let bootstrapPromise: Promise<PlatformSessionState> | null = null
let loginNavigationStarted = false

export const platformSessionState = readonly(state)
export const platformSession = readonly(session)
export const platformSessionUser = computed(() => session.value?.principal ?? null)
export const platformSessionId = computed(() => session.value?.sessionId || '')
export const isPlatformAuthenticated = computed(
  () => state.value === 'AUTHENTICATED',
)
export const isPlatformExplorationAcknowledged = readonly(explorationAcknowledged)
export const isPlatformWorkspaceReady = computed(
  () => isPlatformAuthenticated.value && explorationAcknowledged.value,
)
export const requiresPlatformExplorationAcknowledgement = computed(
  () => isPlatformAuthenticated.value && !explorationAcknowledged.value,
)

function isPlatformSessionView(value: PlatformAuthMeResponse): value is PlatformSessionView {
  return Boolean(
    typeof value.sessionId === 'string'
      && value.sessionId.length > 0
      && typeof value.expiresAt === 'string'
      && value.principal
      && typeof value.principal.userId === 'number'
      && typeof value.principal.username === 'string',
  )
}

function platformSessionView(value: PlatformAuthMeResponse): PlatformSessionView | null {
  if (isPlatformSessionView(value)) return value
  return value.data && isPlatformSessionView(value.data) ? value.data : null
}

function replaceSession(nextSession: PlatformSessionView) {
  setPlatformUser(nextSession.principal)
  setPlatformSessionId(nextSession.sessionId)
  setPlatformSessionExpiresAt(nextSession.expiresAt)
  session.value = nextSession
  explorationAcknowledged.value = hasAcknowledgedExplorationNotice(nextSession.sessionId)
}

function clearSession(stateAfterClear: PlatformSessionState = 'ANONYMOUS') {
  clearPlatformToken()
  session.value = null
  explorationAcknowledged.value = false
  state.value = stateAfterClear
}

function responseHeader(response: Response | { headers?: { get?: (name: string) => string | null } }, name: string) {
  return response.headers?.get?.(name) || ''
}

/**
 * Performs the only authoritative client-side session bootstrap check. A saved
 * token is merely a hint; pages are unlocked only after Control accepts /me.
 */
export function bootstrapPlatformSession(): Promise<PlatformSessionState> {
  if (state.value === 'AUTHENTICATED') {
    return Promise.resolve(state.value)
  }
  if (bootstrapPromise) return bootstrapPromise

  bootstrapPromise = (async () => {
    discardLegacyPlatformPersistentSession()
    const token = getPlatformToken()
    if (!token || hasLocallyExpiredPlatformSession()) {
      clearSession()
      return state.value
    }

    state.value = 'BOOTSTRAPPING'
    const controller = new AbortController()
    const timeoutId = globalThis.setTimeout(
      () => controller.abort(),
      PLATFORM_SESSION_BOOTSTRAP_TIMEOUT_MS,
    )
    try {
      const response = await fetch('/api/platform/auth/me', {
        headers: { Authorization: `Bearer ${token}` },
        signal: controller.signal,
      })
      if (response.status === 401) {
        if (responseHeader(response, PLATFORM_SESSION_INVALID_HEADER) === PLATFORM_SESSION_INVALID_VALUE) {
          clearSession()
        } else {
          state.value = 'UNAVAILABLE'
        }
        return state.value
      }
      if (!response.ok) {
        state.value = 'UNAVAILABLE'
        return state.value
      }
      const nextSession = platformSessionView(
        await response.json() as PlatformAuthMeResponse,
      )
      if (!nextSession) {
        state.value = 'UNAVAILABLE'
        return state.value
      }
      replaceSession(nextSession)
      state.value = 'AUTHENTICATED'
      return state.value
    } catch {
      state.value = 'UNAVAILABLE'
      return state.value
    } finally {
      globalThis.clearTimeout(timeoutId)
      bootstrapPromise = null
    }
  })()

  return bootstrapPromise
}

export function markPlatformSessionAuthenticated(nextSession: PlatformSessionView) {
  replaceSession(nextSession)
  state.value = 'AUTHENTICATED'
  loginNavigationStarted = false
}

export function acknowledgePlatformExplorationNotice() {
  const sessionId = session.value?.sessionId
  if (!sessionId || state.value !== 'AUTHENTICATED') return false
  acknowledgeExplorationNotice(sessionId)
  explorationAcknowledged.value = true
  return true
}

export function markPlatformSessionAnonymous() {
  clearSession()
  loginNavigationStarted = false
}

/** Only a platform-console 401 may call this; business/API credential failures must not log out the console. */
export function handlePlatformSessionFailure() {
  const wasAuthenticated = state.value === 'AUTHENTICATED' || Boolean(getPlatformToken())
  clearSession()
  if (!wasAuthenticated || loginNavigationStarted || typeof window === 'undefined') return false
  if (window.location.pathname.startsWith('/login')) return false

  loginNavigationStarted = true
  const redirect = window.location.pathname + window.location.search + window.location.hash
  window.location.assign(`/login?redirect=${encodeURIComponent(redirect)}`)
  return true
}

export function sanitizePlatformRedirect(redirect: unknown, fallback = '/dashboard'): string {
  if (typeof redirect !== 'string') return fallback
  const trimmed = redirect.trim()
  if (!trimmed.startsWith('/') || trimmed.startsWith('//') || trimmed.includes('://')) {
    return fallback
  }
  return trimmed
}

/** Test-only reset that keeps all production state transitions explicit. */
export function resetPlatformSessionForTest() {
  bootstrapPromise = null
  loginNavigationStarted = false
  session.value = persistedSession()
  explorationAcknowledged.value = hasAcknowledgedExplorationNotice(session.value?.sessionId)
  state.value = 'BOOTSTRAPPING'
}
