import type { Ref } from 'vue'
import type { WorkflowDebugRunResult, WorkflowDebugSessionView } from '@/types/workflow'

export interface UseWorkflowStudioDebugSessionDeps {
  workflowId: Readonly<Ref<string>>
  workflowKeySlug: Readonly<Ref<string>>
  debugSession: Ref<WorkflowDebugSessionView | null>
  debugRunResult: Ref<WorkflowDebugRunResult | WorkflowDebugSessionView | null>
  selectedDebugStepIndex: Ref<number | null>
  currentDebugNodeId: Ref<string>
  currentTraceId: Ref<string>
  replayTraceInput: Ref<string>
  selectedRecentTraceId: Ref<string>
  refreshWorkflowNodeClasses: () => void
  getDebugSessionById: (sessionId: string) => Promise<WorkflowDebugSessionView>
}

export function useWorkflowStudioDebugSession({
  workflowId,
  workflowKeySlug,
  debugSession,
  debugRunResult,
  selectedDebugStepIndex,
  currentDebugNodeId,
  currentTraceId,
  replayTraceInput,
  selectedRecentTraceId,
  refreshWorkflowNodeClasses,
  getDebugSessionById,
}: UseWorkflowStudioDebugSessionDeps) {
  function debugSessionStorageKey() {
    return `workflow-studio-debug-session:${workflowId.value || workflowKeySlug.value || 'draft'}`
  }

  function rememberDebugSession(sessionId?: string) {
    if (!sessionId || typeof window === 'undefined') return
    window.localStorage.setItem(debugSessionStorageKey(), sessionId)
  }

  function forgetDebugSession() {
    if (typeof window === 'undefined') return
    window.localStorage.removeItem(debugSessionStorageKey())
  }

  function applyDebugSession(data: WorkflowDebugSessionView) {
    debugSession.value = data
    debugRunResult.value = data
    rememberDebugSession(data.sessionId)
  }

  function clearDebugSessionView() {
    debugSession.value = null
    debugRunResult.value = null
    selectedDebugStepIndex.value = null
    currentDebugNodeId.value = ''
    forgetDebugSession()
    refreshWorkflowNodeClasses()
  }

  async function loadStoredDebugSession() {
    if (typeof window === 'undefined' || debugSession.value) return
    const sessionId = window.localStorage.getItem(debugSessionStorageKey())
    if (!sessionId) return
    try {
      const data = await getDebugSessionById(sessionId)
      applyDebugSession(data)
      currentTraceId.value = data.traceId || ''
      replayTraceInput.value = data.traceId || ''
      selectedRecentTraceId.value = data.traceId || ''
      refreshWorkflowNodeClasses()
      if (data.steps?.length) {
        selectedDebugStepIndex.value = data.steps.length - 1
      }
    } catch {
      forgetDebugSession()
    }
  }

  return {
    rememberDebugSession,
    forgetDebugSession,
    applyDebugSession,
    clearDebugSessionView,
    loadStoredDebugSession,
  }
}
