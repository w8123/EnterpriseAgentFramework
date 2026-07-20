import { computed, ref, type Ref } from 'vue'

export type PanelValidationState = {
  valid: boolean
  message?: string
}

/**
 * Collects node-panel local validation (e.g. VARIABLE_ASSIGN illegal JSON)
 * so Save / Publish / AI Apply can fail closed before syncing GraphSpec.
 */
export function useWorkflowStudioPanelValidation() {
  const panelValidationByNodeId = ref<Record<string, PanelValidationState>>({})

  function setPanelValidation(nodeId: string, state: PanelValidationState) {
    if (!nodeId) return
    if (state.valid) {
      const next = { ...panelValidationByNodeId.value }
      delete next[nodeId]
      panelValidationByNodeId.value = next
      return
    }
    panelValidationByNodeId.value = {
      ...panelValidationByNodeId.value,
      [nodeId]: { valid: false, message: state.message || '节点配置无效' },
    }
  }

  function clearPanelValidation(nodeId?: string) {
    if (!nodeId) {
      panelValidationByNodeId.value = {}
      return
    }
    const next = { ...panelValidationByNodeId.value }
    delete next[nodeId]
    panelValidationByNodeId.value = next
  }

  function prunePanelValidation(nodeIds: Iterable<string>) {
    const currentNodeIds = new Set(nodeIds)
    const next = Object.fromEntries(
      Object.entries(panelValidationByNodeId.value)
        .filter(([nodeId]) => currentNodeIds.has(nodeId)),
    ) as Record<string, PanelValidationState>
    if (Object.keys(next).length !== Object.keys(panelValidationByNodeId.value).length) {
      panelValidationByNodeId.value = next
    }
  }

  const panelValidationErrors = computed(() =>
    Object.entries(panelValidationByNodeId.value)
      .filter(([, state]) => !state.valid)
      .map(([nodeId, state]) => ({
        nodeId,
        message: state.message || '节点配置无效',
      })),
  )

  const hasPanelValidationErrors = computed(() => panelValidationErrors.value.length > 0)

  function ensurePanelValidationClear() {
    return !hasPanelValidationErrors.value
  }

  function firstPanelValidationMessage(): string | null {
    const first = panelValidationErrors.value[0]
    return first ? `节点 ${first.nodeId}: ${first.message}` : null
  }

  return {
    panelValidationByNodeId: panelValidationByNodeId as Ref<Record<string, PanelValidationState>>,
    panelValidationErrors,
    hasPanelValidationErrors,
    setPanelValidation,
    clearPanelValidation,
    prunePanelValidation,
    ensurePanelValidationClear,
    firstPanelValidationMessage,
  }
}
