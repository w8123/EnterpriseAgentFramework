import { computed, nextTick, ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { PageActionRegistryView, PageRegistryView } from '@/api/embedOps'
import type { PageAssistantWorkflowBindingResult, WorkflowDraftGenerationResult } from '@/types/workflow'
import {
  WIZARD_STEP_KEYS,
  buildWizardStats,
  buildWizardSteps,
  isRequiredWizardStepComplete,
  resolveActiveWizardStep,
  type DraftSource,
  type WizardStepKey,
} from '@/views/registry/pageAssistantWizardViewModel'

export interface UsePageAssistantWizardStepsDeps {
  pageRegistry: Ref<PageRegistryView[]>
  pageActions: Ref<PageActionRegistryView[]>
  apiAssets: Ref<unknown[]>
  selectedPageKey: Ref<string>
  selectedPage: ComputedRef<PageRegistryView | null>
  selectedActions: Ref<PageActionRegistryView[]>
  focusedStep: Ref<WizardStepKey | ''>
  statusStripRef: Ref<HTMLElement | null>
  pagePanelRef: Ref<HTMLElement | null>
  stepTransitionName: Ref<string>
  stepAttentionName: Ref<string>
  draftPreview: Ref<WorkflowDraftGenerationResult | null>
  draftSource: Ref<DraftSource>
  createdWorkflowId: Ref<string>
  bindingResult: Ref<PageAssistantWorkflowBindingResult | null>
  pageCopilotAgent: Ref<unknown | null>
  resetWizardProgressFromDraft: () => void
}

export function usePageAssistantWizardSteps(deps: UsePageAssistantWizardStepsDeps) {
  let lastWheelAt = 0
  let cardAnimationTimer: ReturnType<typeof setTimeout> | undefined
  let cardAttentionTimer: ReturnType<typeof setTimeout> | undefined

  const activeActionCount = computed(() =>
    deps.pageActions.value.filter((action) => action.status === 'ACTIVE').length,
  )

  const isAiCodingWorkflowSelected = computed(() =>
    deps.draftSource.value === 'AI_CODING_RETURNED' && Boolean(deps.createdWorkflowId.value),
  )

  const isDraftStepComplete = computed(() => {
    if (deps.draftSource.value === 'AI_CODING_RETURNED') {
      return Boolean(deps.createdWorkflowId.value)
    }
    return Boolean(deps.draftPreview.value)
  })

  const activeStep = computed(() =>
    resolveActiveWizardStep({
      pageRegistryLength: deps.pageRegistry.value.length,
      pageActionsLength: deps.pageActions.value.length,
      selectedPageKey: deps.selectedPageKey.value,
      selectedActionsLength: deps.selectedActions.value.length,
      isDraftStepComplete: isDraftStepComplete.value,
      isAiCodingWorkflowSelected: isAiCodingWorkflowSelected.value,
      createdWorkflowId: deps.createdWorkflowId.value,
      bindingResultPresent: Boolean(deps.bindingResult.value),
    }),
  )

  const displayedStep = computed(() => deps.focusedStep.value || activeStep.value)
  const displayedStepIndex = computed(() => WIZARD_STEP_KEYS.indexOf(displayedStep.value))
  const canGoPrev = computed(() => displayedStepIndex.value > 0)
  const canGoNext = computed(() => displayedStepIndex.value >= 0 && displayedStepIndex.value < WIZARD_STEP_KEYS.length - 1)

  const stats = computed(() =>
    buildWizardStats({
      pageCount: deps.pageRegistry.value.length,
      actionCount: deps.pageActions.value.length,
      activeActionCount: activeActionCount.value,
      apiAssetCount: deps.apiAssets.value.length,
    }),
  )

  const steps = computed(() =>
    buildWizardSteps({
      pageRegistryLength: deps.pageRegistry.value.length,
      pageActionsLength: deps.pageActions.value.length,
      selectedPageKey: deps.selectedPageKey.value,
      selectedPageName: deps.selectedPage.value?.name,
      selectedActionsLength: deps.selectedActions.value.length,
      isDraftStepComplete: isDraftStepComplete.value,
      isAiCodingWorkflowSelected: isAiCodingWorkflowSelected.value,
      createdWorkflowId: deps.createdWorkflowId.value,
      bindingResultPresent: Boolean(deps.bindingResult.value),
      draftPreview: deps.draftPreview.value,
    }),
  )

  function requiredStepComplete(key: WizardStepKey) {
    return isRequiredWizardStepComplete(key, {
      selectedPageKey: deps.selectedPageKey.value,
      selectedActionsLength: deps.selectedActions.value.length,
      draftSource: deps.draftSource.value,
      createdWorkflowId: deps.createdWorkflowId.value,
      draftPreview: deps.draftPreview.value,
      bindingResultPresent: Boolean(deps.bindingResult.value),
    })
  }

  function firstBlockingStep(targetKey: WizardStepKey) {
    const targetIndex = WIZARD_STEP_KEYS.indexOf(targetKey)
    if (targetIndex <= displayedStepIndex.value) return null
    const requiredKeys: WizardStepKey[] = ['page', 'action', 'draft', 'confirm', 'bind']
    return requiredKeys.find((key) => WIZARD_STEP_KEYS.indexOf(key) < targetIndex && !requiredStepComplete(key)) || null
  }

  function triggerStepAttention(delay = 0) {
    if (cardAttentionTimer) {
      clearTimeout(cardAttentionTimer)
    }
    deps.stepAttentionName.value = ''
    cardAttentionTimer = setTimeout(() => {
      deps.stepAttentionName.value = 'step-shake'
      cardAttentionTimer = setTimeout(() => {
        deps.stepAttentionName.value = ''
        cardAttentionTimer = undefined
      }, 520)
    }, delay)
  }

  function focusStepCard(key: WizardStepKey, options: { attention?: boolean } = {}) {
    const fromIndex = displayedStepIndex.value
    const toIndex = WIZARD_STEP_KEYS.indexOf(key)
    if (cardAnimationTimer) {
      clearTimeout(cardAnimationTimer)
    }
    deps.stepTransitionName.value = toIndex === fromIndex ? '' : toIndex > fromIndex ? 'step-slide-down' : 'step-slide-up'
    deps.focusedStep.value = key
    const shouldDelayAttention = options.attention && toIndex !== fromIndex
    if (deps.stepTransitionName.value) {
      cardAnimationTimer = setTimeout(() => {
        deps.stepTransitionName.value = ''
        cardAnimationTimer = undefined
      }, 460)
    }
    if (options.attention) {
      triggerStepAttention(shouldDelayAttention ? 470 : 0)
    }
    const target = key === 'connect' ? deps.statusStripRef.value : deps.pagePanelRef.value
    nextTick(() => {
      const scrollParent = target?.closest?.('.main-content') as HTMLElement | null
      if (!target || !scrollParent || target.offsetHeight > scrollParent.clientHeight) return
      target.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
    })
  }

  function selectStep(key: WizardStepKey) {
    const blocker = firstBlockingStep(key)
    if (blocker) {
      focusStepCard(blocker, { attention: true })
      return false
    }
    if (['page', 'action', 'draft'].includes(key) && deps.createdWorkflowId.value && !deps.bindingResult.value) {
      const wasAiCoding = deps.draftSource.value === 'AI_CODING_RETURNED'
      deps.createdWorkflowId.value = ''
      deps.draftSource.value = 'NONE'
      deps.pageCopilotAgent.value = null
      deps.resetWizardProgressFromDraft()
      ElMessage.warning(
        wasAiCoding
          ? '已选择的 AI Coding Workflow 状态已清空，请重新选择或生成'
          : '已创建但未挂载的 Workflow 状态已清空，请重新确认创建',
      )
    }
    focusStepCard(key)
    return true
  }

  function goStepByOffset(offset: number) {
    const nextIndex = displayedStepIndex.value + offset
    if (nextIndex < 0 || nextIndex >= WIZARD_STEP_KEYS.length) return
    selectStep(WIZARD_STEP_KEYS[nextIndex])
  }

  function goPrevStep() {
    goStepByOffset(-1)
  }

  function goNextStep() {
    goStepByOffset(1)
  }

  function handleWizardWheel(event: WheelEvent) {
    if ((event.target as HTMLElement | null)?.closest?.('.focus-panel')) return
    if (Math.abs(event.deltaY) < 24) return
    const now = Date.now()
    if (now - lastWheelAt < 520) return
    lastWheelAt = now
    event.preventDefault()
    goStepByOffset(event.deltaY > 0 ? 1 : -1)
  }

  return {
    activeActionCount,
    isAiCodingWorkflowSelected,
    isDraftStepComplete,
    activeStep,
    displayedStep,
    displayedStepIndex,
    canGoPrev,
    canGoNext,
    stats,
    steps,
    focusStepCard,
    selectStep,
    goPrevStep,
    goNextStep,
    handleWizardWheel,
  }
}
