import type { CanvasEdge, CanvasNode } from '@/types/studio'

/**
 * Node ids whose outputs a node can read: everything reachable backwards over edges. A loop body node also
 * sees its loop node and everything upstream of it, because body nodes are linked by membership, not edges.
 */
export function upstreamNodeIds(nodeId: string, nodes: CanvasNode[], edges: CanvasEdge[]): Set<string> {
  const incoming = new Map<string, string[]>()
  for (const edge of edges) {
    if (!edge.source || !edge.target) continue
    const list = incoming.get(edge.target) || []
    list.push(edge.source)
    incoming.set(edge.target, list)
  }
  const seeds = [nodeId]
  for (const node of nodes) {
    if (node.data?.kind === 'loop' && (node.data.loopConfig?.bodyNodeIds || []).includes(nodeId)) {
      seeds.push(node.id)
    }
  }
  const result = new Set<string>()
  const queue = [...seeds]
  while (queue.length) {
    const current = queue.shift() as string
    for (const source of incoming.get(current) || []) {
      if (result.has(source)) continue
      result.add(source)
      queue.push(source)
    }
    if (current !== nodeId && !result.has(current)) result.add(current)
  }
  result.delete(nodeId)
  // Loop membership is transitive: a body node inside a loop that sits in another loop body.
  for (const node of nodes) {
    if (node.data?.kind === 'loop' && result.has(node.id)) {
      for (const member of node.data.loopConfig?.bodyNodeIds || []) {
        if (member !== nodeId && (node.data.loopConfig?.bodyNodeIds || []).includes(nodeId)) result.add(member)
      }
    }
  }
  return result
}
