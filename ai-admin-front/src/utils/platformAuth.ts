const TOKEN_KEY = 'reachai.platform.accessToken'
const USER_KEY = 'reachai.platform.user'
const EXPLORATION_NOTICE_ACK_KEY = 'reachai.platform.explorationNotice.ack.v1'

export interface PlatformUserProfile {
  userId: number
  username: string
  displayName?: string
  roles?: string[]
  permissions?: string[]
}

export function getPlatformToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setPlatformToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearPlatformToken() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  resetExplorationNoticeAcknowledgement()
}

export function setPlatformUser(user: PlatformUserProfile) {
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function getPlatformUser(): PlatformUserProfile | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as PlatformUserProfile
  } catch {
    return null
  }
}

export function hasAcknowledgedExplorationNotice(): boolean {
  try {
    return sessionStorage.getItem(EXPLORATION_NOTICE_ACK_KEY) === 'true'
  } catch {
    return false
  }
}

export function acknowledgeExplorationNotice() {
  try {
    sessionStorage.setItem(EXPLORATION_NOTICE_ACK_KEY, 'true')
  } catch {
    // Storage can be unavailable in privacy-restricted browsers. The current
    // layout still keeps the dialog closed after acknowledgement.
  }
}

export function resetExplorationNoticeAcknowledgement() {
  try {
    sessionStorage.removeItem(EXPLORATION_NOTICE_ACK_KEY)
  } catch {
    // Keep authentication cleanup resilient when storage access is blocked.
  }
}
