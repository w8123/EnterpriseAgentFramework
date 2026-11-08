import { DocumentChecked, House, Memo, Monitor } from '@element-plus/icons-vue'
import { filterSidebarMenu, sidebarMenu, type SidebarEntry } from './sidebarMenu'

export type SidebarWorkspaceKey = 'developer' | 'manager' | 'admin' | 'all' | 'my'

export interface SidebarWorkspace {
  key: SidebarWorkspaceKey
  /** 拥有该内置角色即可进入此工作台；平台管理员可进入全部工作台。空表示不按角色给出。 */
  roleCode: string
  /** 切换到这个身份后打开的页面。 */
  home: string
  label: string
  description: string
  menu: SidebarEntry[]
  /** 放在「平台资源」里的只读入口；从链接打开这些页面时优先切到把它当主业的工作台。 */
  referenceIndexes?: string[]
}

export const PLATFORM_ADMIN_ROLE = 'PLATFORM_ADMIN'
export const PLATFORM_AI_CONNECTIONS_PATH = '/me/ai-connections'
const aiConnectionsEntry: SidebarEntry = { kind: 'item', index: PLATFORM_AI_CONNECTIONS_PATH, label: 'AI 工具接入', icon: Monitor }

/** 完整菜单也提供个人 AI 连接入口，放在概览之后。 */
const fullWorkspaceMenu: SidebarEntry[] = sidebarMenu.flatMap(item =>
  item.kind === 'item' && item.index === '/dashboard' ? [item, aiConnectionsEntry] : [item],
)

/** 从完整菜单目录取入口；子菜单项提升为一级入口并沿用所属分组的图标。 */
function entry(index: string, label?: string): SidebarEntry {
  for (const candidate of sidebarMenu) {
    if (candidate.kind !== 'item') continue
    if (candidate.index === index) return label ? { ...candidate, label } : candidate
    const child = candidate.children?.find(leaf => leaf.index === index)
    if (child) {
      return {
        kind: 'item',
        index: child.index,
        label: label ?? child.label,
        icon: candidate.icon,
        requiredPermissions: [...(candidate.requiredPermissions ?? []), ...(child.requiredPermissions ?? [])],
      }
    }
  }
  throw new Error(`sidebar entry ${index} is not in sidebarMenu`)
}

function group(label: string): SidebarEntry {
  return { kind: 'group', label }
}

/**
 * 按身份拆分的工作台菜单。切换工作台只改变菜单和概览视图，不改变权限：
 * 每个入口仍按当前账号的全部权限过滤，路由守卫和后端校验保持不变。
 * 平台管理员拥有全部权限，默认看完整菜单；切到其他身份只是预览该身份的菜单。
 */
export const sidebarWorkspaces: SidebarWorkspace[] = [
  {
    key: 'developer',
    home: '/dashboard',
    roleCode: 'DEVELOPER',
    label: '开发人员',
    description: '接入业务系统、开发 Agent 和 Workflow',
    menu: [
      entry('/dashboard'),
      aiConnectionsEntry,
      group('接入与能力'),
      entry('/registry/projects'),
      entry('/business-capabilities'),
      group('智能体开发'),
      entry('/agent'),
      entry('/workflows'),
      entry('/skills'),
      entry('/knowledge'),
      group('开放与互联'),
      entry('/api-market'),
      entry('/mcp-hub'),
      entry('/a2a-hub'),
      group('平台资源'),
      entry('/model/instances'),
    ],
    referenceIndexes: ['/model/instances'],
  },
  {
    key: 'manager',
    home: '/dashboard',
    roleCode: 'MANAGER',
    label: '管理人员',
    description: '上线和运营智能体、查看员工 AI 使用情况、审批写操作',
    menu: [
      entry('/dashboard'),
      aiConnectionsEntry,
      group('使用与审批'),
      entry('/usage'),
      entry('/mcp-hub', '员工连接与审批'),
      entry('/run-center'),
      entry('/automations'),
      group('智能体'),
      entry('/agent'),
      entry('/workflows'),
      entry('/knowledge'),
      group('员工与权限'),
      entry('/settings/business-users'),
      entry('/settings/tool-acl'),
    ],
  },
  {
    key: 'admin',
    home: '/dashboard',
    roleCode: PLATFORM_ADMIN_ROLE,
    label: '平台管理员',
    description: '全部功能：安装平台、管理账号、认证和模型',
    menu: fullWorkspaceMenu,
  },
]

/** 「AI 操作审核」：审核 AI 申请的写操作（自己平台 AI 的，管理人员还有员工的）。 */
export const AI_APPROVALS_PATH = '/me/ai-approvals'

/** 人人都有的「我的」：普通用户的个人空间，也是没有管理端角色的账号的落脚点。 */
export const mySidebarWorkspace: SidebarWorkspace = {
  key: 'my',
  roleCode: '',
  home: '/me',
  label: '我的',
  description: '普通用户：在业务系统里用 AI、我的 AI 连接和记忆',
  menu: [
    { kind: 'item', index: '/me', label: '我的首页', icon: House },
    aiConnectionsEntry,
    { kind: 'item', index: AI_APPROVALS_PATH, label: 'AI 操作审核', icon: DocumentChecked },
    { kind: 'item', index: '/me/memories', label: '我的记忆', icon: Memo },
  ],
}

/** 只有自定义角色的账号：按权限看完整菜单。 */
const customRoleWorkspace: SidebarWorkspace = {
  key: 'all',
  roleCode: '',
  home: '/dashboard',
  label: '全部功能',
  description: '按你的权限显示全部菜单',
  menu: fullWorkspaceMenu,
}

export function findSidebarWorkspace(key: unknown): SidebarWorkspace | undefined {
  return [...sidebarWorkspaces, customRoleWorkspace, mySidebarWorkspace].find(workspace => workspace.key === key)
}

/**
 * 账号能进入的工作台：按内置角色给出，平台管理员可进入全部；只有自定义角色时给「全部功能」。
 * 「我的」人人都有，放在最后。
 */
export function availableSidebarWorkspaces(
  roles: readonly string[] = [],
  permissions: readonly string[] = [],
): SidebarWorkspace[] {
  const builtIn = roles.includes(PLATFORM_ADMIN_ROLE)
    ? sidebarWorkspaces
    : sidebarWorkspaces.filter(workspace => roles.includes(workspace.roleCode))
  const work = builtIn.length || permissions.length === 0 ? builtIn : [customRoleWorkspace]
  return [...work, mySidebarWorkspace]
}

/** 默认工作台：平台管理员进入完整菜单，其余按开发、管理的顺序取第一个；没有任何权限的账号进入「我的」。 */
export function defaultSidebarWorkspace(
  roles: readonly string[] = [],
  permissions: readonly string[] = [],
): SidebarWorkspace {
  if (roles.includes(PLATFORM_ADMIN_ROLE)) return findSidebarWorkspace('admin')!
  return availableSidebarWorkspaces(roles, permissions)[0]
}

export function workspaceMenuIndexes(entries: readonly SidebarEntry[]): Set<string> {
  const indexes = new Set<string>()
  for (const candidate of entries) {
    if (candidate.kind !== 'item') continue
    indexes.add(candidate.index)
    for (const child of candidate.children ?? []) indexes.add(child.index)
  }
  return indexes
}

/**
 * 页面不在当前工作台时，找到第一个能看到它的工作台。概览、个人中心等
 * 不属于某个工作台的页面返回 undefined，保持当前工作台不变。
 */
export function resolveWorkspaceForMenu(
  activeMenu: string,
  current: SidebarWorkspace | undefined,
  available: readonly SidebarWorkspace[],
  permissions: readonly string[],
): SidebarWorkspace | undefined {
  const contains = (workspace: SidebarWorkspace) => (
    workspaceMenuIndexes(filterSidebarMenu(workspace.menu, permissions)).has(activeMenu)
  )
  if (current && contains(current)) return current
  const candidates = available.filter(contains)
  return candidates.find(workspace => !workspace.referenceIndexes?.includes(activeMenu)) ?? candidates[0]
}
