import { describe, expect, it } from 'vitest'
import {
  chunkStrategyForSplitType,
  endUserVisibilityHint,
  resolveKnowledgeDetailTab,
  usageFor,
  usageSummary,
} from './knowledgeDetailModel'

describe('knowledge detail model', () => {
  it('maps old tabs and unknown values onto the four tabs', () => {
    expect(resolveKnowledgeDetailTab('retrieval')).toBe('retrieval')
    expect(resolveKnowledgeDetailTab('policy')).toBe('settings')
    expect(resolveKnowledgeDetailTab('overview')).toBe('hits')
    expect(resolveKnowledgeDetailTab('tags')).toBe('files')
    expect(resolveKnowledgeDetailTab(undefined)).toBe('files')
  })

  it('uploads with the knowledge base split settings', () => {
    expect(chunkStrategyForSplitType('PARAGRAPH')).toBe('paragraph')
    expect(chunkStrategyForSplitType('SEMANTIC')).toBe('semantic')
    expect(chunkStrategyForSplitType('FIXED')).toBe('fixed_length')
    expect(chunkStrategyForSplitType(undefined)).toBe('fixed_length')
  })

  it('summarises which Workflows and Agents use a knowledge base', () => {
    expect(usageSummary(usageFor([], 'kb'))).toBe('还没有 Agent 使用')
    expect(usageSummary({
      knowledgeBaseCode: 'kb',
      workflows: [
        { id: 'w1', name: '知识问答', keySlug: 'kb-qa', status: 'ACTIVE', agents: [{ id: 'a1', name: 'ERP 助手' }] },
        { id: 'w2', name: '另一个', keySlug: 'x', status: 'DRAFT', agents: [{ id: 'a1', name: 'ERP 助手' }] },
      ],
    })).toBe('2 个 Workflow，Agent：ERP 助手')
    expect(usageSummary({
      knowledgeBaseCode: 'kb',
      workflows: [{ id: 'w1', name: '草稿', keySlug: 'd', status: 'DRAFT', agents: [] }],
      hiddenWorkflowCount: 1,
    })).toBe('2 个 Workflow，未接入 Agent')
  })

  it('tells operators whether end users see the same retrieval results', () => {
    expect(endUserVisibilityHint('ALL_CALLERS')).toContain('查到的就是下面这些段落')
    expect(endUserVisibilityHint('FILE_GRANTS')).toContain('仅授权文件')
  })
})
