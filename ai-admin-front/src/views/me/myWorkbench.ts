import { EMPLOYEE_PORTAL_TOKEN_KEY, type EmployeeProfile } from '@/api/employeePortal'

const STORAGE_KEY = 'reachai.my-workbench.sessions.v1'
const PREFERENCES_KEY = 'reachai.my-workbench.preferences.v1'
export const MAX_WORKBENCH_SYSTEMS = 12

export interface WorkbenchConnection {
  token: string
  profile: EmployeeProfile
  expired?: boolean
}

// Like the employee portal, credentials are tab-scoped, never persisted in localStorage.
// Each console session owns a separate set; every restore is revalidated by /employee-portal/me.
export function readWorkbenchConnections(owner: string): WorkbenchConnection[] {
  try {
    const stored = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || 'null')
    if (!owner || stored?.owner !== owner) {
      sessionStorage.removeItem(STORAGE_KEY)
      return []
    }
    return Array.isArray(stored.connections) ? stored.connections.filter((item: WorkbenchConnection) =>
      typeof item?.token === 'string' && item.token.length > 0 && typeof item.profile?.projectCode === 'string',
    ).slice(0, MAX_WORKBENCH_SYSTEMS) : []
  } catch { return [] }
}

export function writeWorkbenchConnections(owner: string, connections: WorkbenchConnection[]) {
  try {
    if (!owner) sessionStorage.removeItem(STORAGE_KEY)
    else sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ owner, connections: connections.filter(connection => !connection.expired).slice(0, MAX_WORKBENCH_SYSTEMS) }))
  } catch { /* Without storage, the connections remain available until this page closes. */ }
}

export function readPortalToken(): string {
  try { return sessionStorage.getItem(EMPLOYEE_PORTAL_TOKEN_KEY) || '' } catch { return '' }
}

export interface WorkbenchPreferences { selected: string; pinned: string[] }

export function readWorkbenchPreferences(userId: number): WorkbenchPreferences {
  try {
    const value = JSON.parse(localStorage.getItem(`${PREFERENCES_KEY}.${userId}`) || 'null')
    return {
      selected: typeof value?.selected === 'string' ? value.selected : '',
      pinned: Array.isArray(value?.pinned) ? value.pinned.filter((key: unknown) => typeof key === 'string').slice(0, MAX_WORKBENCH_SYSTEMS) : [],
    }
  } catch { return { selected: '', pinned: [] } }
}

export function writeWorkbenchPreferences(userId: number, preferences: WorkbenchPreferences) {
  try { localStorage.setItem(`${PREFERENCES_KEY}.${userId}`, JSON.stringify(preferences)) } catch { /* Optional preference. */ }
}

/** Never infer a UI address from a backend baseUrl or accept credential-bearing links. */
export function businessSystemUrl(value?: string | null): string | undefined {
  if (!value?.trim()) return undefined
  try {
    const url = new URL(value.trim())
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : undefined
  } catch { return undefined }
}

export function systemMonogram(name: string): string {
  const latin = name.match(/[A-Za-z][A-Za-z0-9]*/g)
  if (latin?.length) return latin[0].slice(0, 3).toUpperCase()
  return Array.from(name).slice(0, 1).join('') || '企'
}
