import { agentRequest } from './request'
import type {
  AgentResult,
  PendingHumanApproval,
  AgentReleaseValidationResult,
  AgentReleaseEvent,
  AgentNodeDebugRequest,
  AgentNodeDebugResult,
  AgentWorkflowDebugRunRequest,
  AgentWorkflowDebugRunResult,
  ExecutableDebugSessionCreateRequest,
  ExecutableDebugSessionSubmitRequest,
  ExecutableDebugSessionView,
  AgentVersion,
  PublishVersionRequest,
  WorkflowDraftGenerationRequest,
  WorkflowDraftGenerationResult,
  WorkflowDraftEditRequest,
  WorkflowDraftEditResult,
} from '@/types/agent'
import type { ChatRequest, ChatResponse } from '@/types/chat'

export {
  listAgentEntries,
  getAgentEntry,
  createAgentEntry,
  updateAgentEntry,
  deleteAgentEntry,
} from './workflow'

export function debugAgentNode(data: AgentNodeDebugRequest) {
  return agentRequest.post<AgentNodeDebugResult>('/api/agent/studio/debug-node', data)
}

export function debugAgentWorkflowRun(data: AgentWorkflowDebugRunRequest) {
  return agentRequest.post<AgentWorkflowDebugRunResult>('/api/agent/studio/debug-run', data)
}

export function createExecutableDebugSession(data: ExecutableDebugSessionCreateRequest) {
  return agentRequest.post<ExecutableDebugSessionView>('/api/runtime/debug-sessions', data)
}

export function getExecutableDebugSession(sessionId: string) {
  return agentRequest.get<ExecutableDebugSessionView>(`/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}`)
}

export function submitExecutableDebugSession(sessionId: string, data: ExecutableDebugSessionSubmitRequest) {
  return agentRequest.post<ExecutableDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}/submit`,
    data,
  )
}

export function cancelExecutableDebugSession(sessionId: string) {
  return agentRequest.post<ExecutableDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}/cancel`,
  )
}

export function generateWorkflowDraft(data: WorkflowDraftGenerationRequest) {
  return agentRequest.post<WorkflowDraftGenerationResult>('/api/agent/studio/generate-draft', data)
}

export function editWorkflowDraft(data: WorkflowDraftEditRequest) {
  return agentRequest.post<WorkflowDraftEditResult>('/api/agent/studio/edit-draft', data)
}

export function executeAgent(data: ChatRequest) {
  return agentRequest.post<ChatResponse>('/api/agent/execute', data)
}

export function listPendingHumanApprovals(params?: { agentId?: number | string; userId?: string; limit?: number }) {
  return agentRequest.get<PendingHumanApproval[]>('/api/agent/interactions/human-approvals', { params })
}

export function submitHumanApproval(
  interactionId: string,
  data: { action?: string; values?: Record<string, unknown>; userId?: string; sessionId?: string },
) {
  return agentRequest.post<AgentResult>(
    `/api/agent/interactions/human-approvals/${encodeURIComponent(interactionId)}/submit`,
    data,
  )
}

export function cancelHumanApproval(interactionId: string, userId?: string) {
  return agentRequest.delete<AgentResult>(
    `/api/agent/interactions/human-approvals/${encodeURIComponent(interactionId)}`,
    { params: { userId } },
  )
}

export function executeAgentDetailed(data: ChatRequest) {
  return agentRequest.post<AgentResult>('/api/agent/execute/detailed', data)
}

/** @deprecated Agent 版本已迁移至 Workflow 版本 */
export function listAgentVersions(agentId: string) {
  return agentRequest.get<AgentVersion[]>(`/api/agents/${agentId}/versions`)
}

/** @deprecated Agent 版本已迁移至 Workflow 版本 */
export function publishAgentVersion(agentId: string, data: PublishVersionRequest) {
  return agentRequest.post<AgentVersion>(`/api/agents/${agentId}/versions`, data)
}

/** @deprecated Agent 版本已迁移至 Workflow 版本 */
export function listAgentReleaseEvents(agentId: string, limit = 100) {
  return agentRequest.get<AgentReleaseEvent[]>(`/api/agents/${agentId}/versions/events`, {
    params: { limit },
  })
}

/** @deprecated Agent 版本已迁移至 Workflow 版本 */
export function validateAgentRelease(
  agentId: string,
  data?: { version?: string; rolloutPercent?: number; operator?: string },
) {
  return agentRequest.post<AgentReleaseValidationResult>(`/api/agents/${agentId}/versions/validate`, data ?? {})
}

/** @deprecated Agent 版本已迁移至 Workflow 版本 */
export function rollbackAgentVersion(agentId: string, versionId: number, operator?: string) {
  return agentRequest.post<AgentVersion>(
    `/api/agents/${agentId}/versions/${versionId}/rollback`,
    { operator },
  )
}

/** 通过发布端点调用 Agent（{key} 对应 keySlug） */
export function gatewayChat(key: string, data: { message: string; sessionId?: string; userId?: string }) {
  return agentRequest.post<ChatResponse>(`/api/v1/agents/${key}/chat`, data)
}
