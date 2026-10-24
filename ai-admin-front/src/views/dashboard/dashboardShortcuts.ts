import type { Component } from 'vue'
import { filterSidebarMenu, sidebarMenu } from '@/components/common/sidebarMenu'
import type { DashboardWidgetConfig } from '@/types/operationsDashboard'

export const SHORTCUT_WIDGET_KEY = 'navigation.shortcuts'
export type ShortcutPresetKey = NonNullable<DashboardWidgetConfig['shortcutPreset']>

export interface DashboardShortcut {
  id: string
  label: string
  detail: string
  to: string
  /** 决定入口是否可见的主导航地址；子页面入口与所属菜单相同。 */
  menu: string
  icon: Component
}

// 地址与图标复用主导航；此处只定义入口的稳定 ID、短名称和用途。
const shortcutSources = [
  ['projects', '/registry/projects', '项目接入', '注册业务系统，查看 SDK 接入与同步状态'],
  ['capabilities', '/business-capabilities', '业务能力', '管理 Java 业务方法与 HTTP API'],
  ['api-market', '/api-market', 'API 市场', '发现和接入外部 API'],
  ['workflows', '/workflows', 'Workflow', '编排、调试和发布业务流程'],
  ['agents', '/agent', 'Agent', '配置和发布智能体'],
  ['knowledge', '/knowledge', '知识库', '管理知识文件与业务索引'],
  ['run-center', '/run-center', '运行中心', '查看对话、运行记录与评测'],
  ['business-users', '/settings/business-users', '业务用户', '查看使用 Agent 的业务用户'],
  ['tool-acl', '/settings/tool-acl', '调用权限', '配置业务用户可调用的能力'],
  ['platform-users', '/settings/platform-users', '账号与角色', '管理平台账号、角色与项目授权'],
  ['auth-providers', '/settings/auth-providers', '认证源', '配置平台认证方式'],
  ['model-center', '/model/instances', '模型中心', '管理可用模型与服务商'],
  ['model-playground', '/model/playground', '模型调试台', '调试模型调用'],
  ['compliance', '/compliance/erasure', '合规管理', '管理跨域数据擦除'],
  ['skills', '/skills', 'Skill 包', '管理标准 Agent Skill 包'],
  ['mcp', '/mcp-hub', 'MCP 开放', '管理 Agent 的 MCP 开放入口'],
  ['a2a', '/a2a-hub', 'A2A 开放', '管理 Agent 的 A2A 开放入口'],
  ['employee-connections', '/mcp-hub/employee-connections', '员工连接', '谁在用自己的 AI 工具连接业务系统', '/mcp-hub'],
  ['mcp-call-logs', '/mcp-hub/call-logs', 'AI 工具调用记录', '谁、用什么 AI 工具、调用了什么', '/mcp-hub'],
] as const

/** 子页面入口按所属菜单判断可见性和图标。 */
function menuOf(source: readonly string[]): string {
  return source[4] ?? source[1]
}

const menuIcons = new Map<string, Component>()
for (const entry of sidebarMenu) {
  if (entry.kind !== 'item') continue
  menuIcons.set(entry.index, entry.icon)
  for (const child of entry.children ?? []) menuIcons.set(child.index, entry.icon)
}

export const dashboardShortcuts: readonly DashboardShortcut[] = shortcutSources.map((source) => {
  const [id, to, label, detail] = source
  return { id, to, label, detail, menu: menuOf(source), icon: menuIcons.get(menuOf(source))! }
})

export const shortcutPresets = [
  { key: 'developer', title: '开发快捷入口', label: '开发常用', description: '项目接入、业务能力、API 市场', ids: ['projects', 'capabilities', 'api-market'] },
  { key: 'operator', title: '运营快捷入口', label: '运营常用', description: 'Workflow、Agent、知识库与运行中心', ids: ['workflows', 'agents', 'knowledge', 'run-center', 'tool-acl'] },
  { key: 'manager', title: '管理快捷入口', label: '管理人员常用', description: '运行中心、员工连接、AI 工具调用记录与业务用户', ids: ['run-center', 'employee-connections', 'mcp-call-logs', 'business-users'] },
  { key: 'admin', title: '平台管理快捷入口', label: '平台管理常用', description: '账号与角色、认证源、模型与合规', ids: ['platform-users', 'auth-providers', 'model-center', 'compliance'] },
] as const

/** 入口只由共享导航的权限投影决定，不把角色预设当作授权。 */
export function availableDashboardShortcuts(permissions: readonly string[]): DashboardShortcut[] {
  const paths = new Set<string>()
  for (const entry of filterSidebarMenu(sidebarMenu, permissions)) {
    if (entry.kind !== 'item') continue
    paths.add(entry.index)
    for (const child of entry.children ?? []) paths.add(child.index)
  }
  return dashboardShortcuts.filter(shortcut => paths.has(shortcut.menu))
}

export function sanitizeShortcutIds(value: unknown): string[] {
  if (!Array.isArray(value)) return []
  const registered = new Set(dashboardShortcuts.map(shortcut => shortcut.id))
  return [...new Set(value.filter((id): id is string => typeof id === 'string' && registered.has(id)))]
}

export function isShortcutPreset(value: unknown): value is ShortcutPresetKey {
  return value === 'custom' || shortcutPresets.some(preset => preset.key === value)
}

export function resolveShortcutIds(config: DashboardWidgetConfig): string[] {
  if (Array.isArray(config.shortcutIds)) return sanitizeShortcutIds(config.shortcutIds)
  return [...(shortcutPresets.find(preset => preset.key === config.shortcutPreset)?.ids ?? [])]
}

export function shortcutPresetConfig(key: ShortcutPresetKey, permissions?: readonly string[]): DashboardWidgetConfig {
  const preset = shortcutPresets.find(item => item.key === key)
  const available = permissions ? new Set(availableDashboardShortcuts(permissions).map(shortcut => shortcut.id)) : null
  return {
    shortcutPreset: key,
    shortcutIds: [...(preset?.ids ?? [])].filter(id => !available || available.has(id)),
  }
}

export function defaultShortcutConfig(permissions: readonly string[], roles: readonly string[] = []): DashboardWidgetConfig | null {
  const available = new Set(availableDashboardShortcuts(permissions).map(shortcut => shortcut.id))
  if (!available.size) return null
  const rolePresets: Record<string, ShortcutPresetKey> = { DEVELOPER: 'developer', OPERATOR: 'operator', MANAGER: 'manager', PLATFORM_ADMIN: 'admin' }
  const preferred = roles.map(role => rolePresets[role]).find(key => key && shortcutPresetConfig(key, permissions).shortcutIds?.length)
  const key = preferred ?? (available.has('platform-users') ? 'admin'
    : available.has('workflows') || available.has('agents') || available.has('run-center') ? 'operator'
    : 'developer')
  const config = shortcutPresetConfig(key, permissions)
  return config.shortcutIds?.length ? config : { shortcutPreset: 'custom', shortcutIds: [...available].slice(0, 5) }
}
