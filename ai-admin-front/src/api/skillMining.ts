import { agentRequest } from './request'

export interface SkillMiningPrecheck {
  days: number
  logCount: number
  traceCount: number
  multiStepTraceCount: number
  readyForMining: boolean
  recommendedScenarios: string[]
}

export interface SkillDraft {
  id: number
  name: string
  description: string
  status: string
  sourceTraceIds?: string
  specJson?: string
  confidenceScore?: number
  reviewNote?: string
}

export interface DemoTraceResult {
  scenario: string
  traceCount: number
  insertedLogCount: number
  sequence: string[]
}

export function getSkillMiningPrecheck(days = 7) {
  return agentRequest.get<SkillMiningPrecheck>('/api/skill-mining/precheck', { params: { days } })
}

export function generateSkillDrafts(data: { days: number; minSupport: number; limit: number }) {
  return agentRequest.post<SkillDraft[]>('/api/skill-mining/drafts/generate', data)
}

export function listSkillDrafts() {
  return agentRequest.get<SkillDraft[]>('/api/skill-mining/drafts')
}

export function updateSkillDraftStatus(id: number, data: { status: string; reviewNote?: string }) {
  return agentRequest.post(`/api/skill-mining/drafts/${id}/status`, data)
}

export function publishSkillDraft(id: number) {
  return agentRequest.post(`/api/skill-mining/drafts/${id}/publish`)
}

/**
 * Phase 3.0：Trace → Skill 一键抽取。
 * toolNames 可选，未传则用 trace 里的完整 tool 序列。
 */
export function extractDraftFromTrace(data: { traceId: string; toolNames?: string[] }) {
  return agentRequest.post<SkillDraft>('/api/skill-mining/drafts/from-trace', data)
}

export function generateDemoTraces(data: {
  scenario?: string
  traceCount?: number
  successRate?: number
  noiseRate?: number
}) {
  return agentRequest.post<DemoTraceResult>('/api/skill-mining/demo-traces/generate', data)
}

export function clearDemoTraces() {
  return agentRequest.post<{ deleted: number }>('/api/skill-mining/demo-traces/clear')
}
