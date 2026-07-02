import { ElMessage } from 'element-plus'
import type { ComputedRef, Ref } from 'vue'
import type { CanvasEdge, CanvasNode, CanvasSnapshot } from '@/types/studio'
import { normalizeCanvasEdgeHandles } from '@/utils/studio'

const TRANSIENT_NODE_CLASSES = [
  'run-current',
  'run-success',
  'run-error',
  'run-waiting',
  'run-running',
] as const

const DECORATED_NODE_CLASSES = [
  'workflow-node-collapsed',
  ...TRANSIENT_NODE_CLASSES,
] as const

export interface WorkflowNodeTraceState {
  nodeId: string
  status: 'success' | 'error' | 'waiting' | 'running'
  elapsedMs?: number
  spanType?: string
  toolName?: string
  input?: string
  output?: string
  errorCode?: string
  route?: string
  interactionId?: string
  createdAt?: string
}

export function normalizeNodeClassNames(value: CanvasNode['class']): string[] {
  if (!value) return []
  if (Array.isArray(value)) return value.filter(Boolean)
  if (typeof value === 'string') return value.split(/\s+/).filter(Boolean)
  return Object.entries(value)
    .filter(([, enabled]) => enabled)
    .map(([name]) => name)
}

export function stripTransientNodeClasses(node: CanvasNode): CanvasNode {
  const classes = normalizeNodeClassNames(node.class)
    .filter((name) => !TRANSIENT_NODE_CLASSES.includes(name as typeof TRANSIENT_NODE_CLASSES[number]))
  return {
    ...node,
    class: classes.length ? classes : undefined,
  }
}

export function cloneCanvasNode(node: CanvasNode): CanvasNode {
  return JSON.parse(JSON.stringify(node)) as CanvasNode
}

export function isDynamicCondition(condition?: string) {
  const normalized = (condition || '').trim().toLowerCase()
  return !!normalized && normalized !== 'always' && normalized !== 'default'
}

export function connectionCondition(source?: CanvasNode | null, sourceHandle?: string) {
  if (!sourceHandle) return 'always'
  if (['condition', 'classifier', 'approval', 'loop'].includes(source?.data.kind || '')) {
    const normalized = sourceHandle.trim()
    if (!normalized) return 'always'
    return normalized === 'else' || normalized === 'default' ? 'else' : `route:${normalized}`
  }
  return 'always'
}

export function isRouteCondition(condition?: string) {
  const normalized = (condition || '').trim().toLowerCase()
  return normalized === 'else' || normalized === 'default' || normalized.startsWith('route:')
}

export function isSupportedCanvasCondition(condition?: string) {
  const normalized = (condition || '').trim().toLowerCase()
  if (!normalized) return true
  if (['always', 'default', 'else', 'success', 'error', 'failure', 'empty', 'not_empty'].includes(normalized)) {
    return true
  }
  return normalized.startsWith('contains:')
    || normalized.startsWith('not_contains:')
    || normalized.startsWith('equals:')
    || normalized.startsWith('not_equals:')
    || normalized.startsWith('route:')
}

export function previewEdgeLabel(edge: CanvasEdge) {
  const condition = edge.condition || edge.label || 'always'
  if (condition === 'always') return '连线'
  if (condition === 'else') return '默认分支'
  if (condition.startsWith('route:')) return `分支：${condition.slice('route:'.length) || edge.sourceHandle || '未命名'}`
  return condition
}

export interface UseWorkflowStudioCanvasActionsDeps {
  studioReadOnly: Readonly<Ref<boolean>>
  nodes: Ref<CanvasNode[]>
  edges: Ref<CanvasEdge[]>
  selectedNodeId: Ref<string | null>
  selectedEdgeId: Ref<string | null>
  debugNodeId: Ref<string>
  copiedNode: Ref<CanvasNode | null>
  currentDebugNodeId: Ref<string>
  nodeTraceStates: ComputedRef<Record<string, WorkflowNodeTraceState>>
  canCopySelectedNode: ComputedRef<boolean>
  selectedNode: ComputedRef<CanvasNode | null>
  selectedEdge: ComputedRef<CanvasEdge | null>
  workflowPath: ComputedRef<Array<{ fromNodeId?: string; toNodeId?: string; route?: string; condition?: string }>>
  workflowHitEdgeKeys: ComputedRef<Set<string>>
  workflowPathSourceNodeIds: ComputedRef<Set<string>>
  getNodeDebugState: (nodeId: string) => WorkflowNodeTraceState | null
  getLastRouteForNode: (nodeId: string) => string
  markCanvasDirty: () => void
  syncJsonFromCanvas: () => void
  activeTab: Ref<string>
  propertyDetailOpen: Ref<boolean>
  fitView: (options?: { padding?: number; duration?: number }) => Promise<boolean> | void
  nextTick: (fn?: () => void) => Promise<void>
}

export function useWorkflowStudioCanvasActions({
  studioReadOnly,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  debugNodeId,
  copiedNode,
  currentDebugNodeId,
  nodeTraceStates,
  canCopySelectedNode,
  selectedNode,
  selectedEdge,
  workflowPath,
  workflowHitEdgeKeys,
  workflowPathSourceNodeIds,
  getNodeDebugState,
  getLastRouteForNode,
  markCanvasDirty,
  syncJsonFromCanvas,
  activeTab,
  propertyDetailOpen,
  fitView,
  nextTick,
}: UseWorkflowStudioCanvasActionsDeps) {
  function edgeKey(source?: string, target?: string) {
    return `${source || ''}->${target || ''}`
  }

  function edgeDisplayLabel(condition?: string, edge?: CanvasEdge | null) {
    const raw = (condition || '').trim()
    const normalized = raw.toLowerCase()
    const source = edge ? nodes.value.find((node) => node.id === edge.source) : null
    if (!raw || normalized === 'always' || normalized === 'default') {
      return source?.data.kind === 'condition' || source?.data.kind === 'classifier' || source?.data.kind === 'approval' || source?.data.kind === 'loop' ? '默认' : ''
    }
    const labels: Record<string, string> = {
      success: '成功',
      error: '失败',
      failure: '失败',
      else: '否则',
      empty: '为空',
      not_empty: '非空',
    }
    if (labels[normalized]) return labels[normalized]
    if (normalized.startsWith('route:')) return raw.slice('route:'.length).trim() || '分支'
    if (normalized.startsWith('contains:')) return `包含 ${raw.slice('contains:'.length).trim()}`
    if (normalized.startsWith('not_contains:')) return `不含 ${raw.slice('not_contains:'.length).trim()}`
    if (normalized.startsWith('equals:')) return `等于 ${raw.slice('equals:'.length).trim()}`
    if (normalized.startsWith('not_equals:')) return `不等于 ${raw.slice('not_equals:'.length).trim()}`
    return raw
  }

  function edgeRuntimeClass(edge: CanvasEdge, rawCondition?: string) {
    const condition = (rawCondition || edge.condition || edge.label || '').trim()
    const source = nodes.value.find((node) => node.id === edge.source)
    const route = getLastRouteForNode(edge.source)
    const classes: string[] = []
    const key = edgeKey(edge.source, edge.target)
    if (workflowHitEdgeKeys.value.has(key)) {
      classes.push('edge-route-hit')
    } else if (workflowPath.value.length && workflowPathSourceNodeIds.value.has(edge.source)) {
      classes.push('edge-route-miss')
    }
    if ((source?.data.kind === 'condition' || source?.data.kind === 'classifier' || source?.data.kind === 'approval' || source?.data.kind === 'loop') && route) {
      const expected = condition.toLowerCase().startsWith('route:')
        ? condition.slice('route:'.length).trim()
        : condition === 'else' || condition === 'default'
          ? 'else'
          : ''
      if (expected) {
        classes.push(expected === route ? 'edge-route-hit' : 'edge-route-miss')
      }
    }
    const state = getNodeDebugState(edge.source)
    if (state?.status === 'error' && ['error', 'failure'].includes(condition.toLowerCase())) {
      classes.push('edge-route-hit')
    }
    if (state?.status === 'success' && condition.toLowerCase() === 'success') {
      classes.push('edge-route-hit')
    }
    return classes.join(' ')
  }

  function decorateWorkflowEdge(edge: CanvasEdge): CanvasEdge {
    const nodesById = new Map(nodes.value.map((node) => [node.id, node]))
    const normalized = normalizeCanvasEdgeHandles(edge, nodesById)
    const condition = normalized.condition || normalized.label || 'always'
    return {
      ...normalized,
      condition,
      type: normalized.type || 'smoothstep',
      markerEnd: normalized.markerEnd || 'arrowclosed',
      interactionWidth: normalized.interactionWidth || 18,
      animated: normalized.animated ?? isDynamicCondition(condition),
      label: edgeDisplayLabel(condition, normalized) || undefined,
      class: edgeRuntimeClass(normalized, condition),
    }
  }

  function decorateWorkflowNode(node: CanvasNode): CanvasNode {
    const classes = normalizeNodeClassNames(node.class)
      .filter((name) => !DECORATED_NODE_CLASSES.includes(name as typeof DECORATED_NODE_CLASSES[number]))
    const trace = nodeTraceStates.value[node.id]
    if (node.data.collapsed) classes.push('workflow-node-collapsed')
    if (currentDebugNodeId.value === node.id) classes.push('run-current')
    if (trace) classes.push(`run-${trace.status}`)
    return {
      ...node,
      class: classes.length ? classes : undefined,
    }
  }

  function decorateWorkflowEdges() {
    edges.value = edges.value.map(decorateWorkflowEdge)
  }

  function refreshWorkflowNodeClasses() {
    nodes.value = nodes.value.map(decorateWorkflowNode)
    decorateWorkflowEdges()
  }

  function canvasSnapshot(): CanvasSnapshot {
    return {
      version: 2,
      nodes: nodes.value.map(stripTransientNodeClasses),
      edges: edges.value.map(decorateWorkflowEdge),
    }
  }

  function onConnect(connection: {
    source?: string | null
    target?: string | null
    sourceHandle?: string | null
    targetHandle?: string | null
  }) {
    if (studioReadOnly.value || !connection.source || !connection.target) return
    const sourceNode = nodes.value.find((node) => node.id === connection.source)
    const sourceHandle = connection.sourceHandle || undefined
    const targetHandle = connection.targetHandle || undefined
    const condition = connectionCondition(sourceNode, sourceHandle)
    const edge = decorateWorkflowEdge({
      id: `e-${connection.source}-${connection.target}-${Date.now()}`,
      source: connection.source,
      target: connection.target,
      sourceHandle,
      targetHandle,
      condition,
      label: condition,
    })
    edges.value.push(edge)
    selectedEdgeId.value = edge.id
    selectedNodeId.value = null
    markCanvasDirty()
  }

  function copySelectedNode() {
    if (!canCopySelectedNode.value || !selectedNode.value) return
    copiedNode.value = cloneCanvasNode(selectedNode.value)
    ElMessage.success('节点已复制')
  }

  function pasteCopiedNode() {
    if (studioReadOnly.value) return
    if (!copiedNode.value) return
    const copy = cloneCanvasNode(copiedNode.value)
    const id = `${copy.data.kind}-${Date.now()}`
    copy.id = id
    copy.position = {
      x: copy.position.x + 48,
      y: copy.position.y + 48,
    }
    copy.data = {
      ...copy.data,
      label: `${copy.data.label || copy.data.kind} Copy`,
      source: 'CANVAS',
    }
    nodes.value.push(decorateWorkflowNode(copy))
    selectedNodeId.value = id
    selectedEdgeId.value = null
    debugNodeId.value = id
    markCanvasDirty()
    syncJsonFromCanvas()
    activeTab.value = 'visual'
    nextTick(() => fitView({ padding: 0.2, duration: 180 }))
  }

  function deleteSelection() {
    if (studioReadOnly.value) return
    if (selectedEdge.value) {
      edges.value = edges.value.filter((edge) => edge.id !== selectedEdge.value?.id)
      selectedEdgeId.value = null
      markCanvasDirty()
      syncJsonFromCanvas()
      return
    }
    deleteSelectedNode()
  }

  function deleteSelectedNode() {
    if (studioReadOnly.value) return
    if (!selectedNode.value) return
    if (['start', 'end'].includes(selectedNode.value.data.kind)) {
      ElMessage.warning('开始和结束节点不能删除')
      return
    }
    const id = selectedNode.value.id
    nodes.value = nodes.value.filter((node) => node.id !== id)
    edges.value = edges.value.filter((edge) => edge.source !== id && edge.target !== id)
    selectedNodeId.value = null
    selectedEdgeId.value = null
    propertyDetailOpen.value = false
    markCanvasDirty()
    syncJsonFromCanvas()
  }

  function syncSelectedEdgeLabel() {
    if (studioReadOnly.value) return
    if (!selectedEdge.value) return
    const condition = selectedEdge.value.condition?.trim() || ''
    selectedEdge.value.label = edgeDisplayLabel(condition, selectedEdge.value) || undefined
    selectedEdge.value.animated = isDynamicCondition(condition)
    markCanvasDirty()
  }

  function applySelectedEdgeCondition(condition: string) {
    if (studioReadOnly.value) return
    if (!selectedEdge.value) return
    selectedEdge.value.condition = condition
    syncSelectedEdgeLabel()
  }

  return {
    normalizeNodeClassNames,
    stripTransientNodeClasses,
    cloneCanvasNode,
    isDynamicCondition,
    connectionCondition,
    isRouteCondition,
    isSupportedCanvasCondition,
    previewEdgeLabel,
    edgeDisplayLabel,
    decorateWorkflowEdge,
    decorateWorkflowNode,
    decorateWorkflowEdges,
    refreshWorkflowNodeClasses,
    canvasSnapshot,
    onConnect,
    copySelectedNode,
    pasteCopiedNode,
    deleteSelection,
    deleteSelectedNode,
    syncSelectedEdgeLabel,
    applySelectedEdgeCondition,
  }
}
