import { computed, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { ScanProject } from '@/types/scanProject'
import { useProjectStore } from '@/store/project'

export interface UseRegistryProjectDetailNavigationDeps {
  project: Ref<ScanProject | null>
  projectCode?: Ref<string>
}

export function useRegistryProjectDetailNavigation(deps: UseRegistryProjectDetailNavigationDeps) {
  const route = useRoute()
  const router = useRouter()
  const projectStore = useProjectStore()
  const projectCode = deps.projectCode ?? computed(() => String(route.params.projectCode || ''))

  function goBack() {
    router.back()
  }

  function goCapability(path: string) {
    if (deps.project.value) {
      projectStore.setCurrentProject(deps.project.value.id)
      router.push({ path, query: { projectId: deps.project.value.id } })
    }
  }

  function goScanProjectDetail() {
    if (!deps.project.value?.id) return
    projectStore.setCurrentProject(deps.project.value.id)
    router.push({ name: 'ScanProjectDetail', params: { id: String(deps.project.value.id) } })
  }

  function goCapabilitySync() {
    if (!deps.project.value?.id) return
    projectStore.setCurrentProject(deps.project.value.id)
    router.push({ name: 'CapabilitySyncDebug' })
  }

  function goWorkflowList() {
    const code = deps.project.value?.projectCode || projectCode.value
    if (!code) return
    if (deps.project.value?.id) {
      projectStore.setCurrentProject(deps.project.value.id)
    }
    router.push({ name: 'WorkflowList', query: { projectCode: code } })
  }

  function goPageActionGovernance() {
    router.push({
      name: 'EmbedOpsMonitor',
      params: { projectCode: deps.project.value?.projectCode || projectCode.value },
    })
  }

  function goContextGovernance() {
    if (deps.project.value?.id) {
      projectStore.setCurrentProject(deps.project.value.id)
    }
    router.push({
      name: 'ContextGovernance',
      query: {
        projectId: deps.project.value?.id,
        projectCode: deps.project.value?.projectCode || projectCode.value,
      },
    })
  }

  function goContextCandidateReview() {
    if (deps.project.value?.id) {
      projectStore.setCurrentProject(deps.project.value.id)
    }
    router.push({
      name: 'ContextGovernance',
      query: {
        tab: 'candidates',
        projectId: deps.project.value?.id,
        projectCode: deps.project.value?.projectCode || projectCode.value,
      },
    })
  }

  function goPageAssistantWizard() {
    router.push({
      name: 'PageAssistantWizard',
      params: { projectCode: deps.project.value?.projectCode || projectCode.value },
    })
  }

  function goSdkAccessWizard() {
    router.push({
      name: 'SdkAccessWizard',
      params: { projectCode: deps.project.value?.projectCode || projectCode.value },
    })
  }

  return {
    goBack,
    goCapability,
    goScanProjectDetail,
    goCapabilitySync,
    goWorkflowList,
    goPageActionGovernance,
    goContextGovernance,
    goContextCandidateReview,
    goPageAssistantWizard,
    goSdkAccessWizard,
  }
}
