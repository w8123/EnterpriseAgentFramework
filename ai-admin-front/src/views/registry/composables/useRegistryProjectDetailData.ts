import { computed, ref, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  listPageActionCatalog,
  listPageRegistry,
  type PageActionRegistryView,
  type PageRegistryView,
} from '@/api/embedOps'
import { listRegistryProjectInstances } from '@/api/registry'
import { getScanProjectDetail, getScanProjects } from '@/api/scanProject'
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
  const loadingInstances = ref(false)
  const loadingPageCatalog = ref(false)

  const offlineInstanceCount = computed(() => countOfflineInstances(instances.value))

  const isSdkBackedProject = computed(() =>
    isSdkBackedProjectKind(project.value?.projectKind || 'REGISTERED'),
  )

  async function loadInstances() {
    if (!projectCode.value) return
    loadingInstances.value = true
    try {
      const { data } = await listRegistryProjectInstances(projectCode.value)
      instances.value = data
    } finally {
      loadingInstances.value = false
    }
  }

  async function loadPageCatalog() {
    const code = project.value?.projectCode || projectCode.value
    if (!code) return
    loadingPageCatalog.value = true
    try {
      const [pages, actions] = await Promise.all([
        listPageRegistry({ projectCode: code, limit: 200 }),
        listPageActionCatalog({ projectCode: code, limit: 500 }),
      ])
      pageRegistry.value = pages.data
      pageActions.value = actions.data
    } finally {
      loadingPageCatalog.value = false
    }
  }

  async function refresh() {
    const { data } = await getScanProjects()
    const found =
      data.find((item) => item.projectCode === projectCode.value || String(item.id) === projectCode.value) || null
    projectStore.projects = data
    if (found?.id) {
      try {
        const { data: detail } = await getScanProjectDetail(found.id)
        project.value = detail
        await deps.loadAiCodingAccess(found.id)
      } catch {
        project.value = found
      }
    } else {
      project.value = found
    }
    await loadInstances()
    await loadPageCatalog()
  }

  return {
    projectCode,
    project,
    instances,
    pageRegistry,
    pageActions,
    loadingInstances,
    loadingPageCatalog,
    offlineInstanceCount,
    isSdkBackedProject,
    refresh,
    loadInstances,
    loadPageCatalog,
  }
}
