export interface RuntimeUserMapping {
  id: number
  tenantId: string
  platformUserId: number
  runtimeUserId: string
  globalUserId?: string | null
  externalUserId?: string | null
  projectId?: number | null
  projectCode?: string | null
  status: string
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  deletedAt?: string | null
}

export interface RuntimeUserMappingCreateRequest {
  tenantId: string
  platformUserId: number
  runtimeUserId?: string
  globalUserId?: string
  externalUserId?: string
  projectId?: number | null
  projectCode?: string | null
}

export interface RuntimeUserMappingListParams {
  tenantId: string
  platformUserId?: number
  runtimeUserId?: string
  projectId?: number | null
  projectCode?: string | null
  status?: string
  limit?: number
}
