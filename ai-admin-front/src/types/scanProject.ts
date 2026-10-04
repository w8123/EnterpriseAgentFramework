import type { ToolInfo } from '@/types/tool'

export type ScanType = 'openapi' | 'controller' | 'auto'
export type ScanStatus = 'created' | 'scanning' | 'scanned' | 'failed'

/** 与后端 ScanOptions / ScanSettings 枚举值一致 */
export type DescriptionSource =
  | 'JAVADOC'
  | 'SWAGGER_API_OPERATION'
  | 'OPENAPI_OPERATION'
  | 'METHOD_NAME'

export type ParamDescriptionSource =
  | 'JAVADOC_PARAM'
  | 'SCHEMA_ANNO'
  | 'PARAMETER_ANNO'
  | 'FIELD_NAME'

export type ScanIncrementalMode = 'OFF' | 'MTIME' | 'GIT_DIFF'

export interface ScanDefaultFlags {
  enabled: boolean
}

/** 各说明源是否参与解析；未列出或为 true=开启，为 false=不参与（优先级行仍可排序） */
export type SourceEnabledMap<T extends string> = Partial<Record<T, boolean>>

export interface ScanSettings {
  descriptionSourceOrder: DescriptionSource[]
  paramDescriptionSourceOrder: ParamDescriptionSource[]
  /** 缺省或 true=解析该源；false=跳过。未出现的 key 视为 true（兼容旧数据） */
  descriptionSourceEnabled: SourceEnabledMap<DescriptionSource>
  paramDescriptionSourceEnabled: SourceEnabledMap<ParamDescriptionSource>
  onlyRestController: boolean
  httpMethodWhitelist: string[]
  classIncludeRegex: string
  classExcludeRegex: string
  skipDeprecated: boolean
  defaultFlags: ScanDefaultFlags
  incrementalMode: ScanIncrementalMode
}

export function getDefaultScanSettings(): ScanSettings {
  return {
    descriptionSourceOrder: [
      'SWAGGER_API_OPERATION',
      'OPENAPI_OPERATION',
      'JAVADOC',
      'METHOD_NAME',
    ],
    paramDescriptionSourceOrder: ['PARAMETER_ANNO', 'SCHEMA_ANNO', 'JAVADOC_PARAM', 'FIELD_NAME'],
    descriptionSourceEnabled: {
      JAVADOC: true,
      SWAGGER_API_OPERATION: true,
      OPENAPI_OPERATION: true,
      METHOD_NAME: true,
    },
    paramDescriptionSourceEnabled: {
      JAVADOC_PARAM: true,
      SCHEMA_ANNO: true,
      PARAMETER_ANNO: true,
      FIELD_NAME: true,
    },
    onlyRestController: true,
    httpMethodWhitelist: [],
    classIncludeRegex: '',
    classExcludeRegex: '',
    skipDeprecated: false,
    defaultFlags: { enabled: false },
    incrementalMode: 'OFF',
  }
}

/** 项目级 HTTP 鉴权；与能力目录管理无关，测试扫描接口及带 projectId 的运行时调用时附加 */
export type ScanProjectAuthType = 'none' | 'api_key'
export type ScanProjectAuthApiKeyIn = 'header' | 'query'

export interface ScanProject {
  id: number
  name: string
  projectCode?: string | null
  projectKind?: 'SCAN' | 'REGISTERED' | 'HYBRID'
  environment?: string
  owner?: string | null
  visibility?: 'PRIVATE' | 'PROJECT' | 'SHARED' | 'PUBLIC'
  baseUrl: string
  contextPath: string
  scanPath: string
  scanType: ScanType
  specFile?: string | null
  toolCount: number
  status: ScanStatus
  errorMessage?: string | null
  authType?: ScanProjectAuthType
  authApiKeyIn?: ScanProjectAuthApiKeyIn | null
  authApiKeyName?: string | null
  authApiKeyValue?: string | null
  /** 与后端一致；缺省时前端用 getDefaultScanSettings() */
  scanSettings?: ScanSettings
  /** 列表展示描述，由后端按项目环境、地址等统一生成 */
  description?: string | null
  /** SDK 注册项目最近心跳上报的 SDK 版本，无实例时为后端 fallback */
  sdkVersion?: string | null
  /** 列表展示 API 数量；默认等同 toolCount */
  apiCount?: number
  /** 列表展示状态摘要 */
  registryStatusSummary?: string | null
  /** 仅 GET /api/scan-projects/:id 返回；列表不含 */
  registryCredentialConfigured?: boolean
  registryAppKey?: string | null
  registryAppSecret?: string | null
  lastScannedAt?: string | null
}

export interface ScanProjectUpsertRequest {
  name: string
  projectCode?: string | null
  projectKind?: 'SCAN' | 'REGISTERED' | 'HYBRID'
  environment?: string
  owner?: string | null
  visibility?: 'PRIVATE' | 'PROJECT' | 'SHARED' | 'PUBLIC'
  baseUrl: string
  contextPath: string
  scanPath: string
  scanType: ScanType
  specFile?: string | null
}

/** PATCH /api/scan-projects/:id/auth-settings */
export interface ScanProjectAuthSaveRequest {
  authType: ScanProjectAuthType
  authApiKeyIn?: ScanProjectAuthApiKeyIn | null
  authApiKeyName?: string | null
  authApiKeyValue?: string | null
}

/** PATCH /api/scan-projects/:id/registry-credential */
export interface ScanProjectRegistryCredentialSaveRequest {
  appKey: string
  appSecret: string
}

export type SdkAccessCheckStatus = 'PASS' | 'WARN' | 'FAIL' | 'PENDING'

export interface SdkAccessCheckItem {
  key: string
  label: string
  status: SdkAccessCheckStatus
  message: string
  evidence?: string | null
}

export type SdkAccessReadinessKey =
  | 'CODE_READY'
  | 'RUNTIME_READY'
  | 'SDK_CALLBACK_READY'
  | 'E2E_READY'

export interface SdkAccessReadiness {
  key: SdkAccessReadinessKey | string
  label: string
  status: SdkAccessCheckStatus
  message: string
}

export interface SdkAccessCheckResponse {
  projectId: number
  projectCode: string
  overallStatus: SdkAccessCheckStatus
  readiness: SdkAccessReadiness[]
  checks: SdkAccessCheckItem[]
}

export interface SdkArtifact {
  type: 'maven' | 'npm' | string
  language: 'java' | 'browser' | string
  coordinates: string
  groupId?: string | null
  artifactId?: string | null
  packageName?: string | null
  version: string
  sourcePolicy: string
  repositoryUrls: string[]
  localInstallCommand?: string | null
  notes?: string | null
  format?: string | null
  downloadUrl?: string | null
  integritySha256?: string | null
  installCommand?: string | null
  fallbackPolicy?: string | null
  requiredFiles?: string[] | null
  /** Path of the tarball inside the extracted onboarding skill zip, e.g. reachai-onboarding/artifacts/...tgz */
  artifactPathWithinSkill?: string | null
  /** Where to run install: business frontend directory that contains package.json */
  installWorkingDirectory?: string | null
  /** Stable installer template; replace {skillExtractDir} with absolute Skill extract root */
  installCommandTemplate?: string | null
  /** Standalone consumer POM served by ReachAI for source-independent Maven local installation */
  pomDownloadUrl?: string | null
  /** SHA-256 for pomDownloadUrl */
  pomIntegritySha256?: string | null
}

export interface GatewayChecklistItem {
  id: string
  description: string
  required: boolean
  verificationHint: string
  failureImpact: string
}

export interface ResponseShape {
  wrapper: 'ApiResult' | 'bare-json' | string
  fields: Record<string, string>
  notes?: string | null
}

export interface AiOnboardingManifest {
  schema: string
  project: {
    id: number
    name: string
    projectCode?: string | null
    projectKind?: string | null
    environment?: string | null
    baseUrl?: string | null
    contextPath?: string | null
    registryAppKey?: string | null
    registryCredentialConfigured: boolean
  }
  aiCodingAccess: {
    enabled: boolean
    accessKey?: string | null
  }
  sdk: {
    version: string
    dependencies: Array<{
      groupId: string
      artifactId: string
      version: string
    }>
    config: {
      registryUrl: string
      appKey?: string | null
      appSecretEnv: string
      projectCode?: string | null
      projectName?: string | null
      projectBaseUrl?: string | null
      projectContextPath?: string | null
      environment?: string | null
    }
  }
  sdkArtifacts?: SdkArtifact[]
  responseShapes?: Record<string, ResponseShape>
  /** Top-level machine checklist; not a ResponseShape comma string */
  gatewayChecklist?: GatewayChecklistItem[]
  endpoints: {
    skillPackageUrl: string
    manifestUrl: string
    sdkAccessCheckUrl: string
    reconcileToolsUrl: string
  }
  embed: {
    tokenPath: string
    defaultAgentId?: string | null
    defaultAgentKeySlug?: string | null
    allowedAgents: Array<{
      id: string
      keySlug?: string | null
      name: string
      projectCode?: string | null
      enabled: boolean
    }>
  }
  agentProvisioning?: {
    model: string
    defaultKeySlug?: string | null
    provisionAgentUrl?: string | null
    idempotent?: boolean
    createsSupervisorConfig?: boolean
    activatesSupervisorConfig?: boolean
    modelSelection?: string | null
    requiredSteps?: string[]
  }
  agentSupervisor?: {
    model: string
    globalAgentKeySlug?: string | null
    runtimeType?: string | null
    workflowToolCatalog?: string | null
    endpoints?: {
      agentsUrl?: string | null
      configVersionsUrlTemplate?: string | null
      configDraftUrlTemplate?: string | null
      workflowToolAttachUrlTemplate?: string | null
      executeUrl?: string | null
    }
    workflowAiCoding?: {
      skillPackageUrl?: string | null
      createUrl?: string | null
      contextUrlTemplate?: string | null
      patchUrlTemplate?: string | null
      validateUrlTemplate?: string | null
      runUrlTemplate?: string | null
      versionsUrlTemplate?: string | null
      publishUrlTemplate?: string | null
      runsUrlTemplate?: string | null
      requiredSteps?: string[]
    }
    requiredSteps?: string[]
  }
  security: {
    appSecretEnv: string
    secretSetupScriptWithinSkill?: string | null
    secretSetupCommandTemplate?: string | null
    message: string
  }
}

export interface AiCodingGatewayManifest {
  schema: 'reachai.ai-coding.gateway.v3' | string
  project: {
    id: number
    projectCode?: string | null
    name?: string | null
    projectKind?: string | null
    environment?: string | null
  }
  auth: {
    headerName: string
    auditActor: string
    guidance: string[]
  }
  sdkArtifacts?: SdkArtifact[]
  responseShapes?: Record<string, ResponseShape>
  gatewayChecklist?: GatewayChecklistItem[]
  endpoints: {
    manifestUrl: string
    contextCandidatesUrl: string
    contextCandidatesBatchUrl: string
    contextCandidateStatusUrlTemplate: string
    sdkAccessManifestUrl: string
    handoffActivationUrlTemplate: string
    taskContextUrlTemplate: string
    taskHeartbeatUrlTemplate: string
    taskEventsUrlTemplate: string
    taskQuestionsUrlTemplate: string
    taskArtifactsUrlTemplate: string
    workflowCreateUrl: string
    workflowContextUrlTemplate: string
    workflowPatchUrlTemplate: string
    workflowValidateUrlTemplate: string
    workflowRunUrlTemplate: string
    workflowVersionsUrlTemplate: string
    workflowPublishUrlTemplate: string
    workflowRunsUrlTemplate: string
    contextCandidateReviewUrl: string
    contextCandidateAuditUrlTemplate: string
  }
  contextCandidateSubmission: {
    schema: 'reachai.context-candidate-submission.v1' | string
    endpoint: string
    batchEndpoint: string
    reviewMode: 'PENDING_HUMAN_REVIEW' | string
    memoryLane: 'PROJECT_DEV' | string
    tenantId: string
    defaultSourceType: string
    defaultCandidateType: string
    requiredFields: string[]
    candidateTypes: string[]
    sourceTypes: string[]
    traceMetadata: {
      metadataKey: string
      generatedSubmissionIdPrefix: string
      defaultOrigin: string
      traceIdPolicy: string
      sessionIdPolicy: string
    }
    serverControlledFields: string[]
    guidance: string[]
  }
  capabilities: Array<{
    key: 'SDK_ACCESS' | 'PAGE_ASSISTANT' | 'WORKFLOW_AI_CODING' | 'CONTEXT_CANDIDATES' | string
    title: string
    targetType: 'PROJECT' | 'PAGE' | 'WORKFLOW' | string
    entryUrl: string
    guidance: string[]
  }>
}

export interface AiCodingAccessUpdateRequest {
  enabled: boolean
  accessKey?: string | null
}

export interface AiCodingAccessResponse {
  enabled: boolean
  accessKey?: string | null
}

export interface ScanProjectScanResult {
  projectId: number
  projectName: string
  toolCount: number
  toolNames: string[]
}

/** GET /api/scan-projects/:id/operation-blockers；与 409 响应体结构一致 */
export interface ScanProjectBlockers {
  blocked: boolean
  toolNames: string[]
  agents: { id: string; name: string }[]
}

/** GET tools 返回的敏感扫描摘要（来自 scan_project_tool.sensitive_data_json） */
export interface ScanToolSensitiveData {
  types: string[]
  summary?: string | null
  scannedAt?: string | null
  modelName?: string | null
}

export type SensitiveScanTaskStage = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'

export interface SensitiveScanTask {
  taskId: string
  projectId: number
  stage: SensitiveScanTaskStage
  totalSteps: number
  completedSteps: number
  failedCount: number
  currentStep: string | null
  errorMessage: string | null
  totalTokens: number
  startedAt: string | null
  finishedAt: string | null
}

export interface ProjectToolInfo extends ToolInfo {
  /** 扫描表 capability_scan_project_tool.id，仅用于来源详情、重扫与语义证据。 */
  scanToolId: number
  projectId?: number | null
  /** 扫描模块 scan_module.id，与语义文档模块一致 */
  moduleId?: number | null
  /** 模块展示名（优先 displayName） */
  moduleDisplayName?: string | null
  /** SDK 自动或历史人工技术投影关联，不代表当前接纳、连接或调用授权。 */
  globalToolDefinitionId?: number | null
  /** 运行时执行定义的 name（与项目内名可能不同） */
  globalToolName?: string | null
  /** 扫描行与运行时执行定义在可同步字段上是否不一致（需“更新能力定义”） */
  globalToolOutOfSync?: boolean
  /** SDK/扫描源中是否已不存在该接口（墓碑行） */
  removedFromSource?: boolean
  /** 只读历史投影关联状态，与后端技术枚举一致。 */
  toolLinkStatus?: string
  toolLinkMessage?: string | null
  /** 与运行时执行定义不一致的字段名列表 */
  toolSyncDiffFields?: string[]
  /** 最近一次能力快照中存在待评审的 SDK diff（与 qualifiedName 匹配） */
  sdkCapabilityReviewPending?: boolean
  /** GET tools 返回的敏感扫描摘要 */
  sensitiveData?: ScanToolSensitiveData | null
  /** summary 视图不返回完整参数树，首屏用该字段展示参数数量 */
  parameterCount?: number
}

/** POST /api/scan-projects/:id/tools/reconcile 汇总 */
export interface ToolReconcileSummary {
  sdkMirrorsEnsured: number
  notLinked: number
  inSync: number
  pendingUpdate: number
  apiRemovedStale: number
  globalMissing: number
  sdkReviewPendingRows: number
}

export interface SdkCapabilityScanResult {
  projectId: number
  projectCode: string
  instanceId: string
  targetUrl: string
  capabilityCount: number
  businessResponse: Record<string, unknown>
}

/** POST .../promote-to-tool response */

/** POST .../promote-by-module 响应 */
