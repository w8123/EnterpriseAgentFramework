import { reactive, ref } from 'vue'
import type { SdkAccessWizardStepKey } from './useSdkAccessWizardProgress'

export type SdkAccessAiPromptTool = 'cursor' | 'claude' | 'codex'

export function useSdkAccessWizardUiState() {
  const aiPromptTool = ref<SdkAccessAiPromptTool>('cursor')
  const activeStep = ref<SdkAccessWizardStepKey>('overview')
  const selectedApiAssetId = ref<number | null>(null)
  const argsText = ref('{}')
  const gatewayBaseUrl = ref('http://localhost:8080')
  const embedTokenPath = ref('/api/reachai/embed-token')
  const manualChecks = reactive({
    starter: false,
    gateway: false,
    frontend: false,
  })

  return {
    aiPromptTool,
    activeStep,
    selectedApiAssetId,
    argsText,
    gatewayBaseUrl,
    embedTokenPath,
    manualChecks,
  }
}
