import type { Ref } from 'vue'
import type { ChatResponse } from '@/types/chat'
import type { WorkflowStudioState } from '@/types/workflow'
import type { WorkflowDebugSessionView, WorkflowDebugRunResult, WorkflowDraftGenerationResult, WorkflowDraftEditResult, WorkflowNodeDebugResult } from '@/types/workflow'
import type { CanvasNode, CanvasEdge } from '@/types/studio'

export interface UseWorkflowStudioSessionLifecycleDeps {
  closeCanvasSearch: () => void
  selectedNodeId: Ref<string | null>
  selectedEdgeId: Ref<string | null>
  debugNodeId: Ref<string>
  currentDebugNodeId: Ref<string>
  selectedDebugStepIndex: Ref<number | null>
  nodeDebugResult: Ref<WorkflowNodeDebugResult | null>
  debugRunResult: Ref<WorkflowDebugRunResult | WorkflowDebugSessionView | null>
  debugSession: Ref<WorkflowDebugSessionView | null>
  debugResult: Ref<ChatResponse | null>
  aiDraftPreview: Ref<WorkflowDraftGenerationResult | null>
  aiEditPreview: Ref<WorkflowDraftEditResult | null>
  validation: Ref<unknown>
  visualDirty: Ref<boolean>
  historyPast: Ref<string[]>
  historyFuture: Ref<string[]>
  historyReady: Ref<boolean>
  studio: Ref<WorkflowStudioState | null>
  graphSpecJson: Ref<string>
  canvasJson: Ref<string>
  nodes: Ref<CanvasNode[]>
  edges: Ref<CanvasEdge[]>
  emptyGraphSpecJson: string
  emptyCanvasJson: string
  resetHistorySnapshot: () => void
  forgetDebugSession: () => void
}

export function useWorkflowStudioSessionLifecycle({
  closeCanvasSearch,
  selectedNodeId,
  selectedEdgeId,
  debugNodeId,
  currentDebugNodeId,
  selectedDebugStepIndex,
  nodeDebugResult,
  debugRunResult,
  debugSession,
  debugResult,
  aiDraftPreview,
  aiEditPreview,
  validation,
  visualDirty,
  historyPast,
  historyFuture,
  historyReady,
  studio,
  graphSpecJson,
  canvasJson,
  nodes,
  edges,
  emptyGraphSpecJson,
  emptyCanvasJson,
  resetHistorySnapshot,
  forgetDebugSession,
}: UseWorkflowStudioSessionLifecycleDeps) {
  function resetWorkflowSessionState() {
    closeCanvasSearch()
    selectedNodeId.value = null
    selectedEdgeId.value = null
    debugNodeId.value = ''
    currentDebugNodeId.value = ''
    selectedDebugStepIndex.value = null
    nodeDebugResult.value = null
    debugRunResult.value = null
    debugSession.value = null
    debugResult.value = null
    forgetDebugSession()
    aiDraftPreview.value = null
    aiEditPreview.value = null
    validation.value = null
    visualDirty.value = false
    historyPast.value = []
    historyFuture.value = []
    historyReady.value = false
  }

  function clearWorkflowDocumentState() {
    studio.value = null
    graphSpecJson.value = emptyGraphSpecJson
    canvasJson.value = emptyCanvasJson
    nodes.value = []
    edges.value = []
    visualDirty.value = false
    resetHistorySnapshot()
  }

  return {
    resetWorkflowSessionState,
    clearWorkflowDocumentState,
  }
}
