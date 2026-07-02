import { controlRequest } from './request'

export type ApiGraphNodeKind = 'API' | 'FIELD_IN' | 'FIELD_OUT' | 'DTO' | 'MODULE'
export type ApiGraphEdgeKind = 'REQUEST_REF' | 'RESPONSE_REF' | 'MODEL_REF' | 'BELONGS_TO'
export type ApiGraphEdgeSource = 'auto' | 'manual'
export type ApiGraphEdgeStatus = 'CANDIDATE' | 'CONFIRMED' | 'REJECTED'

export interface ApiGraphNode {
  id: number
  projectId: number
  kind: ApiGraphNodeKind
  refId: number | null
  parentId: number | null
  label: string
  typeName: string | null
  /** 扫描时自动落表后的字段 JSON 字符串，使用前请先执行 JSON.parse。 */
  propsJson: string | null
}

export interface ApiGraphEdge {
  id: number
  projectId: number
  sourceNodeId: number
  targetNodeId: number
  kind: ApiGraphEdgeKind
  source: ApiGraphEdgeSource
  confidence: number | null
  status?: ApiGraphEdgeStatus | null
  inferStrategy?: string | null
  confirmedBy?: string | null
  confirmedAt?: string | null
  rejectReason?: string | null
  evidenceJson: string | null
  note: string | null
  enabled: boolean
}

export interface ApiGraphLayout {
  nodeId: number
  x: number
  y: number
  extJson: string | null
}

export interface ApiGraphSnapshot {
  nodes: ApiGraphNode[]
  edges: ApiGraphEdge[]
  layouts: ApiGraphLayout[]
}

export interface ApiGraphInferResult {
  generated: number
}

export interface ApiGraphParamSourceHint {
  targetPath: string
  targetField: string
  targetApi: string
  sourcePath: string
  sourceField: string
  sourceApi: string
  confidence: number | null
}

export interface ApiGraphEdgeUpsertRequest {
  sourceNodeId: number
  targetNodeId: number
  kind: ApiGraphEdgeKind
  note?: string | null
}

export interface ApiGraphLayoutSaveRequest {
  positions: Array<{
    nodeId: number
    x: number
    y: number
    extJson?: string | null
  }>
}

export function getApiGraphSnapshot(projectId: number) {
  return controlRequest.get<ApiGraphSnapshot>(`/api/api-graph/projects/${projectId}/snapshot`)
}

/** 兼容历史包装响应：若返回 ApiResult，优先解析其 body。 */
export function parseApiGraphSnapshot(raw: unknown): ApiGraphSnapshot {
  let body = raw
  if (body !== null && typeof body === 'object' && typeof (body as Record<string, unknown>).code === 'number') {
    const wrap = body as { code: number; message?: string; data?: unknown }
    if (wrap.code !== 200 && wrap.code !== 0) {
      throw new Error(wrap.message || '璇锋眰澶辫触')
    }
    body = wrap.data
  }
  if (body === null || typeof body !== 'object') {
    throw new Error('解析返回数据失败')
  }
  const s = body as Record<string, unknown>
  if (!Array.isArray(s.nodes) || !Array.isArray(s.edges) || !Array.isArray(s.layouts)) {
    throw new Error('解析返回数据格式异常，缺少 nodes / edges / layouts')
  }
  return body as ApiGraphSnapshot
}

export function rebuildApiGraph(projectId: number) {
  return controlRequest.post<ApiGraphSnapshot>(`/api/api-graph/projects/${projectId}/rebuild`, {})
}

/** 删除图谱后先重建图谱再继续关联；否则会影响历史节点 ID 与画布布局关系。 */
export function regenerateApiGraph(projectId: number) {
  return controlRequest.post<ApiGraphSnapshot>(`/api/api-graph/projects/${projectId}/regenerate`, {})
}

export function inferApiGraphModelEdges(projectId: number) {
  return controlRequest.post<ApiGraphInferResult>(`/api/api-graph/projects/${projectId}/infer`, {})
}

export function inferApiGraphRequestResponseEdges(projectId: number) {
  return controlRequest.post<ApiGraphInferResult>(`/api/api-graph/projects/${projectId}/infer/request-response`, {})
}

export function listApiGraphCandidates(projectId: number, status = 'CANDIDATE', minConfidence?: number) {
  return controlRequest.get<ApiGraphEdge[]>(`/api/api-graph/projects/${projectId}/candidates`, {
    params: { status, minConfidence },
  })
}

export function confirmApiGraphCandidate(projectId: number, edgeId: number, confirmedBy = 'operator') {
  return controlRequest.post<ApiGraphEdge>(`/api/api-graph/projects/${projectId}/candidates/${edgeId}/confirm`, {
    confirmedBy,
  })
}

export function rejectApiGraphCandidate(projectId: number, edgeId: number, rejectReason?: string) {
  return controlRequest.post<ApiGraphEdge>(`/api/api-graph/projects/${projectId}/candidates/${edgeId}/reject`, {
    rejectReason,
  })
}

export function getApiGraphParamHints(projectId: number, toolName: string) {
  return controlRequest.get<ApiGraphParamSourceHint[]>(
    `/api/api-graph/projects/${projectId}/tools/${encodeURIComponent(toolName)}/param-hints`
  )
}

export function upsertApiGraphEdge(projectId: number, payload: ApiGraphEdgeUpsertRequest) {
  return controlRequest.post<ApiGraphEdge>(`/api/api-graph/projects/${projectId}/edges`, payload)
}

export function deleteApiGraphEdge(projectId: number, edgeId: number) {
  return controlRequest.delete(`/api/api-graph/projects/${projectId}/edges/${edgeId}`)
}

export function saveApiGraphLayout(projectId: number, payload: ApiGraphLayoutSaveRequest) {
  return controlRequest.put(`/api/api-graph/projects/${projectId}/layout`, payload)
}
