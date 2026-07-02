import { controlRequest } from './request'

const BASE = '/api/capability-mining'

export interface CapabilityMiningPrecheck {
  logCount: number
  traceCount: number
  multiStepTraceCount: number
  readyForMining: boolean
  recommendedScenarios: string[]
}

/** 后续流程把 skill_draft 能力草稿统一转换为能力草稿（legacy 名称保留，仅作兼容）。 */
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
  return controlRequest.get<CapabilityMiningPrecheck>(`${BASE}/precheck`, { params: { days } })
}

export function generateCapabilityDrafts(data: { days: number; minSupport: number; limit: number }) {
  return controlRequest.post<CapabilityDraft[]>(`${BASE}/drafts/generate`, data)
}

export function listCapabilityDrafts() {
  return controlRequest.get<CapabilityDraft[]>(`${BASE}/drafts`)
}

export function updateCapabilityDraftStatus(id: number, data: { status: string; reviewNote?: string }) {
  return controlRequest.post(`${BASE}/drafts/${id}/status`, data)
}

export function publishCapabilityDraft(id: number) {
  return controlRequest.post(`${BASE}/drafts/${id}/publish`)
}

/** Trace 仅做聚合分析后产生草稿能力；输出保持兼容字段。 */
export function extractDraftFromTrace(data: { traceId: string; toolNames?: string[] }) {
  return controlRequest.post<CapabilityDraft>(`${BASE}/drafts/from-trace`, data)
}

export function extractDraftFromCanvas(data: {
  agentName: string
  toolNames: string[]
  canvasJson?: string | null
}) {
  return controlRequest.post<CapabilityDraft>(`${BASE}/drafts/from-canvas`, data)
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
  return controlRequest.post<DemoTraceResult>(`${BASE}/demo-traces/generate`, data)
}

export function clearDemoTraces() {
  return controlRequest.post<{ deleted: number }>(`${BASE}/demo-traces/clear`)
}
