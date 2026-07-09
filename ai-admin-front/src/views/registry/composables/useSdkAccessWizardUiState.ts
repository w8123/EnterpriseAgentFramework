import { reactive, ref } from 'vue'
import type { SdkAccessWizardStepKey } from './useSdkAccessWizardProgress'

export type SdkAccessAiPromptTool = 'cursor' | 'claude' | 'codex'
export type SdkAccessMode = 'manual' | 'ai-coding'

export function useSdkAccessWizardUiState() {
  const aiPromptTool = ref<SdkAccessAiPromptTool>('cursor')
  const accessMode = ref<SdkAccessMode>('ai-coding')
  const activeStep = ref<SdkAccessWizardStepKey>('starter')
  const selectedScanToolId = ref<number | null>(null)
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
    accessMode,
    activeStep,
    selectedScanToolId,
    argsText,
    gatewayBaseUrl,
    embedTokenPath,
    manualChecks,
  }
}
