import { controlRequest } from './request'
import type { TraceDetailResponse, TraceSummary } from '@/types/trace'

export function getTraceDetail(traceId: string) {
  return controlRequest.get<TraceDetailResponse>(`/api/traces/${traceId}`)
}

export function getRecentTraces(params?: { userId?: string; days?: number; limit?: number }) {
  return controlRequest.get<TraceSummary[]>('/api/traces/recent', { params })
}
