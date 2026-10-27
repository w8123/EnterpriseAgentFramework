import type { Component } from 'vue'
import {
  hasAllPlatformPermissions,
  hasPlatformPermission,
  PLATFORM_PERMISSION_ADMIN,
  PLATFORM_PERMISSION_AGENT_READ,
  PLATFORM_PERMISSION_AUTOMATION_READ,
  PLATFORM_PERMISSION_A2A_HUB_READ,
  PLATFORM_PERMISSION_BUSINESS_USER_READ,
  PLATFORM_PERMISSION_KNOWLEDGE_READ,
  PLATFORM_PERMISSION_MCP_HUB_READ,
  PLATFORM_PERMISSION_MODEL_READ,
  PLATFORM_PERMISSION_SKILL_READ,
  PLATFORM_PERMISSION_TOOL_ACL_READ,
  PLATFORM_PERMISSION_MEMORY_ERASURE_MANAGE,
  PLATFORM_PERMISSION_RUNTIME_USER_MAPPING_MANAGE,
  PLATFORM_PERMISSION_READ,
  PLATFORM_PERMISSION_WORKFLOW_READ,
} from '@/auth/platformAccess'
import {
  Coin,
  Collection,
  Connection,
  Cpu,
  DataAnalysis,
  Grid,
  Lock,
  Share,
  Clock,
  Timer,
  User,
} from '@element-plus/icons-vue'
import { RUN_CENTER_PERMISSIONS } from '@/views/runops/runCenterTabs'

export interface SidebarLeaf {
  index: string
  label: string
  requiredPermissions?: string[]
}

export type SidebarEntry =
  | {
      kind: 'item'
      index: string
      label: string
      icon: Component
      children?: SidebarLeaf[]
      requiredPermissions?: string[]
      /** 只要具备其中一个权限就显示；用于内含多个按权限显示页签的入口。 */
      anyPermissions?: string[]
    }
  /** 面向用户任务的信息架构分组标题。 */
  | { kind: 'group'; label: string }

/**
 * 全局主菜单结构。视觉由 AppSidebar 统一控制，这里只描述导航语义。
 * 按功能领域恢复「智能化改造 / AI 资产 / 运行与治理 / 平台资源」分组。
 * 每个入口继续按权限过滤；已退役的实验室不恢复，知识库保留收拢后的入口。
 */
export const sidebarMenu: SidebarEntry[] = [
  { kind: 'group', label: '智能化改造' },
  { kind: 'item', index: '/dashboard', label: '概览', icon: DataAnalysis },
  {
    kind: 'item',
    index: '/registry/projects',
    label: '项目管理',
    icon: Connection,
    requiredPermissions: [PLATFORM_PERMISSION_READ],
  },
  { kind: 'group', label: 'AI 资产' },
  {
    kind: 'item',
    index: '/business-capabilities',
    label: '业务能力',
    icon: Grid,
    requiredPermissions: [PLATFORM_PERMISSION_READ],
  },
  {
    kind: 'item',
    index: '/workflows',
    label: 'Workflow',
    icon: Share,
    requiredPermissions: [PLATFORM_PERMISSION_WORKFLOW_READ],
  },
  {
    kind: 'item',
    index: '/agent',
    label: 'Agent',
    icon: Cpu,
    requiredPermissions: [PLATFORM_PERMISSION_AGENT_READ],
  },
  {
    kind: 'item',
    index: '/skills',
    label: 'Skill',
    icon: Collection,
    requiredPermissions: [PLATFORM_PERMISSION_SKILL_READ],
  },
  {
    kind: 'item',
    index: '/integration-group',
    label: '集成与开放',
    icon: Share,
    children: [
      { index: '/api-market', label: 'API 市场', requiredPermissions: [PLATFORM_PERMISSION_READ] },
      { index: '/mcp-hub', label: 'MCP 开放', requiredPermissions: [PLATFORM_PERMISSION_MCP_HUB_READ] },
      { index: '/a2a-hub', label: 'A2A 互联中心', requiredPermissions: [PLATFORM_PERMISSION_A2A_HUB_READ] },
    ],
  },
  {
    kind: 'item',
    index: '/knowledge',
    label: '知识库',
    icon: Collection,
    requiredPermissions: [PLATFORM_PERMISSION_KNOWLEDGE_READ],
  },
  { kind: 'group', label: '运行与治理' },
  {
    kind: 'item',
    index: '/automations',
    label: '自动化中心',
    icon: Clock,
    requiredPermissions: [PLATFORM_PERMISSION_AUTOMATION_READ],
  },
  {
    kind: 'item',
    index: '/run-center',
    label: '运行中心',
    icon: Timer,
    anyPermissions: RUN_CENTER_PERMISSIONS,
  },
  {
    kind: 'item',
    index: '/identity-group',
    label: '身份与权限',
    icon: User,
    children: [
      {
        index: '/settings/platform-users',
        label: '账号与权限',
        requiredPermissions: [PLATFORM_PERMISSION_ADMIN],
      },
      {
        index: '/settings/business-users',
        label: '业务用户',
        requiredPermissions: [PLATFORM_PERMISSION_BUSINESS_USER_READ],
      },
      {
        index: '/settings/auth-providers',
        label: '认证源',
        requiredPermissions: [PLATFORM_PERMISSION_ADMIN],
      },
      {
        index: '/settings/tool-acl',
        label: '调用权限',
        requiredPermissions: [PLATFORM_PERMISSION_TOOL_ACL_READ],
      },
      {
        index: '/settings/runtime-user-mappings',
        label: '运行时用户映射',
        requiredPermissions: [PLATFORM_PERMISSION_RUNTIME_USER_MAPPING_MANAGE],
      },
    ],
  },
  {
    kind: 'item',
    index: '/compliance-group',
    label: '合规',
    icon: Lock,
    children: [
      {
        index: '/compliance/erasure',
        label: '跨域数据擦除',
        requiredPermissions: [PLATFORM_PERMISSION_MEMORY_ERASURE_MANAGE],
      },
    ],
  },
  { kind: 'group', label: '平台资源' },
  {
    kind: 'item',
    index: '/model-group',
    label: '模型服务',
    icon: Coin,
    children: [
      { index: '/model/instances', label: '模型中心', requiredPermissions: [PLATFORM_PERMISSION_MODEL_READ] },
      { index: '/model/playground', label: '模型调试台', requiredPermissions: [PLATFORM_PERMISSION_MODEL_READ] },
    ],
  },
]

/**
 * Removes unauthorized entries before rendering the navigation. This is a UX
 * projection only; the matching route guard and Control endpoint checks remain
 * the security boundary.
 */
export function filterSidebarMenu(
  entries: SidebarEntry[],
  grantedPermissions: readonly string[],
): SidebarEntry[] {
  const visibleEntries = entries.map((entry): SidebarEntry | null => {
    if (entry.kind === 'group') return entry
    if (!hasAllPlatformPermissions(grantedPermissions, entry.requiredPermissions ?? [])) {
      return null
    }
    if (entry.anyPermissions && !entry.anyPermissions.some((permission) => (
      hasPlatformPermission(grantedPermissions, permission)
    ))) {
      return null
    }
    if (!entry.children) return entry
    const children = entry.children.filter((child) => (
      hasAllPlatformPermissions(grantedPermissions, child.requiredPermissions ?? [])
    ))
    return children.length > 0 ? { ...entry, children } : null
  })

  const result: SidebarEntry[] = []
  visibleEntries.forEach((entry, index) => {
    if (!entry) return
    if (entry.kind !== 'group') {
      result.push(entry)
      return
    }
    const nextGroupIndex = visibleEntries.findIndex((candidate, candidateIndex) => (
      candidateIndex > index && candidate?.kind === 'group'
    ))
    const sectionEnd = nextGroupIndex < 0 ? visibleEntries.length : nextGroupIndex
    const sectionHasVisibleItem = visibleEntries
      .slice(index + 1, sectionEnd)
      .some((candidate) => candidate?.kind === 'item')
    if (sectionHasVisibleItem) result.push(entry)
  })
  return result
}

/**
 * 由当前路由解析菜单选中项。
 * 优先使用路由的 meta.activeMenu；未配置的路由回落到路径前缀匹配
 * （与旧 MainLayout 的 activeMenu 逻辑一致，随页面迁移逐步补 meta 后可删减）。
 */
export function resolveActiveMenu(path: string, metaActiveMenu?: unknown): string {
  if (typeof metaActiveMenu === 'string' && metaActiveMenu) return metaActiveMenu
  if (path.startsWith('/knowledge')) return '/knowledge'
  // 业务索引按项目由 SDK 同步，从「项目管理 → 项目详情」进入。
  if (path.startsWith('/biz-index')) return '/registry/projects'
  if (path.startsWith('/api-market')) return '/api-market'
  if (path.startsWith('/skill-market')) return '/skills'
  if (path.startsWith('/skills')) return '/skills'
  if (path.startsWith('/workflows')) return '/workflows'
  if (path.startsWith('/agents')) return '/agent'
  if (path.startsWith('/agent')) return '/agent'
  if (path.startsWith('/model/playground')) return '/model/playground'
  if (path.startsWith('/model/instances')) return '/model/instances'
  if (path.startsWith('/model')) return '/model/instances'
  if (path.startsWith('/business-capabilities') || path.startsWith('/business-methods') || path.startsWith('/apis')) return '/business-capabilities'
  if (path.startsWith('/settings/platform-users')) return '/settings/platform-users'
  if (path.startsWith('/me/memories')) return '/me/memories'
  if (path.startsWith('/compliance/erasure')) return '/compliance/erasure'
  if (path.startsWith('/settings/runtime-user-mappings')) return '/settings/runtime-user-mappings'
  if (path.startsWith('/settings/business-users')) return '/settings/business-users'
  if (path.startsWith('/settings/auth-providers')) return '/settings/auth-providers'
  if (path.startsWith('/settings/tool-acl')) return '/settings/tool-acl'
  if (path.startsWith('/registry/projects')) return '/registry/projects'
  if (path.startsWith('/automations') || path.startsWith('/runops/automations')) return '/automations'
  if (path.startsWith('/runops') || path.startsWith('/run-center')) return '/run-center'
  if (path.startsWith('/mcp-hub')) return '/mcp-hub'
  if (path.startsWith('/a2a-hub')) return '/a2a-hub'
  return path
}

/** 进入子路由时需要自动展开的菜单分组。 */
export function resolveOpenGroups(path: string): string[] {
  const active = resolveActiveMenu(path)
  const open: string[] = []

  if (active === '/api-market' || active === '/mcp-hub' || active === '/a2a-hub') {
    open.push('/integration-group')
  }

  if (
    active === '/settings/platform-users'
    || active === '/settings/business-users'
    || active === '/settings/tool-acl'
    || active === '/settings/runtime-user-mappings'
    || active === '/settings/auth-providers'
  ) {
    open.push('/identity-group')
  }

  if (active === '/compliance/erasure') {
    open.push('/compliance-group')
  }

  if (active === '/model/instances' || active === '/model/playground') {
    open.push('/model-group')
  }

  return open
}
