import {
  computed,
  inject,
  provide,
  ref,
  watch,
  type ComputedRef,
  type InjectionKey,
} from 'vue'
import {
  useRoute,
  useRouter,
  type LocationQuery,
  type LocationQueryRaw,
  type LocationQueryValueRaw,
} from 'vue-router'
import { platformSessionId, platformSessionUser, isPlatformWorkspaceReady } from '@/auth/platformSession'
import {
  hasPlatformGlobalPermissionGrant,
  hasPlatformResourcePermission,
} from '@/auth/platformAccess'
import { useProjectStore } from '@/store/project'
import type { ScanProject } from '@/types/scanProject'
import {
  parseProjectScopeQuery,
  resolveProjectScope,
  withProjectScopeQuery,
  type ProjectScopeBlockedReason,
  type ProjectScopeCatalogProject,
  type ProjectScopeProjectIdentity,
  type ProjectScopeQueryReference,
  type ProjectScopeRequestIdentity,
  type ResolvedProjectScope,
  type ResolvedProjectScopeSuccess,
} from '@/utils/projectScope'

export interface ProjectScopeRouteConfig {
  permission: string
  resourceLabel: string
  requiresProjectCode?: boolean
}

declare module 'vue-router' {
  interface RouteMeta {
    public?: unknown
    requiredPermissions?: unknown
    projectScope?: ProjectScopeRouteConfig
  }
}

export type PageProjectScopeSelection = number | 'all'
export type PageProjectScopeStatus = 'inactive' | 'pending' | 'resolved' | 'blocked'
export type PageProjectScopeRecoveryAction =
  | 'none'
  | 'select-project'
  | 'retry-catalog'
  | 'retry-normalization'

export interface PageProjectScopeController {
  isRouteConnected: ComputedRef<boolean>
  isActive: ComputedRef<boolean>
  resourceLabel: ComputedRef<string>
  requiresProjectCode: ComputedRef<boolean>
  status: ComputedRef<PageProjectScopeStatus>
  reference: ComputedRef<ProjectScopeQueryReference | null>
  resolution: ComputedRef<ResolvedProjectScope | null>
  canLoadData: ComputedRef<boolean>
  currentScopeLabel: ComputedRef<string>
  feedbackMessage: ComputedRef<string>
  recoveryAction: ComputedRef<PageProjectScopeRecoveryAction>
  recoveryLabel: ComputedRef<string>
  isCatalogLoading: ComputedRef<boolean>
  hasCatalogError: ComputedRef<boolean>
  readableProjects: ComputedRef<readonly ScanProject[]>
  canSelectAll: ComputedRef<boolean>
  selection: ComputedRef<PageProjectScopeSelection | null>
  requestParams: ComputedRef<{ projectId?: number; projectCode?: string } | undefined>
  scopeQuery: ComputedRef<LocationQueryRaw | null>
  requestKey: ComputedRef<string>
  retryCatalog: () => Promise<ScanProject[] | null>
  retryScope: () => Promise<boolean>
  refreshCatalog: () => Promise<ScanProject[] | null>
  selectScope: (selection: PageProjectScopeSelection) => Promise<boolean>
}

export const pageProjectScopeKey: InjectionKey<PageProjectScopeController> = Symbol('page-project-scope')
export const PAGE_PROJECT_SCOPE_KEY = pageProjectScopeKey

const NO_SCOPE_LABEL = '范围未确认'
const ALL_SCOPE_VALUE: PageProjectScopeSelection = 'all'

type RouteQueryValue = LocationQueryValueRaw | LocationQueryValueRaw[]

function toScopeQuery(query: LocationQuery): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  Object.entries(query).forEach(([key, value]) => {
    result[key] = value
  })
  return result
}

function isRouteQueryValue(value: unknown): value is RouteQueryValue {
  if (value === null || value === undefined || typeof value === 'string' || typeof value === 'number') {
    return true
  }
  return Array.isArray(value) && value.every((item) => (
    item === null || item === undefined || typeof item === 'string' || typeof item === 'number'
  ))
}

function toLocationQueryRaw(query: Record<string, unknown>): LocationQueryRaw {
  const result: LocationQueryRaw = {}
  Object.entries(query).forEach(([key, value]) => {
    if (isRouteQueryValue(value)) result[key] = value
  })
  return result
}

function projectIdentity(project: ScanProject): ProjectScopeProjectIdentity {
  const projectCode = typeof project.projectCode === 'string' ? project.projectCode.trim() : ''
  return {
    id: project.id,
    name: project.name,
    projectCode: projectCode || null,
  }
}

function isSuccess(value: ResolvedProjectScope | null): value is ResolvedProjectScopeSuccess {
  return value?.kind === 'all' || value?.kind === 'project'
}

function isSameResolvedScope(
  current: ResolvedProjectScope | null,
  next: ResolvedProjectScopeSuccess,
) {
  if (!current || current.kind !== next.kind) return false
  if (current.kind === 'all' && next.kind === 'all') return true
  return current.kind === 'project'
    && next.kind === 'project'
    && current.project.id === next.project.id
    && current.project.projectCode === next.project.projectCode
}

function blockedFeedback(reason: ProjectScopeBlockedReason, resourceLabel: string): string {
  const messages: Record<ProjectScopeBlockedReason, string> = {
    'invalid-reference': '地址中的项目范围无效，请从左侧当前范围重新选择',
    'all-forbidden': `当前账号没有全部 ${resourceLabel} 访问权限，请选择一个项目`,
    'project-not-found': '项目不存在或已不可见，请重新选择项目',
    'ambiguous-project': '项目编码不唯一，请从左侧当前范围重新选择',
    'project-forbidden': `当前账号没有该项目的 ${resourceLabel} 查看权限，请重新选择项目`,
    'project-code-missing': `当前项目缺少项目编码，无法确认 ${resourceLabel} 范围`,
    'catalog-error': '项目目录加载失败，请重试或重新选择范围',
    'project-required': '请选择项目后继续',
    'no-access': '暂无可读项目，请联系管理员开通访问权限',
  }
  return messages[reason]
}

function requestFromResolution(resolution: ResolvedProjectScopeSuccess) {
  if (resolution.kind === 'all') return undefined
  return {
    projectId: resolution.project.id,
    ...(resolution.project.projectCode ? { projectCode: resolution.project.projectCode } : {}),
  }
}

export function createPageProjectScope(): PageProjectScopeController {
  const route = useRoute()
  const router = useRouter()
  const projectStore = useProjectStore()

  const routeConfig = computed(() => route.meta.projectScope ?? null)
  const isRouteConnected = computed(() => routeConfig.value !== null)
  const isActive = computed(() => isRouteConnected.value && isPlatformWorkspaceReady.value)
  const resourceLabel = computed(() => routeConfig.value?.resourceLabel?.trim() || '资源')
  const requiresProjectCode = computed(() => routeConfig.value?.requiresProjectCode ?? false)
  const navigationPending = ref(false)
  const navigationError = ref<string | null>(null)
  const capturedDefaultProjectId = ref<number | null>(null)
  const defaultCaptured = ref(false)
  const addressConfirmed = ref(false)
  const routeRevision = ref(0)
  let routeSignature = ''
  let normalizationRevision: number | null = null

  function syncRouteContext() {
    const nextSignature = `${route.fullPath}|${platformSessionId.value}|${routeConfig.value?.permission || ''}`
    if (nextSignature === routeSignature && defaultCaptured.value === isActive.value) return

    routeSignature = nextSignature
    routeRevision.value += 1
    navigationPending.value = false
    navigationError.value = null
    normalizationRevision = null
    defaultCaptured.value = false
    capturedDefaultProjectId.value = null
    addressConfirmed.value = false

    if (!isActive.value) return

    const nextReference = parseProjectScopeQuery(toScopeQuery(route.query))
    if (nextReference.kind === 'unspecified') {
      capturedDefaultProjectId.value = projectStore.currentProjectId
      addressConfirmed.value = false
    } else {
      addressConfirmed.value = nextReference.kind !== 'invalid'
    }
    defaultCaptured.value = true
  }

  syncRouteContext()

  const reference = computed<ProjectScopeQueryReference | null>(() => (
    isActive.value ? parseProjectScopeQuery(toScopeQuery(route.query)) : null
  ))
  const canSelectAll = computed(() => (
    isActive.value
      && routeConfig.value !== null
      && hasPlatformGlobalPermissionGrant(
        platformSessionUser.value?.permissionGrants,
        routeConfig.value.permission,
      )
  ))

  function canReadProject(project: ProjectScopeCatalogProject) {
    const permission = routeConfig.value?.permission
    if (!permission) return false
    return hasPlatformResourcePermission(
      platformSessionUser.value?.permissionGrants,
      permission,
      'PROJECT',
      null,
      project.projectCode,
    )
  }

  const readableProjects = computed<readonly ScanProject[]>(() => {
    if (!isActive.value || projectStore.status === 'error' || !projectStore.hasLoadedSuccessfully) return []
    return projectStore.projects.filter((project) => canReadProject(project))
  })

  const resolution = computed<ResolvedProjectScope | null>(() => {
    if (!isActive.value || !defaultCaptured.value || routeConfig.value === null || reference.value === null) {
      return null
    }
    return resolveProjectScope({
      reference: reference.value,
      selectedProjectId: reference.value.kind === 'unspecified'
        ? capturedDefaultProjectId.value
        : null,
      catalog: projectStore.projects,
      catalogStatus: projectStore.status,
      hasLoadedSuccessfully: projectStore.hasLoadedSuccessfully,
      canReadAll: canSelectAll.value,
      canReadProject,
      requiresProjectCode: requiresProjectCode.value,
    })
  })

  const status = computed<PageProjectScopeStatus>(() => {
    if (!isActive.value) return 'inactive'
    if (navigationPending.value || resolution.value?.kind === 'pending') return 'pending'
    if (resolution.value?.kind === 'blocked') return 'blocked'
    return isSuccess(resolution.value) && addressConfirmed.value ? 'resolved' : 'pending'
  })

  const canLoadData = computed(() => (
    isActive.value
      && !navigationPending.value
      && addressConfirmed.value
      && isSuccess(resolution.value)
  ))

  const currentScopeLabel = computed(() => {
    if (!isActive.value || navigationPending.value) return NO_SCOPE_LABEL
    if (resolution.value?.kind === 'all' && addressConfirmed.value) return '全部项目'
    if (resolution.value?.kind === 'project' && addressConfirmed.value) return resolution.value.project.name
    if (resolution.value?.kind === 'blocked' && resolution.value.reason === 'project-required') return '请选择项目'
    return NO_SCOPE_LABEL
  })

  const feedbackMessage = computed(() => {
    if (!isActive.value) return ''
    if (navigationError.value) return navigationError.value
    if (navigationPending.value) return '正在切换项目范围…'
    if (resolution.value?.kind === 'pending') return '正在确认项目范围…'
    if (resolution.value?.kind === 'blocked') {
      return blockedFeedback(resolution.value.reason, resourceLabel.value)
    }
    return ''
  })

  const recoveryAction = computed<PageProjectScopeRecoveryAction>(() => {
    if (!isActive.value) return 'none'
    if (navigationError.value) {
      return reference.value?.kind === 'unspecified' && isSuccess(resolution.value)
        ? 'retry-normalization'
        : 'select-project'
    }
    if (resolution.value?.kind === 'blocked') {
      return resolution.value.reason === 'catalog-error' ? 'retry-catalog' : 'select-project'
    }
    if (projectStore.status === 'error' || resolution.value?.kind === 'pending') return 'retry-catalog'
    return 'none'
  })
  const recoveryLabel = computed(() => {
    if (recoveryAction.value === 'select-project') {
      return navigationError.value ? '重新选择项目' : '选择项目'
    }
    if (recoveryAction.value === 'retry-catalog') return '重试加载项目目录'
    if (recoveryAction.value === 'retry-normalization') return '重试范围确认'
    return ''
  })

  const isCatalogLoading = computed(() => projectStore.status === 'loading')
  const hasCatalogError = computed(() => projectStore.status === 'error')
  const selection = computed<PageProjectScopeSelection | null>(() => {
    const currentResolution = resolution.value
    if (!canLoadData.value || !isSuccess(currentResolution)) return null
    if (currentResolution.kind === 'all') return ALL_SCOPE_VALUE
    return currentResolution.project.id
  })
  const requestParams = computed(() => (
    canLoadData.value && isSuccess(resolution.value)
      ? requestFromResolution(resolution.value)
      : undefined
  ))
  const scopeQuery = computed<LocationQueryRaw | null>(() => {
    const currentResolution = resolution.value
    return canLoadData.value && isSuccess(currentResolution)
      ? toLocationQueryRaw(withProjectScopeQuery({}, currentResolution))
      : null
  })
  const requestKey = computed(() => {
    const current = resolution.value
    const resolvedKey = current?.kind === 'all'
      ? 'all'
      : current?.kind === 'project'
        ? `project:${current.project.id}:${current.project.projectCode || ''}`
        : current?.kind === 'pending'
          ? 'pending'
          : current?.kind === 'blocked'
            ? `blocked:${current.reason}`
            : 'inactive'
    return `${platformSessionId.value}|${routeRevision.value}|${resolvedKey}|${canLoadData.value ? 'ready' : 'not-ready'}`
  })

  async function normalizeDefaultScope(resolved: ResolvedProjectScopeSuccess, revision: number) {
    if (normalizationRevision === revision || !isActive.value || reference.value?.kind !== 'unspecified') return false
    normalizationRevision = revision
    navigationPending.value = true
    navigationError.value = null
    const nextQuery = toLocationQueryRaw(withProjectScopeQuery(
      toScopeQuery(route.query),
      resolved,
    ))
    try {
      const navigationResult = await router.replace({
        path: route.path,
        query: nextQuery,
        hash: route.hash,
      })
      if (navigationResult || routeRevision.value === revision) {
        if (routeRevision.value === revision) {
          navigationPending.value = false
          navigationError.value = '范围确认失败，请重试'
          normalizationRevision = null
        }
        return false
      }
      return true
    } catch {
      if (routeRevision.value === revision && isActive.value) {
        navigationPending.value = false
        navigationError.value = '范围确认失败，请重试'
        normalizationRevision = null
      }
      return false
    }
  }

  async function retryCatalog() {
    if (!isActive.value) return null
    navigationError.value = null
    const result = await projectStore.fetchProjects()
    return result
  }

  async function retryScope() {
    if (!isActive.value) return false
    if (recoveryAction.value !== 'retry-normalization') {
      return recoveryAction.value === 'retry-catalog' && (await retryCatalog()) !== null
    }
    if (isSuccess(resolution.value) && reference.value?.kind === 'unspecified') {
      navigationError.value = null
      normalizationRevision = null
      return normalizeDefaultScope(resolution.value, routeRevision.value)
    }
    return false
  }

  async function refreshCatalog() {
    return projectStore.fetchProjects()
  }

  async function selectScope(nextSelection: PageProjectScopeSelection) {
    if (!isActive.value || navigationPending.value) return false
    let resolvedScope: ResolvedProjectScopeSuccess
    if (nextSelection === ALL_SCOPE_VALUE) {
      if (!canSelectAll.value) return false
      resolvedScope = { kind: 'all' }
    } else {
      const project = readableProjects.value.find((item) => item.id === nextSelection)
      if (!project) return false
      resolvedScope = { kind: 'project', project: projectIdentity(project) }
      if (routeConfig.value?.requiresProjectCode && !resolvedScope.project.projectCode) return false
    }

    if (canLoadData.value && isSameResolvedScope(resolution.value, resolvedScope)) {
      navigationError.value = null
      return true
    }

    const revision = routeRevision.value
    const currentPath = route.path
    navigationPending.value = true
    navigationError.value = null
    try {
      const navigationResult = await router.push({
        path: currentPath,
        query: toLocationQueryRaw(withProjectScopeQuery(toScopeQuery(route.query), resolvedScope)),
        hash: route.hash,
      })
      if (navigationResult && routeRevision.value === revision) {
        navigationPending.value = false
        navigationError.value = '未能切换项目，仍保留当前范围，请重新选择'
        return false
      }
      if (routeRevision.value === revision) {
        navigationPending.value = false
        navigationError.value = '未能切换项目，仍保留当前范围，请重新选择'
        return false
      }
      return true
    } catch {
      if (routeRevision.value === revision && isActive.value) {
        navigationPending.value = false
        navigationError.value = '未能切换项目，仍保留当前范围，请重新选择'
      }
      return false
    }
  }

  watch(
    [() => route.fullPath, () => route.meta.projectScope, isPlatformWorkspaceReady, platformSessionId],
    syncRouteContext,
    { flush: 'sync' },
  )

  watch(
    [isActive, () => projectStore.status],
    () => {
      if (isActive.value && projectStore.status === 'idle') void projectStore.fetchProjects()
    },
    { immediate: true },
  )

  watch(
    [resolution, canLoadData, isActive],
    ([nextResolution, nextCanLoad, nextActive]) => {
      if (
        nextActive
        && reference.value?.kind === 'unspecified'
        && nextCanLoad === false
        && isSuccess(nextResolution)
        && !navigationPending.value
        && !navigationError.value
      ) {
        void normalizeDefaultScope(nextResolution, routeRevision.value)
      }
    },
    { flush: 'post', immediate: true },
  )

  watch(
    [resolution, canLoadData, isActive, () => route.fullPath],
    ([nextResolution, nextCanLoad, nextActive]) => {
      if (!nextActive || !nextCanLoad || !isSuccess(nextResolution)) return
      projectStore.selectCurrentProject(nextResolution.kind === 'project' ? nextResolution.project.id : null)
    },
    { flush: 'sync', immediate: true },
  )

  return {
    isRouteConnected,
    isActive,
    resourceLabel,
    requiresProjectCode,
    status,
    reference,
    resolution,
    canLoadData,
    currentScopeLabel,
    feedbackMessage,
    recoveryAction,
    recoveryLabel,
    isCatalogLoading,
    hasCatalogError,
    readableProjects,
    canSelectAll,
    selection,
    requestParams,
    scopeQuery,
    requestKey,
    retryCatalog,
    retryScope,
    refreshCatalog,
    selectScope,
  }
}

export function providePageProjectScope() {
  const controller = createPageProjectScope()
  provide(pageProjectScopeKey, controller)
  return controller
}

export function usePageProjectScope(options: { required: true }): PageProjectScopeController
export function usePageProjectScope(options?: { required?: false }): PageProjectScopeController | null
export function usePageProjectScope(options: { required?: boolean } = {}) {
  const controller = inject(pageProjectScopeKey, null)
  if (!controller && options.required) {
    throw new Error('当前页面缺少项目范围控制器')
  }
  return controller
}
