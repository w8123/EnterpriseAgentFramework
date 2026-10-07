import type { RouteLocationRaw } from 'vue-router'
import type { AssetUsage } from '@/types/assetReferences'

export function referenceKindLabel(kind: string) {
  return ({ WORKFLOW: 'Workflow', AGENT: 'Agent', MCP: 'MCP 发布', A2A: 'A2A 发布' } as Record<string, string>)[kind] || kind
}

export function usageRoute(usage: AssetUsage): RouteLocationRaw | null {
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


export function referenceStage(stage?: string): string {
  return ({ DRAFT: '草稿', PUBLISHED: '已发布', HISTORICAL: '历史版本', EXTERNAL_PIN: '开放固定版本' } as Record<string, string>)[stage || ''] || stage || '状态未提供'
}
