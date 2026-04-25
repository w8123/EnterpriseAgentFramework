import type { ToolInfo } from '@/types/tool'

export type ScanType = 'openapi' | 'controller' | 'auto'
export type ScanStatus = 'created' | 'scanning' | 'scanned' | 'failed'

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
