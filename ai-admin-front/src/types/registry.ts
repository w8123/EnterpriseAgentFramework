import type { ToolParameter } from './tool'

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
  status: 'ONLINE' | 'OFFLINE'
  metadataJson?: string
  lastHeartbeatAt?: string
}

export interface CapabilityRegistration {
  name: string
  title?: string
  description?: string
  httpMethod?: string
  baseUrl?: string
  contextPath?: string
  endpointPath?: string
  requestBodyType?: string
  responseType?: string
  sideEffect?: string
  enabled?: boolean
  agentVisible?: boolean
  lightweightEnabled?: boolean
  visibility?: ProjectVisibility
  parameters?: ToolParameter[]
  metadata?: Record<string, unknown>
}

export interface CapabilitySyncRequest {
  syncId?: string
  source?: string
  apply?: boolean
  capabilities: CapabilityRegistration[]
}

export interface CapabilityDiffItem {
  qualifiedName: string
  name: string
  changeType: 'ADDED' | 'CHANGED' | 'UNCHANGED' | 'DELETED'
  existingToolId?: number | null
  storageName: string
  fieldDiffs?: Array<{ field: string; oldValue: unknown; newValue: unknown }>
  impact?: Record<string, unknown>
}

export interface CapabilitySyncResponse {
  syncId: string
  projectId: number
  projectCode: string
  received: number
  added: number
  changed: number
  unchanged: number
  applied: number
  items: CapabilityDiffItem[]
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
  reviewStatus: 'PENDING' | 'APPLIED' | 'IGNORED'
  reviewNote?: string
}
