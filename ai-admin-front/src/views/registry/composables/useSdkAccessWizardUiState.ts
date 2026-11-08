import { reactive, ref } from 'vue'
import type { SdkAccessWizardManualChecks, SdkAccessWizardStepKey } from './useSdkAccessWizardProgress'

export type SdkAccessMode = 'manual' | 'ai-coding'
export type SdkAccessLanguage = 'java' | 'csharp' | 'python' | 'node' | 'go'

export const sdkAccessLanguageOptions = [
  { value: 'java', label: 'Java / Spring' },
  { value: 'csharp', label: 'C# / ASP.NET Core' },
  { value: 'python', label: 'Python / FastAPI' },
  { value: 'node', label: 'Node.js / Express' },
  { value: 'go', label: 'Go / net/http' },
] as const satisfies ReadonlyArray<{ value: SdkAccessLanguage; label: string }>

export function useSdkAccessWizardUiState() {
  const accessMode = ref<SdkAccessMode>('ai-coding')
  const sdkLanguage = ref<SdkAccessLanguage>('java')
  const activeStep = ref<SdkAccessWizardStepKey>('starter')
  const gatewayBaseUrl = ref('')
  const embedTokenPath = ref('/reachai/embed/token')
  const defaultManualChecks: SdkAccessWizardManualChecks = {
    starter: false,
    capabilities: false,
    identity: false,
    gateway: false,
    sync: false,
    frontend: false,
    acceptance: false,
  }
  const manualChecks = reactive({ ...defaultManualChecks })

  function resetManualChecks() {
    Object.assign(manualChecks, defaultManualChecks)
  }

  return {
    accessMode,
    sdkLanguage,
    activeStep,
    gatewayBaseUrl,
    embedTokenPath,
    manualChecks,
    resetManualChecks,
  }
}
