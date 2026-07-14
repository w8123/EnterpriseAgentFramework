import type { ELK, ElkNode, ElkPort } from 'elkjs/lib/elk-api'
import type { CanvasEdge, CanvasNode } from '@/types/studio'

export const WORKFLOW_LAYOUT_COLUMN_GAP = 88
export const WORKFLOW_LAYOUT_ROW_GAP = 56
export const WORKFLOW_LAYOUT_COMPONENT_GAP = 96
export const WORKFLOW_LAYOUT_MARGIN = 80

export const WORKFLOW_LAYOUT_DEFAULT_HANDLE_KEY = '__default__'
const DEFAULT_NODE_WIDTH = 240
const DEFAULT_NODE_HEIGHT = 156
const WIDE_NODE_WIDTH = 300
const COLLAPSED_NODE_WIDTH = 190
const COLLAPSED_NODE_HEIGHT = 64

export interface WorkflowNodeMeasurement {
  width: number
  height: number
  sourceHandleCenters?: Record<string, number>
  targetHandleCenters?: Record<string, number>
}

export interface WorkflowAutoLayoutOptions {
  columnGap?: number
  rowGap?: number
  componentGap?: number
  margin?: number
  measurements?: Record<string, WorkflowNodeMeasurement>
}

type SizedCanvasNode = {
  node: CanvasNode
  width: number
  height: number
  measurement: WorkflowNodeMeasurement
}

type LayoutPortMaps = {
  source: Map<string, string>
  target: Map<string, string>
}

function normalizedHandleKey(handleId?: string) {
  return handleId?.trim() || WORKFLOW_LAYOUT_DEFAULT_HANDLE_KEY
}

function compareText(left?: string, right?: string) {
  return String(left || '').localeCompare(String(right || ''), 'en')
}

function compareEdges(left: CanvasEdge, right: CanvasEdge) {
  return compareText(left.source, right.source)
    || compareText(left.sourceHandle, right.sourceHandle)
    || compareText(left.target, right.target)
    || compareText(left.targetHandle, right.targetHandle)
    || compareText(left.id, right.id)
}

function fallbackMeasurement(node: CanvasNode): WorkflowNodeMeasurement {
  if (node.data.collapsed) {
    return { width: COLLAPSED_NODE_WIDTH, height: COLLAPSED_NODE_HEIGHT }
  }
  const isWide = ['classifier', 'condition', 'approval', 'loop'].includes(node.data.kind)
  const routeCount = node.data.kind === 'classifier'
    ? node.data.classifierConfig?.classes?.length || 0
    : node.data.kind === 'condition'
      ? node.data.conditionConfig?.groups?.length || 0
      : 0
  return {
    width: isWide ? WIDE_NODE_WIDTH : DEFAULT_NODE_WIDTH,
    height: DEFAULT_NODE_HEIGHT + Math.max(0, routeCount - 2) * 28,
  }
}

function validMeasurement(
  node: CanvasNode,
  measurements: Record<string, WorkflowNodeMeasurement>,
): WorkflowNodeMeasurement {
  const fallback = fallbackMeasurement(node)
  const measured = measurements[node.id]
  return {
    width: measured?.width > 0 ? measured.width : fallback.width,
    height: measured?.height > 0 ? measured.height : fallback.height,
    sourceHandleCenters: measured?.sourceHandleCenters,
    targetHandleCenters: measured?.targetHandleCenters,
  }
}

function stableNodeOrder(nodes: CanvasNode[], edges: CanvasEdge[]) {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const outgoing = new Map<string, CanvasEdge[]>()
  for (const edge of edges.slice().sort(compareEdges)) {
    outgoing.set(edge.source, [...(outgoing.get(edge.source) || []), edge])
  }

  const ordered: CanvasNode[] = []
  const visited = new Set<string>()
  const visit = (nodeId: string) => {
    if (visited.has(nodeId) || !byId.has(nodeId)) return
    visited.add(nodeId)
    ordered.push(byId.get(nodeId)!)
    for (const edge of outgoing.get(nodeId) || []) visit(edge.target)
  }

  nodes
    .filter((node) => node.data.kind === 'start')
    .sort((left, right) => compareText(left.id, right.id))
    .forEach((node) => visit(node.id))
  nodes
    .slice()
    .sort((left, right) => compareText(left.id, right.id))
    .forEach((node) => visit(node.id))
  return ordered
}

function weaklyConnectedComponents(nodes: CanvasNode[], edges: CanvasEdge[]) {
  const neighbors = new Map<string, Set<string>>()
  for (const node of nodes) neighbors.set(node.id, new Set())
  for (const edge of edges) {
    neighbors.get(edge.source)?.add(edge.target)
    neighbors.get(edge.target)?.add(edge.source)
  }

  const components: CanvasNode[][] = []
  const visited = new Set<string>()
  const ordered = stableNodeOrder(nodes, edges)
  for (const node of ordered) {
    if (visited.has(node.id)) continue
    const componentIds = new Set<string>()
    const queue = [node.id]
    visited.add(node.id)
    for (let index = 0; index < queue.length; index += 1) {
      const current = queue[index]
      componentIds.add(current)
      const nextIds = Array.from(neighbors.get(current) || []).sort(compareText)
      for (const nextId of nextIds) {
        if (visited.has(nextId)) continue
        visited.add(nextId)
        queue.push(nextId)
      }
    }
    components.push(ordered.filter((item) => componentIds.has(item.id)))
  }
  return components
}

function handleCenter(
  measurement: WorkflowNodeMeasurement,
  side: 'source' | 'target',
  handleKey: string,
) {
  const centers = side === 'source'
    ? measurement.sourceHandleCenters
    : measurement.targetHandleCenters
  const direct = centers?.[handleKey]
  if (Number.isFinite(direct)) return Number(direct)
  const availableCenters = Object.values(centers || {}).filter(Number.isFinite)
  return availableCenters.length === 1 ? Number(availableCenters[0]) : measurement.height / 2
}

function createPorts(
  sizedNode: SizedCanvasNode,
  componentEdges: CanvasEdge[],
  nodeIndex: number,
): { ports: ElkPort[]; maps: LayoutPortMaps } {
  const sourceKeys = Array.from(new Set(
    componentEdges
      .filter((edge) => edge.source === sizedNode.node.id)
      .map((edge) => normalizedHandleKey(edge.sourceHandle)),
  )).sort(compareText)
  const targetKeys = Array.from(new Set(
    componentEdges
      .filter((edge) => edge.target === sizedNode.node.id)
      .map((edge) => normalizedHandleKey(edge.targetHandle)),
  )).sort(compareText)
  const maps: LayoutPortMaps = { source: new Map(), target: new Map() }
  const ports: ElkPort[] = []

  const appendPort = (side: 'source' | 'target', handleKey: string, portIndex: number) => {
    const id = `port-${nodeIndex}-${side}-${portIndex}`
    const centerY = Math.min(
      sizedNode.height - 1,
      Math.max(1, handleCenter(sizedNode.measurement, side, handleKey)),
    )
    ports.push({
      id,
      width: 0,
      height: 0,
      x: side === 'source' ? sizedNode.width : 0,
      y: centerY,
      layoutOptions: {
        'elk.port.side': side === 'source' ? 'EAST' : 'WEST',
      },
    })
    maps[side].set(handleKey, id)
  }

  sourceKeys.forEach((handleKey, index) => appendPort('source', handleKey, index))
  targetKeys.forEach((handleKey, index) => appendPort('target', handleKey, index))
  return { ports, maps }
}

async function layoutComponent(
  elk: ELK,
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  measurements: Record<string, WorkflowNodeMeasurement>,
  columnGap: number,
  rowGap: number,
) {
  const orderedNodes = stableNodeOrder(nodes, edges)
  const sizedNodes = orderedNodes.map((node) => {
    const measurement = validMeasurement(node, measurements)
    return { node, width: measurement.width, height: measurement.height, measurement }
  })
  const portMaps = new Map<string, LayoutPortMaps>()
  const children: ElkNode[] = sizedNodes.map((sizedNode, nodeIndex) => {
    const { ports, maps } = createPorts(sizedNode, edges, nodeIndex)
    portMaps.set(sizedNode.node.id, maps)
    return {
      id: sizedNode.node.id,
      width: sizedNode.width,
      height: sizedNode.height,
      ports,
      layoutOptions: {
        'elk.portConstraints': 'FIXED_POS',
      },
    }
  })
  const elkEdges = edges.slice().sort(compareEdges).map((edge, index) => ({
    id: `edge-${index}`,
    sources: [portMaps.get(edge.source)?.source.get(normalizedHandleKey(edge.sourceHandle)) || edge.source],
    targets: [portMaps.get(edge.target)?.target.get(normalizedHandleKey(edge.targetHandle)) || edge.target],
  }))
  const graph: ElkNode = {
    id: 'workflow-layout-root',
    children,
    edges: elkEdges,
    layoutOptions: {
      'elk.algorithm': 'layered',
      'elk.direction': 'RIGHT',
      'elk.edgeRouting': 'ORTHOGONAL',
      'elk.spacing.nodeNode': String(rowGap),
      'elk.layered.spacing.nodeNodeBetweenLayers': String(columnGap),
      'elk.layered.cycleBreaking.strategy': 'DEPTH_FIRST',
      'elk.layered.crossingMinimization.strategy': 'LAYER_SWEEP',
      'elk.layered.nodePlacement.strategy': 'SIMPLE',
      'elk.layered.considerModelOrder.strategy': 'NODES_AND_EDGES',
      'elk.padding': '[top=0,left=0,bottom=0,right=0]',
    },
  }

  const result = await elk.layout(graph)
  const positions = new Map<string, { x: number; y: number; width: number; height: number }>()
  for (const child of result.children || []) {
    positions.set(child.id, {
      x: Math.round(child.x || 0),
      y: Math.round(child.y || 0),
      width: child.width || validMeasurement(nodes.find((node) => node.id === child.id)!, measurements).width,
      height: child.height || validMeasurement(nodes.find((node) => node.id === child.id)!, measurements).height,
    })
  }
  return positions
}

/**
 * Computes visual-only Workflow coordinates. GraphSpec semantics are never changed.
 */
export async function autoLayoutWorkflowNodes(
  nodes: CanvasNode[],
  edges: CanvasEdge[],
  options: WorkflowAutoLayoutOptions = {},
) {
  if (!nodes.length) return []
  const columnGap = options.columnGap ?? WORKFLOW_LAYOUT_COLUMN_GAP
  const rowGap = options.rowGap ?? WORKFLOW_LAYOUT_ROW_GAP
  const componentGap = options.componentGap ?? WORKFLOW_LAYOUT_COMPONENT_GAP
  const margin = options.margin ?? WORKFLOW_LAYOUT_MARGIN
  const measurements = options.measurements || {}
  const nodeIds = new Set(nodes.map((node) => node.id))
  const validEdges = edges.filter((edge) => nodeIds.has(edge.source) && nodeIds.has(edge.target))
  const components = weaklyConnectedComponents(nodes, validEdges)
  const positions = new Map<string, { x: number; y: number }>()
  let nextComponentY = margin
  const { default: ELKConstructor } = await import('elkjs/lib/elk.bundled.js')
  const elk = new ELKConstructor()
  try {
    for (const component of components) {
      const componentIds = new Set(component.map((node) => node.id))
      const componentEdges = validEdges.filter(
        (edge) => componentIds.has(edge.source) && componentIds.has(edge.target),
      )
      const componentLayout = await layoutComponent(
        elk,
        component,
        componentEdges,
        measurements,
        columnGap,
        rowGap,
      )
      const values = Array.from(componentLayout.values())
      const minX = Math.min(0, ...values.map((item) => item.x))
      const minY = Math.min(0, ...values.map((item) => item.y))
      let componentBottom = nextComponentY
      for (const node of component) {
        const item = componentLayout.get(node.id)
        if (!item) continue
        const x = margin + item.x - minX
        const y = nextComponentY + item.y - minY
        positions.set(node.id, { x: Math.round(x), y: Math.round(y) })
        componentBottom = Math.max(componentBottom, y + item.height)
      }
      nextComponentY = componentBottom + componentGap
    }
  } finally {
    try {
      elk.terminateWorker()
    } catch {
      // elkjs uses a synchronous worker shim under Node-based contract checks.
    }
  }

  return nodes.map((node) => ({
    ...node,
    position: positions.get(node.id) || node.position,
  }))
}
