import { controlRequest } from './request'
import type {
  ApiMarketCategory,
  ApiMarketEntryDetail,
  ApiMarketIntegration,
  ApiMarketIntegrationCreateRequest,
  ApiMarketPage,
  ApiMarketSource,
  ApiMarketStats,
} from '@/types/apiMarket'

export function listApiMarketEntries(params?: {
  current?: number
  size?: number
  keyword?: string
  category?: string
  authType?: string
  verificationStatus?: string
  specStatus?: string
  source?: string
  specAvailable?: boolean
}) {
  return controlRequest.get<ApiMarketPage>('/api/api-market/entries', { params })
}

export function getApiMarketEntry(entryKey: string) {
  return controlRequest.get<ApiMarketEntryDetail>(
    `/api/api-market/entries/${encodeURIComponent(entryKey)}`,
  )
}

export function getApiMarketStats() {
  return controlRequest.get<ApiMarketStats>('/api/api-market/stats')
}

export function listApiMarketCategories() {
  return controlRequest.get<ApiMarketCategory[]>('/api/api-market/categories')
}

export function listApiMarketSources() {
  return controlRequest.get<ApiMarketSource[]>('/api/api-market/sources')
}

export function listApiMarketIntegrations(params?: {
  projectId?: number
  projectCode?: string
  status?: string
}) {
  return controlRequest.get<ApiMarketIntegration[]>('/api/api-market/integrations', { params })
}

export function createApiMarketIntegration(
  entryKey: string,
  data: ApiMarketIntegrationCreateRequest,
) {
  return controlRequest.post<ApiMarketIntegration>(
    `/api/api-market/entries/${encodeURIComponent(entryKey)}/integrations`,
    data,
  )
}

export function updateApiMarketIntegrationStatus(
  integrationId: number,
  data: { status: string; note?: string },
) {
  return controlRequest.put<ApiMarketIntegration>(
    `/api/api-market/integrations/${integrationId}/status`,
    data,
  )
}
