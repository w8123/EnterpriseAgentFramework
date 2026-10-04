import type { RunType } from '@/types/runops'

export function canOfferWorkflowCandidate(runType?: RunType): boolean {
  return runType === 'AGENT' || runType === 'WORKFLOW'
}

export function canReplayRunType(runType?: RunType): boolean {
  return canOfferWorkflowCandidate(runType)
}
