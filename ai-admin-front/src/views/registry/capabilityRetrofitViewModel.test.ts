import { describe, expect, it } from 'vitest'
import type { CapabilityRetrofitOverview } from '@/api/aiCodingTasks'
import type { ToolInfo } from '@/types/tool'
import {
  currentRetrofitStage,
  matchCatalogTool,
  retrofitStages,
  selectionAnswer,
} from './capabilityRetrofitViewModel'

function overview(patch: Partial<CapabilityRetrofitOverview>): CapabilityRetrofitOverview {
  return {
    schema: 'reachai.capability-retrofit-overview.v1',
    taskId: 'ait_1',
    executionStatus: 'RUNNING',
    selection: null,
    report: null,
    mode: 'SDK_DECLARATION',
    capabilityStates: [],
    ...patch,
  }
}

describe('capability retrofit view model', () => {
  it('moves through stages only on platform facts', () => {
    expect(currentRetrofitStage(null)).toBe('INVENTORY')
    const selection = { questionId: 'retrofit-select-1', status: 'OPEN', answered: false, selected: [] }
    expect(currentRetrofitStage(overview({ selection }))).toBe('SELECT')
    const answered = { ...selection, status: 'ANSWERED', answered: true, selected: ['a'] }
    expect(currentRetrofitStage(overview({ selection: answered }))).toBe('IMPLEMENT')
    const report = { summary: 'done' }
    expect(currentRetrofitStage(overview({ selection: answered, report, capabilityStates: [{ name: 'a', state: 'PENDING_REVIEW' }] }))).toBe('REVIEW')
    expect(currentRetrofitStage(overview({ selection: answered, report, capabilityStates: [] }))).toBe('REVIEW')
    expect(currentRetrofitStage(overview({ selection: answered, report, capabilityStates: [{ name: 'a', state: 'ACCEPTED' }] }))).toBe('PUBLISH')
  })

  it('marks earlier stages done', () => {
    const stages = retrofitStages(overview({ selection: { questionId: 'q', status: 'OPEN', answered: false, selected: [] } }))
    expect(stages.map((stage) => stage.status)).toEqual(['done', 'current', 'todo', 'todo', 'todo'])
  })

  it('builds the selection answer the backend parses', () => {
    expect(JSON.parse(selectionAnswer(['a', 'b'], '退货先不开'))).toEqual({
      schema: 'reachai.capability-retrofit-selection.v1', selected: ['a', 'b'], note: '退货先不开',
    })
  })

  it('matches catalog tools by qualified name tail within the project', () => {
    const tools = [
      { name: 'other:erpStockPage', qualifiedName: 'other:erpStockPage', projectCode: 'other', assetType: 'BUSINESS_METHOD', enabled: true },
      { name: 'yudao-erp:erpReceivablePage', qualifiedName: 'yudao-erp:erpReceivablePage', projectCode: 'yudao-erp', assetType: 'BUSINESS_METHOD', enabled: true },
    ] as ToolInfo[]
    const matched = matchCatalogTool({ name: 'erpReceivablePage', state: 'ACCEPTED', qualifiedName: 'yudao-erp:erpReceivablePage' }, tools, 'yudao-erp')
    expect(matched?.name).toBe('yudao-erp:erpReceivablePage')
    expect(matchCatalogTool({ name: 'erpStockPage', state: 'ACCEPTED' }, tools, 'yudao-erp')).toBeUndefined()
  })
})
