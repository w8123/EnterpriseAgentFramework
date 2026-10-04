export type HttpApiSourceStatus = 'DISCOVERED' | 'ACCEPTED' | 'CONTRACT_DRIFT'
  | 'CONFLICT' | 'SOURCE_MISSING' | 'SOURCE_UNCONFIRMED'

export interface HttpApiSummary {
  id: number
  qualifiedName: string
  projectId: number
  projectCode: string
  environment: string
  httpMethod: string
  routeTemplate: string
  sourceStatus: HttpApiSourceStatus
  sourceConfirmed: boolean
  sourceReason: string | null
  candidateContractHash: string | null
  acceptedContractHash: string | null
  sourceSetRevision: string
  activeSourceCount: number
  sourceKinds: string[]
  acceptedBy: string | null
  acceptedAt: string | null
  /** Runtime-owned display hints; never a grant to invoke this API. */
  connectionStatus?: 'SAVED' | 'UNCONFIGURED' | 'UNAVAILABLE'
  latestInvocationStatus?: string | null
  latestHttpStatus?: number | null
  latestInvocationAt?: string | null
}

export interface HttpApiParameter {
  name: string
  location: 'PATH' | 'QUERY' | string
  required: boolean
  schema: Record<string, unknown>
  contentTypes: string[]
}

export interface HttpApiContract {
  scope?: { projectCode: string; environment: string; externalServiceKey?: string | null }
  identity: { method: string; routeTemplate: string }
  parameters: HttpApiParameter[]
  requestBody: { required: boolean; schema: Record<string, unknown>; contentTypes: string[] } | null
  responses: Array<{ status: string; schema: Record<string, unknown>; contentTypes: string[] }>
  authentication: { state: string; schemes: string[]; requiredHeaderNames: string[] }
  sideEffect: string
}

export interface HttpApiSource {
  id: number
  sourceKind: string
  sourceKey: string
  sourceLocation: string | null
  sourceRevision: string | null
  sourceContractHash: string
  status: string
  observedAt: string | null
  confirmedInLatestInventory: boolean
  reason: string | null
  latestInventoryAt: string | null
  inventoryComplete: boolean
  confirmedAt: string | null
  /** Capability-owned canonical source facts, including conflicting or removed historical facts. */
  contract?: HttpApiContract | null
}

export interface HttpApiDetail {
  summary: HttpApiSummary
  contract: HttpApiContract | null
  acceptedContract: HttpApiContract | null
  sources: HttpApiSource[]
}

export interface HttpApiPage {
  records: HttpApiSummary[]
  total: number
  current: number
  size: number
  pages: number
}

export interface HttpApiConnection {
  qualifiedName: string
  projectId: number
  projectCode: string
  environment: string
  origin: string | null
  authMode: string | null
  credentialRef: string | null
  credentialName: string | null
  credentialRevision: string | null
  revision: number | null
  status: 'UNCONFIGURED' | 'CONFIGURED' | 'BLOCKED'
  blockingReason: string | null
  previewUrl: string | null
  verification?: HttpApiMarketVerification | null
}

export interface HttpApiMarketVerification {
  status: 'BLOCKED' | 'UNVERIFIED' | 'STALE' | 'FAILED' | 'UNKNOWN' | 'VERIFIED'
  reason: string
  apiId: number | null
  acceptedContractHash: string | null
  sourceSetRevision: string | null
  connectionRevision: number | null
  credentialRevision: string | null
  invocationId: string | null
  runId: number | null
  traceId: string | null
  verifiedAt: string | null
}

export interface HttpApiInvocationOutcome {
  contractVersion: number
  invocationId: string
  targetType: 'HTTP_API'
  qualifiedName: string
  projectId: number
  projectCode: string
  environment: string
  runId: number
  traceId: string
  status: string
  dispatchStage: string
  terminal: boolean
  errorCode: string | null
  httpStatus: number | null
  latencyMs: number | null
  result: unknown
  resultTruncated: boolean
  resultExpiresAt: number | null
  expectedContractHash: string
  sourceSetRevision: string
  connectionRevision: number
  credentialRevision: string | null
}

export interface HttpApiInvocationUnconfirmed {
  success: false
  code: 'HTTP_API_OUTCOME_UNCONFIRMED'
  invocationId: string
  status: 'UNKNOWN'
  terminal: false
  queryable: true
  message: string
}
