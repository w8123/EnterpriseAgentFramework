import { controlRequest } from './request'
import type {
  Agent,
  AgentStatistics,
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
  PageAssistantWorkflowAttachRequest,
  PageAssistantWorkflowAttachmentResult,
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
import type { AgentConfigDraft, AgentConfigVersion } from '@/types/agent'

export function listAgents(params?: {
  projectId?: number
  projectCode?: string
}) {
  return controlRequest.get<Agent[]>('/api/agents', { params })
}

export function getAgentStatistics(params?: {
  projectId?: number
  projectCode?: string
}) {
  return controlRequest.get<AgentStatistics>('/api/agents/statistics', { params })
}

export function getAgent(id: string) {
  return controlRequest.get<Agent>(`/api/agents/${encodeURIComponent(id)}`)
}

export function createAgent(data: Partial<Agent>) {
  return controlRequest.post<Agent>('/api/agents', data)
}

export function updateAgent(id: string, data: Partial<Agent>) {
  return controlRequest.put<Agent>(`/api/agents/${encodeURIComponent(id)}`, data)
}

export function deleteAgent(id: string) {
  return controlRequest.delete(`/api/agents/${encodeURIComponent(id)}`)
}

export function listAgentConfigVersions(agentId: string) {
  return controlRequest.get<AgentConfigVersion[]>(
    `/api/agents/${encodeURIComponent(agentId)}/config-versions`,
  )
}

export function saveAgentConfigDraft(agentId: string, data: AgentConfigDraft) {
  return controlRequest.put<AgentConfigVersion>(
    `/api/agents/${encodeURIComponent(agentId)}/config-versions/draft`,
    data,
  )
}

export function publishAgentConfig(agentId: string, configVersionId: number, publishedBy?: string) {
  return controlRequest.post<AgentConfigVersion>(
    `/api/agents/${encodeURIComponent(agentId)}/config-versions/${configVersionId}/publish`,
    { publishedBy },
  )
}

export function copyAgentConfigToDraft(agentId: string, configVersionId: number) {
  return controlRequest.post<AgentConfigVersion>(
    `/api/agents/${encodeURIComponent(agentId)}/config-versions/${configVersionId}/copy-to-draft`,
  )
}

export function listWorkflows(params?: {
  projectId?: number
  projectCode?: string
  workflowType?: string
  status?: string
}) {
  return controlRequest.get<WorkflowDefinition[]>('/api/workflows', { params })
}

export interface WorkflowSearchPage {
  records: WorkflowDefinition[]
  total: number
  current: number
  size: number
}

export function searchWorkflows(params: {
  projectId?: number
  projectCode?: string
  workflowType?: string
  status?: string
  keyword?: string
  current?: number
  size?: number
}) {
  return controlRequest.get<WorkflowSearchPage>('/api/workflows/search', { params })
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
  return controlRequest.put<WorkflowStudioState>(
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

/** Workflow Studio 可恢复调试会话（GraphSpec-native，targetType=WORKFLOW_DRAFT） */
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

export function attachPageAssistantWorkflowTool(workflowId: string, data: PageAssistantWorkflowAttachRequest) {
  return controlRequest.post<PageAssistantWorkflowAttachmentResult>(
    `/api/workflows/${encodeURIComponent(workflowId)}/page-assistant/attach-tool`,
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
