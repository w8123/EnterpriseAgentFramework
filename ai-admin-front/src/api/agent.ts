import { controlRequest } from './request'
import type { PendingHumanApproval } from '@/types/agent'
export {
  listAgents,
  getAgent,
  createAgent,
  updateAgent,
  deleteAgent,
} from './workflow'

export function listPendingHumanApprovals(params?: { agentId?: string; userId?: string; limit?: number }) {
  return controlRequest.get<PendingHumanApproval[]>('/api/runtime/interactions/human-approvals', { params })
}

export interface AgentExecutionStreamEvent {
  event: string
  data: unknown
}

export interface AgentExecutionStreamHandlers {
  onEvent?: (event: AgentExecutionStreamEvent) => void
  onMessageDelta?: (text: string) => void
}
