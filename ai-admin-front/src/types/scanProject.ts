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
      'JAVADOC',
      'SWAGGER_API_OPERATION',
      'OPENAPI_OPERATION',
      'METHOD_NAME',
    ],
    paramDescriptionSourceOrder: ['JAVADOC_PARAM', 'SCHEMA_ANNO', 'PARAMETER_ANNO', 'FIELD_NAME'],
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
  lastScannedAt?: string | null
}

export interface ScanProjectUpsertRequest {
  name: string
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

export interface ScanProjectScanResult {
  projectId: number
  projectName: string
  toolCount: number
  toolNames: string[]
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
