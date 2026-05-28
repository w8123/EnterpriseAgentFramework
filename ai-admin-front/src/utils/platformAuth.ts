const TOKEN_KEY = 'reachai.platform.accessToken'
const USER_KEY = 'reachai.platform.user'

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
