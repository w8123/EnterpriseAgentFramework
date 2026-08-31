import { describe, expect, it } from 'vitest'
import {
  hasAllPlatformPermissions,
  hasPlatformPermission,
  hasPlatformResourcePermission,
  hasPlatformGlobalPermissionGrant,
  normalizeRequiredPermissions,
  platformProjectPermissionScopes,
  PLATFORM_PERMISSION_AUTOMATION_WRITE,
  PLATFORM_PERMISSION_BUSINESS_USER_MANAGE,
  PLATFORM_PERMISSION_BUSINESS_USER_READ,
} from './platformAccess'

describe('platform access projection', () => {
  it('keeps business-user read and manage permissions independent', () => {
    const permissions = [PLATFORM_PERMISSION_BUSINESS_USER_READ]

    expect(hasPlatformPermission(permissions, PLATFORM_PERMISSION_BUSINESS_USER_READ)).toBe(true)
    expect(hasPlatformPermission(permissions, PLATFORM_PERMISSION_BUSINESS_USER_MANAGE)).toBe(false)
  })

  it('lets the platform wildcard satisfy an entire permission set', () => {
    expect(hasAllPlatformPermissions(['*'], [
      PLATFORM_PERMISSION_BUSINESS_USER_READ,
      PLATFORM_PERMISSION_BUSINESS_USER_MANAGE,
    ])).toBe(true)
  })

  it('ignores malformed route metadata instead of creating phantom permissions', () => {
    expect(normalizeRequiredPermissions([
      PLATFORM_PERMISSION_BUSINESS_USER_READ,
      '',
      null,
      7,
    ])).toEqual([PLATFORM_PERMISSION_BUSINESS_USER_READ])
  })

  it('keeps project-scoped actions inside the granted project', () => {
    const grants = [{
      permissionCode: PLATFORM_PERMISSION_AUTOMATION_WRITE,
      scopeType: 'PROJECT',
      scopeValue: 'sales',
    }]

    expect(hasPlatformResourcePermission(
      grants, PLATFORM_PERMISSION_AUTOMATION_WRITE, 'PROJECT', null, 'sales',
    )).toBe(true)
    expect(hasPlatformResourcePermission(
      grants, PLATFORM_PERMISSION_AUTOMATION_WRITE, 'PROJECT', null, 'finance',
    )).toBe(false)
  })

  it('lets a global wildcard grant reach every project', () => {
    expect(hasPlatformResourcePermission(
      [{ permissionCode: '*', scopeType: 'GLOBAL', scopeValue: '*' }],
      PLATFORM_PERMISSION_AUTOMATION_WRITE,
      'PROJECT',
      null,
      'finance',
    )).toBe(true)
  })

  it('projects distinct project scopes without treating them as global', () => {
    const grants = [
      { permissionCode: 'workflow:read', scopeType: 'PROJECT', scopeValue: 'sales' },
      { permissionCode: 'workflow:read', scopeType: 'PROJECT', scopeValue: 'sales' },
      { permissionCode: 'workflow:read', scopeType: 'PROJECT', scopeValue: 'finance' },
    ]

    expect(platformProjectPermissionScopes(grants, 'workflow:read')).toEqual(['sales', 'finance'])
    expect(hasPlatformGlobalPermissionGrant(grants, 'workflow:read')).toBe(false)
  })
})
