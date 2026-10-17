import { controlRequest } from './request'
import type { TraceDetailResponse } from '@/types/trace'
export function getTraceDetail(traceId: string) {
  return controlRequest.get<TraceDetailResponse>(`/api/traces/${traceId}`)
}
