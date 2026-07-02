import { controlRequest } from './request'
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
  return controlRequest.post<AgentNodeDebugResult>('/api/workflows/studio/debug-node', data)
}

export function debugAgentWorkflowRun(data: AgentWorkflowDebugRunRequest) {
  return controlRequest.post<AgentWorkflowDebugRunResult>('/api/workflows/studio/debug-run', data)
}

export function createExecutableDebugSession(data: ExecutableDebugSessionCreateRequest) {
  return controlRequest.post<ExecutableDebugSessionView>('/api/runtime/debug-sessions', data)
}

export function getExecutableDebugSession(sessionId: string) {
  return controlRequest.get<ExecutableDebugSessionView>(`/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}`)
}

export function submitExecutableDebugSession(sessionId: string, data: ExecutableDebugSessionSubmitRequest) {
  return controlRequest.post<ExecutableDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}/submit`,
    data,
  )
}

export function cancelExecutableDebugSession(sessionId: string) {
  return controlRequest.post<ExecutableDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}/cancel`,
  )
}

export function generateWorkflowDraft(data: WorkflowDraftGenerationRequest) {
  return controlRequest.post<WorkflowDraftGenerationResult>('/api/workflows/studio/generate-draft', data)
}

export function editWorkflowDraft(data: WorkflowDraftEditRequest) {
  return controlRequest.post<WorkflowDraftEditResult>('/api/workflows/studio/edit-draft', data)
}

export function executeAgent(data: ChatRequest) {
  return controlRequest.post<ChatResponse>('/api/runtime/agents/execute', data)
}

export function listPendingHumanApprovals(params?: { agentId?: number | string; userId?: string; limit?: number }) {
  return controlRequest.get<PendingHumanApproval[]>('/api/runtime/interactions/human-approvals', { params })
}

export function submitHumanApproval(
  interactionId: string,
  data: { action?: string; values?: Record<string, unknown>; userId?: string; sessionId?: string },
) {
  return controlRequest.post<AgentResult>(
    `/api/runtime/interactions/human-approvals/${encodeURIComponent(interactionId)}/submit`,
    data,
  )
}

export function cancelHumanApproval(interactionId: string, userId?: string) {
  return controlRequest.delete<AgentResult>(
    `/api/runtime/interactions/human-approvals/${encodeURIComponent(interactionId)}`,
    { params: { userId } },
  )
}

export function executeAgentDetailed(data: ChatRequest) {
  return controlRequest.post<AgentResult>('/api/runtime/agents/execute/detailed', data)
}

/** @deprecated Agent 版本语义已逐步收敛为 Workflow 版本语义 */
export function listAgentVersions(agentId: string) {
  return controlRequest.get<AgentVersion[]>(`/api/agents/${agentId}/versions`)
}

/** @deprecated Agent 版本语义已逐步收敛为 Workflow 版本语义 */
export function publishAgentVersion(agentId: string, data: PublishVersionRequest) {
  return controlRequest.post<AgentVersion>(`/api/agents/${agentId}/versions`, data)
}

/** @deprecated Agent 版本语义已逐步收敛为 Workflow 版本语义 */
export function listAgentReleaseEvents(agentId: string, limit = 100) {
  return controlRequest.get<AgentReleaseEvent[]>(`/api/agents/${agentId}/versions/events`, {
    params: { limit },
  })
}

/** @deprecated Agent 版本语义已逐步收敛为 Workflow 版本语义 */
export function validateAgentRelease(
  agentId: string,
  data?: { version?: string; rolloutPercent?: number; operator?: string },
) {
  return controlRequest.post<AgentReleaseValidationResult>(`/api/agents/${agentId}/versions/validate`, data ?? {})
}

/** @deprecated Agent 版本语义已逐步收敛为 Workflow 版本语义 */
export function rollbackAgentVersion(agentId: string, versionId: number, operator?: string) {
  return controlRequest.post<AgentVersion>(
    `/api/agents/${agentId}/versions/${versionId}/rollback`,
    { operator },
  )
}

/** 通过网关入口调用 Agent Chat（key 对应 keySlug）。 */
export function gatewayChat(key: string, data: { message: string; sessionId?: string; userId?: string }) {
  return controlRequest.post<ChatResponse>(`/api/v1/agents/${key}/chat`, data)
}
