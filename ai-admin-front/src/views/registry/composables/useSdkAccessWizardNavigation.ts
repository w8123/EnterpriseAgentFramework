import { computed, type ComputedRef, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { SdkAccessWizardStepKey } from './useSdkAccessWizardProgress'

export interface UseSdkAccessWizardNavigationDeps {
  activeStep: Ref<SdkAccessWizardStepKey>
  activeStepIndex: ComputedRef<number>
  steps: ComputedRef<Array<{ key: SdkAccessWizardStepKey }>>
  projectCode?: Ref<string>
}

export function useSdkAccessWizardNavigation(deps: UseSdkAccessWizardNavigationDeps) {
  const route = useRoute()
  const router = useRouter()
  const projectCode = deps.projectCode ?? computed(() => String(route.params.projectCode || ''))

  function goBack() {
    router.push({ name: 'RegistryProjectDetail', params: { projectCode: projectCode.value } })
  }

  function goProjectDetail() {
    goBack()
  }

  function goPrev() {
    const nextIndex = Math.max(deps.activeStepIndex.value - 1, 0)
    deps.activeStep.value = deps.steps.value[nextIndex].key
  }

  function goNext() {
    const nextIndex = Math.min(deps.activeStepIndex.value + 1, deps.steps.value.length - 1)
    deps.activeStep.value = deps.steps.value[nextIndex].key
  }

  return {
    projectCode,
    goBack,
    goProjectDetail,
    goPrev,
    goNext,
  }
}
