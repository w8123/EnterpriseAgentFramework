import { controlRequest } from './request'
import type {
  Agent,
  AgentStatistics,
  PublishWorkflowVersionRequest,
  WorkflowReleaseValidationResult,
  WorkflowGraphNodeTypeDescriptor,
  WorkflowWorkingCopy,
  WorkflowWorkingCopyInput,
  WorkflowWorkingCopyUpdateInput,
  WorkflowDebugRunRequest,
  WorkflowDebugRunResult,
  WorkflowProposalEditRequest,
  WorkflowProposalEditResult,
  WorkflowProposalGenerationRequest,
  WorkflowProposalGenerationResult,
  PageAssistantWorkflowAttachRequest,
  PageAssistantWorkflowAttachmentResult,
  WorkflowRuntimeValidationRequest,
  WorkflowRuntimeValidationResult,
  WorkflowNodeDebugRequest,
  WorkflowNodeDebugResult,
  WorkflowDebugSessionCreateRequest,
  WorkflowDebugSessionSubmitRequest,
  WorkflowDebugSessionView,
  SaveWorkflowWorkingCopyRequest,
  WorkflowWorkingCopyState,
  WorkflowVersion,
} from '@/types/workflow'
import type { AgentConfigDraft, AgentConfigVersion } from '@/types/agent'

const WORKFLOW_AI_AUTHORING_TIMEOUT_MS = 300000

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
  workflowKind?: string
  definitionAuthority?: string
  status?: string
}) {
  return controlRequest.get<WorkflowWorkingCopy[]>('/api/workflows', { params })
}

export interface WorkflowSearchPage {
  records: WorkflowWorkingCopy[]
  total: number
  current: number
  size: number
}

export function searchWorkflows(params: {
  projectId?: number
  projectCode?: string
  workflowKind?: string
  definitionAuthority?: string
  status?: string
  keyword?: string
  current?: number
  size?: number
}) {
  return controlRequest.get<WorkflowSearchPage>('/api/workflows/search', { params })
}

export function getWorkflow(id: string) {
  return controlRequest.get<WorkflowWorkingCopy>(`/api/workflows/${encodeURIComponent(id)}`)
}

export function getWorkflowWorkingCopy(id: string) {
  return controlRequest.get<WorkflowWorkingCopyState>(`/api/workflows/${encodeURIComponent(id)}/working-copy`)
}

export function createWorkflow(data: WorkflowWorkingCopyInput) {
  return controlRequest.post<WorkflowWorkingCopy>('/api/workflows', normalizeWorkingCopyInput(data))
}

export function updateWorkflow(id: string, data: WorkflowWorkingCopyUpdateInput) {
  return controlRequest.put<WorkflowWorkingCopy>(
    `/api/workflows/${encodeURIComponent(id)}`,
    normalizeWorkingCopyInput(data),
  )
}

export function saveWorkflowWorkingCopy(id: string, data: SaveWorkflowWorkingCopyRequest) {
  return controlRequest.put<WorkflowWorkingCopyState>(
    `/api/workflows/${encodeURIComponent(id)}/working-copy`,
    data,
  )
}

export function getWorkflowGraphNodeTypes() {
  return controlRequest.get<WorkflowGraphNodeTypeDescriptor[]>('/api/workflows/graph-node-types')
}

export function validateWorkflowRuntime(data: WorkflowRuntimeValidationRequest) {
  return controlRequest.post<WorkflowRuntimeValidationResult>('/api/workflows/runtime-validation', data)
}

export function generateWorkflowProposal(data: WorkflowProposalGenerationRequest) {
  return controlRequest.post<WorkflowProposalGenerationResult>(
    '/api/workflows/studio/proposals/generate',
    data,
    { timeout: WORKFLOW_AI_AUTHORING_TIMEOUT_MS },
  )
}

export function editWorkflowProposal(data: WorkflowProposalEditRequest) {
  return controlRequest.post<WorkflowProposalEditResult>(
    '/api/workflows/studio/proposals/edit',
    data,
    { timeout: WORKFLOW_AI_AUTHORING_TIMEOUT_MS },
  )
}

export function debugWorkflowNode(data: WorkflowNodeDebugRequest) {
  return controlRequest.post<WorkflowNodeDebugResult>('/api/workflows/studio/debug-node', data)
}

export function debugWorkflowRun(data: WorkflowDebugRunRequest) {
  return controlRequest.post<WorkflowDebugRunResult>('/api/workflows/studio/debug-run', data)
}

/** Workflow Studio 可恢复调试会话（GraphSpec-native，targetType=WORKFLOW_WORKING_COPY） */
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

export function rollbackWorkflowVersion(workflowId: string, versionId: number | string, baseRevision: string) {
  return controlRequest.post<WorkflowVersion>(
    `/api/workflows/${encodeURIComponent(workflowId)}/versions/${versionId}/rollback`,
    { baseRevision },
  )
}

export function attachPageAssistantWorkflowTool(workflowId: string, data: PageAssistantWorkflowAttachRequest) {
  return controlRequest.post<PageAssistantWorkflowAttachmentResult>(
    `/api/workflows/${encodeURIComponent(workflowId)}/page-assistant/attach-tool`,
    data,
  )
}

export function getWorkflowDebugSessionByCreationKey(key: string) {
  return controlRequest.get<WorkflowDebugSessionView>(
    `/api/runtime/debug-sessions/by-creation-key/${encodeURIComponent(key)}`,
  )
}

function normalizeWorkingCopyInput(data: WorkflowWorkingCopyInput) {
  if (!data.graphSpec) {
    return data
  }
  const { graphSpec, ...rest } = data
  return {
    ...rest,
    graphSpecJson: rest.graphSpecJson ?? JSON.stringify(graphSpec),
  }
}
