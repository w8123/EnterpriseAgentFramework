import type {
  AgentSkillBundleCandidate,
  AgentSkillImportResult,
  AgentSkillVisibility,
} from './skill'

export interface SkillMarketItem {
  id: string
  slug: string
  name: string
  description?: string | null
  source: string
  sourceType: 'github' | 'well-known' | string
  installs: number
  installUrl?: string | null
  marketUrl?: string | null
  duplicate: boolean
  trustLevel: 'OFFICIAL' | 'VERIFIED' | 'COMMUNITY' | 'AGGREGATED' | string
  importable: boolean
}

export interface SkillMarketSearchResult {
  provider: string
  mode: 'OFFICIAL_V1' | 'LEGACY_PUBLIC_SEARCH' | 'SOURCE_DISCOVERY_ONLY' | string
  query?: string | null
  view: string
  count: number
  authenticatedUpstream: boolean
  message?: string | null
  items: SkillMarketItem[]
}

export interface SkillMarketSource {
  id: number
  sourceKey: string
  displayName: string
  sourceType: string
  baseUrl?: string | null
  repositoryUrl?: string | null
  trustLevel: string
  status: string
  supportsSearch: boolean
  supportsImport: boolean
  official: boolean
  description?: string | null
  displayOrder: number
  updatedAt?: string | null
}

export interface SkillMarketRepository {
  owner: string
  repository: string
  repositoryUrl: string
  defaultBranch: string
  commitSha: string
  description?: string | null
  license?: string | null
  stars: number
  archived: boolean
  updatedAt?: string | null
}

export interface SkillMarketProbeResult {
  schema: string
  provider: string
  marketplaceSkillId?: string | null
  repository: SkillMarketRepository
  bundleSourceSha256: string
  bundleSize: number
  archiveFileCount: number
  candidateCount: number
  suggestedSourceRoot?: string | null
  warnings: string[]
  candidates: AgentSkillBundleCandidate[]
}

export interface SkillMarketProbeRequest {
  sourceUrl: string
  marketplaceProvider?: string
  marketplaceSkillId?: string
  expectedSkillName?: string
}

export interface SkillMarketImportRequest {
  sourceUrl: string
  commitSha: string
  sourceRoot: string
  expectedSelectedSourceSha256: string
  marketplaceProvider?: string
  marketplaceSkillId?: string
  publisher?: string
  version?: string
  displayName?: string
  visibility: AgentSkillVisibility
  projectCode?: string
}

export interface SkillMarketImportOrigin {
  id: number
  providerKey: string
  marketplaceSkillId?: string | null
  repository: string
  repositoryUrl: string
  sourceCommitSha: string
  sourceRoot: string
  bundleSourceSha256: string
  selectedSourceSha256: string
  skillId: number
  skillVersionId: number
  publisher: string
  name: string
  version: string
  visibility: AgentSkillVisibility
  projectCode?: string | null
  importedBy: string
  createdAt?: string | null
}

export interface SkillMarketImportResult {
  imported: AgentSkillImportResult
  origin: SkillMarketImportOrigin
}
