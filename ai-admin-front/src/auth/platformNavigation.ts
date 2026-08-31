import type { RouteLocationRaw } from 'vue-router'
import {
  bootstrapPlatformSession,
  platformSessionUser,
  type PlatformSessionState,
} from '@/auth/platformSession'
import {
  hasAllPlatformPermissions,
  normalizeRequiredPermissions,
} from '@/auth/platformAccess'

export interface PlatformNavigationTarget {
  fullPath: string
  meta: { public?: unknown; requiredPermissions?: unknown }
}

export function platformSessionNavigation(
  target: PlatformNavigationTarget,
  state: PlatformSessionState,
  grantedPermissions: readonly string[] = [],
): true | RouteLocationRaw {
  if (target.meta.public) return true
  if (state === 'AUTHENTICATED') {
    const requiredPermissions = normalizeRequiredPermissions(target.meta.requiredPermissions)
    if (hasAllPlatformPermissions(grantedPermissions, requiredPermissions)) return true
    return {
      path: '/access-denied',
      query: { redirect: target.fullPath },
    }
  }

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
  return platformSessionNavigation(
    target,
    state,
    platformSessionUser.value?.permissions ?? [],
  )
}
