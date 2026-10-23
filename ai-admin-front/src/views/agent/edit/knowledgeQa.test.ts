import { describe, expect, it, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: {}, query: {} }), useRouter: () => ({}) }))

import { knowledgeQaKeySlug } from './useAgentEditor'
import { agentEditScript, agentEditTemplate } from '../agentEditSources.testutil'

describe('knowledge bases on the Agent config page', () => {
  it('derives the same Workflow key as the Runtime knowledge Q&A service', () => {
    expect(knowledgeQaKeySlug('yudao-erp', 'erp_policy')).toBe('kb-qa-yudao-erp-erp_policy')
    expect(knowledgeQaKeySlug('Sales Team', 'HR 制度')).toBe('kb-qa-sales-team-hr')
  })

  it('lists knowledge bases in their own section and keeps them out of the Workflow list', () => {
    expect(agentEditTemplate).toContain('title="知识库"')
    expect(agentEditTemplate).toContain('in generalWorkflowEntries')
    expect(agentEditTemplate).not.toContain('in workflowTools"')
    expect(agentEditTemplate).toContain('修改只保存在草稿里')
    // 知识库只加入草稿，随 Agent 发布，不在添加时直接发布
    expect(agentEditScript).toContain('attachToAgent: false')
  })
})
