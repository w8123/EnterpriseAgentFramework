import { controlRequest } from './request'
import type { ReplayRequest, ReplayResult, RunComparison, RunDetail, RunDiagnostics, RunSummary } from '@/types/runops'

export function getRunOpsDetail(traceId: string) {
  return controlRequest.get<RunDetail>(`/api/runops/traces/${traceId}`)
}

export function getRecentRunOps(params?: { userId?: string; days?: number; limit?: number }) {
  return controlRequest.get<RunSummary[]>('/api/runops/traces/recent', { params })
}

export function getRunOpsDiagnostics(params?: { userId?: string; days?: number; limit?: number }) {
  return controlRequest.get<RunDiagnostics>('/api/runops/diagnostics', { params })
}

export function replayRunOpsTrace(traceId: string, data?: ReplayRequest) {
  return controlRequest.post<ReplayResult>(`/api/runops/traces/${traceId}/replay`, data ?? {})
}

export function compareRunOpsTrace(traceId: string, candidateTraceId: string) {
  return controlRequest.get<RunComparison>(`/api/runops/traces/${traceId}/compare/${candidateTraceId}`)
}
