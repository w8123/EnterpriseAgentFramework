import { computed, nextTick, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { CanvasEdge, CanvasNode } from '@/types/studio'
import { createDefaultNodeData } from '@/utils/studio'
import { useWorkflowStudioCanvasActions } from './useWorkflowStudioCanvasActions'
import { useWorkflowStudioHistory } from './useWorkflowStudioHistory'
import type { WorkflowNodeTraceState } from './workflowStudioTrace'

vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() } }))

function editingSession() {
  const nodes = ref<CanvasNode[]>([
    { id: 'route', type: 'condition', position: { x: 0, y: 0 }, data: createDefaultNodeData('condition', '分支') },
    { id: 'answer', type: 'answer', position: { x: 300, y: 0 }, data: createDefaultNodeData('answer', '回复') },
  ])
  const edges = ref<CanvasEdge[]>([{ id: 'done', source: 'route', target: 'answer', condition: 'route:done', class: 'flow-branch' }])
  const selectedNodeId = ref<string | null>(null)
  const selectedEdgeId = ref<string | null>(null)
  const trace = ref<Record<string, WorkflowNodeTraceState>>({})
  const hitEdges = ref(new Set<string>())
  const route = ref('')
  const canvas = useWorkflowStudioCanvasActions({
    nodes,
    edges,
    selectedNodeId,
    selectedEdgeId,
    studioReadOnly: ref(false),
    debugNodeId: ref(''),
    copiedNode: ref(null),
    currentDebugNodeId: ref(''),
    nodeTraceStates: computed(() => trace.value),
    canCopySelectedNode: computed(() => false),
    selectedNode: computed(() => null),
    selectedEdge: computed(() => null),
    workflowExecutionPath: computed(() => []),
    workflowHitEdgeKeys: computed(() => hitEdges.value),
    workflowExecutionSourceNodeIds: computed(() => new Set<string>()),
    getNodeDebugState: nodeId => trace.value[nodeId] || null,
    getLastRouteForNode: () => route.value,
    markCanvasDirty: vi.fn(),
    syncJsonFromCanvas: vi.fn(),
    activeTab: ref('visual'),
    propertyDetailOpen: ref(false),
    fitView: vi.fn(),
    nextTick,
    nodeTypes: ref([]),
    graphNodeTypeCapabilitiesLoaded: ref(true),
  })
  const historyPast = ref<string[]>([])
  const historyFuture = ref<string[]>([])
  const history = useWorkflowStudioHistory({
    historyPast,
    historyFuture,
    historyApplying: ref(false),
    historyReady: ref(true),
    nodes,
    edges,
    selectedNodeId,
    selectedEdgeId,
    visualDirty: ref(false),
    editGeneration: ref(0),
    stripTransientNodeClasses: canvas.stripTransientNodeClasses,
    serializeWorkflowEdge: canvas.serializeWorkflowEdge,
    decorateWorkflowNode: canvas.decorateWorkflowNode,
    decorateWorkflowEdge: canvas.decorateWorkflowEdge,
    syncJsonFromCanvas: vi.fn(),
    nextTick,
  })
  canvas.refreshWorkflowNodeClasses()
  history.pushHistorySnapshot()
  return { nodes, edges, trace, hitEdges, route, canvas, history, historyPast, historyFuture }
}

describe('Workflow Studio editing history', () => {
  it('keeps replay styling and selection out of saved canvas data and undo history', () => {
    const session = editingSession()
    const initialCanvas = JSON.stringify(session.canvas.canvasSnapshot())
    const initialHistory = session.history.currentSnapshotText()
    session.historyFuture.value = ['redo-entry']

    session.trace.value = { route: { nodeId: 'route', status: 'success', route: 'done' } }
    session.route.value = 'done'
    session.hitEdges.value.add('route->answer')
    session.canvas.refreshWorkflowNodeClasses()
    Object.assign(session.edges.value[0], {
      selected: true,
      sourceNode: session.nodes.value[0],
      targetNode: session.nodes.value[1],
      sourceX: 240,
      sourceY: 98.5,
      targetX: 300,
      targetY: 99,
    })

    expect(session.edges.value[0].class).toContain('edge-route-hit')
    expect(session.history.currentSnapshotText()).toBe(initialHistory)
    expect(JSON.stringify(session.canvas.canvasSnapshot())).toBe(initialCanvas)
    expect(session.canvas.canvasSnapshot().edges[0].class).toBe('flow-branch')
    session.history.pushHistorySnapshot()
    expect(session.historyPast.value).toEqual([initialHistory])
    expect(session.historyFuture.value).toEqual(['redo-entry'])
  })

  it('still records a real routing edit and restores its condition on undo', async () => {
    const session = editingSession()
    const initialHistory = session.history.currentSnapshotText()
    session.historyFuture.value = ['old-redo']

    session.edges.value[0].condition = 'route:retry'
    session.canvas.refreshWorkflowNodeClasses()
    session.history.pushHistorySnapshot()

    expect(session.history.currentSnapshotText()).not.toBe(initialHistory)
    expect(session.historyPast.value).toHaveLength(2)
    expect(session.historyFuture.value).toEqual([])
    session.history.undoCanvas()
    await nextTick()
    expect(session.edges.value[0].condition).toBe('route:done')
  })
})
