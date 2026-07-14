import { computed, type ComputedRef, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

export interface UsePageAssistantWizardNavigationDeps {
  projectCode?: ComputedRef<string>
  attachmentResult: Ref<{ workflowId?: string; agentId?: string } | null>
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
    const workflowId = deps.attachmentResult.value?.workflowId || deps.createdWorkflowId.value
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

  function enterAgentWorkbench() {
    const agentId = deps.attachmentResult.value?.agentId
    if (!agentId) {
      ElMessage.warning('缺少 Agent ID，无法打开工作台')
      return false
    }
    router.push(`/agent/${agentId}/edit`)
    return true
  }

  function openRunOps() {
    const agentId = deps.attachmentResult.value?.agentId
    router.push({ path: '/runops', query: agentId ? { agentId } : {} })
    return true
  }

  return {
    goBack,
    enterWorkflowStudio,
    enterAgentWorkbench,
    openRunOps,
    openAiCodingWorkflowStudio,
  }
}
