export interface ToolCandidate {
  toolId: number
  toolName: string
  toolTitle: string | null
  projectId: number | null
  moduleId: number | null
  score: number
  text: string | null
}

export interface ToolRetrievalSearchRequest {
  query: string
  topK?: number
  projectIds?: number[]
  moduleIds?: number[]
  toolWhitelist?: number[]
  enabledOnly?: boolean
  /** 缺省、null 或 0 表示不按匹配分数过滤 */
  minScore?: number | null
}

export interface ToolRetrievalSearchResponse {
  candidates: ToolCandidate[]
  message?: string | null
}
