import {
  hasPlatformPermission,
  PLATFORM_PERMISSION_AGENT_EVALUATE,
  PLATFORM_PERMISSION_RUNOPS_READ,
} from '@/auth/platformAccess'

export type RunCenterTabKey = 'conversations' | 'runs' | 'evals'

export interface RunCenterTab {
  key: RunCenterTabKey
  label: string
  path: string
  permission: string
}

/**
 * 运行中心的页签。对话、运行记录和评测是同一个运营入口下的三种视角，
 * 每个页签只按自己的权限显示，路由守卫和 Control 接口仍是安全边界。
 */
export const RUN_CENTER_TABS: RunCenterTab[] = [
  { key: 'conversations', label: '对话', path: '/runops/conversations', permission: PLATFORM_PERMISSION_RUNOPS_READ },
  { key: 'runs', label: '运行记录', path: '/runops', permission: PLATFORM_PERMISSION_RUNOPS_READ },
  { key: 'evals', label: '评测', path: '/runops/evals', permission: PLATFORM_PERMISSION_AGENT_EVALUATE },
]

export const RUN_CENTER_PERMISSIONS = [...new Set(RUN_CENTER_TABS.map(tab => tab.permission))]

export function visibleRunCenterTabs(permissions: readonly string[] | null | undefined): RunCenterTab[] {
  return RUN_CENTER_TABS.filter(tab => hasPlatformPermission(permissions, tab.permission))
}

/** 侧边栏「运行中心」落到当前用户能看的第一个页签；运营人员先看到对话。 */
export function firstRunCenterPath(permissions: readonly string[] | null | undefined): string {
  return visibleRunCenterTabs(permissions)[0]?.path ?? '/runops/conversations'
}

/** 运行详情归属运行记录页签；对话和评测按路径前缀匹配。 */
export function activeRunCenterTab(path: string): RunCenterTabKey {
  if (path.startsWith('/runops/conversations')) return 'conversations'
  if (path.startsWith('/runops/evals')) return 'evals'
  return 'runs'
}
