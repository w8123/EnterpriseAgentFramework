import type { AssetParameter } from './assetParameter'

/** Accepted Java operation owned by Capability; identity is independent of Tool projections. */
export interface BusinessMethodInfo {
  assetType: 'BUSINESS_METHOD'
  assetId: number
  methodCode: string
  acceptedRevisionId: number
  qualifiedName: string
  name: string
  title: string
  description: string
  status: 'ACCEPTED' | 'REMOVED'
  contractHash: string
  invocationHash: string
  bindingHash: string
  projectId: number
  projectCode: string
  sourceProjectName?: string | null
  source: 'sdk'
  sourceLocation?: string | null
  sourceQualifiedName?: string | null
  sourceAvailability: string
  enabled: boolean
  sideEffect: string
  parameters: AssetParameter[]
  metadata: Record<string, unknown> | null
  httpMethod?: string | null
  baseUrl?: string | null
  contextPath?: string | null
  endpointPath?: string | null
  requestBodyType?: string | null
  responseType?: string | null
}

export interface BusinessMethodListQuery {
  current?: number
  size?: number
  keyword?: string
  projectId?: number
  enabled?: boolean
}

export interface BusinessMethodPageResult {
  records: BusinessMethodInfo[]
  total: number
  size: number
  current: number
  pages: number
}

export interface BusinessMethodCatalogSummary {
  total: number
  enabled: number
  disabled: number
}

export function isBusinessMethodInfo(value: unknown): value is BusinessMethodInfo {
  if (!value || typeof value !== 'object') return false
  const method = value as Partial<BusinessMethodInfo>
  return method.assetType === 'BUSINESS_METHOD'
    && Number.isSafeInteger(method.assetId) && (method.assetId ?? 0) > 0
    && Number.isSafeInteger(method.acceptedRevisionId) && (method.acceptedRevisionId ?? 0) > 0
    && Number.isSafeInteger(method.projectId) && (method.projectId ?? 0) > 0
    && typeof method.projectCode === 'string' && Boolean(method.projectCode.trim())
    && typeof method.qualifiedName === 'string' && Boolean(method.qualifiedName.trim())
    && typeof method.name === 'string' && Boolean(method.name.trim())
    && typeof method.methodCode === 'string' && Boolean(method.methodCode.trim())
    && ['ACCEPTED', 'REMOVED'].includes(method.status ?? '')
    && method.source === 'sdk' && typeof method.enabled === 'boolean'
    && typeof method.contractHash === 'string' && Boolean(method.contractHash.trim())
    && typeof method.invocationHash === 'string' && Boolean(method.invocationHash.trim())
    && typeof method.bindingHash === 'string' && Boolean(method.bindingHash.trim())
    && Array.isArray(method.parameters)
}
