import { computed, type ComputedRef, type Ref } from 'vue'
import type { CanvasEdge, CanvasNode } from '@/types/studio'

export interface UseWorkflowStudioHistoryDeps {
  historyPast: Ref<string[]>
  historyFuture: Ref<string[]>
  historyApplying: Ref<boolean>
  historyReady: Ref<boolean>
  nodes: Ref<CanvasNode[]>
  edges: Ref<CanvasEdge[]>
  selectedNodeId: Ref<string | null>
  selectedEdgeId: Ref<string | null>
  visualDirty: Ref<boolean>
  stripTransientNodeClasses: (node: CanvasNode) => CanvasNode
  decorateWorkflowNode: (node: CanvasNode) => CanvasNode
  decorateWorkflowEdge: (edge: CanvasEdge) => CanvasEdge
  syncJsonFromCanvas: () => void
  nextTick: (fn?: () => void) => Promise<void>
}

export function useWorkflowStudioHistory({
  historyPast,
  historyFuture,
  historyApplying,
  historyReady,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  visualDirty,
  stripTransientNodeClasses,
  decorateWorkflowNode,
  decorateWorkflowEdge,
  syncJsonFromCanvas,
  nextTick,
}: UseWorkflowStudioHistoryDeps) {
  const canUndo: ComputedRef<boolean> = computed(() => historyPast.value.length > 1)
  const canRedo: ComputedRef<boolean> = computed(() => historyFuture.value.length > 0)

  function currentSnapshotText() {
    return JSON.stringify({
      nodes: nodes.value.map(stripTransientNodeClasses),
      edges: edges.value.map(decorateWorkflowEdge),
    })
  }

  function restoreSnapshotText(text: string) {
    historyApplying.value = true
    try {
      const parsed = JSON.parse(text) as { nodes?: CanvasNode[]; edges?: CanvasEdge[] }
      nodes.value = (parsed.nodes || []).map(decorateWorkflowNode)
      edges.value = (parsed.edges || []).map(decorateWorkflowEdge)
      selectedNodeId.value = null
      selectedEdgeId.value = null
      syncJsonFromCanvas()
      visualDirty.value = true
    } finally {
      nextTick(() => {
        historyApplying.value = false
      })
    }
  }

  function pushHistorySnapshot() {
    const text = currentSnapshotText()
    if (historyPast.value[historyPast.value.length - 1] === text) return
    historyPast.value.push(text)
    if (historyPast.value.length > 80) historyPast.value.shift()
    historyFuture.value = []
  }

  function resetHistorySnapshot() {
    historyReady.value = false
    historyPast.value = []
    historyFuture.value = []
    nextTick(() => {
      pushHistorySnapshot()
      historyReady.value = true
    })
  }

  function undoCanvas() {
    if (!canUndo.value) return
    const current = historyPast.value.pop()
    if (current) historyFuture.value.unshift(current)
    const previous = historyPast.value[historyPast.value.length - 1]
    if (previous) restoreSnapshotText(previous)
  }

  function redoCanvas() {
    const next = historyFuture.value.shift()
    if (!next) return
    historyPast.value.push(next)
    restoreSnapshotText(next)
  }

  return {
    canUndo,
    canRedo,
    currentSnapshotText,
    pushHistorySnapshot,
    resetHistorySnapshot,
    undoCanvas,
    redoCanvas,
  }
}
