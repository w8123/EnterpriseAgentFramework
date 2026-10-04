import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { getScanProjects } from '@/api/scanProject'
import { platformSessionId } from '@/auth/platformSession'
import type { ScanProject } from '@/types/scanProject'

export type ProjectCatalogStatus = 'idle' | 'loading' | 'ready' | 'error'

export const PROJECT_CATALOG_ERROR_MESSAGE = '项目列表加载失败，请稍后重试'

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  if (value === null || typeof value !== 'object') return false
  const prototype = Object.getPrototypeOf(value)
  return prototype === Object.prototype || prototype === null
}

function isValidProjectRecord(value: unknown): value is ScanProject {
  if (!isPlainRecord(value)) return false
  if (typeof value.id !== 'number' || !Number.isInteger(value.id) || value.id <= 0) return false
  if (typeof value.name !== 'string' || value.name.trim().length === 0) return false
  if (
    value.projectCode !== undefined
    && value.projectCode !== null
    && typeof value.projectCode !== 'string'
  ) return false
  return true
}

function isValidProjectCatalog(value: unknown): value is ScanProject[] {
  if (!Array.isArray(value)) return false
  const ids = new Set<number>()
  for (const project of value) {
    if (!isValidProjectRecord(project) || ids.has(project.id)) return false
    ids.add(project.id)
  }
  return true
}

export const useProjectStore = defineStore('project', () => {
  const projects = ref<ScanProject[]>([])
  const loading = ref(false)
  const status = ref<ProjectCatalogStatus>('idle')
  const errorMessage = ref<string | null>(null)
  const hasLoadedSuccessfully = ref(false)
  const currentProjectId = ref<number | null>(null)
  let inFlightRequest: Promise<ScanProject[] | null> | null = null
  let requestGeneration = 0

  function invalidateCatalogForSessionChange() {
    requestGeneration += 1
    inFlightRequest = null
    projects.value = []
    loading.value = false
    status.value = 'idle'
    errorMessage.value = null
    hasLoadedSuccessfully.value = false
    currentProjectId.value = null
  }

  watch(platformSessionId, (nextSessionId, previousSessionId) => {
    if (nextSessionId === previousSessionId) return
    invalidateCatalogForSessionChange()
  }, { flush: 'sync' })

  const currentProject = computed(() =>
    projects.value.find((p) => p.id === currentProjectId.value) || null,
  )

  const currentProjectCode = computed(() => currentProject.value?.projectCode || null)

  function setLoadError() {
    status.value = 'error'
    errorMessage.value = PROJECT_CATALOG_ERROR_MESSAGE
  }

  function applyProjectCatalog(nextProjects: unknown): ScanProject[] | null {
    if (!isValidProjectCatalog(nextProjects)) {
      setLoadError()
      return null
    }

    projects.value = nextProjects
    hasLoadedSuccessfully.value = true
    status.value = 'ready'
    errorMessage.value = null
    if (
      currentProjectId.value !== null
      && !projects.value.some((p) => p.id === currentProjectId.value)
    ) {
      clearCurrentProject()
    }
    return projects.value
  }

  function fetchProjects(): Promise<ScanProject[] | null> {
    if (inFlightRequest) return inFlightRequest

    const requestSessionId = platformSessionId.value
    const generation = ++requestGeneration
    status.value = 'loading'
    loading.value = true
    errorMessage.value = null

    let resolveRequest!: (value: ScanProject[] | null) => void
    const request = new Promise<ScanProject[] | null>((resolve) => {
      resolveRequest = resolve
    })
    inFlightRequest = request

    void (async () => {
      let result: ScanProject[] | null = null
      try {
        const { data } = await getScanProjects()
        if (generation === requestGeneration && requestSessionId === platformSessionId.value) {
          result = applyProjectCatalog(data)
        }
      } catch {
        if (generation === requestGeneration && requestSessionId === platformSessionId.value) {
          setLoadError()
        }
      } finally {
        if (generation === requestGeneration && requestSessionId === platformSessionId.value) {
          loading.value = false
          if (inFlightRequest === request) inFlightRequest = null
        }
        resolveRequest(result)
      }
    })()

    return request
  }

  function selectCurrentProject(projectId: number | null) {
    currentProjectId.value = projectId
  }

  function clearCurrentProject(projectId?: number) {
    if (projectId !== undefined && currentProjectId.value !== projectId) return
    currentProjectId.value = null
  }

  function projectLabel(project?: ScanProject | null) {
    if (!project) return '未选择项目'
    const code = project.projectCode ? ` / ${project.projectCode}` : ''
    const env = project.environment ? ` · ${project.environment}` : ''
    return `${project.name}${code}${env}`
  }

  return {
    projects,
    loading,
    status,
    errorMessage,
    hasLoadedSuccessfully,
    currentProjectId,
    currentProject,
    currentProjectCode,
    fetchProjects,
    selectCurrentProject,
    clearCurrentProject,
    projectLabel,
  }
})
