import { describe, expect, it } from 'vitest'
import { publishedWorkflowRisk } from './publishedWorkflowRisk'
import type { WorkflowVersion } from '@/types/workflow'

function version(snapshot: unknown, nodes = ['USER_INPUT', 'TOOL', 'ANSWER']): WorkflowVersion {
  return { id: 1, workflowId: 'shop', version: '1', status: 'ACTIVE',
    snapshotJson: JSON.stringify(snapshot), graphSpecSnapshotJson: JSON.stringify({ nodes: nodes.map(type => ({ type })) }) }
}

describe('published Workflow risk', () => {
  it('uses the highest declared owner floor of the active published version', () => {
    expect(publishedWorkflowRisk([version({ businessMethodRiskFloor: 'WRITE', httpApiRiskClassified: true })])).toBe('WRITE')
    expect(publishedWorkflowRisk([version({ businessMethodRiskFloor: 'IRREVERSIBLE', httpApiRiskFloor: 'WRITE' })])).toBe('IRREVERSIBLE')
    expect(publishedWorkflowRisk([version({ businessMethodRiskFloor: 'READ', httpApiRiskFloor: 'WRITE' })])).toBe('WRITE')
  })

  it('only proposes read when the declared floors and simple graph support it', () => {
    const snapshot = { businessMethodRiskFloor: 'READ', httpApiRiskClassified: true }
    expect(publishedWorkflowRisk([version(snapshot)])).toBe('READ')
    expect(publishedWorkflowRisk([version(snapshot, ['USER_INPUT', 'HTTP_REQUEST', 'ANSWER'])])).toBeUndefined()
    expect(publishedWorkflowRisk([version({})])).toBeUndefined()
    expect(publishedWorkflowRisk([{ ...version(snapshot), snapshotJson: '{' }])).toBeUndefined()
    expect(publishedWorkflowRisk([{ ...version(snapshot), status: 'RETIRED' }])).toBeUndefined()
    expect(publishedWorkflowRisk([version(snapshot), version(snapshot)])).toBeUndefined()
  })
})
