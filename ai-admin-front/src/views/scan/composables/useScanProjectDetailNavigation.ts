import { useRouter } from 'vue-router'
import type { Ref } from 'vue'

export interface UseScanProjectDetailNavigationDeps {
  aiSettingsDrawerVisible: Ref<boolean>
  modelGenerateDrawerVisible: Ref<boolean>
  scanRulesDrawerVisible: Ref<boolean>
  opsDrawerVisible: Ref<boolean>
}

export function useScanProjectDetailNavigation(deps: UseScanProjectDetailNavigationDeps) {
  const router = useRouter()

  function goBack() {
    router.push('/scan-project')
  }

  function openAiSettingsPanel() {
    deps.aiSettingsDrawerVisible.value = true
  }

  function openModelGeneratePanel() {
    deps.modelGenerateDrawerVisible.value = true
  }

  function openScanRulesPanel() {
    deps.scanRulesDrawerVisible.value = true
  }

  function openOpsPanel() {
    deps.opsDrawerVisible.value = true
  }

  return {
    goBack,
    openAiSettingsPanel,
    openModelGeneratePanel,
    openScanRulesPanel,
    openOpsPanel,
  }
}
