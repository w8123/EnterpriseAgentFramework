import type { PlatformUserRoleGrant, PlatformUserRoleGrantCommand } from '@/api/platformAuth'
import type { ScanProject } from '@/types/scanProject'

/** 弹窗内编辑行：项目作用域用 projectIds 多选，保存时展开为多条 API 记录 */
export interface PlatformGrantEditorRow {
  roleId: number
  scopeType: 'GLOBAL' | 'PROJECT'
  projectIds: number[]
}

export function resolveScopeValueToProjectId(
  scopeValue: string | undefined | null,
  projects: ScanProject[],
): number | null {
  const normalized = scopeValue?.trim()
  if (!normalized || normalized === '*') return null
  const byCode = projects.find((p) => p.projectCode === normalized)
  if (byCode) return byCode.id
  const id = Number(normalized)
  if (Number.isFinite(id) && projects.some((p) => p.id === id)) {
    return id
  }
  return null
}

export function projectScopeValue(project: ScanProject): string {
  return project.projectCode?.trim() || String(project.id)
}

export function formatProjectScopeLabel(
  scopeValue: string | undefined | null,
  projects: ScanProject[],
): string {
  const normalized = scopeValue?.trim()
  if (!normalized || normalized === '*') return '全部'
  const projectId = resolveScopeValueToProjectId(normalized, projects)
  if (projectId != null) {
    const project = projects.find((p) => p.id === projectId)
    if (project) return project.name || project.projectCode || normalized
  }
  return normalized
}

/** 将 API 返回的多条项目授权合并为编辑行 */
export function grantsToEditorRows(
  grants: PlatformUserRoleGrant[],
  projects: ScanProject[],
): PlatformGrantEditorRow[] {
  const rows: PlatformGrantEditorRow[] = []
  const projectByRole = new Map<number, number[]>()

  for (const grant of grants) {
    const scopeType = (grant.scopeType || 'GLOBAL').toUpperCase()
    if (scopeType === 'PROJECT') {
      const projectId = resolveScopeValueToProjectId(grant.scopeValue, projects)
      const bucket = projectByRole.get(grant.roleId) ?? []
      if (projectId != null && !bucket.includes(projectId)) {
        bucket.push(projectId)
      }
      projectByRole.set(grant.roleId, bucket)
      continue
    }
    rows.push({
      roleId: grant.roleId,
      scopeType: 'GLOBAL',
      projectIds: [],
    })
  }

  for (const [roleId, projectIds] of projectByRole) {
    rows.push({
      roleId,
      scopeType: 'PROJECT',
      projectIds,
    })
  }

  return rows
}

/** 编辑行展开为保存请求体 */
export function editorRowsToCommands(
  rows: PlatformGrantEditorRow[],
  projects: ScanProject[],
): PlatformUserRoleGrantCommand[] {
  const commands: PlatformUserRoleGrantCommand[] = []
  for (const row of rows) {
    if (!row.roleId) continue
    const scopeType = row.scopeType.toUpperCase()
    if (scopeType === 'PROJECT') {
      for (const projectId of row.projectIds) {
        const project = projects.find((p) => p.id === projectId)
        if (!project) continue
        commands.push({
          roleId: row.roleId,
          scopeType: 'PROJECT',
          scopeValue: projectScopeValue(project),
        })
      }
      continue
    }
    commands.push({
      roleId: row.roleId,
      scopeType: 'GLOBAL',
      scopeValue: '*',
    })
  }
  return commands
}

/** 表格标签：同一角色 + 项目作用域合并展示 */
export function groupGrantsForDisplay(
  grants: PlatformUserRoleGrant[],
  projects: ScanProject[],
): Array<{
  roleId: number
  roleCode?: string
  roleName?: string
  scopeType: string
  scopeSummary: string
}> {
  const grouped = new Map<string, {
    roleId: number
    roleCode?: string
    roleName?: string
    scopeType: string
    projectLabels: string[]
  }>()

  for (const grant of grants) {
    const scopeType = (grant.scopeType || 'GLOBAL').toUpperCase()
    const key = `${grant.roleId}|${scopeType}`
    const entry = grouped.get(key) ?? {
      roleId: grant.roleId,
      roleCode: grant.roleCode,
      roleName: grant.roleName,
      scopeType,
      projectLabels: [],
    }
    if (scopeType === 'PROJECT') {
      const label = formatProjectScopeLabel(grant.scopeValue, projects)
      if (!entry.projectLabels.includes(label)) {
        entry.projectLabels.push(label)
      }
    }
    grouped.set(key, entry)
  }

  return Array.from(grouped.values()).map((entry) => ({
    roleId: entry.roleId,
    roleCode: entry.roleCode,
    roleName: entry.roleName,
    scopeType: entry.scopeType,
    scopeSummary:
      entry.scopeType === 'PROJECT'
        ? entry.projectLabels.join('、') || '未选项目'
        : '全部',
  }))
}
