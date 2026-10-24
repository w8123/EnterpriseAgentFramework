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

export const NO_ROLE_LANDING_PATH = '/no-role'

function isWorkspaceHome(fullPath: string): boolean {
  const path = fullPath.split(/[?#]/)[0]
  return path === '/' || path === '/dashboard'
}

export function platformSessionNavigation(
  target: PlatformNavigationTarget,
  state: PlatformSessionState,
  grantedPermissions: readonly string[] = [],
): true | RouteLocationRaw {
  if (target.meta.public) return true
  if (state === 'AUTHENTICATED') {
    // 没有任何平台权限的账号是业务人员：概览对他们是空的，改为说明怎样在业务系统里使用。
    if (grantedPermissions.length === 0 && isWorkspaceHome(target.fullPath)) {
      return { path: NO_ROLE_LANDING_PATH }
    }
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
