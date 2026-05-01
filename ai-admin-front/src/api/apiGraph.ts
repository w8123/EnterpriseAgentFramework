import { agentRequest } from './request'

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
  /** 后端透传的原始 JSON 字符串；前端按需 JSON.parse */
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
  return agentRequest.get<ApiGraphSnapshot>(`/api/api-graph/projects/${projectId}/snapshot`)
}

export function rebuildApiGraph(projectId: number) {
  return agentRequest.post<ApiGraphSnapshot>(`/api/api-graph/projects/${projectId}/rebuild`)
}

export function inferApiGraphModelEdges(projectId: number) {
  return agentRequest.post<ApiGraphInferResult>(`/api/api-graph/projects/${projectId}/infer`)
}

export function inferApiGraphRequestResponseEdges(projectId: number) {
  return agentRequest.post<ApiGraphInferResult>(`/api/api-graph/projects/${projectId}/infer/request-response`)
}

export function listApiGraphCandidates(projectId: number, status = 'CANDIDATE', minConfidence?: number) {
  return agentRequest.get<ApiGraphEdge[]>(`/api/api-graph/projects/${projectId}/candidates`, {
    params: { status, minConfidence },
  })
}

export function confirmApiGraphCandidate(projectId: number, edgeId: number, confirmedBy = 'operator') {
  return agentRequest.post<ApiGraphEdge>(`/api/api-graph/projects/${projectId}/candidates/${edgeId}/confirm`, {
    confirmedBy,
  })
}

export function rejectApiGraphCandidate(projectId: number, edgeId: number, rejectReason?: string) {
  return agentRequest.post<ApiGraphEdge>(`/api/api-graph/projects/${projectId}/candidates/${edgeId}/reject`, {
    rejectReason,
  })
}

export function getApiGraphParamHints(projectId: number, toolName: string) {
  return agentRequest.get<ApiGraphParamSourceHint[]>(
    `/api/api-graph/projects/${projectId}/tools/${encodeURIComponent(toolName)}/param-hints`
  )
}

export function upsertApiGraphEdge(projectId: number, payload: ApiGraphEdgeUpsertRequest) {
  return agentRequest.post<ApiGraphEdge>(`/api/api-graph/projects/${projectId}/edges`, payload)
}

export function deleteApiGraphEdge(projectId: number, edgeId: number) {
  return agentRequest.delete(`/api/api-graph/projects/${projectId}/edges/${edgeId}`)
}

export function saveApiGraphLayout(projectId: number, payload: ApiGraphLayoutSaveRequest) {
  return agentRequest.put(`/api/api-graph/projects/${projectId}/layout`, payload)
}
