import { agentRequest } from './request'
import type {
  AgentEntry,
  AgentWorkflowBinding,
  AgentWorkflowResolveRequest,
  PublishWorkflowVersionRequest,
  WorkflowReleaseValidationResult,
  WorkflowGraphNodeTypeDescriptor,
  WorkflowDefinition,
  WorkflowDefinitionDraft,
  WorkflowDebugRunRequest,
  WorkflowDebugRunResult,
  WorkflowDraftEditRequest,
  WorkflowDraftEditResult,
  WorkflowDraftGenerationRequest,
  WorkflowDraftGenerationResult,
  WorkflowRuntimeValidationRequest,
  WorkflowRuntimeValidationResult,
  WorkflowNodeDebugRequest,
  WorkflowNodeDebugResult,
  WorkflowDebugSessionCreateRequest,
  WorkflowDebugSessionSubmitRequest,
  WorkflowDebugSessionView,
  WorkflowStudioSaveRequest,
  WorkflowStudioState,
  WorkflowVersion,
} from '@/types/workflow'

export function listAgentEntries(params?: {
  projectId?: number
  projectCode?: string
  agentKind?: string
}) {
  return agentRequest.get<AgentEntry[]>('/api/agents', { params })
}

export function getAgentEntry(id: string) {
  return agentRequest.get<AgentEntry>(`/api/agents/${encodeURIComponent(id)}`)
}

export function createAgentEntry(data: Partial<AgentEntry>) {
  return agentRequest.post<AgentEntry>('/api/agents', data)
}

export function updateAgentEntry(id: string, data: Partial<AgentEntry>) {
  return agentRequest.put<AgentEntry>(`/api/agents/${encodeURIComponent(id)}`, data)
}

export function deleteAgentEntry(id: string) {
  return agentRequest.delete(`/api/agents/${encodeURIComponent(id)}`)
}

export function listWorkflows(params?: {
  projectId?: number
  projectCode?: string
  workflowType?: string
  status?: string
}) {
  return agentRequest.get<WorkflowDefinition[]>('/api/workflows', { params })
}

export function getWorkflow(id: string) {
  return agentRequest.get<WorkflowDefinition>(`/api/workflows/${encodeURIComponent(id)}`)
}

export function getWorkflowStudio(id: string) {
  return agentRequest.get<WorkflowStudioState>(`/api/workflows/${encodeURIComponent(id)}/studio`)
}

export function createWorkflow(data: WorkflowDefinitionDraft) {
  return agentRequest.post<WorkflowDefinition>('/api/workflows', normalizeWorkflowDraft(data))
}

export function updateWorkflow(id: string, data: WorkflowDefinitionDraft) {
  return agentRequest.put<WorkflowDefinition>(
    `/api/workflows/${encodeURIComponent(id)}`,
    normalizeWorkflowDraft(data),
  )
}

export function saveWorkflowStudio(id: string, data: WorkflowStudioSaveRequest) {
  return agentRequest.put<WorkflowDefinition>(
    `/api/workflows/${encodeURIComponent(id)}/studio`,
    data,
  )
}

export function getWorkflowGraphNodeTypes() {
  return agentRequest.get<WorkflowGraphNodeTypeDescriptor[]>('/api/workflows/graph-node-types')
}

export function validateWorkflowRuntime(data: WorkflowRuntimeValidationRequest) {
  return agentRequest.post<WorkflowRuntimeValidationResult>('/api/workflows/runtime-validation', data)
}

export function generateWorkflowDraft(data: WorkflowDraftGenerationRequest) {
  return agentRequest.post<WorkflowDraftGenerationResult>('/api/workflows/studio/generate-draft', data)
}

export function editWorkflowDraft(data: WorkflowDraftEditRequest) {
  return agentRequest.post<WorkflowDraftEditResult>('/api/workflows/studio/edit-draft', data)
}

export function debugWorkflowNode(data: WorkflowNodeDebugRequest) {
  return agentRequest.post<WorkflowNodeDebugResult>('/api/workflows/studio/debug-node', data)
}

export function debugWorkflowRun(data: WorkflowDebugRunRequest) {
  return agentRequest.post<WorkflowDebugRunResult>('/api/workflows/studio/debug-run', data)
}

/** Workflow Studio 可恢复调试会话（GraphSpec-native，targetType=WORKFLOW_DRAFT） */
export function createWorkflowDebugSession(data: WorkflowDebugSessionCreateRequest) {
  return agentRequest.post<WorkflowDebugSessionView>('/api/runtime/debug-sessions', data)
}

export function getWorkflowDebugSession(sessionId: string) {
  return agentRequest.get<WorkflowDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}`,
  )
}

export function submitWorkflowDebugSession(sessionId: string, data: WorkflowDebugSessionSubmitRequest) {
  return agentRequest.post<WorkflowDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}/submit`,
    data,
  )
}

export function cancelWorkflowDebugSession(sessionId: string) {
  return agentRequest.post<WorkflowDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}/cancel`,
  )
}

export function deleteWorkflow(id: string) {
  return agentRequest.delete(`/api/workflows/${encodeURIComponent(id)}`)
}

export function listWorkflowVersions(workflowId: string) {
  return agentRequest.get<WorkflowVersion[]>(
    `/api/workflows/${encodeURIComponent(workflowId)}/versions`,
  )
}

export function publishWorkflowVersion(workflowId: string, data: PublishWorkflowVersionRequest) {
  return agentRequest.post<WorkflowVersion>(
    `/api/workflows/${encodeURIComponent(workflowId)}/versions/publish`,
    data,
  )
}

export function validateWorkflowVersion(workflowId: string) {
  return agentRequest.post<WorkflowReleaseValidationResult>(
    `/api/workflows/${encodeURIComponent(workflowId)}/versions/validate`,
  )
}

export function rollbackWorkflowVersion(workflowId: string, versionId: number | string, operator?: string) {
  return agentRequest.post<WorkflowVersion>(
    `/api/workflows/${encodeURIComponent(workflowId)}/versions/${versionId}/rollback`,
    { operator },
  )
}

export function listAgentWorkflowBindings(agentId: string) {
  return agentRequest.get<AgentWorkflowBinding[]>(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-bindings`,
  )
}

export function createAgentWorkflowBinding(agentId: string, data: Partial<AgentWorkflowBinding>) {
  return agentRequest.post<AgentWorkflowBinding>(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-bindings`,
    data,
  )
}

export function resolveAgentWorkflowBinding(agentId: string, data: AgentWorkflowResolveRequest) {
  return agentRequest.post<AgentWorkflowBinding>(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-bindings/resolve-preview`,
    data,
  )
}

function normalizeWorkflowDraft(data: WorkflowDefinitionDraft) {
  if (!data.graphSpec) {
    return data
  }
  const { graphSpec, ...rest } = data
  return {
    ...rest,
    graphSpecJson: rest.graphSpecJson ?? JSON.stringify(graphSpec),
  }
}
