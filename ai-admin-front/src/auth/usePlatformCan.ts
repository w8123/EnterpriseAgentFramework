import { computed } from 'vue'
import { platformSessionUser } from '@/auth/platformSession'
import { hasPlatformPermission, PLATFORM_PERMISSION_ALL } from '@/auth/platformAccess'

/**
 * 页面按钮的权限投影：只读角色不显示写操作。服务端接口仍是唯一的安全边界。
 * 按项目授权的资源用 hasPlatformResourcePermission，这里只判断全局功能权限。
 */
export function usePlatformCan() {
  const permissions = computed(() => platformSessionUser.value?.permissions ?? [])

  function can(permission: string): boolean {
    return hasPlatformPermission(permissions.value, permission)
  }

  function canAny(...required: string[]): boolean {
    return permissions.value.includes(PLATFORM_PERMISSION_ALL) || required.some(can)
  }

  return { can, canAny, permissions }
}
