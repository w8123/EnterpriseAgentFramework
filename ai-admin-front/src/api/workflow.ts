import { controlRequest } from './request'
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
  PageAssistantWorkflowBindRequest,
  PageAssistantWorkflowBindingResult,
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
  return controlRequest.get<AgentEntry[]>('/api/agents', { params })
}

export function getAgentEntry(id: string) {
  return controlRequest.get<AgentEntry>(`/api/agents/${encodeURIComponent(id)}`)
}

export function createAgentEntry(data: Partial<AgentEntry>) {
  return controlRequest.post<AgentEntry>('/api/agents', data)
}

export function updateAgentEntry(id: string, data: Partial<AgentEntry>) {
  return controlRequest.put<AgentEntry>(`/api/agents/${encodeURIComponent(id)}`, data)
}

export function deleteAgentEntry(id: string) {
  return controlRequest.delete(`/api/agents/${encodeURIComponent(id)}`)
}

export function listWorkflows(params?: {
  projectId?: number
  projectCode?: string
  workflowType?: string
  status?: string
}) {
  return controlRequest.get<WorkflowDefinition[]>('/api/workflows', { params })
}

export function getWorkflow(id: string) {
  return controlRequest.get<WorkflowDefinition>(`/api/workflows/${encodeURIComponent(id)}`)
}

export function getWorkflowStudio(id: string) {
  return controlRequest.get<WorkflowStudioState>(`/api/workflows/${encodeURIComponent(id)}/studio`)
}

export function createWorkflow(data: WorkflowDefinitionDraft) {
  return controlRequest.post<WorkflowDefinition>('/api/workflows', normalizeWorkflowDraft(data))
}

export function updateWorkflow(id: string, data: WorkflowDefinitionDraft) {
  return controlRequest.put<WorkflowDefinition>(
    `/api/workflows/${encodeURIComponent(id)}`,
    normalizeWorkflowDraft(data),
  )
}

export function saveWorkflowStudio(id: string, data: WorkflowStudioSaveRequest) {
  return controlRequest.put<WorkflowDefinition>(
    `/api/workflows/${encodeURIComponent(id)}/studio`,
    data,
  )
}

export function getWorkflowGraphNodeTypes() {
  return controlRequest.get<WorkflowGraphNodeTypeDescriptor[]>('/api/workflows/graph-node-types')
}

export function validateWorkflowRuntime(data: WorkflowRuntimeValidationRequest) {
  return controlRequest.post<WorkflowRuntimeValidationResult>('/api/workflows/runtime-validation', data)
}

export function generateWorkflowDraft(data: WorkflowDraftGenerationRequest) {
  return controlRequest.post<WorkflowDraftGenerationResult>('/api/workflows/studio/generate-draft', data)
}

export function editWorkflowDraft(data: WorkflowDraftEditRequest) {
  return controlRequest.post<WorkflowDraftEditResult>('/api/workflows/studio/edit-draft', data)
}

export function debugWorkflowNode(data: WorkflowNodeDebugRequest) {
  return controlRequest.post<WorkflowNodeDebugResult>('/api/workflows/studio/debug-node', data)
}

export function debugWorkflowRun(data: WorkflowDebugRunRequest) {
  return controlRequest.post<WorkflowDebugRunResult>('/api/workflows/studio/debug-run', data)
}

/** Workflow Studio 鍙仮澶嶈皟璇曚細璇濓紙GraphSpec-native锛宼argetType=WORKFLOW_DRAFT锛?*/
export function createWorkflowDebugSession(data: WorkflowDebugSessionCreateRequest) {
  return controlRequest.post<WorkflowDebugSessionView>('/api/runtime/debug-sessions', data)
}

export function getWorkflowDebugSession(sessionId: string) {
  return controlRequest.get<WorkflowDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}`,
  )
}

export function submitWorkflowDebugSession(sessionId: string, data: WorkflowDebugSessionSubmitRequest) {
  return controlRequest.post<WorkflowDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}/submit`,
    data,
  )
}

export function cancelWorkflowDebugSession(sessionId: string) {
  return controlRequest.post<WorkflowDebugSessionView>(
    `/api/runtime/debug-sessions/${encodeURIComponent(sessionId)}/cancel`,
  )
}

export function deleteWorkflow(id: string) {
  return controlRequest.delete(`/api/workflows/${encodeURIComponent(id)}`)
}

export function listWorkflowVersions(workflowId: string) {
  return controlRequest.get<WorkflowVersion[]>(
    `/api/workflows/${encodeURIComponent(workflowId)}/versions`,
  )
}

export function publishWorkflowVersion(workflowId: string, data: PublishWorkflowVersionRequest) {
  return controlRequest.post<WorkflowVersion>(
    `/api/workflows/${encodeURIComponent(workflowId)}/versions/publish`,
    data,
  )
}

export function validateWorkflowVersion(workflowId: string) {
  return controlRequest.post<WorkflowReleaseValidationResult>(
    `/api/workflows/${encodeURIComponent(workflowId)}/versions/validate`,
  )
}

export function rollbackWorkflowVersion(workflowId: string, versionId: number | string, operator?: string) {
  return controlRequest.post<WorkflowVersion>(
    `/api/workflows/${encodeURIComponent(workflowId)}/versions/${versionId}/rollback`,
    { operator },
  )
}

export function listAgentWorkflowBindings(agentId: string) {
  return controlRequest.get<AgentWorkflowBinding[]>(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-bindings`,
  )
}

export function createAgentWorkflowBinding(agentId: string, data: Partial<AgentWorkflowBinding>) {
  return controlRequest.post<AgentWorkflowBinding>(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-bindings`,
    data,
  )
}

export function updateAgentWorkflowBinding(
  agentId: string,
  bindingId: number,
  data: Partial<AgentWorkflowBinding>,
) {
  return controlRequest.put<AgentWorkflowBinding>(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-bindings/${bindingId}`,
    data,
  )
}

export function deleteAgentWorkflowBinding(agentId: string, bindingId: number) {
  return controlRequest.delete(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-bindings/${bindingId}`,
  )
}

export function resolveAgentWorkflowBinding(agentId: string, data: AgentWorkflowResolveRequest) {
  return controlRequest.post<AgentWorkflowBinding>(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-bindings/resolve-preview`,
    data,
  )
}

export function bindPageAssistantWorkflow(workflowId: string, data: PageAssistantWorkflowBindRequest) {
  return controlRequest.post<PageAssistantWorkflowBindingResult>(
    `/api/workflows/${encodeURIComponent(workflowId)}/page-assistant/bind`,
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
