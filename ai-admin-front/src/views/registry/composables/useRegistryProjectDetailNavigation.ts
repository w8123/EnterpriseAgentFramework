import { computed, watch, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { ScanProject } from '@/types/scanProject'
import { BUSINESS_METHOD_CATALOG_PATH, projectCapabilityWorkspaceLocation } from '@/views/capability/businessCapabilityRoutes'

export interface UseRegistryProjectDetailNavigationDeps {
  project: Ref<ScanProject | null>
  projectCode?: Ref<string>
}

export function useRegistryProjectDetailNavigation(deps: UseRegistryProjectDetailNavigationDeps) {
  const route = useRoute()
  const router = useRouter()
  const projectCode = deps.projectCode ?? computed(() => String(route.params.projectCode || ''))

  const projectSection = computed(() => 'overview')
  function selectProjectSection(section: string | number) {
    if (section === 'source-changes') goCapabilitySync()
  }
  watch([() => route.query.section, () => deps.project.value?.id, projectCode], () => {
    const project = deps.project.value
    if (route.query.section !== 'source-changes' || !project?.id || project.projectCode !== projectCode.value) return
    void router.replace({ ...projectCapabilityWorkspaceLocation(project), query: { ...route.query,
      ...projectCapabilityWorkspaceLocation(project).query, section: undefined, sourceSection: 'changes' } })
  }, { immediate: true })

  function goBack() {
    router.back()
  }

  function goCapability(path: string) {
    if (deps.project.value) {
      router.push({ path, query: { projectId: deps.project.value.id } })
    }
  }

  function goCapabilitySources() {
    if (!deps.project.value?.id) return
    router.push({ path: BUSINESS_METHOD_CATALOG_PATH, query: {
      projectId: String(deps.project.value.id), projectCode: deps.project.value.projectCode || undefined,
    } })
  }

  function goCapabilitySync() {
    if (!deps.project.value?.id) return
    router.push({ ...projectCapabilityWorkspaceLocation(deps.project.value), query: {
      ...projectCapabilityWorkspaceLocation(deps.project.value).query, sourceSection: 'changes',
    } })
  }

  function goWorkflowList() {
    const code = deps.project.value?.projectCode || projectCode.value
    if (!code) return
    router.push({ name: 'WorkflowList', query: { projectCode: code } })
  }

  function goPageActionGovernance() {
    router.push({
      name: 'EmbedOpsMonitor',
      params: { projectCode: deps.project.value?.projectCode || projectCode.value },
    })
  }

  function goContextGovernance() {
    router.push({
      name: 'ContextGovernance',
      query: {
        projectId: deps.project.value?.id,
        projectCode: deps.project.value?.projectCode || projectCode.value,
      },
    })
  }

  function goContextCandidateReview() {
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
    projectSection,
    selectProjectSection,
    goBack,
    goCapability,
    goCapabilitySources,
    goCapabilitySync,
    goWorkflowList,
    goPageActionGovernance,
    goContextGovernance,
    goContextCandidateReview,
    goPageAssistantWizard,
    goSdkAccessWizard,
  }
}
