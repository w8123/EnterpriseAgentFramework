import { controlRequest } from './request'
import type { AxiosRequestConfig } from 'axios'
import type {
  AgentWorkflowFreshness,
  PublishLatestAgentWorkflowsResult,
  WorkflowAgentReferences,
} from '@/types/agent'

/** Agents whose published (ACTIVE) config runs this Workflow, with pinned vs current version. */
export function listWorkflowAgentReferences(workflowId: string, config?: AxiosRequestConfig) {
  return controlRequest.get<WorkflowAgentReferences>(
    `/api/workflows/${encodeURIComponent(workflowId)}/agent-references`,
    config,
  )
}

/** Pinned vs current version of each enabled Workflow tool in the Agent's ACTIVE config. */
export function getAgentWorkflowFreshness(agentId: string, config?: AxiosRequestConfig) {
  return controlRequest.get<AgentWorkflowFreshness>(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-freshness`,
    config,
  )
}

/**
 * Publishes a copy of the ACTIVE config with stale Workflow tools moved to their current
 * published versions. Fails with 409 when the ACTIVE config is no longer the expected one.
 */
export function publishLatestAgentWorkflowVersions(
  agentId: string,
  expectedActiveConfigVersionId: number,
  publishedBy = 'admin-ui',
  config?: AxiosRequestConfig,
) {
  return controlRequest.post<PublishLatestAgentWorkflowsResult>(
    `/api/agents/${encodeURIComponent(agentId)}/workflow-freshness/publish-latest`,
    { expectedActiveConfigVersionId, publishedBy },
    config,
  )
}

export function agentWorkflowFreshnessFailure(error: unknown, fallback = '更新失败，请刷新后重试') {
  const failure = error as { response?: { status?: number; data?: { message?: string } } } | null
  if (failure?.response?.status === 409) return 'Agent 的线上配置刚被其他人更新，请刷新后再试'
  if (failure?.response?.status === 403) return '没有发布该 Agent 的权限'
  return failure?.response?.data?.message || (error instanceof Error && error.message) || fallback
}

/** "v1.0.2" and "1.0.2" both display as "v1.0.2". */
export function formatWorkflowVersionLabel(version?: string | null) {
  if (!version) return '未知版本'
  return /^v/i.test(version) ? version : `v${version}`
}
