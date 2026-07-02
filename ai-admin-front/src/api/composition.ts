import { controlRequest } from './request'
import type {
  CompositionAdminTestPendingItem,
  CompositionInfo,
  CompositionListQuery,
  CompositionMetrics,
  CompositionPageResult,
  CompositionTestResult,
  CompositionUpsertRequest,
} from '@/types/composition'

const BASE = '/api/compositions'
const COMPOSITION_SELECTOR_PAGE_SIZE = 100
const COMPOSITION_SELECTOR_MAX_PAGES = 20

export function listCompositions(params?: CompositionListQuery) {
  return controlRequest.get<CompositionPageResult>(BASE, { params })
}

export async function listAllCompositions(
  params: CompositionListQuery = {},
  maxPages = COMPOSITION_SELECTOR_MAX_PAGES,
): Promise<CompositionInfo[]> {
  const records: CompositionInfo[] = []
  let current = Math.max(1, params.current ?? 1)
  let pages = 1
  let loadedPages = 0

  do {
    const { data } = await listCompositions({
      ...params,
      current,
      size: COMPOSITION_SELECTOR_PAGE_SIZE,
    })
    if (Array.isArray(data?.records)) {
      records.push(...data.records)
    }
    pages = Math.max(1, Number(data?.pages || 1))
    current += 1
    loadedPages += 1
  } while (current <= pages && loadedPages < maxPages)

  return records
}

export function getCompositionDetail(name: string) {
  return controlRequest.get<CompositionInfo>(`${BASE}/${encodeURIComponent(name)}`)
}

export function createComposition(data: CompositionUpsertRequest) {
  return controlRequest.post<CompositionInfo>(BASE, data)
}

export function updateComposition(name: string, data: CompositionUpsertRequest) {
  return controlRequest.put<CompositionInfo>(`${BASE}/${encodeURIComponent(name)}`, data)
}

export function deleteComposition(name: string) {
  return controlRequest.delete(`${BASE}/${encodeURIComponent(name)}`)
}

export function toggleComposition(name: string, enabled: boolean) {
  return controlRequest.put<CompositionInfo>(`${BASE}/${encodeURIComponent(name)}/toggle`, { enabled })
}

export function testComposition(name: string, args: Record<string, unknown>) {
  return controlRequest.post<CompositionTestResult>(`${BASE}/${encodeURIComponent(name)}/test`, { args })
}

/** 浜や簰寮忚〃鍗曡兘鍔涙寕璧峰悗缁х画锛堢‘璁ゅ崱 / 琛ㄥ崟鎵逛氦绛夛級 */
export function testCompositionResume(
  name: string,
  body: { interactionId: string; action?: string; values?: Record<string, unknown> },
) {
  return controlRequest.post<CompositionTestResult>(`${BASE}/${encodeURIComponent(name)}/test/resume`, body)
}

export function getCompositionMetrics(name: string, days = 7) {
  return controlRequest.get<CompositionMetrics>(`${BASE}/${encodeURIComponent(name)}/metrics`, {
    params: { days },
  })
}

export function getAdminTestPendingInteractions() {
  return controlRequest.get<CompositionAdminTestPendingItem[]>(`${BASE}/pending-interactions/admin-test`)
}

export function cancelAdminTestPendingInteraction(interactionId: string) {
  return controlRequest.delete(`${BASE}/pending-interactions/admin-test/${encodeURIComponent(interactionId)}`)
}

export function cancelAllAdminTestPendingInteractions() {
  return controlRequest.post<{ cancelled: number }>(`${BASE}/pending-interactions/admin-test/cancel-all`)
}
