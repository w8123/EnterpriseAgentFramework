import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { filterSidebarMenu, sidebarMenu, type SidebarEntry } from '@/components/common/sidebarMenu'
import { defaultShortcutConfig } from '@/views/dashboard/dashboardShortcuts'

/** 从初始化 SQL 读取内置角色的权限，保证种子、侧栏和概览快捷入口三处一致。 */
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
  it('seeds exactly the four console roles', () => {
    expect(Object.keys(roles).sort()).toEqual(['DEVELOPER', 'MANAGER', 'OPERATOR', 'PLATFORM_ADMIN'])
  })

  it('shows developers project onboarding and capabilities, read-only build assets, no administration', () => {
    const labels = visibleLabels(filterSidebarMenu(sidebarMenu, roles.DEVELOPER))
    expect(labels).toEqual(expect.arrayContaining(['项目管理', '业务能力', 'API 市场', 'Workflow', 'Agent']))
    expect(labels).not.toContain('账号与权限')
    expect(labels).not.toContain('调用权限')
    expect(labels).not.toContain('MCP 开放')
    expect(labels).not.toContain('Skill')
  })

  it('shows operators Workflow, Agent, knowledge, MCP and call permissions but no platform accounts', () => {
    const labels = visibleLabels(filterSidebarMenu(sidebarMenu, roles.OPERATOR))
    expect(labels).toEqual(expect.arrayContaining([
      'Workflow', 'Agent', 'Skill', 'MCP 开放', '知识库', '自动化中心', '运行中心', '业务用户', '调用权限',
    ]))
    expect(labels).not.toContain('账号与权限')
    expect(labels).not.toContain('认证源')
    expect(labels).not.toContain('跨域数据擦除')
  })

  it('shows managers usage records without projects, models or administration', () => {
    const labels = visibleLabels(filterSidebarMenu(sidebarMenu, roles.MANAGER))
    expect(labels).toEqual(expect.arrayContaining(['运行中心', 'MCP 开放', '业务用户', '调用权限', 'Agent', 'Workflow']))
    for (const hidden of ['项目管理', '业务能力', 'API 市场', '模型中心', '账号与权限', 'A2A 互联中心']) {
      expect(labels).not.toContain(hidden)
    }
    expect(roles.MANAGER).toContain('mcp-hub:employee-connection:manage')
    expect(roles.MANAGER).toContain('mcp-hub:write-approval:manage')
    expect(roles.MANAGER.some((code) => /:(write|publish|manage)$/.test(code)
      && code !== 'mcp-hub:employee-connection:manage' && code !== 'mcp-hub:write-approval:manage')).toBe(false)
  })

  it('picks the matching overview shortcuts for each role', () => {
    expect(defaultShortcutConfig(roles.DEVELOPER, ['DEVELOPER'])?.shortcutPreset).toBe('developer')
    expect(defaultShortcutConfig(roles.OPERATOR, ['OPERATOR'])?.shortcutPreset).toBe('operator')
    expect(defaultShortcutConfig(roles.MANAGER, ['MANAGER'])).toEqual({
      shortcutPreset: 'manager',
      shortcutIds: ['write-approvals', 'run-center', 'employee-connections', 'mcp-call-logs', 'business-users'],
    })
    expect(defaultShortcutConfig(roles.PLATFORM_ADMIN, ['PLATFORM_ADMIN'])?.shortcutPreset).toBe('admin')
  })

  it('leaves accounts without roles only the overview entry', () => {
    expect(visibleLabels(filterSidebarMenu(sidebarMenu, []))).toEqual(['概览'])
  })
})
