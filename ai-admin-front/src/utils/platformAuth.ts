const TOKEN_KEY = 'reachai.platform.accessToken'
const USER_KEY = 'reachai.platform.user'
const SESSION_ID_KEY = 'reachai.platform.sessionId'
const SESSION_EXPIRES_AT_KEY = 'reachai.platform.expiresAt'
const EXPLORATION_NOTICE_ACK_PREFIX = 'reachai.platform.explorationNotice.ack.v3'
const LEGACY_EXPLORATION_NOTICE_ACK_KEY_V1 = 'reachai.platform.explorationNotice.ack.v1'
const LEGACY_EXPLORATION_NOTICE_ACK_PREFIX_V2 = 'reachai.platform.explorationNotice.ack.v2'

export interface PlatformUserProfile {
  userId: number
  username: string
  displayName?: string
  roles?: string[]
  permissions?: string[]
}

/**
 * Management-console credentials intentionally use window-scoped storage. A
 * privacy-restricted browser may reject sessionStorage; in that case a
 * best-effort in-memory fallback preserves only the current page lifetime and
 * never widens the session to localStorage.
 */
const inMemorySessionStorage = new Map<string, string>()

function getSessionValue(key: string): string {
  try {
    return sessionStorage.getItem(key) || ''
  } catch {
    return inMemorySessionStorage.get(key) || ''
  }
}

function setSessionValue(key: string, value: string) {
  try {
    sessionStorage.setItem(key, value)
    inMemorySessionStorage.delete(key)
  } catch {
    inMemorySessionStorage.set(key, value)
  }
}

function removeSessionValue(key: string) {
  inMemorySessionStorage.delete(key)
  try {
    sessionStorage.removeItem(key)
  } catch {
    // The in-memory fallback was already cleared above.
  }
}

/** Removes old cross-window tokens and acknowledgement keys without migrating them. */
export function discardLegacyPlatformPersistentSession() {
  try {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    localStorage.removeItem(SESSION_EXPIRES_AT_KEY)
  } catch {
    // Storage can be disabled; there is no safe persistent fallback.
  }

  removeSessionValue(LEGACY_EXPLORATION_NOTICE_ACK_KEY_V1)
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const key = sessionStorage.key(index)
      if (key?.startsWith(`${LEGACY_EXPLORATION_NOTICE_ACK_PREFIX_V2}.`)) {
        sessionStorage.removeItem(key)
      }
    }
  } catch {
    // Legacy acknowledgement is best-effort only when storage is unavailable.
  }
}

export function getPlatformToken(): string {
  return getSessionValue(TOKEN_KEY)
}

export function setPlatformToken(token: string, expiresAt?: string) {
  setSessionValue(TOKEN_KEY, token)
  if (expiresAt) {
    setSessionValue(SESSION_EXPIRES_AT_KEY, expiresAt)
  } else {
    removeSessionValue(SESSION_EXPIRES_AT_KEY)
  }
}

export function getPlatformSessionExpiresAt(): string {
  return getSessionValue(SESSION_EXPIRES_AT_KEY)
}

export function setPlatformSessionExpiresAt(expiresAt?: string) {
  if (expiresAt) {
    setSessionValue(SESSION_EXPIRES_AT_KEY, expiresAt)
  } else {
    removeSessionValue(SESSION_EXPIRES_AT_KEY)
  }
}

export function setPlatformSessionId(sessionId: string) {
  setSessionValue(SESSION_ID_KEY, sessionId)
}

export function getPlatformSessionId(): string {
  return getSessionValue(SESSION_ID_KEY)
}

export function hasLocallyExpiredPlatformSession(now = Date.now()): boolean {
  const expiresAt = getPlatformSessionExpiresAt()
  if (!expiresAt) return false
  const expiresAtMs = Date.parse(expiresAt)
  return Number.isFinite(expiresAtMs) && expiresAtMs <= now
}

export function clearPlatformToken() {
  const currentSessionId = getPlatformSessionId()
  removeSessionValue(TOKEN_KEY)
  removeSessionValue(USER_KEY)
  removeSessionValue(SESSION_ID_KEY)
  removeSessionValue(SESSION_EXPIRES_AT_KEY)
  resetExplorationNoticeAcknowledgement(currentSessionId)
}

export function setPlatformUser(user: PlatformUserProfile) {
  setSessionValue(USER_KEY, JSON.stringify(user))
}

export function getPlatformUser(): PlatformUserProfile | null {
  const raw = getSessionValue(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as PlatformUserProfile
  } catch {
    return null
  }
}

function explorationNoticeAcknowledgementKey(sessionId?: string): string | null {
  return sessionId ? `${EXPLORATION_NOTICE_ACK_PREFIX}.${sessionId}` : null
}

export function hasAcknowledgedExplorationNotice(sessionId?: string): boolean {
  const key = explorationNoticeAcknowledgementKey(sessionId)
  return key != null && getSessionValue(key) === 'true'
}

export function acknowledgeExplorationNotice(sessionId?: string) {
  const key = explorationNoticeAcknowledgementKey(sessionId)
  if (key) {
    setSessionValue(key, 'true')
  }
}

export function resetExplorationNoticeAcknowledgement(sessionId?: string) {
  const key = explorationNoticeAcknowledgementKey(sessionId)
  if (key) {
    removeSessionValue(key)
  }
}
