/**
 * Phase 3.1 Tool ACL 前端类型定义。
 *
 * 决策语义与 `tool_acl_phase3_1.sql` 和 `ToolAclService.decide` 保持一致。
 */

export type ToolAclTargetKind = 'TOOL' | 'SKILL' | 'ALL'
export type ToolAclPermission = 'ALLOW' | 'DENY'

export interface ToolAclRule {
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

export interface ToolAclBatchGrantRequest {
  roleCode: string
  permission: ToolAclPermission
  targets: ToolAclTargetRef[]
  note?: string
}

export interface ToolAclExplainRequest {
  roles: string[]
  targets: ToolAclTargetRef[]
}

/** 与后端 ToolAclDecision 枚举同步；DENY_* 均为拒绝。 */
export type ToolAclDecision =
  | 'ALLOW'
  | 'DENY_EXPLICIT'
  | 'DENY_NO_MATCH'
  | 'SKIPPED'
