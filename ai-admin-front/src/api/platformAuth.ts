import { controlRequest } from '@/api/request'
import {
  setPlatformToken,
  type PlatformUserProfile,
} from '@/utils/platformAuth'
import {
  markPlatformSessionAnonymous,
  markPlatformSessionAuthenticated,
  type PlatformSessionView,
} from '@/auth/platformSession'

export interface PlatformLoginResult {
  accessToken: string
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
  setPlatformToken(result.accessToken, result.expiresAt)
  markPlatformSessionAuthenticated({
    sessionId: result.sessionId,
    expiresAt: result.expiresAt,
    principal: result.principal,
  })
}

export async function logoutPlatform() {
  try {
    await controlRequest.post('/api/platform/auth/logout')
  } finally {
    markPlatformSessionAnonymous()
  }
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
  return controlRequest.put<PlatformUserRoleGrant[]>(`/api/platform/users/${userId}/roles`, body)
}
