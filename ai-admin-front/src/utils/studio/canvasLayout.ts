import type { CanvasSnapshot } from '@/types/studio'

/** Layout-only document; executable node configuration is owned by GraphSpec. */
interface CanvasLayoutDocument {
  schemaVersion: 1
  layoutVersion: 1
  nodes: Array<{
    id: string
    position: { x: number; y: number }
    collapsed?: boolean
  }>
  edges: Array<{
    id: string
    label?: string
    style?: string
  }>
}

export function overlayCanvasLayout(semantic: CanvasSnapshot, layout: CanvasLayoutDocument): CanvasSnapshot {
  const layoutNodes = new Map(layout.nodes.map((node) => [node.id, node]))
  const layoutEdges = new Map(layout.edges.map((edge) => [edge.id, edge]))
  return {
    version: 2,
    nodes: semantic.nodes.map((node) => {
      const layoutNode = layoutNodes.get(node.id)
      if (!layoutNode) return node
      return {
        ...node,
        position: layoutNode.position || node.position,
        data: {
          ...node.data,
          collapsed: typeof layoutNode.collapsed === 'boolean'
            ? layoutNode.collapsed
            : node.data.collapsed,
        },
      }
    }),
    edges: semantic.edges.map((edge) => {
      const layoutEdge = layoutEdges.get(edge.id)
      if (!layoutEdge) return edge
      return {
        ...edge,
        label: layoutEdge.label ?? edge.label,
        type: layoutEdge.style ?? edge.type,
      }
    }),
  }
}

export function canvasLayoutFromSnapshot(snapshot: CanvasSnapshot): CanvasLayoutDocument {
  return {
    schemaVersion: 1,
    layoutVersion: 1,
    nodes: snapshot.nodes.map((node) => ({
      id: node.id,
      position: { x: node.position.x, y: node.position.y },
      collapsed: node.data.collapsed === true ? true : undefined,
    })),
    edges: snapshot.edges.map((edge) => ({
      id: edge.id,
      label: typeof edge.label === 'string' ? edge.label : undefined,
      style: edge.type,
    })),
  }
}

export function parseCanvasLayout(canvasJson: string): CanvasLayoutDocument {
  const parsed = JSON.parse(canvasJson) as {
    schemaVersion?: unknown
    layoutVersion?: unknown
    nodes?: Array<Record<string, unknown>>
    edges?: Array<Record<string, unknown>>
  }
  if (parsed.schemaVersion !== 1 || parsed.layoutVersion !== 1) {
    throw new Error('Canvas layout schemaVersion/layoutVersion must both be 1')
  }
  assertLayoutFields(parsed as Record<string, unknown>, new Set([
    'schemaVersion', 'layoutVersion', 'viewport', 'layout', 'nodes', 'edges',
  ]), 'Canvas layout')
  return {
    schemaVersion: 1,
    layoutVersion: 1,
    nodes: (Array.isArray(parsed.nodes) ? parsed.nodes : []).flatMap((raw) => {
      assertLayoutFields(raw, new Set(['id', 'position', 'width', 'height', 'collapsed']), 'Canvas layout node')
      const id = typeof raw.id === 'string' ? raw.id : ''
      const position = raw.position as { x?: unknown; y?: unknown } | undefined
      const x = Number(position?.x)
      const y = Number(position?.y)
      if (!id || !Number.isFinite(x) || !Number.isFinite(y)) return []
      const collapsed = typeof raw.collapsed === 'boolean'
        ? raw.collapsed
        : undefined
      return [{ id, position: { x, y }, collapsed }]
    }),
    edges: (Array.isArray(parsed.edges) ? parsed.edges : []).flatMap((raw) => {
      assertLayoutFields(raw, new Set(['id', 'label', 'style']), 'Canvas layout edge')
      const id = typeof raw.id === 'string' ? raw.id : ''
      if (!id) return []
      return [{
        id,
        label: typeof raw.label === 'string' ? raw.label : undefined,
        style: typeof raw.style === 'string' ? raw.style : undefined,
      }]
    }),
  }
}

function assertLayoutFields(value: Record<string, unknown>, allowed: Set<string>, label: string): void {
  for (const field of Object.keys(value)) {
    if (!allowed.has(field)) throw new Error(`${label} contains unsupported field: ${field}`)
  }
}
