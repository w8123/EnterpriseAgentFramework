import { computed, ref } from 'vue'
import { platformSessionUser } from '@/auth/platformSession'
import { filterSidebarMenu } from '@/components/common/sidebarMenu'
import {
  availableSidebarWorkspaces,
  defaultSidebarWorkspace,
  findSidebarWorkspace,
  AI_APPROVALS_PATH,
  PLATFORM_AI_CONNECTIONS_PATH,
  resolveWorkspaceForMenu,
  type SidebarWorkspace,
  type SidebarWorkspaceKey,
} from '@/components/common/sidebarWorkspaces'

const STORAGE_KEY = 'reachai.sidebar.workspace'

/** 每个账号记住上次使用的身份；只是浏览器本地的便利设置，读写失败时回退默认工作台。 */
function storageKey(userId: number | undefined): string | null {
  return typeof userId === 'number' && Number.isSafeInteger(userId) && userId > 0 ? `${STORAGE_KEY}.user.${userId}` : null
}

function readStoredWorkspace(userId: number | undefined): string | null {
  const key = storageKey(userId)
  if (!key) return null
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function writeStoredWorkspace(userId: number | undefined, workspace: SidebarWorkspaceKey) {
  const key = storageKey(userId)
  if (!key) return
  try {
    localStorage.setItem(key, workspace)
  } catch {
    // 隐私模式或配额不足时只在本次会话内生效。
  }
}

// 记录「哪个账号选了哪个工作台」，换账号登录时自动回到该账号自己的选择。
const selection = ref<{ userId: number | undefined; key: string | null } | null>(null)

const roles = computed(() => platformSessionUser.value?.roles ?? [])
const permissions = computed(() => platformSessionUser.value?.permissions ?? [])
const availableWorkspaces = computed(() => availableSidebarWorkspaces(roles.value, permissions.value))

const currentWorkspace = computed<SidebarWorkspace>(() => {
  const available = availableWorkspaces.value
  const userId = platformSessionUser.value?.userId
  const chosen = selection.value
  const key = chosen && chosen.userId === userId ? chosen.key : readStoredWorkspace(userId)
  return available.find(workspace => workspace.key === key) ?? defaultSidebarWorkspace(roles.value, permissions.value)
})

/** 当前工作台的菜单。AI 工具接入让自己的 AI 工具代表本人使用平台，没有任何平台权限时它和 AI 操作审核都不显示。 */
const PLATFORM_AI_PATHS = new Set([PLATFORM_AI_CONNECTIONS_PATH, AI_APPROVALS_PATH])
const visibleMenu = computed(() => filterSidebarMenu(currentWorkspace.value.menu, permissions.value)
  .filter(entry => entry.kind !== 'item' || !PLATFORM_AI_PATHS.has(entry.index) || permissions.value.length > 0))

function setWorkspace(key: SidebarWorkspaceKey) {
  const workspace = findSidebarWorkspace(key)
  if (!workspace || !availableWorkspaces.value.includes(workspace)) return
  const userId = platformSessionUser.value?.userId
  selection.value = { userId, key }
  writeStoredWorkspace(userId, key)
}

/** 打开的页面不在当前工作台时，切到能看到它的工作台。 */
function followActiveMenu(activeMenu: string) {
  const target = resolveWorkspaceForMenu(activeMenu, currentWorkspace.value, availableWorkspaces.value, permissions.value)
  if (target && target !== currentWorkspace.value) setWorkspace(target.key)
}

export function useSidebarWorkspace() {
  return {
    availableWorkspaces,
    currentWorkspace,
    visibleMenu,
    setWorkspace,
    followActiveMenu,
  }
}
