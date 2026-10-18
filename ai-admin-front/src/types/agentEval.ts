export interface EvalOpsDatasetSummary {
  id: number
  tenantId: string
  projectCode?: string | null
  targetType: 'AGENT' | string
  targetId: string
  name: string
  description?: string | null
  source: string
  status: string
  currentVersionId: number
  versionCount: number
  createdAt?: string
  updatedAt?: string
}

export interface EvalOpsDatasetItem {
  id: number
  datasetVersionId: number
  itemKey: string
  message?: string | null
  input: Record<string, unknown>
  expected: Record<string, unknown>
  metadata: Record<string, unknown>
  tags?: unknown
  sourceTraceId?: string | null
  enabled: boolean
  ordinalNo: number
  contentSha256: string
}

export interface EvalOpsDatasetVersion {
  id: number
  datasetId: number
  versionNo: number
  status: string
  fingerprintSha256: string
  itemCount: number
  changeNote?: string | null
  createdBy?: string | null
  createdAt?: string
  publishedAt?: string
  items: EvalOpsDatasetItem[]
}

export interface EvalOpsDatasetDetail {
  dataset: EvalOpsDatasetSummary
  currentVersion: EvalOpsDatasetVersion
  versions: EvalOpsDatasetVersion[]
}

export interface EvalOpsDatasetItemInput {
  itemKey?: string
  caseNo?: string
  message?: string
  input?: Record<string, unknown>
  inputParams?: Record<string, unknown>
  expected?: Record<string, unknown>
  metadata?: Record<string, unknown>
  tags?: unknown
  sourceTraceId?: string
  enabled?: boolean
}

export interface EvalOpsVariantSummary {
  variantId: number
  variantKey: string
  variantRole: string
  targetFingerprint: string
  totalExecutions: number
  completedExecutions: number
  failedExecutions: number
  runtimeSuccessRate: number
  passRate: number
  averageScore: number
  p50LatencyMs: number
  p95LatencyMs: number
}

export interface EvalOpsGateComparison {
  variantId: number
  variantKey: string
  passed: boolean
  scoreDelta: number
  p95LatencyRegressionRatio: number
  failedExecutionDelta: number
  checks: Record<string, boolean>
}

export interface EvalOpsExperimentSummaryPayload {
  totalTasks?: number
  completedTasks?: number
  failedTasks?: number
  cancelledTasks?: number
  variants?: EvalOpsVariantSummary[]
  gate?: {
    status?: string
    comparisons?: EvalOpsGateComparison[]
  }
}

export interface EvalOpsExperimentSummary {
  id: number
  tenantId: string
  projectCode?: string | null
  targetType: string
  targetId: string
  name: string
  datasetVersionId: number
  evaluatorSuiteVersionId: number
  repeatCount: number
  status: string
  variantCount: number
  taskCount: number
  completedTaskCount: number
  failedTaskCount: number
  gateStatus: string
  summary: EvalOpsExperimentSummaryPayload
  createdBy?: string | null
  createdAt?: string
  startedAt?: string | null
  finishedAt?: string | null
}

export interface EvalOpsExperimentVariant {
  id: number
  experimentId: number
  variantKey: string
  displayName: string
  variantRole: 'BASELINE' | 'CANDIDATE' | string
  targetSnapshotId: number
  targetConfigVersionId: number
  targetConfigStatus: string
  targetFingerprint: string
  status: string
  summary: Partial<EvalOpsVariantSummary>
}

export interface EvalOpsExperimentItem {
  id: number
  experimentId: number
  variantId: number
  datasetItemId: number
  repeatNo: number
  status: string
  runtimeSuccess: boolean
  assertionPassed: boolean
  score?: number | null
  elapsedMs?: number | null
  answer?: string | null
  traceId?: string | null
  executionMetadata: Record<string, unknown>
  evaluatorResults: Array<Record<string, unknown>>
  errorCode?: string | null
  errorMessage?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

export interface EvalOpsScore {
  id: number
  experimentItemId: number
  evaluatorKey: string
  evaluatorType: string
  score: number
  passed: boolean
  weight: number
  reason?: string | null
  metadata: Record<string, unknown>
}

export interface EvalOpsExperimentDetail {
  experiment: EvalOpsExperimentSummary
  datasetVersion: EvalOpsDatasetVersion
  variants: EvalOpsExperimentVariant[]
  items: EvalOpsExperimentItem[]
  scores: EvalOpsScore[]
  resultCount: number
  resultsTruncated: boolean
}

export interface EvalOpsExperimentItemPage {
  total: number
  page: number
  pageSize: number
  items: EvalOpsExperimentItem[]
}

export interface EvalOpsExperimentCreateRequest {
  targetType: 'AGENT'
  targetId: string
  projectCode?: string
  name: string
  datasetVersionId: number
  repeatCount: number
  baselineConfigVersionId: number
  candidateConfigVersionId: number
  idempotencyKey: string
  gateConfig?: {
    minCandidateScore?: number
    maxScoreRegression?: number
    maxLatencyRegressionRatio?: number
    requireNoNewFailures?: boolean
  }
}
