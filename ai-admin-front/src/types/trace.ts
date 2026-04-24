export interface TraceNode {
  id: number
  traceId: string
  agentName?: string
  toolName: string
  argsJson?: string
  resultSummary?: string
  success: boolean
  errorCode?: string
  elapsedMs?: number
  tokenCost?: number
  retrievalCandidates: Record<string, unknown>[]
  createdAt?: string
}

export interface TraceDetailResponse {
  traceId: string
  nodes: TraceNode[]
}

export interface TraceSummary {
  traceId: string
  sessionId?: string
  userId?: string
  agentName?: string
  intentType?: string
  callCount: number
  successCount: number
  startedAt?: string
  endedAt?: string
}
