import { computed, type ComputedRef, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

export interface UsePageAssistantWizardNavigationDeps {
  projectCode?: ComputedRef<string>
  bindingResult: Ref<{ workflowId?: string } | null>
  createdWorkflowId: Ref<string>
}

export function usePageAssistantWizardNavigation(deps: UsePageAssistantWizardNavigationDeps) {
  const route = useRoute()
  const router = useRouter()
  const projectCode = deps.projectCode ?? computed(() => String(route.params.projectCode || ''))

  function goBack() {
    router.push({ name: 'RegistryProjectDetail', params: { projectCode: projectCode.value } })
  }

  function enterWorkflowStudio() {
    const workflowId = deps.bindingResult.value?.workflowId || deps.createdWorkflowId.value
    if (!workflowId) {
      ElMessage.warning('缺少 Workflow ID，无法进入 Studio')
      return false
    }
    router.push(`/workflows/${workflowId}/studio`)
    return true
  }

  function openAiCodingWorkflowStudio(studioUrl?: string | null, workflowId?: string | null) {
    const id = workflowId || deps.createdWorkflowId.value
    if (!id) {
      return false
    }
    const url = studioUrl || `/workflows/${id}/studio`
    router.push(url.startsWith('/') ? url : `/${url}`)
    return true
  }

  return {
    goBack,
    enterWorkflowStudio,
    openAiCodingWorkflowStudio,
  }
}
