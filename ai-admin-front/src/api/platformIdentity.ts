import { controlRequest } from './request'

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

export interface BusinessUserView {
  id: number
  tenantId: string
  globalUserId: string
  displayName: string
  email?: string
  mobile?: string
  status: string
  source: string
  lastSeenAt?: string
  createdAt?: string
  updatedAt?: string
  bindingCount: number
  externalIdentities: string[]
  roleCodes: string[]
}

export interface BusinessUserUpdateCommand {
  globalUserId?: string
  displayName?: string
  email?: string
  mobile?: string
  status?: string
}

export interface ExternalRoleView {
  id: number
  roleCode: string
  roleName: string
  source: string
  status: string
}

export interface ExternalIdentityView {
  id: number
  tenantId: string
  businessUserId: number
  appId: string
  externalUserId: string
  externalUserName: string
  deptId?: string
  deptName?: string
  status: string
  lastSeenAt?: string
  roles: ExternalRoleView[]
}

export interface ExternalIdentityCommand {
  id?: number
  appId: string
  externalUserId: string
  externalUserName?: string
  deptId?: string
  deptName?: string
  status?: string
  roles: string[]
}

export function listBusinessUsers(params: {
  current?: number
  size?: number
  tenantId?: string
  keyword?: string
  status?: string
}) {
  return controlRequest.get<PageResult<BusinessUserView>>('/api/platform/business-users', { params })
}

export function updateBusinessUser(id: number, body: BusinessUserUpdateCommand) {
  return controlRequest.put<BusinessUserView>(`/api/platform/business-users/${id}`, body)
}

export function listBusinessUserIdentities(id: number) {
  return controlRequest.get<ExternalIdentityView[]>(`/api/platform/business-users/${id}/identities`)
}

export function saveBusinessUserIdentity(id: number, body: ExternalIdentityCommand) {
  return controlRequest.post<ExternalIdentityView>(`/api/platform/business-users/${id}/identities`, body)
}

export interface PersonalMemoryAdminLink {
  id: number
  peerBindingId: number
  peerAppId: string
  peerExternalUserId: string
  verificationMethod: string
  createdAt: string
}

const memoryIdentityLinks = '/api/platform/personal-memory-identity-links'
export function listPersonalMemoryIdentityLinks(bindingId: number, offset = 0) {
  return controlRequest.get<{ items: PersonalMemoryAdminLink[]; hasMore: boolean }>(memoryIdentityLinks, { params: { bindingId, offset } })
}
export function createPersonalMemoryIdentityLink(firstBindingId: number, secondBindingId: number, evidence: string) {
  return controlRequest.post<{ id: number }>(memoryIdentityLinks, { firstBindingId, secondBindingId, evidence })
}
export function revokePersonalMemoryIdentityLink(id: number, evidence: string) {
  return controlRequest.post<void>(`${memoryIdentityLinks}/${id}/revoke`, { evidence })
}
