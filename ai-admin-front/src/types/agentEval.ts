import type { AgentGraphSpec } from './agent'

/** Workflow 评测运行时上下文（对应后端 GraphRuntimeContext） */
export interface WorkflowEvalRuntimeContext {
  sourceType?: string
  sourceId?: string
  sourceKeySlug?: string
  name?: string
  intentType?: string
  projectCode?: string
  runtimeType?: string
  modelInstanceId?: string
  systemPrompt?: string
  extra?: Record<string, unknown>
}

export interface AgentEvalDataset {
  id: number
  agentId?: string | null
  agentName?: string | null
  name: string
  description?: string | null
  source?: string
  caseCount: number
  createTime?: string
  updateTime?: string
}

export interface AgentEvalCase {
  id: number
  datasetId: number
  caseNo: string
  message?: string | null
  inputParamsJson?: string | null
  expectedJson?: string | null
  judgeConfigJson?: string | null
  tags?: string | null
  enabled?: boolean
}

export interface AgentEvalCaseImportRow {
  caseNo?: string
  message?: string
  inputParams?: Record<string, unknown>
  expected?: Record<string, unknown>
  judgeConfig?: Record<string, unknown>
  tags?: string
}

export interface AgentEvalDatasetImportRequest {
  agentId?: string
  agentName?: string
  name: string
  description?: string
  cases: AgentEvalCaseImportRow[]
}

export interface AgentEvalRunRequest {
  datasetId: number
  agentId?: string
  agentName?: string
  runName?: string
  repeatCount: number
  /** Workflow GraphSpec（评测执行语义，不再传 AgentDefinition） */
  graphSpec: AgentGraphSpec
  graphRuntimeContext: WorkflowEvalRuntimeContext
  canvasSnapshot?: Record<string, unknown>
}

export interface AgentEvalRun {
  id: number
  datasetId: number
  agentId?: string | null
  agentName?: string | null
  runName?: string | null
  repeatCount: number
  status: string
  summaryJson?: string | null
  suggestionJson?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export interface AgentEvalRunSummary {
  caseCount: number
  repeatCount: number
  totalExecutions: number
  runtimeSuccessCount: number
  passedExecutions: number
  runtimeSuccessRate: number
  accuracyRate: number
  avgScore: number
  p50LatencyMs: number
  p95LatencyMs: number
  biasCount: number
  failedNodeCounts: Record<string, number>
}

export interface AgentEvalSuggestionItem {
  nodeId: string
  severity: 'HIGH' | 'MEDIUM' | 'LOW' | string
  reason: string
  recommendation: string
}

export interface AgentEvalSuggestion {
  summary: string
  items: AgentEvalSuggestionItem[]
}

export interface AgentEvalCaseResult {
  id: number
  runId: number
  datasetId: number
  caseId: number
  caseNo: string
  roundNo: number
  status: string
  runtimeSuccess: boolean
  assertionPassed: boolean
  semanticScore?: number | null
  score: number
  elapsedMs: number
  answer?: string | null
  traceId?: string | null
  errorCode?: string | null
  errorMessage?: string | null
}

export interface AgentEvalRunView {
  run: AgentEvalRun
  summary: AgentEvalRunSummary
  suggestion: AgentEvalSuggestion
  results: AgentEvalCaseResult[]
}
