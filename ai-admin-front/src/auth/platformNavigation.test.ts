import { describe, expect, it } from 'vitest'
import { platformSessionNavigation } from './platformNavigation'

describe('platform route navigation', () => {
  const protectedTarget = {
    fullPath: '/workflows/wf-1/studio?tab=visual',
    meta: {},
  }

  it('lets public pages render without a platform session', () => {
    expect(platformSessionNavigation(
      { fullPath: '/login', meta: { public: true } },
      'ANONYMOUS',
    )).toBe(true)
  })

  it('renders a protected deep link only after the session is authenticated', () => {
    expect(platformSessionNavigation(protectedTarget, 'AUTHENTICATED')).toBe(true)
  })

  it('allows a permission-protected deep link when the session has the permission', () => {
    const target = {
      fullPath: '/settings/business-users',
      meta: { requiredPermissions: ['identity:business-user:read'] },
    }

    expect(platformSessionNavigation(
      target,
      'AUTHENTICATED',
      ['identity:business-user:read'],
    )).toBe(true)
  })

  it('returns forbidden UX without logging out an authenticated user', () => {
    const target = {
      fullPath: '/settings/business-users?tenantId=default',
      meta: { requiredPermissions: ['identity:business-user:read'] },
    }

    expect(platformSessionNavigation(target, 'AUTHENTICATED', ['platform:read'])).toEqual({
      path: '/access-denied',
      query: { redirect: target.fullPath },
    })
  })

  it('lets the platform wildcard satisfy every route permission', () => {
    const target = {
      fullPath: '/settings/platform-users',
      meta: { requiredPermissions: ['platform:admin'] },
    }

    expect(platformSessionNavigation(target, 'AUTHENTICATED', ['*'])).toBe(true)
  })

  it('preserves the exact protected deep link while redirecting an anonymous window', () => {
    expect(platformSessionNavigation(protectedTarget, 'ANONYMOUS')).toEqual({
      path: '/login',
      query: { redirect: protectedTarget.fullPath },
    })
  })

  it('shows an explicit unavailable page instead of a blank protected layout', () => {
    expect(platformSessionNavigation(protectedTarget, 'UNAVAILABLE')).toEqual({
      path: '/auth-unavailable',
      query: { redirect: protectedTarget.fullPath },
    })
  })
})
