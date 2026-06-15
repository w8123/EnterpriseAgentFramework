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
  agentVisible: boolean
  lightweightEnabled: boolean
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
    defaultFlags: { enabled: false, agentVisible: false, lightweightEnabled: false },
    incrementalMode: 'OFF',
  }
}

/** 项目级 HTTP 鉴权；与 Tool 管理无关，测试扫描接口及带 projectId 的全局 Tool 调用时附加 */
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

export type SdkAccessCheckStatus = 'PASS' | 'WARN' | 'FAIL'

export interface SdkAccessCheckRequest {
  apiAssetId?: number | null
  args?: Record<string, unknown>
  gatewayBaseUrl?: string | null
  embedTokenPath?: string | null
}

export interface SdkAccessCheckItem {
  key: string
  label: string
  status: SdkAccessCheckStatus
  message: string
  evidence?: string | null
}

export interface SdkAccessCheckResponse {
  projectId: number
  projectCode: string
  overallStatus: SdkAccessCheckStatus
  checks: SdkAccessCheckItem[]
}

export type AiAccessStepStatus = 'TODO' | 'RUNNING' | 'PASS' | 'WARN' | 'FAIL' | 'SKIPPED'

export interface AiAccessStep {
  stepKey: string
  title: string
  status: AiAccessStepStatus
  message?: string | null
  files: string[]
  evidence: Record<string, unknown>
  reportedBy?: string | null
  startedAt?: string | null
  completedAt?: string | null
  updatedAt?: string | null
}

export interface AiAccessSession {
  sessionId: string
  projectId: number
  projectCode?: string | null
  toolName?: string | null
  scenario?: 'SDK_ACCESS' | 'PAGE_ASSISTANT' | string | null
  targetPageKey?: string | null
  targetRoute?: string | null
  status: AiAccessStepStatus | 'OPEN'
  totalSteps: number
  completedSteps: number
  failedSteps: number
  lastMessage?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  steps: AiAccessStep[]
}

export interface AiAccessCheckRunResponse {
  checkResult: SdkAccessCheckResponse
  session: AiAccessSession
}

export interface PageAssistantSessionRequest {
  toolName?: string | null
  pageKey?: string | null
  routePattern?: string | null
  actionKeys?: string[]
}

export interface PageAssistantCheckRequest {
  pageKey?: string | null
  routePattern?: string | null
  actionKeys?: string[]
}

export interface PageAssistantTargetRequest {
  pageKey?: string | null
  routePattern?: string | null
  actionKeys?: string[]
}

export interface PageAssistantCatalogActionRequest {
  actionKey: string
  title?: string | null
  description?: string | null
  confirmRequired?: boolean | null
  inputSchema?: Record<string, unknown>
  outputSchema?: Record<string, unknown>
  sampleArgs?: Record<string, unknown>
  allowedAgentIds?: string[]
  metadata?: Record<string, unknown>
}

export interface PageAssistantCatalogSyncRequest {
  pageKey: string
  name?: string | null
  routePattern?: string | null
  origin?: string | null
  pageInstanceId?: string | null
  replaceActions?: boolean
  actions: PageAssistantCatalogActionRequest[]
  metadata?: Record<string, unknown>
}

export interface PageAssistantWorkflowBinding {
  agentId: string
  agentKeySlug: string
  workflowId: string
  workflowKeySlug: string
  bindingId?: number | null
}

export interface PageAssistantCatalogSyncResponse {
  projectCode: string
  appId: string
  pageKey: string
  actionCount: number
  session: AiAccessSession
  workflowBinding?: PageAssistantWorkflowBinding | null
}

export interface PageAssistantCheckItem {
  key: string
  label: string
  status: SdkAccessCheckStatus
  message: string
  evidence?: string | null
}

export interface PageAssistantCheckResponse {
  projectId: number
  projectCode: string
  pageKey?: string | null
  routePattern?: string | null
  overallStatus: SdkAccessCheckStatus
  checks: PageAssistantCheckItem[]
}

export interface PageAssistantCheckRunResponse {
  checkResult: PageAssistantCheckResponse
  session: AiAccessSession
}

export interface PageAssistantFileEvidence {
  path: string
  role?: string | null
  exists?: boolean | null
  sha256?: string | null
}

export interface PageAssistantPageRegisterRequest {
  sessionId?: string | null
  toolName?: string | null
  pageKey: string
  pageName?: string | null
  routePattern?: string | null
  framework?: string | null
  frameworkVersion?: string | null
  bridgeGlobal?: string | null
  replaceActions?: boolean | null
  files?: PageAssistantFileEvidence[]
  actions: PageAssistantCatalogActionRequest[]
  verification?: Record<string, unknown>
  handoffSummary?: string | null
}

export interface PageAssistantPageRegisterResponse {
  session: AiAccessSession
  checkResult: PageAssistantCheckResponse
  registeredPage: {
    projectCode?: string | null
    appId?: string | null
    pageKey: string
    pageName?: string | null
    routePattern?: string | null
    framework?: string | null
    bridgeGlobal?: string | null
  }
  registeredActions: string[]
  fileEvidence: PageAssistantFileEvidence[]
  workflowBinding?: PageAssistantWorkflowBinding | null
}

export interface PageAssistantSessionSummary {
  sessionId: string
  projectId: number
  projectCode?: string | null
  toolName?: string | null
  targetPageKey?: string | null
  targetRoute?: string | null
  status: AiAccessStepStatus | 'OPEN'
  completionState: 'WAITING_TARGET' | 'IN_PROGRESS' | 'COMPLETED' | 'BLOCKED' | string
  totalSteps: number
  completedSteps: number
  failedSteps: number
  actionCount: number
  lastMessage?: string | null
  lastReportedAt?: string | null
  steps: AiAccessStep[]
}

export interface PageAssistantOnboardingManifest {
  schema: string
  project: AiOnboardingManifest['project']
  aiCodingAccess: AiOnboardingManifest['aiCodingAccess']
  target: {
    pageKey?: string | null
    routePattern?: string | null
    actionKeys: string[]
  }
  session: AiAccessSession
  endpoints: {
    manifestUrl: string
    latestSessionUrl: string
    stepReportUrl: string
    targetBindUrl: string
    catalogSyncUrl: string
    checksRunUrl: string
    registerPageUrl?: string | null
  }
  security: AiOnboardingManifest['security']
  localExecution?: {
    requiresLocalShell: boolean
    reason: string
  }
  pageActionContract?: {
    bridgeGlobal: string
    protocolVersion: string
    supportedFrameworks: string[]
    recommendedActions: string[]
    safety: {
      readonlyFirst: boolean
      highRiskActionsRequireConfirm: boolean
    }
  }
  scaffold?: {
    framework: string
    templates: Array<{
      name: string
      role: string
    }>
  }
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
  agentWorkflow?: {
    model: string
    globalAgentKeySlug?: string | null
    globalAgentKind?: string | null
    workflowStorage?: string | null
    sdkGraphWorkflowType?: string | null
    bindingStrategy?: string | null
    endpoints?: {
      agentsUrl?: string | null
      workflowsUrl?: string | null
      globalAgentBindingsUrl?: string | null
      resolvePreviewUrl?: string | null
    }
    requiredSteps?: string[]
  }
  security: {
    appSecretEnv: string
    message: string
  }
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
  skillNames: string[]
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
  /** 扫描表 scan_project_tool.id，编辑/测试/语义生成/添加为 Tool 均依赖此字段 */
  scanToolId: number
  projectId?: number | null
  /** 扫描模块 scan_module.id，与语义文档模块一致 */
  moduleId?: number | null
  /** 模块展示名（优先 displayName） */
  moduleDisplayName?: string | null
  /** 已「添加为 Tool」时对应全局 tool_definition.id，未添加为 null/undefined */
  globalToolDefinitionId?: number | null
  /** 全局 Tool 的 name（与项目内名可能不同） */
  globalToolName?: string | null
  /** 扫描行与全局 Tool 在可同步字段上是否不一致（需「更新到Tool」） */
  globalToolOutOfSync?: boolean
  /** SDK/扫描源中是否已不存在该接口（墓碑行） */
  removedFromSource?: boolean
  /** 与全局 Tool 关联健康状态，与后端 ApiToolLinkStatus 枚举一致 */
  toolLinkStatus?: string
  toolLinkMessage?: string | null
  /** 与全局 Tool 不一致的字段名列表 */
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

/** POST .../promote-to-tool 响应 */
export interface PromotedGlobalTool {
  globalToolId: number
  globalToolName: string
}

/** POST .../promote-by-module 响应 */
export interface BatchPromoteToToolsResult {
  promotedCount: number
  items: PromotedGlobalTool[]
}
