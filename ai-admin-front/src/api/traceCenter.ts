import { agentRequest } from './request'

export interface GuardDecisionLog {
  id: number
  traceId: string | null
  decisionType: string
  targetKind: string
  targetName: string
  decision: string
  reason: string | null
  metadataJson: string | null
  createdAt: string
}

export interface GuardDecisionQuery {
  traceId?: string
  decisionType?: string
  targetKind?: string
  targetName?: string
  decision?: string
  from?: string
  to?: string
  limit?: number
}

export function listGuardDecisions(params: GuardDecisionQuery = {}) {
  return agentRequest.get<GuardDecisionLog[]>('/api/trace-center/guard-decisions', { params })
}
