import { reactive, ref } from 'vue'
import type { AiCodingExecutorProvider } from '@/types/aiCodingTask'
import type { SdkAccessWizardStepKey } from './useSdkAccessWizardProgress'

export type SdkAccessAiPromptTool = AiCodingExecutorProvider
export type SdkAccessMode = 'manual' | 'ai-coding'
export type SdkAccessLanguage = 'java' | 'csharp' | 'python' | 'node' | 'go'

export function useSdkAccessWizardUiState() {
  const aiPromptTool = ref<SdkAccessAiPromptTool>('CURSOR')
  const accessMode = ref<SdkAccessMode>('ai-coding')
  const sdkLanguage = ref<SdkAccessLanguage>('java')
  const activeStep = ref<SdkAccessWizardStepKey>('starter')
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
    sdkLanguage,
    activeStep,
    gatewayBaseUrl,
    embedTokenPath,
    manualChecks,
  }
}
