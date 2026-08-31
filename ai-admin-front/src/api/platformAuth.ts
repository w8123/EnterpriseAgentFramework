import { controlRequest } from '@/api/request'
import { type PlatformUserProfile } from '@/utils/platformAuth'
import {
  markPlatformSessionAnonymous,
  markPlatformSessionAuthenticated,
  type PlatformSessionView,
} from '@/auth/platformSession'

export interface PlatformLoginResult {
  expiresIn: number
  expiresAt: string
  sessionId: string
  principal: PlatformUserProfile
}

export interface PlatformAuthProviderView {
  id: number
  providerCode: string
  providerName: string
  providerType: string
  status: string
  configurationPresent: boolean
  createdAt?: string
  updatedAt?: string
}

export interface PlatformAuthProviderCommand {
  providerCode: string
  providerName: string
  providerType: string
  status: string
  configJson: string
}

export interface PlatformUserView {
  id: number
  username: string
  displayName: string
  status: string
  sourceProvider: string
  lastLoginAt?: string
}

export interface PlatformRoleView {
  id: number
  roleCode: string
  roleName: string
  status: string
}

export interface PlatformAccountUserView extends PlatformUserView {
  email?: string
  mobile?: string
  createdAt?: string
  updatedAt?: string
  activeSessionCount: number
  grants: PlatformUserRoleGrant[]
}

export interface PlatformPermissionView {
  id: number
  permissionCode: string
  permissionName: string
  resourceType?: string
  action?: string
  description?: string
  reservedForSystemRole: boolean
}

export interface PlatformRoleManagementView extends PlatformRoleView {
  description?: string
  roleKind: 'SYSTEM' | 'CUSTOM'
  permissionIds: number[]
  permissionCodes: string[]
  userCount: number
  grantCount: number
  createdAt?: string
  updatedAt?: string
}

export interface PlatformAuthAuditEventView {
  id: number
  eventType: string
  actorUserId?: number
  actorUsername?: string
  targetType: string
  targetId?: string
  detailsJson?: string
  createdAt?: string
}

export interface PlatformUserRoleGrant {
  id?: number
  roleId: number
  roleCode?: string
  roleName?: string
  scopeType: string
  scopeValue: string
}

export interface PlatformUserRoleGrantCommand {
  roleId: number
  scopeType: string
  scopeValue: string
}

export function loginPlatform(data: { username: string; password: string }) {
  return controlRequest.post<PlatformLoginResult>('/api/platform/auth/login', data, {
    platformAuthFailure: 'ignore',
  })
}

export function getCurrentPlatformUser() {
  return controlRequest.get<PlatformSessionView>('/api/platform/auth/me', {
    platformAuthFailure: 'ignore',
  })
}

export function applyPlatformLogin(result: PlatformLoginResult) {
  markPlatformSessionAuthenticated({
    sessionId: result.sessionId,
    expiresAt: result.expiresAt,
    principal: result.principal,
  })
}

export async function logoutPlatform() {
  await controlRequest.post('/api/platform/auth/logout')
  markPlatformSessionAnonymous()
}

export function listPlatformAuthProviders() {
  return controlRequest.get<PlatformAuthProviderView[]>('/api/platform/auth-providers')
}

export function savePlatformAuthProvider(body: PlatformAuthProviderCommand) {
  return controlRequest.post<PlatformAuthProviderView>('/api/platform/auth-providers', body)
}

export function listPlatformUsers() {
  return controlRequest.get<PlatformUserView[]>('/api/platform/users')
}

export function listPlatformRoles() {
  return controlRequest.get<PlatformRoleView[]>('/api/platform/roles')
}

export function listPlatformUserRoleGrants(userId: number) {
  return controlRequest.get<PlatformUserRoleGrant[]>(`/api/platform/users/${userId}/roles`)
}

export function savePlatformUserRoleGrants(userId: number, body: PlatformUserRoleGrantCommand[]) {
  return controlRequest.put<PlatformUserRoleGrant[]>(
    `/api/platform/account-management/users/${userId}/grants`,
    body,
  )
}

export function listPlatformAccounts() {
  return controlRequest.get<PlatformAccountUserView[]>('/api/platform/account-management/users')
}

export function createPlatformAccount(body: {
  username: string
  displayName: string
  email?: string
  mobile?: string
  password: string
  status: string
  grants: PlatformUserRoleGrantCommand[]
}) {
  return controlRequest.post<PlatformAccountUserView>('/api/platform/account-management/users', body)
}

export function updatePlatformAccount(userId: number, body: {
  displayName: string
  email?: string
  mobile?: string
  status: string
}) {
  return controlRequest.put<PlatformAccountUserView>(
    `/api/platform/account-management/users/${userId}`,
    body,
  )
}

export function resetPlatformAccountPassword(userId: number, password: string) {
  return controlRequest.post<{ revokedSessions: number }>(
    `/api/platform/account-management/users/${userId}/password-reset`,
    { password },
  )
}

export function revokePlatformAccountSessions(userId: number) {
  return controlRequest.post<{ revokedSessions: number }>(
    `/api/platform/account-management/users/${userId}/sessions/revoke`,
  )
}

export function listPlatformPermissions() {
  return controlRequest.get<PlatformPermissionView[]>('/api/platform/account-management/permissions')
}

export function listPlatformManagedRoles() {
  return controlRequest.get<PlatformRoleManagementView[]>('/api/platform/account-management/roles')
}

export function createPlatformRole(body: {
  roleCode: string
  roleName: string
  description?: string
  status: string
  permissionIds: number[]
}) {
  return controlRequest.post<PlatformRoleManagementView>('/api/platform/account-management/roles', body)
}

export function updatePlatformRole(roleId: number, body: {
  roleName: string
  description?: string
  status: string
  permissionIds: number[]
  acknowledgeAssignedUsers?: boolean
}) {
  return controlRequest.put<PlatformRoleManagementView>(
    `/api/platform/account-management/roles/${roleId}`,
    body,
  )
}

export function listPlatformAuthAuditEvents(params?: {
  eventType?: string
  targetType?: string
  limit?: number
}) {
  return controlRequest.get<PlatformAuthAuditEventView[]>(
    '/api/platform/account-management/audit-events',
    { params },
  )
}
