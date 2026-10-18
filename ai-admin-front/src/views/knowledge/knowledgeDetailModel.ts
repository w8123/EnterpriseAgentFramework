import type { KnowledgeAccessMode, KnowledgeHitLog, KnowledgeUsage, KnowledgeWorkflowUsage } from '@/types/knowledge'

export type KnowledgeDetailTab = 'files' | 'retrieval' | 'hits' | 'settings'

export const KNOWLEDGE_DETAIL_TABS: KnowledgeDetailTab[] = ['files', 'retrieval', 'hits', 'settings']

/** Old tab names and the retired 文件入库 / 检索测试 pages land on the matching new tab. */
export function resolveKnowledgeDetailTab(value: unknown): KnowledgeDetailTab {
  const tab = String(value ?? '')
  if ((KNOWLEDGE_DETAIL_TABS as string[]).includes(tab)) return tab as KnowledgeDetailTab
  if (tab === 'policy') return 'settings'
  if (tab === 'overview') return 'hits'
  return 'files'
}

/** Knowledge base splitType (FIXED / PARAGRAPH / SEMANTIC) → import job chunkStrategy. */
export function chunkStrategyForSplitType(splitType?: string | null): string {
  switch ((splitType || '').toUpperCase()) {
    case 'PARAGRAPH':
      return 'paragraph'
    case 'SEMANTIC':
      return 'semantic'
    default:
      return 'fixed_length'
  }
}

export function accessModeLabel(mode?: KnowledgeAccessMode | null): string {
  return mode === 'FILE_GRANTS' ? '仅授权文件' : '所有调用方'
}

export function accessModeHint(mode?: KnowledgeAccessMode | null): string {
  return mode === 'FILE_GRANTS'
    ? '只有被单独授权文件的用户，才能在对话中查到该文件的内容。'
    : '调用使用本知识库的 Agent 的所有用户，都能在对话中查到本库内容。'
}

/** What an end user would see for the same question, shown next to the console retrieval test. */
export function endUserVisibilityHint(mode?: KnowledgeAccessMode | null): string {
  return mode === 'FILE_GRANTS'
    ? '本库设为「仅授权文件」：终端用户只能查到授权给他的文件里的段落，下面的结果可能比他看到的多。'
    : '本库设为「所有调用方」：终端用户在对话中查到的就是下面这些段落。'
}

export function usageFor(usages: KnowledgeUsage[] | null | undefined, code: string): KnowledgeUsage {
  return usages?.find((usage) => usage.knowledgeBaseCode === code)
    ?? { knowledgeBaseCode: code, workflows: [], hiddenWorkflowCount: 0 }
}

export function agentNames(workflows: KnowledgeWorkflowUsage[]): string[] {
  const names = new Set<string>()
  for (const workflow of workflows) {
    for (const agent of workflow.agents || []) {
      if (agent.name) names.add(agent.name)
    }
  }
  return [...names]
}

/** One line for the list column / detail banner. */
export function usageSummary(usage: KnowledgeUsage): string {
  const visible = usage.workflows.length
  const hidden = usage.hiddenWorkflowCount || 0
  if (visible === 0 && hidden === 0) return '还没有 Agent 使用'
  const agents = agentNames(usage.workflows)
  const parts = [`${visible + hidden} 个 Workflow`]
  if (agents.length > 0) parts.push(`Agent：${agents.join('、')}`)
  else if (visible > 0) parts.push('未接入 Agent')
  return parts.join('，')
}

export type HitSourceFilter = 'RUNTIME' | 'CONSOLE' | 'ALL'

export function hitSourceLabel(source?: KnowledgeHitLog['source']): string {
  return source === 'RUNTIME' ? '真实对话' : '检索测试'
}
