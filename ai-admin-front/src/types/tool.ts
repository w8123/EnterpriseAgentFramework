import { isAssetInputParameter, type AssetParameter } from './assetParameter'

export type ToolParameter = AssetParameter
export const isToolInputParameter = isAssetInputParameter

/** 已注册 Tool 信息 */
export interface ToolInfo {
  /** 来源资产投影：业务方法、API 或尚未分类的存量定义。 */
  assetType?: 'BUSINESS_METHOD' | 'HTTP_API' | string | null
  name: string
  /** 面向用户展示的简短名称；name 仍是稳定机器标识 */
  title: string
  description: string
  parameters: ToolParameter[]
  source: 'code' | 'scanner' | 'sdk' | 'manual'
  sourceLocation?: string | null
  /** 由来源治理维护的稳定来源标识，不能由目录编辑改变。 */
  sourceQualifiedName?: string | null
  httpMethod?: string | null
  baseUrl?: string | null
  contextPath?: string | null
  endpointPath?: string | null
  requestBodyType?: string | null
  responseType?: string | null
  projectId?: number | null
  projectCode?: string | null
  qualifiedName?: string | null
  /** 扫描项目显示名，由后端根据 `projectId` 解析；无项目时多为 null */
  sourceProjectName?: string | null
  /** 从扫描项目语义/接口文档同步的「AI 理解」摘要，用于 Agent 与列表展示 */
  aiDescription?: string | null
  /** @ReachCapability 扫描得到的能力声明元数据 JSON */
  capabilityMetadataJson?: string | null
  /** 调用副作用等级：NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE */
  sideEffect?: string | null
  sourceAvailability?: string
  enabled: boolean
  /** 项目 API 目录镜像行 ID（若有） */
  catalogScanToolId?: number | null
  /** 与 scan_project_tool 解析的关联状态；无项目镜像时为 null */
  catalogLinkStatus?: string | null
  catalogLinkMessage?: string | null
}

export interface ToolUpsertRequest {
  name: string
  title: string
  description: string
  parameters: ToolParameter[]
  source: 'code' | 'scanner' | 'sdk' | 'manual'
  sourceLocation?: string | null
  httpMethod?: string | null
  baseUrl?: string | null
  contextPath?: string | null
  endpointPath?: string | null
  requestBodyType?: string | null
  responseType?: string | null
  projectId?: number | null
  projectCode?: string | null
  qualifiedName?: string | null
  enabled: boolean
}

/** Tool 测试请求 */
export interface ToolTestRequest {
  args: Record<string, unknown>
}

/** Tool 测试结果 */
export interface ToolTestResult {
  success: boolean
  result: string
  errorMessage?: string
  durationMs: number
}

/** Tool 列表查询（与 GET /api/tools 查询参数一致） */
export interface ToolListQuery {
  current?: number
  size?: number
  /** 匹配工具名称、机器标识或描述（模糊） */
  keyword?: string
  source?: 'code' | 'scanner' | 'manual' | string
  enabled?: boolean
  projectId?: number
}

/** Tool 列表分页结果 */
export interface ToolPageResult {
  records: ToolInfo[]
  total: number
  size: number
  current: number
  pages: number
}
