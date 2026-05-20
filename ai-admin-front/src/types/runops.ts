export interface RunSummary {
  traceId: string
  status: 'SUCCESS' | 'ERROR' | string
  agentId?: string
  agentName?: string
  version?: string
  versionId?: number
  runtimeType?: string
  runtimePlacement?: string
  graphCode?: string
  sessionId?: string
  userId?: string
  intentType?: string
  startedAt?: string
  endedAt?: string
  latencyMs?: number
  tokenCost?: number
  nodeCount?: number
  toolCallCount?: number
  errorCount?: number
  fallback?: boolean
  dispatchUrl?: string
  fallbackReason?: string
}

export interface RunSpan {
  id: number
  spanId?: string
  parentSpanId?: string
  spanType?: string
  runtimeType?: string
  nodeId?: string
  toolName?: string
  status?: string
  inputSummary?: string
  outputSummary?: string
  metadata?: Record<string, unknown>
  errorCode?: string
  errorMessage?: string
  latencyMs?: number
  tokenCost?: number
  startedAt?: string
  endedAt?: string
}

export interface RunToolCall {
  id: number
  toolName?: string
  agentName?: string
  sessionId?: string
  userId?: string
  intentType?: string
  projectCode?: string
  success: boolean
  argsJson?: string
  resultSummary?: string
  errorCode?: string
  elapsedMs?: number
  tokenCost?: number
  createdAt?: string
}

export interface RunGuardDecision {
  id: number
  decisionType?: string
  targetKind?: string
  targetName?: string
  decision?: string
  reason?: string
  metadata?: Record<string, unknown>
  createdAt?: string
}

export interface RunSnapshot {
  agentId?: string
  agentName?: string
  keySlug?: string
  runtimeType?: string
  runtimePlacement?: string
  runtimeConfig?: Record<string, unknown>
  graphSpec?: unknown
  snapshotJson?: string
}

export interface RunDetail {
  summary: RunSummary
  spans: RunSpan[]
  toolCalls: RunToolCall[]
  guardDecisions: RunGuardDecision[]
  snapshot?: RunSnapshot
  workflowPath?: WorkflowPathItem[]
  repairHints: string[]
}

export interface WorkflowPathItem {
  fromNodeId?: string
  toNodeId?: string
  condition?: string
  route?: string
  status?: string
  workflowStatus?: string
  interactionId?: string
  spanId?: string
  startedAt?: string
  endedAt?: string
}

export interface FailureCluster {
  agentId?: string
  agentName?: string
  version?: string
  versionId?: number
  runtimeType?: string
  runtimePlacement?: string
  errorType?: string
  nodeId?: string
  toolName?: string
  count?: number
  fallbackCount?: number
  avgLatencyMs?: number
  firstSeenAt?: string
  lastSeenAt?: string
  sampleTraceId?: string
  traceIds?: string[]
  sampleError?: string
  repairHints?: string[]
}

export interface VersionComparison {
  agentId?: string
  agentName?: string
  version?: string
  versionId?: number
  runtimeType?: string
  runtimePlacement?: string
  runCount?: number
  successCount?: number
  failureCount?: number
  successRate?: number
  avgLatencyMs?: number
  p95LatencyMs?: number
  avgTokenCost?: number
  fallbackCount?: number
  toolErrorCount?: number
  guardDenyCount?: number
  latestTraceId?: string
  latestStartedAt?: string
}

export interface RunDiagnostics {
  failureClusters: FailureCluster[]
  versionComparisons: VersionComparison[]
}

export interface ReplayRequest {
  messageOverride?: string
  sessionId?: string
  userId?: string
  roles?: string[]
  useSnapshot?: boolean
}

export interface ReplayResult {
  originalTraceId: string
  replayTraceId?: string
  sessionId?: string
  userId?: string
  agentId?: string
  agentName?: string
  version?: string
  versionId?: number
  message?: string
  success: boolean
  answer?: string
  metadata?: Record<string, unknown>
}

export interface DiffItem {
  field: string
  baseline?: unknown
  candidate?: unknown
  changed: boolean
}

export interface SpanDiff {
  key: string
  baseline?: RunSpan
  candidate?: RunSpan
  diffs: DiffItem[]
  changed: boolean
}

export interface ToolDiff {
  key: string
  baseline?: RunToolCall
  candidate?: RunToolCall
  diffs: DiffItem[]
  changed: boolean
}

export interface GuardDiff {
  key: string
  baseline?: RunGuardDecision
  candidate?: RunGuardDecision
  diffs: DiffItem[]
  changed: boolean
}

export interface RunComparison {
  baseline: RunSummary
  candidate: RunSummary
  summaryDiffs: DiffItem[]
  spanDiffs: SpanDiff[]
  toolDiffs: ToolDiff[]
  guardDiffs: GuardDiff[]
}
