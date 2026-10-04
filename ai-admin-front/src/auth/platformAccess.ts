import type { PlatformPermissionGrant } from '@/utils/platformAuth'

export const PLATFORM_PERMISSION_ALL = '*'
export const PLATFORM_PERMISSION_READ = 'platform:read'
export const PLATFORM_PERMISSION_WRITE = 'platform:write'
export const PLATFORM_PERMISSION_ADMIN = 'platform:admin'
export const PLATFORM_PERMISSION_BUILD_WORKSPACE = 'workspace:build:access'
export const PLATFORM_PERMISSION_OPERATE_WORKSPACE = 'workspace:operate:access'
export const PLATFORM_PERMISSION_BUSINESS_USER_READ = 'identity:business-user:read'
export const PLATFORM_PERMISSION_BUSINESS_USER_MANAGE = 'identity:business-user:manage'
export const PLATFORM_PERMISSION_MEMORY_ERASURE_MANAGE = 'context:memory:erasure:manage'
export const PLATFORM_PERMISSION_AUTOMATION_READ = 'automation:read'
export const PLATFORM_PERMISSION_AUTOMATION_WRITE = 'automation:write'
export const PLATFORM_PERMISSION_AUTOMATION_OPERATE = 'automation:operate'
export const PLATFORM_PERMISSION_AGENT_READ = 'agent:read'
export const PLATFORM_PERMISSION_AGENT_WRITE = 'agent:write'
export const PLATFORM_PERMISSION_AGENT_DEBUG = 'agent:debug'
export const PLATFORM_PERMISSION_AGENT_EVALUATE = 'agent:evaluate'
export const PLATFORM_PERMISSION_AGENT_PUBLISH = 'agent:publish'
export const PLATFORM_PERMISSION_WORKFLOW_READ = 'workflow:read'
export const PLATFORM_PERMISSION_WORKFLOW_WRITE = 'workflow:write'
export const PLATFORM_PERMISSION_WORKFLOW_DEBUG = 'workflow:debug'
export const PLATFORM_PERMISSION_WORKFLOW_PUBLISH = 'workflow:publish'
export const PLATFORM_PERMISSION_WORKFLOW_CREDENTIAL_MANAGE = 'workflow:credential:manage'
export const PLATFORM_PERMISSION_RUNOPS_READ = 'runops:read'
export const PLATFORM_PERMISSION_RUNOPS_OPERATE = 'runops:operate'
/** Console-only permission; server still verifies project scope and Tool ACL. */
export const PLATFORM_PERMISSION_CAPABILITY_INVOKE = 'capability:invoke'

export type PlatformWorkspace = 'BUILD' | 'OPERATE'

export function normalizeRequiredPermissions(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  return value.filter(
    (permission): permission is string => typeof permission === 'string' && permission.length > 0,
  )
}

export function hasPlatformPermission(
  grantedPermissions: readonly string[] | null | undefined,
  requiredPermission: string,
): boolean {
  const granted = grantedPermissions ?? []
  return granted.includes(PLATFORM_PERMISSION_ALL) || granted.includes(requiredPermission)
}

export function hasAllPlatformPermissions(
  grantedPermissions: readonly string[] | null | undefined,
  requiredPermissions: readonly string[],
): boolean {
  return requiredPermissions.every((permission) => (
    hasPlatformPermission(grantedPermissions, permission)
  ))
}

/** Mirrors the server-side GLOBAL > WORKSPACE > PROJECT grant hierarchy. */
export function hasPlatformResourcePermission(
  permissionGrants: readonly PlatformPermissionGrant[] | null | undefined,
  requiredPermission: string,
  resourceScope: 'WORKSPACE' | 'PROJECT',
  workspaceId?: string | null,
  projectCode?: string | null,
): boolean {
  const same = (left?: string | null, right?: string | null) => (
    Boolean(left?.trim()) && Boolean(right?.trim()) && left?.trim() === right?.trim()
  )

  return (permissionGrants ?? []).some((grant) => {
    const grantsPermission = grant.permissionCode === PLATFORM_PERMISSION_ALL
      || grant.permissionCode === requiredPermission
    if (!grantsPermission) return false

    const scopeType = grant.scopeType?.trim().toUpperCase()
    const scopeValue = grant.scopeValue?.trim()
    if (scopeType === 'GLOBAL' && scopeValue === PLATFORM_PERMISSION_ALL) return true
    if (resourceScope === 'WORKSPACE') {
      return scopeType === 'WORKSPACE' && same(scopeValue, workspaceId)
    }
    return (scopeType === 'PROJECT' && same(scopeValue, projectCode))
      || (scopeType === 'WORKSPACE' && same(scopeValue, workspaceId))
  })
}

export function hasPlatformGlobalPermissionGrant(
  permissionGrants: readonly PlatformPermissionGrant[] | null | undefined,
  requiredPermission: string,
): boolean {
  return (permissionGrants ?? []).some((grant) => (
    (grant.permissionCode === PLATFORM_PERMISSION_ALL || grant.permissionCode === requiredPermission)
    && grant.scopeType?.trim().toUpperCase() === 'GLOBAL'
    && grant.scopeValue?.trim() === PLATFORM_PERMISSION_ALL
  ))
}

export function platformProjectPermissionScopes(
  permissionGrants: readonly PlatformPermissionGrant[] | null | undefined,
  requiredPermission: string,
): string[] {
  return [...new Set((permissionGrants ?? [])
    .filter((grant) => (
      (grant.permissionCode === PLATFORM_PERMISSION_ALL || grant.permissionCode === requiredPermission)
      && grant.scopeType?.trim().toUpperCase() === 'PROJECT'
      && Boolean(grant.scopeValue?.trim())
    ))
    .map((grant) => grant.scopeValue.trim()))]
}
