import type { LocationQuery } from 'vue-router'
import type { ApiGraphNode } from '@/api/apiGraph'
import { parseProps } from './apiGraphCanvasViewModel'

/** Source graph IDs and formal asset IDs belong to different namespaces. */
export function resolveGraphSubject(nodes: ApiGraphNode[], projectId: number, query: LocationQuery): ApiGraphNode | null {
  if (query.projectId != null && query.projectId !== String(projectId)) return null
  const type = query.subjectType
  const rawId = query.subjectId
  if (!['BUSINESS_METHOD', 'HTTP_API', 'SOURCE_OBSERVATION'].includes(String(type))
    || typeof type !== 'string' || typeof rawId !== 'string' || !/^[1-9]\d*$/.test(rawId)) return null
  const id = Number(rawId)
  if (!Number.isSafeInteger(id)) return null
  const matches = nodes.filter(node => {
    if (node.projectId !== projectId || node.kind !== 'API') return false
    const data = parseProps(node)
    return (data.subjectType || data.assetType) === type && Number(data.subjectId || data.assetId) === id
  })
  return matches.length === 1 ? matches[0] : null
}
