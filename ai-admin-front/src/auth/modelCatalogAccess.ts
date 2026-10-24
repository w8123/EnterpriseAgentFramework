import { platformSessionUser } from '@/auth/platformSession'
import { hasPlatformPermission, PLATFORM_PERMISSION_MODEL_READ } from '@/auth/platformAccess'

/**
 * 只读角色（如管理人员、开发人员）查看 Agent、Workflow、知识库时，跳过它们没有权限的
 * 下拉数据请求（模型、业务方法、凭证），界面回落为显示 id，而不是报 403。
 * 没有登录会话（单元测试）时照常请求。
 */
export function sessionAllows(permission: string): boolean {
  const user = platformSessionUser.value
  return !user || hasPlatformPermission(user.permissions, permission)
}

export function canLoadModelCatalog(): boolean {
  return sessionAllows(PLATFORM_PERMISSION_MODEL_READ)
}
