import type { RouteLocationRaw } from 'vue-router'
import type { CapabilityUsage } from '@/api/tool'

export function referenceKindLabel(kind: string) {
  return ({ WORKFLOW: 'Workflow', AGENT: 'Agent', MCP: 'MCP 发布', A2A: 'A2A 发布' } as Record<string, string>)[kind] || kind
}

export function usageRoute(usage: CapabilityUsage): RouteLocationRaw | null {
  if (usage.kind === 'WORKFLOW') {
    const query: Record<string, string> = {}
    if (usage.nodeId) query.nodeId = usage.nodeId
    if (usage.stage !== 'DRAFT' && usage.versionId) query.versionId = String(usage.versionId)
    return { name: usage.stage === 'DRAFT' ? 'WorkflowStudio' : 'WorkflowVersions',
      params: { workflowId: String(usage.id) }, ...(Object.keys(query).length ? { query } : {}) }
  }
  if (usage.kind === 'AGENT') return { name: 'AgentEdit', params: { id: String(usage.id) } }
  if (usage.kind === 'MCP') return { name: 'McpHubPublicationDetail', params: { id: String(usage.id) } }
  return null
}
