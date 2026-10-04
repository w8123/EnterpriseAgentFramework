import { computed, ref, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  listPageActionCatalog,
  listPageRegistry,
  type PageActionRegistryView,
  type PageRegistryView,
} from '@/api/embedOps'
import { listRegistryProjectInstances } from '@/api/registry'
import { getScanProjectDetail } from '@/api/scanProject'
import type { ProjectInstance } from '@/types/registry'
import type { ScanProject } from '@/types/scanProject'
import { useProjectStore } from '@/store/project'
import {
  countOfflineInstances,
  isSdkBackedProjectKind,
} from '@/views/registry/registryProjectDetailViewModel'

export interface UseRegistryProjectDetailDataDeps {
  loadAiCodingAccess: (projectId: number) => Promise<void>
  projectCode?: Ref<string>
}

export function useRegistryProjectDetailData(deps: UseRegistryProjectDetailDataDeps) {
  const route = useRoute()
  const projectStore = useProjectStore()
  const projectCode = deps.projectCode ?? computed(() => String(route.params.projectCode || ''))

  const project = ref<ScanProject | null>(null)
  const instances = ref<ProjectInstance[]>([])
  const pageRegistry = ref<PageRegistryView[]>([])
  const pageActions = ref<PageActionRegistryView[]>([])
  const loading = ref(false)
  const loadingInstances = ref(false)
  const loadingPageCatalog = ref(false)
  const projectMissing = ref(false)
  const loadError = ref('')
  const projectDetailLoadError = ref('')
  const instancesLoadError = ref('')
  const pageCatalogLoadError = ref('')
  let loadedProjectCode = ''
  let refreshSequence = 0
  let instanceLoadSequence = 0
  let pageCatalogLoadSequence = 0

  const offlineInstanceCount = computed(() => countOfflineInstances(instances.value))

  const isSdkBackedProject = computed(() =>
    isSdkBackedProjectKind(project.value?.projectKind || 'REGISTERED'),
  )

  async function loadInstances() {
    const requestedProjectCode = project.value?.projectCode || projectCode.value
    if (!requestedProjectCode) return
    const currentLoadSequence = ++instanceLoadSequence
    loadingInstances.value = true
    instancesLoadError.value = ''
    try {
      const { data } = await listRegistryProjectInstances(requestedProjectCode)
      if (
        currentLoadSequence !== instanceLoadSequence
        || requestedProjectCode !== (project.value?.projectCode || projectCode.value)
      ) return
      instances.value = data
    } catch (error) {
      if (currentLoadSequence === instanceLoadSequence) {
        const message = (error as Error).message
        instancesLoadError.value = message
          ? `无法读取实例心跳：${message}`
          : '无法读取实例心跳，请重新加载。'
      }
    } finally {
      if (currentLoadSequence === instanceLoadSequence) {
        loadingInstances.value = false
      }
    }
  }

  async function loadPageCatalog() {
    const requestedProjectCode = project.value?.projectCode || projectCode.value
    if (!requestedProjectCode) return
    const currentLoadSequence = ++pageCatalogLoadSequence
    loadingPageCatalog.value = true
    pageCatalogLoadError.value = ''
    try {
      const [pages, actions] = await Promise.all([
        listPageRegistry({ projectCode: requestedProjectCode, limit: 200 }),
        listPageActionCatalog({ projectCode: requestedProjectCode, limit: 500 }),
      ])
      if (
        currentLoadSequence !== pageCatalogLoadSequence
        || requestedProjectCode !== (project.value?.projectCode || projectCode.value)
      ) return
      pageRegistry.value = pages.data
      pageActions.value = actions.data
    } catch (error) {
      if (currentLoadSequence === pageCatalogLoadSequence) {
        const message = (error as Error).message
        pageCatalogLoadError.value = message
          ? `无法读取前端页面与动作目录：${message}`
          : '无法读取前端页面与动作目录，请重新加载。'
      }
    } finally {
      if (currentLoadSequence === pageCatalogLoadSequence) {
        loadingPageCatalog.value = false
      }
    }
  }

  function clearProjectData() {
    instanceLoadSequence += 1
    pageCatalogLoadSequence += 1
    project.value = null
    instances.value = []
    pageRegistry.value = []
    pageActions.value = []
    projectDetailLoadError.value = ''
    instancesLoadError.value = ''
    pageCatalogLoadError.value = ''
    loadingInstances.value = false
    loadingPageCatalog.value = false
  }

  async function refresh() {
    const requestedProjectCode = projectCode.value
    if (!requestedProjectCode) return
    const currentRefreshSequence = ++refreshSequence
    if (loadedProjectCode !== requestedProjectCode) {
      loadedProjectCode = requestedProjectCode
      clearProjectData()
    }
    loading.value = true
    projectMissing.value = false
    loadError.value = ''
    try {
      const catalog = await projectStore.fetchProjects()
      if (currentRefreshSequence !== refreshSequence) return
      if (catalog === null) {
        clearProjectData()
        loadError.value = projectStore.errorMessage
          ? `无法读取当前项目：${projectStore.errorMessage}`
          : '无法读取当前项目，请检查服务状态后重新加载。'
        return
      }
      const found =
        catalog.find(
          (item) =>
            item.projectCode === requestedProjectCode
            || String(item.id) === requestedProjectCode,
        ) || null
      if (!found?.id) {
        clearProjectData()
        projectMissing.value = true
        return
      }
      try {
        const { data: detail } = await getScanProjectDetail(found.id)
        if (currentRefreshSequence !== refreshSequence) return
        project.value = detail
        projectDetailLoadError.value = ''
      } catch (error) {
        if (currentRefreshSequence !== refreshSequence) return
        project.value = found
        const message = (error as Error).message
        projectDetailLoadError.value = message
          ? `项目扩展信息暂不可用：${message}`
          : '项目扩展信息暂不可用，当前展示项目目录中的基础信息。'
      }
      await deps.loadAiCodingAccess(found.id).catch(() => undefined)
      if (currentRefreshSequence !== refreshSequence) return
      await Promise.all([loadInstances(), loadPageCatalog()])
    } catch (error) {
      if (currentRefreshSequence !== refreshSequence) return
      clearProjectData()
      const message = (error as Error).message
      loadError.value = message
        ? `无法读取当前项目：${message}`
        : '无法读取当前项目，请检查服务状态后重新加载。'
    } finally {
      if (currentRefreshSequence === refreshSequence) {
        loading.value = false
      }
    }
  }

  return {
    projectCode,
    project,
    instances,
    pageRegistry,
    pageActions,
    loading,
    loadingInstances,
    loadingPageCatalog,
    projectMissing,
    loadError,
    projectDetailLoadError,
    instancesLoadError,
    pageCatalogLoadError,
    offlineInstanceCount,
    isSdkBackedProject,
    refresh,
    loadInstances,
    loadPageCatalog,
  }
}
