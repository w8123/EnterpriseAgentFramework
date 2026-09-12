import type { WorkflowNodeTraceState } from './workflowStudioTrace'
import { ElMessage } from 'element-plus'
import { normalizeClass } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import type { WorkflowGraphNodeTypeDescriptor } from '@/types/agent'
import type { CanvasEdge, CanvasNode, CanvasSnapshot } from '@/types/studio'
import { normalizeCanvasEdgeHandles } from '@/utils/studio'
import { loopOwnerByBodyNodeId } from '@/utils/studioLoop'
import { resolveStudioNodeCreation } from '@/utils/studioNodeRegistry'

const TRANSIENT_NODE_CLASSES = [
  'run-current',
  'run-success',
  'run-error',
  'run-waiting',
  'run-running',
] as const

const DECORATED_NODE_CLASSES = [
  'workflow-node-collapsed',
  'loop-body-member',
  ...TRANSIENT_NODE_CLASSES,
] as const

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
  const serializable = {
    ...node,
    class: classes.length ? classes : undefined,
  } as CanvasNode & Record<string, unknown>
  delete serializable.selected
  delete serializable.dragging
  delete serializable.resizing
  delete serializable.dimensions
  delete serializable.computedPosition
  delete serializable.handleBounds
  return serializable
}

export function cloneCanvasNode(node: CanvasNode): CanvasNode {
  return JSON.parse(JSON.stringify(node)) as CanvasNode
}

export function serializeCanvasEdge(edge: CanvasEdge): CanvasEdge {
  const classes = normalizeClass(edge.class).split(/\s+/)
    .filter(name => name && name !== 'edge-route-hit' && name !== 'edge-route-miss')
  // Vue Flow adds sourceNode/targetNode references and measured coordinates to rendered edges.
  // Persist only the CanvasEdge contract so replay and layout measurements cannot edit the draft.
  return {
    id: edge.id,
    source: edge.source,
    target: edge.target,
    label: edge.label,
    condition: edge.condition,
    sourceHandle: edge.sourceHandle,
    targetHandle: edge.targetHandle,
    priority: edge.priority,
    type: edge.type,
    class: classes.length ? classes.join(' ') : undefined,
    animated: edge.animated,
    markerEnd: edge.markerEnd,
    interactionWidth: edge.interactionWidth,
  }
}

export function isDynamicCondition(condition?: string) {
  const normalized = (condition || '').trim().toLowerCase()
  return !!normalized && normalized !== 'always' && normalized !== 'default'
}

function isRequiredKnowledgeNode(source?: CanvasNode | null) {
  return source?.data.kind === 'knowledge'
    && source.data.knowledgeConfig?.evidencePolicy === 'REQUIRED'
}

export function connectionCondition(source?: CanvasNode | null, sourceHandle?: string) {
  // FOREACH v1: LOOP has a single linear outgoing edge.
  if (source?.data.kind === 'loop') return 'always'
  if (!sourceHandle) return 'always'
  if (['condition', 'classifier', 'approval'].includes(source?.data.kind || '') || isRequiredKnowledgeNode(source)) {
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

function isKnownBareBranchRoute(source: CanvasNode | null | undefined, route: string) {
  if (!source || !route) return false
  if (source.data.kind === 'condition') {
    const groups = source.data.conditionConfig?.groups || []
    const defaultRoute = (source.data.conditionConfig?.defaultRoute || 'else').trim()
    return groups.some((group) => group.id?.trim() === route) || defaultRoute === route
  }
  if (source.data.kind === 'classifier') {
    const classes = source.data.classifierConfig?.classes || []
    const defaultRoute = (source.data.classifierConfig?.defaultRoute || 'else').trim()
    return classes.some((item) => item.id?.trim() === route) || defaultRoute === route
  }
  if (isRequiredKnowledgeNode(source)) {
    return ['evidence', 'no_evidence'].includes(route)
  }
  return source.data.kind === 'approval' && ['approved', 'rejected', 'timeout'].includes(route)
}

export function isSupportedCanvasCondition(condition?: string, source?: CanvasNode | null) {
  const normalized = (condition || '').trim().toLowerCase()
  if (!normalized) return true
  if (['always', 'default', 'else', 'success', 'error', 'failure', 'empty', 'not_empty'].includes(normalized)) {
    return true
  }
  if (normalized.startsWith('contains:')
    || normalized.startsWith('not_contains:')
    || normalized.startsWith('equals:')
    || normalized.startsWith('not_equals:')
    || normalized.startsWith('route:')) {
    return true
  }
  // Older workflows and some AI-generated proposals store a branch route as
  // its bare group id (for example `approved`) while the canvas-created form
  // is `route:approved`. Both forms are accepted by the runtime; recognise a
  // valid source handle here so the Studio does not report a false warning.
  return isKnownBareBranchRoute(source, (condition || '').trim())
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
  workflowExecutionPath: ComputedRef<Array<{ fromNodeId?: string; toNodeId?: string; route?: string; condition?: string }>>
  workflowHitEdgeKeys: ComputedRef<Set<string>>
  workflowExecutionSourceNodeIds: ComputedRef<Set<string>>
  getNodeDebugState: (nodeId: string) => WorkflowNodeTraceState | null
  getLastRouteForNode: (nodeId: string) => string
  markCanvasDirty: () => void
  syncJsonFromCanvas: () => void
  activeTab: Ref<string>
  propertyDetailOpen: Ref<boolean>
  fitView: (options?: { padding?: number; duration?: number }) => Promise<boolean> | void
  nextTick: (fn?: () => void) => Promise<void>
  nodeTypes: Ref<WorkflowGraphNodeTypeDescriptor[]>
  graphNodeTypeCapabilitiesLoaded: Ref<boolean>
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
  workflowExecutionPath,
  workflowHitEdgeKeys,
  workflowExecutionSourceNodeIds,
  getNodeDebugState,
  getLastRouteForNode,
  markCanvasDirty,
  syncJsonFromCanvas,
  activeTab,
  propertyDetailOpen,
  fitView,
  nextTick,
  nodeTypes,
  graphNodeTypeCapabilitiesLoaded,
}: UseWorkflowStudioCanvasActionsDeps) {
  function edgeKey(source?: string, target?: string) {
    return `${source || ''}->${target || ''}`
  }

  function edgeDisplayLabel(condition?: string, edge?: CanvasEdge | null) {
    const raw = (condition || '').trim()
    const normalized = raw.toLowerCase()
    const source = edge ? nodes.value.find((node) => node.id === edge.source) : null
    if (!raw || normalized === 'always' || normalized === 'default') {
      return source?.data.kind === 'condition'
        || source?.data.kind === 'classifier'
        || source?.data.kind === 'approval'
        || source?.data.kind === 'loop'
        || isRequiredKnowledgeNode(source)
        ? '默认'
        : ''
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
    } else if (workflowExecutionPath.value.length && workflowExecutionSourceNodeIds.value.has(edge.source)) {
      classes.push('edge-route-miss')
    }
    if ((source?.data.kind === 'condition'
      || source?.data.kind === 'classifier'
      || source?.data.kind === 'approval'
      || isRequiredKnowledgeNode(source)) && route) {
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

  function normalizeWorkflowEdge(edge: CanvasEdge): CanvasEdge {
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
    }
  }

  function serializeWorkflowEdge(edge: CanvasEdge): CanvasEdge {
    return serializeCanvasEdge(normalizeWorkflowEdge(edge))
  }

  function decorateWorkflowEdge(edge: CanvasEdge): CanvasEdge {
    const normalized = normalizeWorkflowEdge(edge)
    return {
      ...normalized,
      class: normalizeClass([
        serializeCanvasEdge(normalized).class,
        edgeRuntimeClass(normalized, normalized.condition),
      ]) || undefined,
    }
  }

  function decorateWorkflowNode(node: CanvasNode): CanvasNode {
    const classes = normalizeNodeClassNames(node.class)
      .filter((name) => !DECORATED_NODE_CLASSES.includes(name as typeof DECORATED_NODE_CLASSES[number]))
    const trace = nodeTraceStates.value[node.id]
    if (node.data.collapsed) classes.push('workflow-node-collapsed')
    if (currentDebugNodeId.value === node.id) classes.push('run-current')
    if (trace) classes.push(`run-${trace.status}`)
    const loopOwner = loopOwnerByBodyNodeId(nodes.value).get(node.id)
    if (loopOwner) classes.push('loop-body-member')
    const nextData = { ...node.data }
    if (loopOwner) nextData.loopOwnerId = loopOwner
    else delete nextData.loopOwnerId
    return {
      ...node,
      class: classes.length ? classes : undefined,
      data: nextData,
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
      edges: edges.value.map(serializeWorkflowEdge),
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
    const decision = resolveStudioNodeCreation(
      copiedNode.value.data.kind,
      nodeTypes.value,
      graphNodeTypeCapabilitiesLoaded.value,
      copiedNode.value.data.kind === 'interaction'
        ? copiedNode.value.data.interactionConfig?.interactionType
        : undefined,
    )
    if (!decision.allowed) {
      ElMessage.warning(decision.reason || '当前节点类型不可新增')
      return
    }
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
    nodes.value = nodes.value
      .filter((node) => node.id !== id)
      .map((node) => {
        if (node.data.kind !== 'loop' || !node.data.loopConfig) return node
        const loop = node.data.loopConfig
        const bodyNodeIds = (loop.bodyNodeIds || []).filter((bodyId) => bodyId !== id)
        const bodyEntry = loop.bodyEntry === id ? (bodyNodeIds[0] || '') : loop.bodyEntry
        const bodyExit = loop.bodyExit === id
          ? (bodyNodeIds.includes(loop.bodyExit) ? loop.bodyExit : (bodyNodeIds[bodyNodeIds.length - 1] || bodyEntry || ''))
          : loop.bodyExit
        return {
          ...node,
          data: {
            ...node.data,
            loopConfig: {
              ...loop,
              bodyNodeIds,
              bodyEntry,
              bodyExit,
            },
          },
        }
      })
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
    isRouteCondition,
    isSupportedCanvasCondition,
    previewEdgeLabel,
    edgeDisplayLabel,
    decorateWorkflowEdge,
    decorateWorkflowNode,
    refreshWorkflowNodeClasses,
    canvasSnapshot,
    serializeWorkflowEdge,
    onConnect,
    copySelectedNode,
    pasteCopiedNode,
    deleteSelection,
    deleteSelectedNode,
    syncSelectedEdgeLabel,
    applySelectedEdgeCondition,
  }
}
