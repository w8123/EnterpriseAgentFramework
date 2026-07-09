import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  listPageActionCatalog,
  listPageRegistry,
  type PageActionRegistryView,
  type PageRegistryView,
} from '@/api/embedOps'
import { getModelInstances } from '@/api/model'
import { getScanProjectTools, getScanProjects } from '@/api/scanProject'
import type { ModelInstance } from '@/types/model'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'
import { isProjectApiToolSelectable } from '@/utils/projectApiTools'
import {
  isActiveModelInstance,
  normalizeModelInstanceList,
} from '@/views/registry/pageAssistantWizardUtils'
import { pageIdentity } from '@/views/registry/pageAssistantWizardViewModel'

export interface UsePageAssistantWizardDataDeps {
  projectCode?: ComputedRef<string>
  selectedPageIdentity: Ref<string>
  selectedPageKey: Ref<string>
  selectedActions: Ref<PageActionRegistryView[]>
  modelInstanceId: Ref<string>
  agentName: Ref<string>
  requirement: Ref<string>
  resetWizardProgressFromDraft: () => void
  loadPageAssistantSessions: (options?: { silent?: boolean }) => Promise<void>
  defaultRequirement: () => string
}

export function usePageAssistantWizardData(deps: UsePageAssistantWizardDataDeps) {
  const route = useRoute()
  const projectCode = deps.projectCode ?? computed(() => String(route.params.projectCode || ''))

  const project = ref<ScanProject | null>(null)
  const pageRegistry = ref<PageRegistryView[]>([])
  const pageActions = ref<PageActionRegistryView[]>([])
  const projectApiTools = ref<ProjectToolInfo[]>([])
  const modelOptions = ref<ModelInstance[]>([])
  const loading = ref(false)

  async function loadAll() {
    if (!projectCode.value) return
    loading.value = true
    try {
      const [projects, pages, actions, models] = await Promise.all([
        getScanProjects(),
        listPageRegistry({ projectCode: projectCode.value, limit: 200 }),
        listPageActionCatalog({ projectCode: projectCode.value, limit: 500 }),
        getModelInstances({ modelType: 'LLM' }),
      ])
      project.value = projects.data.find((item) => item.projectCode === projectCode.value) || null
      const projectTools = project.value?.id
        ? await getScanProjectTools(project.value.id, 'full')
        : null
      pageRegistry.value = pages.data || []
      pageActions.value = actions.data || []
      projectApiTools.value = (projectTools?.data || []).filter(isProjectApiToolSelectable)
      modelOptions.value = normalizeModelInstanceList(models.data).filter(isActiveModelInstance)
      deps.modelInstanceId.value = deps.modelInstanceId.value || modelOptions.value[0]?.id || ''
      if (
        deps.selectedPageIdentity.value
        && !pageRegistry.value.some((page) => pageIdentity(page) === deps.selectedPageIdentity.value)
      ) {
        deps.selectedPageIdentity.value = ''
        deps.selectedPageKey.value = ''
        deps.selectedActions.value = []
        deps.resetWizardProgressFromDraft()
      }
      if (project.value?.id) {
        await deps.loadPageAssistantSessions({ silent: true })
      }
      deps.agentName.value = deps.agentName.value || `${project.value?.name || projectCode.value}页面助手`
      deps.requirement.value = deps.requirement.value || deps.defaultRequirement()
    } catch (error) {
      ElMessage.error((error as Error).message || '加载页面助手向导失败')
    } finally {
      loading.value = false
    }
  }

  return {
    projectCode,
    project,
    pageRegistry,
    pageActions,
    projectApiTools,
    modelOptions,
    loading,
    loadAll,
  }
}
