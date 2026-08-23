import type { Component } from 'vue'
import {
  Aim,
  Coin,
  Collection,
  Compass,
  Connection,
  Cpu,
  DataAnalysis,
  SetUp,
  Share,
  User,
} from '@element-plus/icons-vue'

export interface SidebarLeaf {
  index: string
  label: string
}

export type SidebarEntry =
  | {
      kind: 'item'
      index: string
      label: string
      icon: Component
      children?: SidebarLeaf[]
    }
  /** 分组小标题（Glass Workbench：工作台 / 资产与编排 / 平台治理 / 未完成）。 */
  | { kind: 'group'; label: string }

/**
 * 全局主菜单结构。视觉由 AppSidebar 统一控制，这里只描述导航语义。
 * 分组标题对齐 Glass Workbench v2 设计：工作台 / 资产与编排 / 平台治理 / 未完成。
 */
export const sidebarMenu: SidebarEntry[] = [
  { kind: 'group', label: '工作台' },
  { kind: 'item', index: '/dashboard', label: '概览', icon: DataAnalysis },
  { kind: 'item', index: '/registry/projects', label: '项目管理', icon: Connection },
  { kind: 'group', label: '资产与编排' },
  {
    kind: 'item',
    index: '/agent-workflow-group',
    label: '智能体与编排',
    icon: Cpu,
    children: [
      { index: '/agent', label: 'Agent 管理' },
      { index: '/workflows', label: 'Workflow 编排' },
    ],
  },
  {
    kind: 'item',
    index: '/tool-group',
    label: '工具',
    icon: SetUp,
    children: [
      { index: '/tool', label: '工具列表' },
      { index: '/tool/retrieval', label: '工具检索测试' },
      { index: '/settings/tool-acl', label: '工具权限' },
    ],
  },
  {
    kind: 'item',
    index: '/knowledge-group',
    label: '知识检索',
    icon: Collection,
    children: [
      { index: '/knowledge', label: '知识库管理' },
      { index: '/knowledge/import', label: '文件入库' },
      { index: '/retrieval', label: '检索测试' },
      { index: '/biz-index', label: '业务索引' },
    ],
  },
  {
    kind: 'item',
    index: '/model-group',
    label: '模型管理',
    icon: Coin,
    children: [
      { index: '/model/instances', label: '模型中心' },
      { index: '/model/playground', label: '模型调试台' },
    ],
  },
  { kind: 'group', label: '平台治理' },
  {
    kind: 'item',
    index: '/open-group',
    label: '对外开放',
    icon: Share,
    children: [
      { index: '/mcp/visibility', label: '暴露白名单' },
      { index: '/mcp/clients', label: '客户端凭证' },
      { index: '/mcp/monitor', label: '调用流水' },
      { index: '/mcp/onboarding', label: '接入向导' },
      { index: '/a2a/endpoints', label: '智能体暴露' },
      { index: '/a2a/monitor', label: '会话监控' },
    ],
  },
  {
    kind: 'item',
    index: '/user-mgmt-group',
    label: '用户管理',
    icon: User,
    children: [
      { index: '/settings/personal-memory', label: '我的记忆' },
      { index: '/settings/memory-erasure', label: '跨域记忆擦除' },
      { index: '/settings/platform-users', label: '平台用户' },
      { index: '/settings/business-users', label: '业务用户' },
      { index: '/settings/auth-providers', label: '认证源' },
    ],
  },
  {
    kind: 'item',
    index: '/domain-group',
    label: '治理运维',
    icon: Compass,
    children: [
      { index: '/runops', label: '运行中心' },
    ],
  },
  { kind: 'group', label: '未完成' },
  {
    kind: 'item',
    index: '/plugins',
    label: '插件',
    icon: SetUp,
    children: [
      { index: '/context/governance', label: '上下文治理' },
      { index: '/domain', label: '领域定义' },
      { index: '/domain/board', label: '归属画布' },
      { index: '/domain/classifier-test', label: '分类器测试' },
    ],
  },
  {
    kind: 'item',
    index: '/capability-group',
    label: '能力内核',
    icon: Aim,
    children: [
      { index: '/capability', label: '能力模块' },
      { index: '/capability/review', label: '能力变更评审' },
      { index: '/capability/sync-snapshot', label: '同步能力快照' },
      { index: '/capability/compositions', label: '组合' },
      { index: '/capability/tools', label: '工具' },
      { index: '/capability/interactions', label: '交互' },
    ],
  },
]

/**
 * 由当前路由解析菜单选中项。
 * 优先使用路由的 meta.activeMenu；未配置的路由回落到路径前缀匹配
 * （与旧 MainLayout 的 activeMenu 逻辑一致，随页面迁移逐步补 meta 后可删减）。
 */
export function resolveActiveMenu(path: string, metaActiveMenu?: unknown): string {
  if (typeof metaActiveMenu === 'string' && metaActiveMenu) return metaActiveMenu
  if (path.startsWith('/knowledge')) return '/knowledge'
  if (path.startsWith('/biz-index')) return '/biz-index'
  if (path.startsWith('/workflows')) return '/workflows'
  if (path.startsWith('/agents')) return '/agent'
  if (path.startsWith('/agent')) return '/agent'
  if (path.startsWith('/model/playground')) return '/model/playground'
  if (path.startsWith('/model/instances')) return '/model/instances'
  if (path.startsWith('/model')) return '/model'
  if (path.startsWith('/tool/retrieval')) return '/tool/retrieval'
  if (path.startsWith('/settings/platform-users')) return '/settings/platform-users'
  if (path.startsWith('/settings/personal-memory')) return '/settings/personal-memory'
  if (path.startsWith('/settings/memory-erasure')) return '/settings/memory-erasure'
  if (path.startsWith('/settings/business-users')) return '/settings/business-users'
  if (path.startsWith('/settings/auth-providers')) return '/settings/auth-providers'
  if (path.startsWith('/settings/tool-acl')) return '/settings/tool-acl'
  if (path.startsWith('/capability/review')) return '/capability/review'
  if (path.startsWith('/capability/sync-snapshot')) return '/capability/sync-snapshot'
  if (path.startsWith('/capability/tools')) return '/capability/tools'
  if (path.startsWith('/capability/compositions')) return '/capability/compositions'
  if (path.startsWith('/capability/interactions')) return '/capability/interactions'
  if (path.startsWith('/capability')) return '/capability'
  if (path.startsWith('/tool')) return '/tool'
  if (path.startsWith('/registry/capability-sync')) return '/capability/review'
  if (path.startsWith('/registry/projects')) return '/registry/projects'
  if (path.startsWith('/scan-project')) return '/registry/projects'
  if (path.startsWith('/runops')) return '/runops'
  if (path.startsWith('/context/governance')) return '/context/governance'
  if (path.startsWith('/domain/board')) return '/domain/board'
  if (path.startsWith('/domain/classifier-test')) return '/domain/classifier-test'
  if (path.startsWith('/domain')) return '/domain'
  if (path.startsWith('/mcp/visibility')) return '/mcp/visibility'
  if (path.startsWith('/mcp/clients')) return '/mcp/clients'
  if (path.startsWith('/mcp/monitor')) return '/mcp/monitor'
  if (path.startsWith('/mcp/onboarding')) return '/mcp/onboarding'
  if (path.startsWith('/a2a/endpoints')) return '/a2a/endpoints'
  if (path.startsWith('/a2a/monitor')) return '/a2a/monitor'
  return path
}

/** 进入子路由时需要自动展开的菜单分组。 */
export function resolveOpenGroups(path: string): string[] {
  const open: string[] = []
  if (path.startsWith('/agent') || path.startsWith('/agents') || path.startsWith('/workflows')) {
    open.push('/agent-workflow-group')
  }
  if (
    path.startsWith('/settings/personal-memory')
    || path.startsWith('/settings/memory-erasure')
    || path.startsWith('/settings/platform-users')
    || path.startsWith('/settings/business-users')
    || path.startsWith('/settings/auth-providers')
  ) {
    open.push('/user-mgmt-group')
  }
  if (path.startsWith('/domain') || path.startsWith('/runops') || path.startsWith('/context')) {
    open.push('/domain-group')
  }
  if (path.startsWith('/capability') || path.startsWith('/registry/capability-sync')) {
    open.push('/capability-group')
  }
  return open
}
