import { computed, ref, watch, type Ref } from 'vue'
import { getAiOnboardingManifest } from '@/api/scanProject'
import type { AiOnboardingManifest, ScanProject } from '@/types/scanProject'
import type { SdkAccessLanguage } from './useSdkAccessWizardUiState'

export function useSdkAccessWizardLanguage(
  language: Ref<SdkAccessLanguage>,
  project: Ref<ScanProject | null>,
) {
  const csharpManifest = ref<AiOnboardingManifest | null>(null)
  const csharpLoading = ref(false)
  const csharpError = ref('')
  const pythonManifest = ref<AiOnboardingManifest | null>(null)
  const pythonLoading = ref(false)
  const pythonError = ref('')
  const nodeManifest = ref<AiOnboardingManifest | null>(null)
  const nodeLoading = ref(false)
  const nodeError = ref('')
  const goManifest = ref<AiOnboardingManifest | null>(null)
  const goLoading = ref(false)
  const goError = ref('')
  let sequence = 0

  async function loadManifest(selected: 'csharp' | 'python' | 'node' | 'go') {
    const current = ++sequence
    const projectId = project.value?.id
    csharpManifest.value = null
    csharpError.value = ''
    csharpLoading.value = false
    pythonManifest.value = null
    pythonError.value = ''
    pythonLoading.value = false
    nodeManifest.value = null
    nodeError.value = ''
    nodeLoading.value = false
    goManifest.value = null
    goError.value = ''
    goLoading.value = false
    if (language.value !== selected || !projectId) return
    const loading = selected === 'csharp' ? csharpLoading : selected === 'python' ? pythonLoading : selected === 'node' ? nodeLoading : goLoading
    const manifest = selected === 'csharp' ? csharpManifest : selected === 'python' ? pythonManifest : selected === 'node' ? nodeManifest : goManifest
    const error = selected === 'csharp' ? csharpError : selected === 'python' ? pythonError : selected === 'node' ? nodeError : goError
    loading.value = true
    try {
      const response = await getAiOnboardingManifest(projectId, selected)
      if (current === sequence) manifest.value = response.data
    } catch {
      if (current === sequence) error.value = `无法读取 ${selected === 'csharp' ? 'C#' : selected === 'python' ? 'Python' : selected === 'node' ? 'Node.js' : 'Go'} SDK 接入信息，请检查平台服务后重试。`
    } finally {
      if (current === sequence) loading.value = false
    }
  }

  const loadCsharpManifest = () => loadManifest('csharp')
  const loadPythonManifest = () => loadManifest('python')
  const loadNodeManifest = () => loadManifest('node')
  const loadGoManifest = () => loadManifest('go')
  watch([language, () => project.value?.id], () => {
    void loadManifest(language.value === 'python' ? 'python' : language.value === 'node' ? 'node' : language.value === 'go' ? 'go' : 'csharp')
  }, { immediate: true, flush: 'sync' })

  const csharpArtifact = computed(() => csharpManifest.value?.sdkArtifacts?.find(
    (artifact) => artifact.language === 'csharp' && artifact.packageName === 'ReachAI.AspNetCore',
  ))
  const csharpArtifactReady = computed(() => Boolean(
    csharpArtifact.value?.downloadUrl
    && /^[a-f0-9]{64}$/i.test(csharpArtifact.value?.integritySha256 || '')
    && csharpArtifact.value?.installCommandTemplate,
  ))
  const pythonArtifact = computed(() => pythonManifest.value?.sdkArtifacts?.find(
    (artifact) => artifact.language === 'python' && artifact.packageName === 'reachai-fastapi',
  ))
  const pythonArtifactReady = computed(() => Boolean(
    pythonArtifact.value?.downloadUrl
    && /^[a-f0-9]{64}$/i.test(pythonArtifact.value?.integritySha256 || '')
    && pythonArtifact.value?.installCommandTemplate,
  ))

  const nodeArtifact = computed(() => nodeManifest.value?.sdkArtifacts?.find(
    (artifact) => artifact.language === 'node' && artifact.packageName === '@reachai/node-sdk',
  ))
  const nodeArtifactReady = computed(() => Boolean(
    nodeArtifact.value?.downloadUrl
    && /^[a-f0-9]{64}$/i.test(nodeArtifact.value?.integritySha256 || '')
    && nodeArtifact.value?.installCommandTemplate,
  ))
  const goArtifact = computed(() => goManifest.value?.sdkArtifacts?.find(
    (artifact) => artifact.language === 'go' && artifact.packageName === 'github.com/w8123/EnterpriseAgentFramework/reachai-go',
  ))
  const goArtifactReady = computed(() => Boolean(
    goArtifact.value?.downloadUrl
    && /^[a-f0-9]{64}$/i.test(goArtifact.value?.integritySha256 || '')
    && goArtifact.value?.installCommandTemplate,
  ))

  return { csharpManifest, csharpLoading, csharpError, csharpArtifact, csharpArtifactReady, loadCsharpManifest,
    pythonManifest, pythonLoading, pythonError, pythonArtifact, pythonArtifactReady, loadPythonManifest,
    nodeManifest, nodeLoading, nodeError, nodeArtifact, nodeArtifactReady, loadNodeManifest,
    goManifest, goLoading, goError, goArtifact, goArtifactReady, loadGoManifest }
}
