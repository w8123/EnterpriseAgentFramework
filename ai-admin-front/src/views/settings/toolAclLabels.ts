import { formatPlatformRoleLabel } from '@/utils/uiLabels'
import type { ToolAclDecision, ToolAclPermission, ToolAclRule } from '@/types/toolAcl'

/** 调用权限页的中文展示：角色、能力、决策只换显示文案，提交给后端的仍是原始编码。 */

export interface ToolAclRoleDisplay {
  label: string
  /** 与 label 不同时作为副文本显示的原始编码 */
  code?: string
}

const ROLE_WITH_SCOPE = /^([^:\s]+):(.+)$/

export function toolAclRoleDisplay(roleCode: string): ToolAclRoleDisplay {
  const builtin = formatPlatformRoleLabel(roleCode)
  if (builtin && builtin !== roleCode && builtin !== '-') return { label: builtin, code: roleCode }
  const scoped = ROLE_WITH_SCOPE.exec(roleCode)
  if (scoped && scoped[2].trim()) {
    const owner = scoped[1]
    return { label: scoped[2].trim(), code: owner.length > 12 ? `${owner.slice(0, 8)}…` : owner }
  }
  return { label: roleCode }
}

export interface ToolAclTargetDisplay {
  label: string
  /** 便于核对的短标识；完整标识放在 title 提示里 */
  hint?: string
  wildcard: boolean
}

const HTTP_API_NAME = /^http-api:([^:]+):([^:]+):(?:market:)?([0-9a-f]+)$/i

export function isToolAclWildcard(rule: Pick<ToolAclRule, 'targetName'>) {
  return rule.targetName === '*'
}

export function toolAclTargetDisplay(
  rule: Pick<ToolAclRule, 'targetKind' | 'targetName'>,
  titles: Map<string, string>,
): ToolAclTargetDisplay {
  if (isToolAclWildcard(rule)) return { label: '全部能力', wildcard: true }
  const name = rule.targetName
  const title = titles.get(name)
  const api = HTTP_API_NAME.exec(name)
  if (api) {
    const [, project, environment, hash] = api
    return { label: title || `${project} 的接口`, hint: `接口 · ${environment} · ${hash.slice(0, 8)}`, wildcard: false }
  }
  if (title && title !== name) return { label: title, hint: name, wildcard: false }
  return { label: name, wildcard: false }
}

export const TOOL_ACL_PERMISSION_LABELS: Record<ToolAclPermission, string> = {
  ALLOW: '允许',
  DENY: '拒绝',
}

export const TOOL_ACL_DECISION_LABELS: Record<ToolAclDecision, string> = {
  ALLOW: '允许',
  DENY_EXPLICIT: '拒绝（命中拒绝规则）',
  DENY_NO_MATCH: '拒绝（没有匹配的规则）',
  SKIPPED: '未判定（没有提供角色）',
}
