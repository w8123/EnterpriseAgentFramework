import { describe, expect, it } from 'vitest'
import type { EvalOpsDatasetSummary, EvalOpsExperimentSummary } from '@/types/agentEval'
import type { Agent } from '@/types/workflow'
import { buildEvalAgentRows, gateStatusView } from './evalOverview'

const agent = (id: string, name: string): Agent => ({ id, name, keySlug: id, projectCode: 'yudao-erp' })
const dataset = (id: number, targetId: string): EvalOpsDatasetSummary => ({
  id, tenantId: 'default', targetType: 'AGENT', targetId, name: `ds-${id}`, source: 'MANUAL', status: 'ACTIVE',
  currentVersionId: id, versionCount: 1,
})
const experiment = (id: number, targetId: string, createdAt: string): EvalOpsExperimentSummary => ({
  id, tenantId: 'default', targetType: 'AGENT', targetId, name: `exp-${id}`, datasetVersionId: 1,
  evaluatorSuiteVersionId: 1, repeatCount: 1, status: 'COMPLETED', variantCount: 2, taskCount: 2,
  completedTaskCount: 2, failedTaskCount: 0, gateStatus: 'PASSED', summary: {}, createdAt,
})

describe('eval overview', () => {
  it('groups datasets and experiments by agent, most recently tested first', () => {
    const rows = buildEvalAgentRows(
      [agent('a1', 'ERP 助手'), agent('a2', '报表助手'), agent('a3', '没有评测')],
      [dataset(1, 'a1'), dataset(2, 'a1'), dataset(3, 'a2')],
      [experiment(10, 'a1', '2026-10-16 10:00:00'), experiment(11, 'a2', '2026-10-17 09:00:00'), experiment(12, 'a1', '2026-10-17 08:00:00')],
    )
    expect(rows.map(row => [row.agentName, row.datasetCount, row.experimentCount, row.latestExperiment?.id])).toEqual([
      ['报表助手', 1, 1, 11],
      ['ERP 助手', 2, 2, 12],
    ])
  })

  it('keeps evals of agents the user cannot list, labelled by id', () => {
    const rows = buildEvalAgentRows([], [dataset(1, 'gone')], [])
    expect(rows).toEqual([{ agentId: 'gone', agentName: 'gone', projectCode: '', datasetCount: 1, experimentCount: 0 }])
  })

  it('explains release gate results in words', () => {
    expect(gateStatusView('PASSED').label).toBe('可发布')
    expect(gateStatusView('FAILED').tone).toBe('danger')
  })
})
