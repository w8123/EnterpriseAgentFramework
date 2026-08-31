const LEGACY_TOKEN_KEY = 'reachai.platform.accessToken'
const USER_KEY = 'reachai.platform.user'
const SESSION_ID_KEY = 'reachai.platform.sessionId'
const SESSION_EXPIRES_AT_KEY = 'reachai.platform.expiresAt'
const EXPLORATION_NOTICE_ACK_PREFIX = 'reachai.platform.explorationNotice.ack.v4'
const LEGACY_EXPLORATION_NOTICE_ACK_KEY_V1 = 'reachai.platform.explorationNotice.ack.v1'
const LEGACY_EXPLORATION_NOTICE_ACK_PREFIX_V2 = 'reachai.platform.explorationNotice.ack.v2'
const LEGACY_EXPLORATION_NOTICE_ACK_PREFIX_V3 = 'reachai.platform.explorationNotice.ack.v3'

export const PLATFORM_CSRF_HEADER = 'X-ReachAI-CSRF'
export const PLATFORM_SESSION_EVENT_KEY = 'reachai.platform.sessionEvent.v1'

export interface PlatformPermissionGrant {
  permissionCode: string
  scopeType: string
  scopeValue: string
}

export interface PlatformUserProfile {
  userId: number
  username: string
  displayName?: string
  roles?: string[]
  permissions?: string[]
  permissionGrants?: PlatformPermissionGrant[]
}

/**
 * Only non-secret session metadata is kept in window-scoped storage. The
 * credential itself is an HttpOnly cookie and is never exposed to JavaScript.
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

function getLocalValue(key: string): string {
  try {
    return localStorage.getItem(key) || ''
  } catch {
    return ''
  }
}

function setLocalValue(key: string, value: string) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // Cross-tab convenience is best-effort when persistent storage is disabled.
  }
}

function removeLocalValue(key: string) {
  try {
    localStorage.removeItem(key)
  } catch {
    // Cross-tab convenience is best-effort when persistent storage is disabled.
  }
}

/** Removes old script-readable credentials and acknowledgement keys without migrating them. */
export function discardLegacyPlatformPersistentSession() {
  try {
    localStorage.removeItem(LEGACY_TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
    localStorage.removeItem(SESSION_EXPIRES_AT_KEY)
  } catch {
    // Storage can be disabled; there is no safe persistent fallback.
  }

  removeSessionValue(LEGACY_TOKEN_KEY)
  removeSessionValue(LEGACY_EXPLORATION_NOTICE_ACK_KEY_V1)
  try {
    for (let index = sessionStorage.length - 1; index >= 0; index -= 1) {
      const key = sessionStorage.key(index)
      if (key?.startsWith(`${LEGACY_EXPLORATION_NOTICE_ACK_PREFIX_V2}.`)
        || key?.startsWith(`${LEGACY_EXPLORATION_NOTICE_ACK_PREFIX_V3}.`)) {
        sessionStorage.removeItem(key)
      }
    }
  } catch {
    // Legacy acknowledgement is best-effort only when storage is unavailable.
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

export function clearPlatformSessionMetadata() {
  const currentSessionId = getPlatformSessionId()
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
  return key != null && getLocalValue(key) === 'true'
}

export function acknowledgeExplorationNotice(sessionId?: string) {
  const key = explorationNoticeAcknowledgementKey(sessionId)
  if (key) {
    setLocalValue(key, 'true')
  }
}

export function resetExplorationNoticeAcknowledgement(sessionId?: string) {
  const key = explorationNoticeAcknowledgementKey(sessionId)
  if (key) {
    removeLocalValue(key)
  }
}

export function platformCsrfHeaders(headers?: HeadersInit): Headers {
  const result = new Headers(headers)
  const sessionId = getPlatformSessionId()
  if (sessionId) {
    result.set(PLATFORM_CSRF_HEADER, sessionId)
  }
  return result
}

export function publishPlatformLogoutEvent() {
  setLocalValue(PLATFORM_SESSION_EVENT_KEY, JSON.stringify({
    type: 'LOGOUT',
    at: Date.now(),
  }))
}

export function isPlatformLogoutEvent(event: StorageEvent): boolean {
  if (event.key !== PLATFORM_SESSION_EVENT_KEY || !event.newValue) return false
  try {
    return (JSON.parse(event.newValue) as { type?: string }).type === 'LOGOUT'
  } catch {
    return false
  }
}
