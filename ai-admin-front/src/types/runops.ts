export interface RunSummary {
  traceId: string
  status: 'SUCCESS' | 'ERROR' | string
  /** 历史字段：Agent 运行时为 Agent id；Workflow 运行可能为 sourceId/workflowId 兼容值 */
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
  /** Workflow 归属（RunOps 从 span metadata 提取） */
  workflowId?: string
  workflowKeySlug?: string
  workflowVersion?: string
  workflowVersionId?: number | string
  /** 聊天/嵌入入口 AgentEntry id，不是 WorkflowDefinition id */
  entryAgentId?: string
  entryAgentKeySlug?: string
  sourceType?: string
  /** GraphSpec-native 来源 id，Workflow 场景通常等于 workflowId */
  sourceId?: string
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
  /** 历史字段：Agent 运行时为 Agent id */
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
  /** Workflow 归属（失败聚类按 workflowId|workflowVersionId 分组时填充） */
  workflowId?: string
  workflowKeySlug?: string
  workflowVersion?: string
  workflowVersionId?: number | string
  sourceType?: string
  sourceId?: string
}

export interface VersionComparison {
  /** 历史字段：Agent 运行时为 Agent id */
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
  /** Workflow 归属（版本对比按 workflowId|workflowVersionId 分组时填充） */
  workflowId?: string
  workflowKeySlug?: string
  workflowVersion?: string
  workflowVersionId?: number | string
  sourceType?: string
  sourceId?: string
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
  workflowId?: string
  workflowKeySlug?: string
  workflowVersion?: string
  workflowVersionId?: number | string
  entryAgentId?: string
  entryAgentKeySlug?: string
  sourceType?: string
  sourceId?: string
  /** GRAPH_SPEC | AGENT_DEFINITION | AGENT_DEFINITION_FALLBACK */
  executionPath?: string
  fallbackReason?: string
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
