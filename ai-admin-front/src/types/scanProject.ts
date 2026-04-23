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
  projectId?: number | null
}
