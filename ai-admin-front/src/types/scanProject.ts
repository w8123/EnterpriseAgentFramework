import type { ToolInfo } from '@/types/tool'

export type ScanType = 'openapi' | 'controller' | 'auto'
export type ScanStatus = 'created' | 'scanning' | 'scanned' | 'failed'

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
}

export interface ScanProjectUpsertRequest {
  name: string
  baseUrl: string
  contextPath: string
  scanPath: string
  scanType: ScanType
  specFile?: string | null
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
