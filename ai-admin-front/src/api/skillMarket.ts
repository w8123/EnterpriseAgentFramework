import { controlRequest } from './request'
import type {
  SkillMarketImportOrigin,
  SkillMarketImportRequest,
  SkillMarketImportResult,
  SkillMarketProbeRequest,
  SkillMarketProbeResult,
  SkillMarketSearchResult,
  SkillMarketSource,
} from '@/types/skillMarket'

export function searchSkillMarket(params: {
  provider?: string
  query?: string
  view?: string
  owner?: string
  limit?: number
}) {
  return controlRequest.get<SkillMarketSearchResult>('/api/skill-market/search', { params })
}

export function listSkillMarketSources() {
  return controlRequest.get<SkillMarketSource[]>('/api/skill-market/sources')
}

export function listSkillMarketImports(limit = 50) {
  return controlRequest.get<SkillMarketImportOrigin[]>('/api/skill-market/imports', {
    params: { limit },
  })
}

export function probeSkillMarketSource(body: SkillMarketProbeRequest) {
  return controlRequest.post<SkillMarketProbeResult>('/api/skill-market/probes', body, {
    timeout: 120000,
  })
}

export function importSkillMarketCandidate(body: SkillMarketImportRequest) {
  return controlRequest.post<SkillMarketImportResult>('/api/skill-market/imports', body, {
    timeout: 120000,
  })
}
