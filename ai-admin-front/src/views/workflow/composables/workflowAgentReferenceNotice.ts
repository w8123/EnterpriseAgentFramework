import { ElNotification } from 'element-plus'
import { formatWorkflowVersionLabel, listWorkflowAgentReferences } from '@/api/agentWorkflowFreshness'

/**
 * After a Workflow publish, tell the user which Agents still run an older pinned version.
 * Published Agent configs are never changed implicitly; the notice links to the versions
 * page where each Agent can be updated and re-published explicitly.
 */
export async function notifyStaleAgentReferences(workflowId: string, openVersions: () => void) {
  if (!workflowId) return
  let staleNames: string[] = []
  let latest = ''
  try {
    const { data } = await listWorkflowAgentReferences(workflowId, { errorFeedback: 'local' })
    const agents = Array.isArray(data?.agents) ? data.agents : []
    staleNames = agents.filter(agent => agent.stale).map(agent => agent.agentName || agent.keySlug || agent.agentId)
    latest = formatWorkflowVersionLabel(data?.latestWorkflowVersion)
  } catch {
    return
  }
  if (!staleNames.length) return
  const shown = staleNames.slice(0, 3).join('、') + (staleNames.length > 3 ? ` 等 ${staleNames.length} 个` : '')
  ElNotification({
    type: 'warning',
    title: `${staleNames.length} 个 Agent 仍在使用旧版本`,
    message: `${shown} 发布 Agent 后才会用到 ${latest}。点击前往版本页，可一键更新并发布。`,
    duration: 10000,
    onClick: openVersions,
  })
}
