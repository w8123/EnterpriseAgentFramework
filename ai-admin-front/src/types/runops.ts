export type RunType = 'AGENT' | 'WORKFLOW' | 'MCP'

export type RunEntryType = 'DEBUG' | 'EMBED' | 'GATEWAY' | 'EVAL' | 'REPLAY' | 'API' | 'AUTOMATION' | 'MCP'

export type RunStatus =
  | 'RUNNING'
  | 'SUSPENDED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT'

export type RunSuspensionReason = 'USER_INPUT' | 'APPROVAL'

export interface RunOpsQueryParams {
  projectCode?: string
  status?: RunStatus
  runType?: RunType
  entryType?: RunEntryType
  agentId?: string
  userId?: string
  keyword?: string
  days?: number
  limit?: number
}

/** 一次 Agent、Workflow 或外部 MCP tools/call 根运行。其他 Tool 调用仍作为运行内事件。 */
export interface RunSummary {
  traceId: string
  runType: RunType
  entryType: RunEntryType
  status: RunStatus
  suspensionReason?: RunSuspensionReason
  projectCode?: string
  tenantId?: string
  sessionId?: string
  userId?: string
  agentId?: string
  agentKeySlug?: string
  agentName?: string
  agentConfigVersionId?: number
  agentConfigVersion?: number
  workflowId?: string
  workflowKeySlug?: string
  workflowName?: string
  workflowVersionId?: number
  workflowVersion?: string
  runtimeType?: string
  inputSummary?: string
  outputSummary?: string
  errorCode?: string
  errorMessage?: string
  startedAt?: string
  endedAt?: string
  latencyMs?: number
  tokenCost?: number
  planCount?: number
  replanCount?: number
  workflowCallCount?: number
  toolCallCount?: number
  guardDenyCount?: number
  approvalCount?: number
  replayOfTraceId?: string
  metadata?: Record<string, unknown>
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
  metadata?: RunSpanMetadata
  errorCode?: string
  errorMessage?: string
  latencyMs?: number
  tokenCost?: number
  startedAt?: string
  endedAt?: string
}

/** RuntimeExecutionEvent V1 projected metadata for Workflow node spans. */
export interface RunSpanMetadata extends Record<string, unknown> {
  nodeType?: string
  nodeName?: string
  qualifiedName?: string
  attempt?: number
  maxAttempts?: number
  errorPolicy?: string
  failureCode?: string
  failureCategory?: string
  retryableFailure?: boolean
  fallbackNodeId?: string
  outcomeClass?: string
  businessOutcome?: string
  interactionId?: string
  interactionType?: string
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
  /** success preserves the original observation; status describes the current correlated outcome. */
  status?: string
  statusSourceSpanId?: string
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

/** 原运行所使用的已发布配置，只读展示并作为重放依据。 */
export interface RunSnapshot {
  runType?: RunType
  agentConfigVersionId?: number
  workflowVersionId?: number
  runtimeType?: string
  snapshot?: Record<string, unknown>
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
  executionPath?: RunExecutionPathItem[]
  repairHints: string[]
}

export interface TraceWorkflowCandidateEligibility {
  schema: string
  traceId: string
  projectCode?: string
  eligible: boolean
  blockers: string[]
  evidence: string[]
  sourceWorkflowId?: string
  sourceWorkflowVersionId?: number
  sourceWorkflowVersion?: string
  facts?: Record<string, unknown>
}

export interface RunExecutionPathItem {
  spanId?: string
  parentSpanId?: string
  depth: number
  spanType?: string
  label?: string
  status?: string
  nodeId?: string
  toolName?: string
  fromNodeId?: string
  toNodeId?: string
  condition?: string
  route?: string
  workflowStatus?: string
  interactionId?: string
  runtimeType?: string
  startedAt?: string
  endedAt?: string
}

export interface FailureCluster {
  versionType?: RunType
  agentId?: string
  agentName?: string
  agentConfigVersionId?: number
  agentConfigVersion?: number
  workflowId?: string
  workflowName?: string
  workflowVersionId?: number
  workflowVersion?: string
  runtimeType?: string
  errorType?: string
  errorCode?: string
  errorMessage?: string
  spanType?: string
  nodeId?: string
  toolName?: string
  count?: number
  avgLatencyMs?: number
  firstSeenAt?: string
  lastSeenAt?: string
  sampleTraceId?: string
  traceIds?: string[]
  sampleError?: string
  repairHints?: string[]
}

export interface VersionComparison {
  versionType?: RunType
  agentId?: string
  agentName?: string
  agentConfigVersionId?: number
  agentConfigVersion?: number
  workflowId?: string
  workflowName?: string
  workflowVersionId?: number
  workflowVersion?: string
  runtimeType?: string
  runCount?: number
  successCount?: number
  failureCount?: number
  successRate?: number
  avgLatencyMs?: number
  p95LatencyMs?: number
  avgTokenCost?: number
  replanCount?: number
  workflowCallCount?: number
  toolErrorCount?: number
  guardDenyCount?: number
  latestTraceId?: string
  latestStartedAt?: string
}

export interface RunDiagnostics {
  failureClusters: FailureCluster[]
  versionComparisons: VersionComparison[]
}

/** 重放始终使用原运行的已发布配置版本，不提供当前配置切换。 */
export interface ReplayRequest {
  messageOverride?: string
  sessionId?: string
  userId?: string
  roles?: string[]
}

export interface ReplayResult {
  originalTraceId: string
  replayTraceId?: string
  sessionId?: string
  userId?: string
  agentId?: string
  agentName?: string
  agentConfigVersionId?: number
  agentConfigVersion?: number
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
