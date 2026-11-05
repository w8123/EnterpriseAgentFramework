import type { WorkflowVersion } from '@/types/workflow'

/** Published owner floors are evidence of a minimum, not a classification of every node. */
export function publishedWorkflowRisk(versions: WorkflowVersion[]): 'READ' | 'WRITE' | 'IRREVERSIBLE' | undefined {
  const active = versions.filter(version => String(version.status || '').toUpperCase() === 'ACTIVE')
  if (active.length !== 1 || !active[0].snapshotJson) return undefined
  try {
    const snapshot = JSON.parse(active[0].snapshotJson) as Record<string, unknown>
    const business = snapshot.businessMethodRiskFloor
    const api = snapshot.httpApiRiskFloor
    if (business === 'IRREVERSIBLE') return 'IRREVERSIBLE'
    if (business === 'WRITE' || api === 'WRITE') return 'WRITE'
    // The floor covers business methods and APIs, not arbitrary nodes. Only infer READ for
    // a simple graph whose other nodes cannot perform an external action.
    const graph = active[0].graphSpecSnapshotJson ? JSON.parse(active[0].graphSpecSnapshotJson) : null
    const nodes = Array.isArray(graph?.nodes) ? graph.nodes : []
    const simpleRead = nodes.length > 0 && nodes.every((node: { type?: unknown }) =>
      ['USER_INPUT', 'TOOL', 'ANSWER'].includes(String(node?.type || '').toUpperCase()))
    if (business === 'READ' && snapshot.httpApiRiskClassified === true && simpleRead) return 'READ'
  } catch {
    // An unreadable snapshot must not silently become a read-only declaration.
  }
  return undefined
}
