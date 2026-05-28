import { agentRequest } from '@/api/request'
import { clearPlatformToken, setPlatformToken, setPlatformUser, type PlatformUserProfile } from '@/utils/platformAuth'

export interface PlatformLoginResult {
  accessToken: string
  expiresIn: number
  expiresAt: string
  principal: PlatformUserProfile
}

export interface PlatformAuthProviderView {
  id: number
  providerCode: string
  providerName: string
  providerType: string
  status: string
  configJson: string
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
  return agentRequest.post<PlatformLoginResult>('/api/platform/auth/login', data)
}

export function getCurrentPlatformUser() {
  return agentRequest.get<PlatformUserProfile>('/api/platform/auth/me')
}

export function applyPlatformLogin(result: PlatformLoginResult) {
  setPlatformToken(result.accessToken)
  setPlatformUser(result.principal)
}

export async function logoutPlatform() {
  try {
    await agentRequest.post('/api/platform/auth/logout')
  } finally {
    clearPlatformToken()
  }
}

export function listPlatformAuthProviders() {
  return agentRequest.get<PlatformAuthProviderView[]>('/api/platform/auth-providers')
}

export function savePlatformAuthProvider(body: PlatformAuthProviderCommand) {
  return agentRequest.post<PlatformAuthProviderView>('/api/platform/auth-providers', body)
}

export function listPlatformUsers() {
  return agentRequest.get<PlatformUserView[]>('/api/platform/users')
}

export function listPlatformRoles() {
  return agentRequest.get<PlatformRoleView[]>('/api/platform/roles')
}

export function listPlatformUserRoleGrants(userId: number) {
  return agentRequest.get<PlatformUserRoleGrant[]>(`/api/platform/users/${userId}/roles`)
}

export function savePlatformUserRoleGrants(userId: number, body: PlatformUserRoleGrantCommand[]) {
  return agentRequest.put<PlatformUserRoleGrant[]>(`/api/platform/users/${userId}/roles`, body)
}
