export type A2aDirection = 'INBOUND' | 'OUTBOUND' | 'BIDIRECTIONAL'
export type A2aEnvironment = 'DEVELOPMENT' | 'TEST' | 'STAGING' | 'PRODUCTION'
export type A2aPublicationStatus =
  | 'DRAFT'
  | 'VALIDATING'
  | 'READY'
  | 'PUBLISHED'
  | 'SUSPENDED'
  | 'ARCHIVED'
export type A2aRemoteAgentStatus =
  | 'DISCOVERED'
  | 'VERIFYING'
  | 'REVIEW_REQUIRED'
  | 'TRUSTED'
  | 'DISABLED'
  | 'QUARANTINED'
  | 'ARCHIVED'
export type A2aRemoteAgentHealth = 'UNKNOWN' | 'HEALTHY' | 'DEGRADED' | 'UNREACHABLE'
export type A2aTaskState =
  | 'TASK_STATE_SUBMITTED'
  | 'TASK_STATE_WORKING'
  | 'TASK_STATE_INPUT_REQUIRED'
  | 'TASK_STATE_AUTH_REQUIRED'
  | 'TASK_STATE_COMPLETED'
  | 'TASK_STATE_FAILED'
  | 'TASK_STATE_CANCELED'
  | 'TASK_STATE_REJECTED'

export interface A2aPageView<T> {
  schema: string
  items: T[]
  total: number
  limit: number
  offset: number
}

export interface A2aHubOverview {
  schema: string
  windowStartedAt: string
  generatedAt: string
  publications: { published: number; draft: number; suspended: number }
  remoteAgents: { trusted: number; quarantined: number; unhealthy: number }
  tasks: {
    total: number
    completed: number
    failed: number
    working: number
    inputRequired: number
    authRequired: number
    completionRate: number
  }
  transport: {
    requests: number
    successful: number
    successRate: number
    averageLatencyMs?: number | null
    p95LatencyMs?: number | null
    percentileStatus: string
  }
  governance: { expiringCredentials: number; failedConformanceRuns: number }
  attention: Array<{ code: string; severity: string; count: number; actionRoute: string }>
}

export interface A2aProtocolSkill {
  id: string
  name: string
  description: string
  tags: string[]
  examples: string[]
  inputModes: string[]
  outputModes: string[]
}

export interface A2aPublication {
  schema: string
  id: number
  publicationKey: string
  agentId: string
  projectId?: number | null
  projectCode?: string | null
  environment: A2aEnvironment
  tenantScope: string
  publicHost: string
  trustProfileId: number
  currentRevisionId?: number | null
  status: A2aPublicationStatus
  version: number
  publishedAt?: string | null
  suspendedAt?: string | null
  createdBy: string
  updatedBy: string
  createdAt: string
  updatedAt: string
}

export interface A2aPublicationRevision {
  schema: string
  id: number
  publicationId: number
  revisionNo: number
  agentConfigVersionId: number
  agentVersion: string
  name: string
  description: string
  providerOrganization?: string | null
  providerUrl?: string | null
  documentationUrl?: string | null
  iconUrl?: string | null
  publicOrigin: string
  protocolBasePath: string
  protocolBinding: string
  protocolVersion: string
  streamingSupported: boolean
  pushNotificationsSupported: boolean
  extendedCardSupported: boolean
  defaultInputModes: string[]
  defaultOutputModes: string[]
  protocolSkills: A2aProtocolSkill[]
  agentCardJson: string
  agentCardSha256: string
  signatureStatus: string
  conformanceStatus: string
  validationSummaryJson: string
  status: string
  createdBy: string
  createdAt: string
  publishedAt?: string | null
}

export interface A2aPublicationDetail {
  schema: string
  publication: A2aPublication
  revisions: A2aPublicationRevision[]
}

export interface A2aRemoteInterface {
  interfaceKey: string
  url: string
  protocolBinding: string
  protocolVersion: string
  tenant?: string | null
}

export interface A2aRemoteAgent {
  schema: string
  id: number
  remoteAgentKey: string
  displayName: string
  tenantScope: string
  agentCardUrl: string
  trustProfileId?: number | null
  credentialId?: number | null
  currentRevisionId?: number | null
  preferredInterfaceKey?: string | null
  preferredSecuritySchemeKey?: string | null
  status: A2aRemoteAgentStatus
  healthStatus: A2aRemoteAgentHealth
  consecutiveHealthFailures: number
  lastDiscoveredAt?: string | null
  lastHealthCheckedAt?: string | null
  lastHealthSummary?: string | null
  callable: boolean
  callabilityCode: string
  version: number
  createdBy: string
  updatedBy: string
  createdAt: string
  updatedAt: string
}

export interface A2aRemoteAuthenticationOption {
  securitySchemeKey: string
  schemeType: string
  credentialType?: 'API_KEY' | 'BEARER' | null
  placement?: string | null
  headerName?: string | null
  authorizationScheme?: string | null
  scopes: string[]
  selectable: boolean
  supportCode: string
}

export interface A2aRemoteAgentRevision {
  schema: string
  id: number
  remoteAgentId: number
  revisionNo: number
  name: string
  description: string
  providerOrganization?: string | null
  providerUrl?: string | null
  documentationUrl?: string | null
  iconUrl?: string | null
  agentVersion: string
  supportedInterfaces: A2aRemoteInterface[]
  streamingSupported: boolean
  pushNotificationsSupported: boolean
  extendedCardSupported: boolean
  defaultInputModes: string[]
  defaultOutputModes: string[]
  protocolSkills: A2aProtocolSkill[]
  securitySchemesJson?: string | null
  securityRequirementsJson?: string | null
  authenticationRequired: boolean
  anonymousAccessAllowed: boolean
  authenticationOptions: A2aRemoteAuthenticationOption[]
  authenticationSupportCode: string
  agentCardJson: string
  agentCardSha256: string
  signatureStatus: string
  signingKeyId?: string | null
  tlsIdentitySha256?: string | null
  networkEvidenceJson?: string | null
  httpEtag?: string | null
  httpLastModified?: string | null
  reviewStatus: 'PENDING' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED'
  discoveredAt: string
  reviewedBy?: string | null
  reviewedAt?: string | null
  createdAt: string
}

export interface A2aRemoteAgentDetail {
  schema: string
  remoteAgent: A2aRemoteAgent
  revisions: A2aRemoteAgentRevision[]
}

export interface A2aRemoteAgentDiscoverRequest {
  remoteAgentKey: string
  displayName: string
  tenantScope?: string
  agentCardUrl: string
}

export interface A2aRemoteAgentDiscoveryResult {
  schema: string
  outcome: 'REVIEW_REQUIRED' | 'UNCHANGED' | 'QUARANTINED'
  reasonCode?: string | null
  detail: A2aRemoteAgentDetail
}

export interface A2aPublicationDraft {
  agentConfigVersionId: number
  agentVersion?: string
  name: string
  description: string
  providerOrganization?: string
  providerUrl?: string
  documentationUrl?: string
  iconUrl?: string
  publicOrigin: string
  streamingSupported: boolean
  pushNotificationsSupported: boolean
  extendedCardSupported: boolean
  defaultInputModes: string[]
  defaultOutputModes: string[]
  protocolSkills: A2aProtocolSkill[]
}

export interface A2aPublicationCreateRequest {
  publicationKey: string
  agentId: string
  environment: A2aEnvironment
  tenantScope?: string
  publicHost: string
  trustProfileId: number
  revision: A2aPublicationDraft
}

export interface A2aAuthorizationPolicy {
  publicationKeys: string[]
  remoteAgentKeys: string[]
  protocolSkillIds: string[]
  operations: string[]
  tenantScopes: string[]
}

export interface A2aDataPolicy {
  allowedDataClassifications: string[]
  allowTextParts: boolean
  allowFileParts: boolean
  allowUrlParts: boolean
  payloadRetentionDays: number
}

export interface A2aTrustProfile {
  schema: string
  id: number
  profileKey: string
  name: string
  description: string
  direction: A2aDirection
  environment: A2aEnvironment
  trustLevel: 'UNTRUSTED' | 'KNOWN' | 'TRUSTED' | 'PRIVILEGED'
  authenticationMethods: string[]
  allowedScopes: string[]
  authorizationPolicy: A2aAuthorizationPolicy
  dataPolicy: A2aDataPolicy
  delegatedIdentityPolicy: 'DENY' | 'ATTESTED_ONLY'
  personalMemoryPolicy: 'DISABLED' | 'ATTESTED_USER_ONLY'
  rateLimitPerMinute: number
  maxConcurrentTasks: number
  maxRequestBytes: number
  maxArtifactBytes: number
  taskTimeoutMs: number
  allowAnonymous: boolean
  status: 'ACTIVE' | 'DISABLED' | 'ARCHIVED'
  version: number
  createdBy: string
  updatedBy: string
  createdAt: string
  updatedAt: string
}

export type A2aTrustProfileUpsertRequest = Omit<
  A2aTrustProfile,
  | 'schema'
  | 'id'
  | 'status'
  | 'version'
  | 'createdBy'
  | 'updatedBy'
  | 'createdAt'
  | 'updatedAt'
>

export interface A2aCredential {
  schema: string
  id: number
  credentialKey: string
  name: string
  direction: A2aDirection
  credentialType: string
  materialMode: string
  fingerprint: string
  versionNo: number
  status: 'ACTIVE' | 'GRACE' | 'REVOKED' | 'EXPIRED'
  notBefore?: string | null
  expiresAt?: string | null
  lastUsedAt?: string | null
  rotatedFromId?: number | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface A2aApiKeyIssue {
  schema: string
  credential: A2aCredential
  apiKeyOnce: string
  nextAction: string
}

export interface A2aOutboundSecretStored {
  schema: string
  credential: A2aCredential
  nextAction: string
}

export interface A2aPrincipal {
  schema: string
  id: number
  principalKey: string
  principalType: string
  displayName: string
  tenantScope: string
  authenticatedSubject: string
  trustProfileId: number
  credentialId?: number | null
  scopes: string[]
  attributes: Record<string, string>
  status: 'ACTIVE' | 'DISABLED' | 'QUARANTINED' | 'ARCHIVED'
  lastAuthenticatedAt?: string | null
  version: number
  createdBy: string
  updatedBy: string
  createdAt: string
  updatedAt: string
}

export interface A2aPrincipalCreateRequest {
  principalKey: string
  principalType: string
  runtimeAgentId?: string
  displayName: string
  tenantScope?: string
  trustProfileId: number
  credentialId?: number
  scopes: string[]
  attributes: Record<string, string>
}

export interface A2aTaskSummary {
  taskId: string
  direction: 'INBOUND' | 'OUTBOUND'
  state: A2aTaskState
  contextId: string
  publicationId?: number | null
  publicationKey?: string | null
  publicationRevisionId?: number | null
  remoteAgentId?: number | null
  remoteAgentKey?: string | null
  remoteRevisionId?: number | null
  principalId: number
  principalKey: string
  tenantScope: string
  runtimeRunId?: string | null
  traceId?: string | null
  statusSummary?: string | null
  errorCode?: string | null
  errorSummary?: string | null
  cancelPhase?: string | null
  outboundPollStatus?: string | null
  outboundPollAttemptCount: number
  outboundNextPollAt?: string | null
  outboundLastPolledAt?: string | null
  outboundLastPollErrorCode?: string | null
  outboundLastPollErrorSummary?: string | null
  attemptCount: number
  lastEventSequence: number
  submittedAt: string
  startedAt?: string | null
  completedAt?: string | null
  deadlineAt?: string | null
  updatedAt: string
}

export interface A2aTaskEvent {
  sequence: number
  eventId: string
  eventType: string
  fromState?: string | null
  toState?: string | null
  actorType: string
  actorId: string
  resourceType?: string | null
  resourceId?: string | null
  safeSummary?: string | null
  runtimeSequence?: number | null
  traceId?: string | null
  createdAt: string
}

export interface A2aMessageSummary {
  messageId: string
  role: string
  payloadBytes: number
  payloadSha256: string
  contentClassification: string
  safeSummary: string
  contentAvailable: boolean
  retentionExpiresAt?: string | null
  createdAt: string
}

export interface A2aArtifactSummary {
  artifactId: string
  name?: string | null
  description?: string | null
  payloadBytes: number
  payloadSha256: string
  mediaTypes: string[]
  contentClassification: string
  safeSummary: string
  appendRevision: number
  lastChunk: boolean
  contentAvailable: boolean
  retentionExpiresAt?: string | null
  updatedAt: string
}

export interface A2aTaskDetail {
  schema: string
  task: A2aTaskSummary
  identity: {
    principalId: number
    principalKey: string
    principalDisplayName: string
    tenantScope: string
    trustProfileId?: number | null
    trustProfileKey?: string | null
  }
  runtime: {
    executionId: string
    runId?: string | null
    traceId?: string | null
    interactionId?: string | null
  }
  events: A2aTaskEvent[]
  messages: A2aMessageSummary[]
  artifacts: A2aArtifactSummary[]
  eventGapDetected: boolean
}

export interface A2aPayloadView {
  resourceType: string
  resourceId: string
  mediaType: string
  bytes: number
  sha256: string
  contentClassification: string
  content: unknown
}

export interface A2aTaskQuery {
  search?: string
  direction?: string
  state?: string
  publicationId?: number
  remoteAgentId?: number
  principalId?: number
  tenantScope?: string
  submittedAfter?: string
  submittedBefore?: string
  limit?: number
  offset?: number
}
