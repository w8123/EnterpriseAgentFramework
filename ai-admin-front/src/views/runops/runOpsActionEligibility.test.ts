import { describe, expect, it } from 'vitest'
import { canOfferWorkflowCandidate, canReplayRunType } from './runOpsActionEligibility'

describe('RunOps action eligibility', () => {
  it.each(['AGENT', 'WORKFLOW'] as const)('keeps workflow actions for %s runs', (runType) => {
    expect(canOfferWorkflowCandidate(runType)).toBe(true)
    expect(canReplayRunType(runType)).toBe(true)
  })

  it.each(['MCP', 'CONSOLE_HTTP_API', 'CONSOLE_CAPABILITY'] as const)('does not offer workflow actions for %s runs', (runType) => {
    expect(canOfferWorkflowCandidate(runType)).toBe(false)
    expect(canReplayRunType(runType)).toBe(false)
  })

  it('does not guess actions before the run type is known', () => {
    expect(canOfferWorkflowCandidate()).toBe(false)
    expect(canReplayRunType()).toBe(false)
  })
})
