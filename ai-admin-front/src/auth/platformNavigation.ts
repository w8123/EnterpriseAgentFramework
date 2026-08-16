import type { RouteLocationRaw } from 'vue-router'
import {
  bootstrapPlatformSession,
  type PlatformSessionState,
} from '@/auth/platformSession'

export interface PlatformNavigationTarget {
  fullPath: string
  meta: { public?: unknown }
}

export function platformSessionNavigation(
  target: PlatformNavigationTarget,
  state: PlatformSessionState,
): true | RouteLocationRaw {
  if (target.meta.public) return true
  if (state === 'AUTHENTICATED') return true

  const redirect = target.fullPath
  if (state === 'UNAVAILABLE') {
    return { path: '/auth-unavailable', query: { redirect } }
  }
  return { path: '/login', query: { redirect } }
}

export async function resolvePlatformNavigation(
  target: PlatformNavigationTarget,
): Promise<true | RouteLocationRaw> {
  if (target.meta.public) return true
  const state = await bootstrapPlatformSession()
  return platformSessionNavigation(target, state)
}
