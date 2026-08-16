import { controlRequest } from './request'
import type {
  ReplayRequest,
  ReplayResult,
  RunComparison,
  RunDetail,
  RunDiagnostics,
  RunOpsQueryParams,
  RunSummary,
  TraceWorkflowCandidateEligibility,
} from '@/types/runops'
import type { AiCodingExecutorProvider, AiCodingTask } from '@/types/aiCodingTask'

export function getRunOpsDetail(traceId: string) {
  return controlRequest.get<RunDetail>(`/api/runops/traces/${traceId}`)
}

export function getRecentRunOps(params?: RunOpsQueryParams) {
  return controlRequest.get<RunSummary[]>('/api/runops/traces/recent', { params })
}

export function getRunOpsDiagnostics(params?: RunOpsQueryParams) {
  return controlRequest.get<RunDiagnostics>('/api/runops/diagnostics', { params })
}

export function replayRunOpsTrace(traceId: string, data?: ReplayRequest) {
  return controlRequest.post<ReplayResult>(`/api/runops/traces/${traceId}/replay`, data ?? {})
}

export function compareRunOpsTrace(traceId: string, candidateTraceId: string) {
  return controlRequest.get<RunComparison>(`/api/runops/traces/${traceId}/compare/${candidateTraceId}`)
}

export function getTraceWorkflowCandidateEligibility(traceId: string) {
  return controlRequest.get<TraceWorkflowCandidateEligibility>(
    `/api/runops/traces/${encodeURIComponent(traceId)}/workflow-candidate/eligibility`,
  )
}

export interface TraceWorkflowCandidateTaskResult {
  schema: string
  created: boolean
  eligibility: TraceWorkflowCandidateEligibility
  task: AiCodingTask
}

export function createTraceWorkflowCandidateTask(
  traceId: string,
  data: {
    executorProvider: AiCodingExecutorProvider
    createdBy?: string
  },
) {
  return controlRequest.post<TraceWorkflowCandidateTaskResult>(
    `/api/runops/traces/${encodeURIComponent(traceId)}/workflow-candidate/tasks`,
    data,
  )
}
