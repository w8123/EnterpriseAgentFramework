export type ProjectVisibility = 'PRIVATE' | 'PROJECT' | 'SHARED' | 'PUBLIC'
export type ProjectKind = 'SCAN' | 'REGISTERED' | 'HYBRID'

export interface RegistryProjectRegisterRequest {
  projectCode: string
  name: string
  environment?: string
  owner?: string
  visibility?: ProjectVisibility
  baseUrl: string
  contextPath?: string
  appKey?: string
  appSecret?: string
  metadata?: Record<string, unknown>
}

export interface RegistryProjectResponse {
  projectId: number
  projectCode: string
  name: string
  environment: string
  visibility: ProjectVisibility
}

export interface ProjectInstance {
  id: number
  projectId: number
  projectCode: string
  instanceId: string
  baseUrl?: string
  host?: string
  port?: number
  appVersion?: string
  sdkVersion?: string
  status: 'ONLINE' | 'OFFLINE' | 'DISABLED' | 'STALE'
  metadataJson?: string
  lastHeartbeatAt?: string
}

/** Returned once after a platform administrator starts zero-touch SDK enrollment. */
export interface RegistryEnrollmentToken {
  enrollmentToken: string
  projectCode: string
  expiresAt: string
  ttlHours: number
}

export interface InstanceHeartbeatResponse {
  instance: ProjectInstance
}

export interface CapabilitySnapshot {
  id: number
  projectId: number
  projectCode: string
  syncId: string
  source: string
  status: 'PENDING' | 'APPLIED' | 'PARTIAL' | 'IGNORED'
  received: number
  added: number
  changed: number
  unchanged: number
  deleted: number
  createdAt?: string
  updatedAt?: string
}

export interface CapabilityDiffReviewItem {
  id: number
  snapshotId: number
  syncId: string
  projectCode: string
  qualifiedName: string
  name: string
  storageName: string
  changeType: 'ADDED' | 'CHANGED' | 'UNCHANGED' | 'DELETED'
  existingToolId?: number | null
  fieldDiffJson?: string
  impactJson?: string
  reviewStatus: 'PENDING' | 'APPLIED' | 'AUTO_APPLIED' | 'UNCHANGED' | 'IGNORED' | 'ROLLED_BACK' | 'SUPERSEDED' | 'DIAGNOSTIC'
  reviewNote?: string
  rollbackAvailable?: boolean
}

export interface CapabilityChangePage {
  records: CapabilityDiffReviewItem[]
  total: number
  current: number
  size: number
  pending: number
  automated: number
}
