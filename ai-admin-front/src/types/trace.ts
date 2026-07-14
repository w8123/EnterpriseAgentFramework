export interface TraceNode {
  id: number
  source?: 'runtime_tool_call_log' | 'runtime_trace_span' | string
  traceId: string
  agentName?: string
  toolName: string
  spanType?: string
  spanId?: string
  parentSpanId?: string
  nodeId?: string
  runtimeType?: string
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
