import type { Component } from 'vue'
import {
  hasAllPlatformPermissions,
  PLATFORM_PERMISSION_ADMIN,
  PLATFORM_PERMISSION_AGENT_READ,
  PLATFORM_PERMISSION_AUTOMATION_READ,
  PLATFORM_PERMISSION_BUSINESS_USER_READ,
  PLATFORM_PERMISSION_MEMORY_ERASURE_MANAGE,
  PLATFORM_PERMISSION_READ,
  PLATFORM_PERMISSION_RUNOPS_READ,
  PLATFORM_PERMISSION_WRITE,
  PLATFORM_PERMISSION_WORKFLOW_READ,
} from '@/auth/platformAccess'
import {
  Coin,
  Collection,
  Compass,
  Connection,
  Cpu,
  DataAnalysis,
  Grid,
  SetUp,
  Share,
  Timer,
  User,
} from '@element-plus/icons-vue'

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
    }
  /** 面向用户任务的信息架构分组标题。 */
  | { kind: 'group'; label: string }

/**
 * 全局主菜单结构。视觉由 AppSidebar 统一控制，这里只描述导航语义。
 * 主导航只承载高频任务与资产入口；测试、诊断和实验性能力统一收进实验室。
 */
export const sidebarMenu: SidebarEntry[] = [
  { kind: 'group', label: '智能化改造' },
  { kind: 'item', index: '/dashboard', label: '概览', icon: DataAnalysis },
  { kind: 'item', index: '/registry/projects', label: '项目管理', icon: Connection },

  { kind: 'group', label: 'AI 资产' },
  {
    kind: 'item',
    index: '/capability',
    label: '能力目录',
    icon: Grid,
    requiredPermissions: [PLATFORM_PERMISSION_READ],
  },
  {
    kind: 'item',
    index: '/business-methods',
    label: '业务方法',
    icon: Grid,
    requiredPermissions: [PLATFORM_PERMISSION_READ],
  },
  {
    kind: 'item',
    index: '/apis',
    label: 'API',
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
  { kind: 'item', index: '/skills', label: 'Skill', icon: Collection },
  {
    kind: 'item',
    index: '/integration-group',
    label: '集成与开放',
    icon: Share,
    children: [
      { index: '/api-market', label: 'API 市场' },
      { index: '/mcp-hub', label: 'MCP Hub' },
      { index: '/a2a-hub', label: 'A2A 互联中心' },
    ],
  },
  {
    kind: 'item',
    index: '/knowledge-group',
    label: '知识库',
    icon: Collection,
    children: [
      { index: '/knowledge', label: '知识库' },
      { index: '/knowledge/import', label: '文件入库' },
      { index: '/biz-index', label: '业务索引' },
    ],
  },

  { kind: 'group', label: '运行与治理' },
  {
    kind: 'item',
    index: '/automations',
    label: '自动化中心',
    icon: Timer,
    requiredPermissions: [PLATFORM_PERMISSION_AUTOMATION_READ],
  },
  {
    kind: 'item',
    index: '/runops',
    label: '运行中心',
    icon: DataAnalysis,
    requiredPermissions: [PLATFORM_PERMISSION_RUNOPS_READ],
  },
  {
    kind: 'item',
    index: '/identity-group',
    label: '身份与权限',
    icon: User,
    children: [
      { index: '/settings/personal-memory', label: '我的记忆' },
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
      { index: '/settings/tool-acl', label: '调用权限' },
      {
        index: '/settings/memory-erasure',
        label: '跨域记忆擦除',
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
      { index: '/model/instances', label: '模型中心' },
      { index: '/model/playground', label: '模型调试台' },
    ],
  },

  { kind: 'group', label: '实验室' },
  {
    kind: 'item',
    index: '/experimental-group',
    label: '实验性能力',
    icon: Compass,
    children: [
      { index: '/context/governance', label: '上下文治理' },
      { index: '/domain', label: '领域定义' },
      { index: '/domain/board', label: '归属画布' },
    ],
  },
  {
    kind: 'item',
    index: '/diagnostics-group',
    label: '诊断工具',
    icon: SetUp,
    children: [
      { index: '/capability/sync-snapshot', label: '同步能力快照', requiredPermissions: [PLATFORM_PERMISSION_WRITE] },
      { index: '/tool/retrieval', label: '调用候选检索' },
      { index: '/retrieval', label: '知识检索测试' },
      { index: '/domain/classifier-test', label: '分类器测试' },
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
  if (path.startsWith('/knowledge/import')) return '/knowledge/import'
  if (path.startsWith('/knowledge')) return '/knowledge'
  if (path.startsWith('/biz-index')) return '/biz-index'
  if (path.startsWith('/api-market')) return '/api-market'
  if (path.startsWith('/skill-market')) return '/skills'
  if (path.startsWith('/skills')) return '/skills'
  if (path.startsWith('/workflows')) return '/workflows'
  if (path.startsWith('/agents')) return '/agent'
  if (path.startsWith('/agent')) return '/agent'
  if (path.startsWith('/model/playground')) return '/model/playground'
  if (path.startsWith('/model/instances')) return '/model/instances'
  if (path.startsWith('/model')) return '/model/instances'
  if (path.startsWith('/tool/retrieval')) return '/tool/retrieval'
  if (path.startsWith('/business-methods')) return '/business-methods'
  if (path.startsWith('/apis')) return '/apis'
  if (path.startsWith('/settings/platform-users')) return '/settings/platform-users'
  if (path.startsWith('/settings/personal-memory')) return '/settings/personal-memory'
  if (path.startsWith('/settings/memory-erasure')) return '/settings/memory-erasure'
  if (path.startsWith('/settings/business-users')) return '/settings/business-users'
  if (path.startsWith('/settings/auth-providers')) return '/settings/auth-providers'
  if (path.startsWith('/settings/tool-acl')) return '/settings/tool-acl'
  if (path.startsWith('/capability/review')) return '/capability'
  if (path.startsWith('/capability/sync-snapshot')) return '/capability/sync-snapshot'
  if (path.startsWith('/capability/tools')) return '/capability'
  if (path.startsWith('/capability/compositions')) return '/capability'
  if (path.startsWith('/capability/interactions')) return '/capability'
  if (path.startsWith('/capability')) return '/capability'
  if (path.startsWith('/registry/capability-sync')) return '/capability'
  if (path.startsWith('/registry/projects')) return '/registry/projects'
  if (path.startsWith('/scan-project')) return '/registry/projects'
  if (path.startsWith('/automations')) return '/automations'
  if (path.startsWith('/runops')) return '/runops'
  if (path.startsWith('/context/governance')) return '/context/governance'
  if (path.startsWith('/domain/board')) return '/domain/board'
  if (path.startsWith('/domain/classifier-test')) return '/domain/classifier-test'
  if (path.startsWith('/domain')) return '/domain'
  if (path.startsWith('/mcp-hub')) return '/mcp-hub'
  if (path.startsWith('/a2a-hub')) return '/a2a-hub'
  return path
}

/** 进入子路由时需要自动展开的菜单分组。 */
export function resolveOpenGroups(path: string): string[] {
  const active = resolveActiveMenu(path)
  const open: string[] = []

  if (active === '/knowledge' || active === '/knowledge/import' || active === '/biz-index') {
    open.push('/knowledge-group')
  }

  if (
    active === '/settings/platform-users'
    || active === '/settings/personal-memory'
    || active === '/settings/memory-erasure'
    || active === '/settings/business-users'
    || active === '/settings/auth-providers'
    || active === '/settings/tool-acl'
  ) {
    open.push('/identity-group')
  }

  if (
    active === '/api-market'
    || active === '/mcp-hub'
    || active === '/a2a-hub'
  ) {
    open.push('/integration-group')
  }

  if (active === '/model/instances' || active === '/model/playground') {
    open.push('/model-group')
  }


  if (
    active === '/context/governance'
    || active === '/domain'
    || active === '/domain/board'
  ) {
    open.push('/experimental-group')
  }

  if (
    active === '/capability/sync-snapshot'
    || active === '/tool/retrieval'
    || active === '/retrieval'
    || active === '/domain/classifier-test'
  ) {
    open.push('/diagnostics-group')
  }

  return open
}
