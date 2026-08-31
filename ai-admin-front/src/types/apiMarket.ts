export type ApiMarketPublicationStatus = 'DRAFT' | 'PUBLISHED' | 'SUSPENDED' | 'DEPRECATED' | string
export type ApiMarketVerificationStatus = 'UNVERIFIED' | 'VERIFIED' | 'STALE' | 'FAILED' | string
export type ApiMarketSpecStatus = 'NONE' | 'VALID' | 'INVALID' | 'CHANGED' | string
export type ApiMarketIntegrationStatus = 'CONFIGURING' | 'READY' | 'BROKEN' | 'DISABLED' | string

export interface ApiMarketProvider {
  id: number
  providerKey: string
  name: string
  homepageUrl?: string | null
  logoUrl?: string | null
  verified: boolean
  status: string
}

export interface ApiMarketSource {
  id: number
  sourceKey: string
  name: string
  sourceType: string
  sourceUrl?: string | null
  homepageUrl?: string | null
  description?: string | null
  syncStrategy?: string | null
  trustLevel?: string | null
  status: string
  lastSyncedAt?: string | null
  entryCount: number
}

export interface ApiMarketEntrySummary {
  id: number
  entryKey: string
  title: string
  summary: string
  categoryCode: string
  tags: string[]
  authType: string
  pricingType: string
  httpsSupported: boolean
  corsPolicy?: string | null
  publicationStatus: ApiMarketPublicationStatus
  verificationStatus: ApiMarketVerificationStatus
  specStatus: ApiMarketSpecStatus
  featured: boolean
  popularityScore: number
  lastVerifiedAt?: string | null
  provider?: ApiMarketProvider | null
  source?: ApiMarketSource | null
}

export interface ApiMarketOperation {
  id: number
  operationKey: string
  operationId?: string | null
  title: string
  description?: string | null
  httpMethod: string
  path: string
  sideEffect: string
  authRequired: boolean
  requestSchema?: Record<string, unknown> | null
  responseSchema?: Record<string, unknown> | null
  exampleParams: Record<string, unknown>
  status: string
}

export interface ApiMarketVersion {
  id: number
  versionKey: string
  baseUrl: string
  openapiUrl?: string | null
  specHash?: string | null
  publicationStatus: ApiMarketPublicationStatus
  publishedAt?: string | null
  deprecatedAt?: string | null
  operations: ApiMarketOperation[]
}

export interface ApiMarketEntryDetail {
  entry: ApiMarketEntrySummary
  description?: string | null
  docsUrl?: string | null
  termsUrl?: string | null
  sourceEntryKey?: string | null
  sourceCategory?: string | null
  versions: ApiMarketVersion[]
  verifications: ApiMarketVerification[]
}

export interface ApiMarketVerification {
  id: number
  versionId?: number | null
  verificationType: string
  status: ApiMarketVerificationStatus
  httpStatus?: number | null
  latencyMs?: number | null
  checkedUrl?: string | null
  evidenceSummary?: string | null
  checkedAt: string
}

export interface ApiMarketPage {
  records: ApiMarketEntrySummary[]
  total: number
  current: number
  size: number
  pages: number
}

export interface ApiMarketCategory {
  code: string
  name: string
  count: number
}

export interface ApiMarketStats {
  publishedApis: number
  verifiedApis: number
  apiSpecs: number
  projectIntegrations: number
}

export interface ApiMarketIntegration {
  id: number
  projectId: number
  projectCode: string
  projectName: string
  environment: string
  status: ApiMarketIntegrationStatus
  note?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  entry?: ApiMarketEntrySummary | null
  version?: ApiMarketVersion | null
  selectedOperations: ApiMarketOperation[]
  credentialRequired: boolean
}

export interface ApiMarketIntegrationCreateRequest {
  projectId: number
  projectCode: string
  versionId: number
  operationIds: number[]
  environment?: string
  note?: string
}
