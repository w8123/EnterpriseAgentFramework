import { agentRequest } from './request'

const BASE = '/api/capability-mining'

export interface CapabilityMiningPrecheck {
  logCount: number
  traceCount: number
  multiStepTraceCount: number
  readyForMining: boolean
  recommendedScenarios: string[]
}

/** 与后端 skill_draft 表序列化一致（legacy 存储命名） */
export interface CapabilityDraft {
  id: number
  name: string
  description?: string | null
  status: string
  confidenceScore?: number | null
  specJson?: string | null
  sourceTraceIds?: string | null
}

export function getCapabilityMiningPrecheck(days = 7) {
  return agentRequest.get<CapabilityMiningPrecheck>(`${BASE}/precheck`, { params: { days } })
}

export function generateCapabilityDrafts(data: { days: number; minSupport: number; limit: number }) {
  return agentRequest.post<CapabilityDraft[]>(`${BASE}/drafts/generate`, data)
}

export function listCapabilityDrafts() {
  return agentRequest.get<CapabilityDraft[]>(`${BASE}/drafts`)
}

export function updateCapabilityDraftStatus(id: number, data: { status: string; reviewNote?: string }) {
  return agentRequest.post(`${BASE}/drafts/${id}/status`, data)
}

export function publishCapabilityDraft(id: number) {
  return agentRequest.post(`${BASE}/drafts/${id}/publish`)
}

/** Trace → 能力草稿一键抽取 */
export function extractDraftFromTrace(data: { traceId: string; toolNames?: string[] }) {
  return agentRequest.post<CapabilityDraft>(`${BASE}/drafts/from-trace`, data)
}

export function extractDraftFromCanvas(data: {
  agentName: string
  toolNames: string[]
  canvasJson?: string | null
}) {
  return agentRequest.post<CapabilityDraft>(`${BASE}/drafts/from-canvas`, data)
}

export interface DemoTraceResult {
  traceCount: number
  insertedLogCount: number
}

export function generateDemoTraces(data: {
  scenario: string
  traceCount: number
  successRate: number
  noiseRate: number
}) {
  return agentRequest.post<DemoTraceResult>(`${BASE}/demo-traces/generate`, data)
}

export function clearDemoTraces() {
  return agentRequest.post<{ deleted: number }>(`${BASE}/demo-traces/clear`)
}
