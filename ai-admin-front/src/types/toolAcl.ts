/**
 * 调用权限前端契约。
 * 项目范围与决策值由当前 Control 接口和决策服务定义。
 */

export type ToolAclTargetKind = 'TOOL' | 'ALL'
export type ToolAclPermission = 'ALLOW' | 'DENY'

export interface ToolAclProjectScope {
  projectId?: number | null
  projectCode?: string | null
}

export interface ToolAclRule extends ToolAclProjectScope {
  id: number
  roleCode: string
  targetKind: ToolAclTargetKind
  targetName: string
  permission: ToolAclPermission
  note?: string
  enabled?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface ToolAclTargetRef {
  kind: ToolAclTargetKind
  name: string
}

export interface ToolAclBatchGrantRequest extends ToolAclProjectScope {
  roleCode: string
  permission: ToolAclPermission
  targets: ToolAclTargetRef[]
  note?: string
}

export interface ToolAclExplainRequest extends ToolAclProjectScope {
  roles: string[]
  targets: ToolAclTargetRef[]
}

/** 与后端 ToolAclDecision 枚举同步；DENY_* 均为拒绝。 */
export type ToolAclDecision =
  | 'ALLOW'
  | 'DENY_EXPLICIT'
  | 'DENY_NO_MATCH'
  | 'SKIPPED'
