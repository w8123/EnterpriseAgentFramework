import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { filterSidebarMenu, sidebarMenu, type SidebarEntry } from '@/components/common/sidebarMenu'
import {
  availableSidebarWorkspaces,
  defaultSidebarWorkspace,
  findSidebarWorkspace,
  resolveWorkspaceForMenu,
  sidebarWorkspaces,
} from '@/components/common/sidebarWorkspaces'

/** 从初始化 SQL 读取内置角色的权限，保证种子与侧栏一致。 */
function seededRolePermissions(): Record<string, string[]> {
  const sql = readFileSync(resolve(__dirname, '../../../sql/initV2.sql'), 'utf-8')
  const result: Record<string, string[]> = {}
  const pattern = /WHERE r\.role_code = '([A-Z_]+)'\s+AND p\.permission_code IN \(([^)]*)\);/g
  for (const match of sql.matchAll(pattern)) {
    result[match[1]] = [...match[2].matchAll(/'([^']+)'/g)].map((code) => code[1])
  }
  result.PLATFORM_ADMIN = ['*']
  return result
}

function visibleLabels(entries: SidebarEntry[]): string[] {
  return entries.flatMap((entry) => {
    if (entry.kind === 'group') return []
    return entry.children ? entry.children.map((child) => child.label) : [entry.label]
  })
}

const roles = seededRolePermissions()

describe('built-in role navigation', () => {
  it('seeds exactly the three console roles', () => {
    expect(Object.keys(roles).sort()).toEqual(['DEVELOPER', 'MANAGER', 'PLATFORM_ADMIN'])
  })

  it('lets developers build agents end to end but not govern employees or the platform', () => {
    const labels = visibleLabels(filterSidebarMenu(sidebarMenu, roles.DEVELOPER))
    expect(labels).toEqual(expect.arrayContaining([
      '项目管理', '业务能力', 'API 市场', 'Workflow', 'Agent', 'Skill', '知识库', 'MCP 开放', 'A2A 互联中心',
    ]))
    for (const hidden of ['账号与权限', '调用权限', '业务用户']) {
      expect(labels).not.toContain(hidden)
    }
    for (const code of ['agent:write', 'agent:publish', 'workflow:write', 'workflow:publish', 'skill:import', 'knowledge:write', 'mcp-hub:publication:manage', 'a2a-hub:publication:manage']) {
      expect(roles.DEVELOPER).toContain(code)
    }
    expect(roles.DEVELOPER).not.toContain('mcp-hub:write-approval:manage')
    expect(roles.DEVELOPER).not.toContain('tool-acl:manage')
  })

  it('gives managers the former operator and manager work but no platform accounts', () => {
    const labels = visibleLabels(filterSidebarMenu(sidebarMenu, roles.MANAGER))
    expect(labels).toEqual(expect.arrayContaining([
      'AI 使用情况', '运行中心', '自动化中心', 'MCP 开放', 'Workflow', 'Agent', 'Skill', '知识库', '业务用户', '调用权限',
    ]))
    for (const hidden of ['账号与权限', '认证源', '跨域数据擦除']) {
      expect(labels).not.toContain(hidden)
    }
    for (const code of ['mcp-hub:employee-connection:manage', 'mcp-hub:write-approval:manage', 'agent:publish', 'tool-acl:manage', 'identity:business-user:manage']) {
      expect(roles.MANAGER).toContain(code)
    }
  })

  it('leaves accounts without roles only the overview entry', () => {
    expect(visibleLabels(filterSidebarMenu(sidebarMenu, []))).toEqual(['概览'])
  })
})

describe('role workspaces', () => {
  function workspaceLabels(key: string, permissions: string[]): string[] {
    return visibleLabels(filterSidebarMenu(findSidebarWorkspace(key)!.menu, permissions))
  }

  it('gives each built-in role its own workspace and platform admins all of them', () => {
    expect(availableSidebarWorkspaces(['DEVELOPER'], roles.DEVELOPER).map(w => w.key)).toEqual(['developer', 'my'])
    expect(availableSidebarWorkspaces(['DEVELOPER', 'MANAGER'], roles.MANAGER).map(w => w.key)).toEqual(['developer', 'manager', 'my'])
    expect(availableSidebarWorkspaces(['PLATFORM_ADMIN'], ['*']).map(w => w.key)).toEqual(['developer', 'manager', 'admin', 'my'])
    expect(availableSidebarWorkspaces(['CUSTOM_ROLE'], ['knowledge:read']).map(w => w.key)).toEqual(['all', 'my'])
    expect(availableSidebarWorkspaces([], []).map(w => w.key)).toEqual(['my'])
    expect(defaultSidebarWorkspace(['PLATFORM_ADMIN'], ['*']).key).toBe('admin')
    expect(defaultSidebarWorkspace(['MANAGER', 'DEVELOPER'], roles.MANAGER).key).toBe('developer')
    expect(defaultSidebarWorkspace([], []).key).toBe('my')
  })

  it('shows each role only its own work and platform admins the full menu', () => {
    expect(workspaceLabels('developer', roles.DEVELOPER)).toEqual([
      '概览', 'AI 工具接入', '项目管理', '业务能力', 'Agent', 'Workflow', 'Skill', '知识库', 'API 市场', 'MCP 开放', 'A2A 互联中心', '模型中心',
    ])
    expect(workspaceLabels('manager', roles.MANAGER)).toEqual([
      '概览', 'AI 工具接入', 'AI 使用情况', '员工连接与审批', '运行中心', '自动化中心', 'Agent', 'Workflow', '知识库', '业务用户', '调用权限',
    ])
    const fullLabels = visibleLabels(filterSidebarMenu(sidebarMenu, ['*']))
    fullLabels.splice(fullLabels.indexOf('概览') + 1, 0, 'AI 工具接入')
    expect(workspaceLabels('admin', roles.PLATFORM_ADMIN)).toEqual(fullLabels)
  })

  it('keeps every workspace entry reachable from the full menu', () => {
    const fullMenu = new Set(findSidebarWorkspace('admin')!.menu.flatMap(entry => (
      entry.kind === 'item' ? [entry.index, ...(entry.children ?? []).map(child => child.index)] : []
    )))
    for (const workspace of sidebarWorkspaces) {
      for (const entry of workspace.menu) {
        if (entry.kind === 'item') expect(fullMenu.has(entry.index)).toBe(true)
      }
    }
  })

  it('moves the sidebar to the workspace that owns a page opened from a link', () => {
    const all = availableSidebarWorkspaces(['PLATFORM_ADMIN'])
    const admin = findSidebarWorkspace('admin')
    const developer = findSidebarWorkspace('developer')
    expect(resolveWorkspaceForMenu('/agent', admin, all, ['*'])?.key).toBe('admin')
    expect(resolveWorkspaceForMenu('/usage', developer, all, ['*'])?.key).toBe('manager')
    expect(resolveWorkspaceForMenu('/settings/platform-users', developer, all, ['*'])?.key).toBe('admin')
    expect(resolveWorkspaceForMenu('/model/instances', developer, all, ['*'])?.key).toBe('developer')
    expect(resolveWorkspaceForMenu('/me/memories', developer, all, ['*'])?.key).toBe('my')
    expect(resolveWorkspaceForMenu('/dashboard', findSidebarWorkspace('my'), all, ['*'])?.key).toBe('developer')
    const both = availableSidebarWorkspaces(['DEVELOPER', 'MANAGER'])
    expect(resolveWorkspaceForMenu('/usage', both[0], both, [...roles.DEVELOPER, ...roles.MANAGER])?.key).toBe('manager')
  })
})
