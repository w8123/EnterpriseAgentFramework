export type AgentSkillVisibility = 'PRIVATE' | 'PROJECT' | 'SHARED' | 'PUBLIC' | string
export type AgentSkillVersionStatus =
  | 'REVIEW_PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'PUBLISHED'
  | 'DEPRECATED'
  | 'REVOKED'
  | string

export interface AgentSkillSummary {
  id: number
  publisher: string
  name: string
  displayName: string
  description?: string | null
  visibility: AgentSkillVisibility
  ownerUserId?: number | null
  projectCode?: string | null
  status: string
  latestVersionId?: number | null
  defaultVersionId?: number | null
  versionCount: number
  updatedAt?: string | null
  latestVersion?: string | null
  latestVersionStatus?: AgentSkillVersionStatus | null
  defaultVersion?: string | null
}

export interface AgentSkillVersion {
  id: number
  skillId: number
  version: string
  status: AgentSkillVersionStatus
  sourceType: 'UPLOAD' | 'GIT' | 'MARKET' | 'BUILTIN' | string
  sourceRef?: string | null
  sourceSha256: string
  contentTreeSha256: string
  artifactSize: number
  declaredLicense?: string | null
  declaredCompatibility?: string | null
  hasScripts: boolean
  frontmatter?: Record<string, unknown> | null
  packageManifest?: Record<string, unknown> | null
  validationReport?: Record<string, unknown> | null
  riskReport?: Record<string, unknown> | null
  compatibilityReport?: Record<string, unknown> | null
  reviewedBy?: string | null
  reviewedAt?: string | null
  publishedBy?: string | null
  publishedAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface AgentSkillDetail {
  skill: AgentSkillSummary
  versions: AgentSkillVersion[]
  actions?: {
    canReview: boolean
    canPublish: boolean
    canBind: boolean
  }
}

export interface AgentSkillAccessCapabilities {
  canImport: boolean
  canImportSharedOrPublic: boolean
  maxPackageBytes: number
  maxExpandedBytes: number
  maxSingleFileBytes: number
  maxFiles: number
  maxInstructionBytes: number
  scriptExecutionEnabled: boolean
}

export interface AgentSkillImportResult {
  skill: AgentSkillSummary
  version: AgentSkillVersion
  created: boolean
}

export interface AgentSkillBundleCandidate {
  sourceRoot: string
  selectable: boolean
  name?: string | null
  description?: string | null
  declaredVersion?: string | null
  license?: string | null
  compatibility?: string | null
  hasScripts: boolean
  fileCount: number
  selectedSourceSha256?: string | null
  contentTreeSha256?: string | null
  warnings: string[]
  errorCode?: string | null
  errorMessage?: string | null
}

export interface AgentSkillBundleDiscovery {
  schema: 'reachai.agent-skill-bundle-discovery.v1' | string
  bundleSourceSha256: string
  archiveFileCount: number
  candidateCount: number
  multiSkill: boolean
  candidates: AgentSkillBundleCandidate[]
}

export interface AgentSkillReview {
  id: number
  skillId: number
  skillVersionId: number
  decision: 'APPROVE' | 'REJECT' | string
  comment?: string | null
  findings?: Record<string, unknown> | null
  reviewer: string
  createdAt?: string | null
}

export interface AgentSkillFilePreview {
  path: string
  kind: string
  size: number
  sha256: string
  previewable: boolean
  truncated: boolean
  content?: string | null
}

export interface AgentSkillBindingConfig {
  id?: number
  skillId: number
  skillVersionId: number
  publisher?: string
  name?: string
  displayName?: string | null
  visibility?: AgentSkillVisibility
  projectCode?: string | null
  version?: string
  sourceSha256?: string
  hasScripts?: boolean
  activationMode: 'MODEL_SELECTED' | 'ALWAYS' | 'EXPLICIT' | string
  scriptPolicy: 'DENY' | 'SANDBOX_REVIEWED' | string
  required: boolean
  enabled: boolean
  priority: number
}
