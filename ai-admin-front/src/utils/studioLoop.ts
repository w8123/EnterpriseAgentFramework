import type { CanvasNode, CanvasNodeKind, LoopNodeConfig } from '@/types/studio'

export interface LoopBodyCandidate {
  id: string
  label: string
  kind: CanvasNodeKind
}

const FORBIDDEN_BODY_KINDS = new Set<CanvasNodeKind>([
  'start',
  'end',
  'loop',
  'interaction',
  'approval',
])

function nodeLabel(node: CanvasNode): string {
  const name = node.data?.label?.trim() || node.id
  const kind = node.data?.kind || 'unknown'
  return `${name} · ${kind} · ${node.id}`
}

/** Nodes already claimed by another LOOP body. */
export function occupiedLoopBodyNodeIds(nodes: CanvasNode[], exceptLoopId?: string): Set<string> {
  const occupied = new Set<string>()
  for (const node of nodes || []) {
    if (node.data?.kind !== 'loop') continue
    if (exceptLoopId && node.id === exceptLoopId) continue
    for (const bodyId of node.data.loopConfig?.bodyNodeIds || []) {
      if (bodyId) occupied.add(bodyId)
    }
    const entry = node.data.loopConfig?.bodyEntry
    const exit = node.data.loopConfig?.bodyExit
    if (entry) occupied.add(entry)
    if (exit) occupied.add(exit)
  }
  return occupied
}

export function listLoopBodyCandidates(input: {
  loopNodeId: string
  nodes: CanvasNode[]
}): LoopBodyCandidate[] {
  const occupied = occupiedLoopBodyNodeIds(input.nodes, input.loopNodeId)
  return (input.nodes || [])
    .filter((node) => {
      if (!node?.id || node.id === input.loopNodeId) return false
      const kind = node.data?.kind
      if (!kind || FORBIDDEN_BODY_KINDS.has(kind)) return false
      if (occupied.has(node.id)) return false
      return true
    })
    .map((node) => ({
      id: node.id,
      kind: node.data.kind,
      label: nodeLabel(node),
    }))
}

export function syncLoopBodyMembership(input: {
  loopNodeId: string
  nodes: CanvasNode[]
  loopConfig: LoopNodeConfig
}): LoopNodeConfig {
  // Avoid wiping persisted membership when canvas nodes are not yet loaded.
  if (!input.nodes?.length) {
    return {
      ...input.loopConfig,
      mode: 'FOREACH',
      maxIterations: normalizeMaxIterations(input.loopConfig.maxIterations),
    }
  }
  const occupied = occupiedLoopBodyNodeIds(input.nodes, input.loopNodeId)
  const existing = new Map((input.nodes || []).map((node) => [node.id, node]))
  const bodyNodeIds = Array.from(new Set(input.loopConfig.bodyNodeIds || []))
    .filter((id) => {
      if (!id || id === input.loopNodeId) return false
      const node = existing.get(id)
      if (!node) return false
      const kind = node.data?.kind
      if (!kind || FORBIDDEN_BODY_KINDS.has(kind)) return false
      if (occupied.has(id)) return false
      return true
    })
  let bodyEntry = input.loopConfig.bodyEntry || ''
  let bodyExit = input.loopConfig.bodyExit || ''
  if (bodyEntry && !bodyNodeIds.includes(bodyEntry)) bodyEntry = ''
  if (bodyExit && !bodyNodeIds.includes(bodyExit)) bodyExit = ''
  if (!bodyEntry && bodyNodeIds.length) bodyEntry = bodyNodeIds[0]
  if (!bodyExit && bodyNodeIds.length) bodyExit = bodyNodeIds[bodyNodeIds.length - 1]
  return {
    ...input.loopConfig,
    mode: 'FOREACH',
    maxIterations: normalizeMaxIterations(input.loopConfig.maxIterations),
    bodyNodeIds,
    bodyEntry,
    bodyExit,
  }
}

export function normalizeMaxIterations(raw: unknown): number {
  const value = typeof raw === 'number' ? raw : Number(raw)
  if (!Number.isFinite(value) || value < 1) return 100
  if (value > 1000) return 1000
  return Math.floor(value)
}

export function loopOwnerByBodyNodeId(nodes: CanvasNode[]): Map<string, string> {
  const owner = new Map<string, string>()
  for (const node of nodes || []) {
    if (node.data?.kind !== 'loop') continue
    for (const bodyId of node.data.loopConfig?.bodyNodeIds || []) {
      if (!bodyId) continue
      if (!owner.has(bodyId)) owner.set(bodyId, node.id)
    }
  }
  return owner
}

export function defaultLoopConfig(): LoopNodeConfig {
  return {
    mode: 'FOREACH',
    collection: '',
    itemAlias: 'item',
    indexAlias: 'index',
    outputAlias: 'loop_results',
    bodyOutput: 'lastOutput',
    maxIterations: 100,
    bodyEntry: '',
    bodyExit: '',
    bodyNodeIds: [],
  }
}
