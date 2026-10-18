import { ref, type Ref } from 'vue'
import { listWorkflowAgentReferences } from '@/api/agentWorkflowFreshness'
import type { WorkflowAgentReference } from '@/types/agent'

export interface UseWorkflowStudioEvalLinkDeps {
  workflowId: Ref<string>
  openPath: (path: string) => void
}

/**
 * Evaluation datasets are saved per Agent in the run center, so Studio only links there:
 * straight to the Agent's eval page when one Agent uses this Workflow, otherwise a picker.
 */
export function useWorkflowStudioEvalLink(deps: UseWorkflowStudioEvalLinkDeps) {
  const evalTargetOpen = ref(false)
  const evalTargetsLoading = ref(false)
  const evalTargetsLoadError = ref(false)
  const evalTargets = ref<WorkflowAgentReference[]>([])
  const hiddenEvalTargetCount = ref(0)

  function openAgentEval(agentId: string) {
    evalTargetOpen.value = false
    deps.openPath(`/runops/evals/${encodeURIComponent(agentId)}`)
  }

  async function openWorkflowEval() {
    if (!deps.workflowId.value) return
    evalTargetsLoading.value = true
    evalTargetsLoadError.value = false
    try {
      const { data } = await listWorkflowAgentReferences(deps.workflowId.value, { errorFeedback: 'local' })
      evalTargets.value = Array.isArray(data?.agents) ? data.agents : []
      hiddenEvalTargetCount.value = Number(data?.hiddenAgentCount || 0)
    } catch {
      evalTargets.value = []
      hiddenEvalTargetCount.value = 0
      evalTargetsLoadError.value = true
    } finally {
      evalTargetsLoading.value = false
    }
    if (!evalTargetsLoadError.value && evalTargets.value.length === 1 && !hiddenEvalTargetCount.value) {
      openAgentEval(evalTargets.value[0].agentId)
      return
    }
    evalTargetOpen.value = true
  }

  return {
    evalTargetOpen,
    evalTargetsLoading,
    evalTargetsLoadError,
    evalTargets,
    hiddenEvalTargetCount,
    openWorkflowEval,
    openAgentEval,
  }
}
