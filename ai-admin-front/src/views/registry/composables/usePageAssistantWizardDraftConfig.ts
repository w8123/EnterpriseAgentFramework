import { computed, type ComputedRef, type Ref } from 'vue'
import type { PageActionRegistryView, PageRegistryView } from '@/api/embedOps'
import { buildPageAssistantDraftRequirement, type PageAssistantGoal } from '@/views/registry/pageAssistantDraftRequirement'
import {
  actionCountForPage,
  actionRowKey,
  pageIdentity,
  type AssistantGoal,
  type WizardStepKey,
} from '@/views/registry/pageAssistantWizardViewModel'
import { workflowKeyPart } from '@/views/registry/pageAssistantWizardUtils'

export interface UsePageAssistantWizardDraftConfigDeps {
  projectCode: Ref<string>
  pageActions: Ref<PageActionRegistryView[]>
  selectedPageKey: Ref<string>
  selectedPageIdentity: Ref<string>
  selectedPage: ComputedRef<PageRegistryView | null>
  selectedActions: Ref<PageActionRegistryView[]>
  assistantGoal: Ref<AssistantGoal>
  agentName: Ref<string>
  resetWizardProgressFromDraft: () => void
  selectStep: (key: WizardStepKey) => boolean
}

export function usePageAssistantWizardDraftConfig(deps: UsePageAssistantWizardDraftConfigDeps) {
  const filteredActions = computed(() =>
    deps.pageActions.value.filter((action) => !deps.selectedPageKey.value || action.pageKey === deps.selectedPageKey.value),
  )

  function defaultRequirement() {
    return buildPageAssistantDraftRequirement({
      pageName: deps.selectedPage.value?.name || deps.selectedPageKey.value || '当前业务页面',
      assistantGoal: deps.assistantGoal.value as PageAssistantGoal,
      actions: deps.selectedActions.value.map((item) => ({
        actionKey: item.actionKey,
        title: item.title,
        confirmRequired: Boolean(item.confirmRequired),
      })),
    })
  }

  function pageAssistantWorkflowKeySlug() {
    const projectPart = workflowKeyPart(deps.projectCode.value, 'project')
    const pagePart = workflowKeyPart(
      deps.selectedPageKey.value || deps.selectedPage.value?.pageKey || deps.selectedPage.value?.name,
      'page',
    )
    const suffix = Date.now().toString(36)
    return `${projectPart}-${pagePart}-page-assistant-${suffix}`.slice(0, 128)
  }

  function pageAssistantWorkflowName() {
    return deps.agentName.value.trim() || `${deps.selectedPage.value?.name || deps.selectedPageKey.value || '页面'}页面助手 Workflow`
  }

  function actionCount(pageKey: string) {
    return actionCountForPage(pageKey, deps.pageActions.value)
  }

  function isActionSelected(action: PageActionRegistryView) {
    const key = actionRowKey(action)
    return deps.selectedActions.value.some((item) => actionRowKey(item) === key)
  }

  function toggleActionSelection(action: PageActionRegistryView) {
    const key = actionRowKey(action)
    deps.selectedActions.value = isActionSelected(action)
      ? deps.selectedActions.value.filter((item) => actionRowKey(item) !== key)
      : [...deps.selectedActions.value, action]
    deps.resetWizardProgressFromDraft()
  }

  function selectAllFilteredActions() {
    const visibleKeys = new Set(filteredActions.value.map(actionRowKey))
    const kept = deps.selectedActions.value.filter((action) => !visibleKeys.has(actionRowKey(action)))
    deps.selectedActions.value = [...kept, ...filteredActions.value]
    deps.resetWizardProgressFromDraft()
  }

  function clearFilteredActions() {
    const visibleKeys = new Set(filteredActions.value.map(actionRowKey))
    deps.selectedActions.value = deps.selectedActions.value.filter((action) => !visibleKeys.has(actionRowKey(action)))
    deps.resetWizardProgressFromDraft()
  }

  function selectPage(page: PageRegistryView) {
    const pageKey = page.pageKey
    deps.selectedPageKey.value = pageKey
    deps.selectedPageIdentity.value = pageIdentity(page)
    deps.selectedActions.value = deps.selectedActions.value.filter((action) => action.pageKey === pageKey)
    deps.resetWizardProgressFromDraft()
    deps.selectStep('action')
  }

  return {
    filteredActions,
    defaultRequirement,
    pageAssistantWorkflowKeySlug,
    pageAssistantWorkflowName,
    actionCount,
    actionRowKey,
    isActionSelected,
    toggleActionSelection,
    selectAllFilteredActions,
    clearFilteredActions,
    selectPage,
    pageIdentity,
  }
}
