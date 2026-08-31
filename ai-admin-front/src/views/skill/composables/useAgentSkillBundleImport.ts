import { computed, ref, shallowRef } from 'vue'
import { discoverAgentSkillBundle } from '@/api/skill'
import type {
  AgentSkillBundleCandidate,
  AgentSkillBundleDiscovery,
} from '@/types/skill'

export type AgentSkillBundleDiscoverer = (
  file: File,
) => Promise<{ data: AgentSkillBundleDiscovery }>

export const AGENT_SKILL_BUNDLE_DISCOVERY_ERROR =
  'ZIP 未通过安全解析，请检查包结构、大小限制和 SKILL.md 格式。'

/**
 * Owns the upload/discovery selection state independently from the dialog.
 * A monotonic request id prevents a late response for an old file from replacing
 * the currently selected bundle or re-enabling import with a stale candidate.
 */
export function useAgentSkillBundleImport(
  discoverer: AgentSkillBundleDiscoverer = discoverAgentSkillBundle,
) {
  const importFile = shallowRef<File | null>(null)
  const bundleDiscovery = shallowRef<AgentSkillBundleDiscovery | null>(null)
  const selectedCandidateRoot = ref<string | null>(null)
  const discoveryLoading = ref(false)
  const discoveryError = ref('')
  let requestSequence = 0

  const selectedBundleCandidate = computed<AgentSkillBundleCandidate | null>(() => {
    if (!bundleDiscovery.value || selectedCandidateRoot.value == null) return null
    return bundleDiscovery.value.candidates.find(candidate =>
      candidate.sourceRoot === selectedCandidateRoot.value && candidate.selectable) || null
  })

  const canSubmitImport = computed(() => Boolean(
    importFile.value
      && !discoveryLoading.value
      && selectedBundleCandidate.value,
  ))

  function reset() {
    requestSequence += 1
    importFile.value = null
    bundleDiscovery.value = null
    selectedCandidateRoot.value = null
    discoveryLoading.value = false
    discoveryError.value = ''
  }

  async function selectFile(file: File | null) {
    if (!file) {
      reset()
      return false
    }
    importFile.value = file
    bundleDiscovery.value = null
    selectedCandidateRoot.value = null
    discoveryError.value = ''
    return discover(file)
  }

  async function discover(file: File) {
    const requestId = ++requestSequence
    discoveryLoading.value = true
    discoveryError.value = ''
    bundleDiscovery.value = null
    selectedCandidateRoot.value = null
    try {
      const { data } = await discoverer(file)
      if (requestId !== requestSequence || importFile.value !== file) return false
      bundleDiscovery.value = data
      const selectable = data.candidates.filter(candidate => candidate.selectable)
      if (data.candidates.length === 1 && selectable.length === 1) {
        selectedCandidateRoot.value = selectable[0].sourceRoot
      }
      return true
    } catch {
      if (requestId !== requestSequence || importFile.value !== file) return false
      discoveryError.value = AGENT_SKILL_BUNDLE_DISCOVERY_ERROR
      return false
    } finally {
      if (requestId === requestSequence) discoveryLoading.value = false
    }
  }

  async function retry() {
    return importFile.value ? discover(importFile.value) : false
  }

  return {
    importFile,
    bundleDiscovery,
    selectedCandidateRoot,
    discoveryLoading,
    discoveryError,
    selectedBundleCandidate,
    canSubmitImport,
    reset,
    selectFile,
    retry,
  }
}
