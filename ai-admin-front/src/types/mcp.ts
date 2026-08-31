/** MCP 互联中心（MCP Hub）领域类型，与 Control 服务 /api/mcp 契约一一对应。 */

export type McpPublicationState = 'DRAFT' | 'VALIDATING' | 'READY' | 'PUBLISHED' | 'SUSPENDED' | 'ARCHIVED'
export type McpItemKind = 'CAPABILITY' | 'WORKFLOW'
export type McpClientState = 'ACTIVE' | 'ROTATED' | 'REVOKED' | 'EXPIRED'
export type McpCallDirection = 'OUTBOUND' | 'INBOUND'
export type McpRiskLevel = 'READ' | 'WRITE' | 'PAGE_ACTION' | 'IRREVERSIBLE' | 'UNKNOWN'

/** MCP Hub 总览指标。 */
export interface McpHubOverview {
  publications: number
  publishedPublications: number
  activeClients: number
  expiringCredentials: number
  outboundCalls: number
  outboundSuccessRate: number
  outboundP95Ms: number | null
  /** M2 预留，当前恒 0。 */
  inboundCalls: number
}

/** 对外发布聚合根。 */
export interface McpPublication {
  id: number
  name: string
  description: string | null
  state: McpPublicationState
  currentRevisionId: number | null
  createdAt: string | null
  updatedAt: string | null
}

/** 发布条目：草稿态组成单元，引用 Capability 或已发布 Workflow。 */
export interface McpPublicationItem {
  id: number
  publicationId: number
  sourceKind: McpItemKind
  sourceRef: string
  alias: string | null
  descriptionOverride: string | null
  riskLevelOverride: McpRiskLevel | null
  enabled: boolean
  createdAt: string | null
  updatedAt: string | null
}

/** 发布修订的风险摘要计数。 */
export interface McpRiskSummary {
  read: number
  write: number
  page_action: number
  irreversible: number
  unknown: number
}

/** 发布修订视图（riskSummaryJson 为 JSON 字符串，前端解析展示）。 */
export interface McpPublicationRevisionView {
  id: number
  publicationId: number
  revisionNo: number
  toolCount: number
  riskSummaryJson: string
  publishedAt: string | null
}

/** 挂接在发布下的客户端凭证视图。 */
export interface McpClientView {
  id: number
  publicationId: number
  name: string
  projectId: number | null
  projectCode: string
  environment: string
  tenantId: string
  apiKeyPrefix: string
  roles: string[]
  toolScope: string[]
  state: McpClientState
  enabled: boolean
  expiresAt: string | null
  lastUsedAt: string | null
  createdAt: string | null
}

/** 发布详情聚合视图。 */
export interface McpPublicationDetail {
  publication: McpPublication
  items: McpPublicationItem[]
  revisions: McpPublicationRevisionView[]
  clients: McpClientView[]
  availableToolNames: string[]
}

/** MCP 工具投影（tools/list 预览用，inputSchema 已是解析后的 JSON 对象）。 */
export interface McpToolProjectionView {
  name: string
  description: string | null
  inputSchema: unknown
  sourceKind: McpItemKind
  sourceRef: string
  workflowVersionId?: number
  riskLevel: McpRiskLevel | string | null
}

/** 解析预览行：条目能否解析为工具投影，失败时带 problem。 */
export interface McpResolvePreviewRow {
  itemId: number
  sourceKind: McpItemKind
  sourceRef: string
  enabled: boolean
  resolvable: boolean
  tool?: McpToolProjectionView
  problem?: string
}

/** 发布前检查报告。 */
export interface McpPrecheckReport {
  ok: boolean
  toolCount: number
  irreversibleToolCount: number
  riskSummary: McpRiskSummary
  tools: McpToolProjectionView[]
}

/** 双向调用流水条目（列表返回已脱敏，正文仅详情接口返回）。 */
export interface McpCallLogEntry {
  id: number | null
  direction: McpCallDirection
  publicationId: number | null
  clientId: number | null
  clientName: string | null
  method: string | null
  toolName: string | null
  projectId: number | null
  projectCode: string | null
  environment: string | null
  tenantId: string | null
  success: boolean | null
  latencyMs: number | null
  errorCategory: string | null
  requestBody: string | null
  responseBody: string | null
  errorMessage: string | null
  traceId: string | null
  runId: string | null
  remoteIp: string | null
  createdAt: string | null
}

/** 简单分页结构（publications 列表）。 */
export interface McpItemPageView<T> {
  items: T[]
  total: number
}

/** 调用流水分页结构。 */
export interface McpCallLogPageView {
  items: McpCallLogEntry[]
  total: number
  limit: number
  offset: number
}

/** 凭证创建 / 轮换结果：明文 API Key 仅此一次返回。 */
export interface McpClientCredentialIssue {
  client: McpClientView
  plaintextApiKey: string
  note: string
}

/** 创建 / 更新发布请求体。 */
export interface McpPublicationUpsertRequest {
  name: string
  description?: string | null
}

/** 添加发布条目请求体。 */
export interface McpPublicationItemAddRequest {
  sourceKind: McpItemKind
  sourceRef: string
  alias?: string | null
  descriptionOverride?: string | null
  riskLevelOverride?: Exclude<McpRiskLevel, 'UNKNOWN'> | null
}

/** 创建凭证请求体。 */
export interface McpClientCreateRequest {
  name: string
  projectId?: number | null
  projectCode: string
  environment: string
  tenantId: string
  roles: string[]
  toolScope: string[]
  expiresAt?: string | null
}

/** 更新凭证请求体。 */
export interface McpClientUpdateRequest {
  roles: string[]
  toolScope: string[]
  enabled: boolean
  expiresAt: string | null
}
