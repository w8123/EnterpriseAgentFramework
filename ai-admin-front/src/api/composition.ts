import { agentRequest } from './request'
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
  return agentRequest.get<CompositionPageResult>(BASE, { params })
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
  return agentRequest.get<CompositionInfo>(`${BASE}/${encodeURIComponent(name)}`)
}

export function createComposition(data: CompositionUpsertRequest) {
  return agentRequest.post<CompositionInfo>(BASE, data)
}

export function updateComposition(name: string, data: CompositionUpsertRequest) {
  return agentRequest.put<CompositionInfo>(`${BASE}/${encodeURIComponent(name)}`, data)
}

export function deleteComposition(name: string) {
  return agentRequest.delete(`${BASE}/${encodeURIComponent(name)}`)
}

export function toggleComposition(name: string, enabled: boolean) {
  return agentRequest.put<CompositionInfo>(`${BASE}/${encodeURIComponent(name)}/toggle`, { enabled })
}

export function testComposition(name: string, args: Record<string, unknown>) {
  return agentRequest.post<CompositionTestResult>(`${BASE}/${encodeURIComponent(name)}/test`, { args })
}

/** 交互式表单能力挂起后继续（确认卡 / 表单批交等） */
export function testCompositionResume(
  name: string,
  body: { interactionId: string; action?: string; values?: Record<string, unknown> },
) {
  return agentRequest.post<CompositionTestResult>(`${BASE}/${encodeURIComponent(name)}/test/resume`, body)
}

export function getCompositionMetrics(name: string, days = 7) {
  return agentRequest.get<CompositionMetrics>(`${BASE}/${encodeURIComponent(name)}/metrics`, {
    params: { days },
  })
}

export function getAdminTestPendingInteractions() {
  return agentRequest.get<CompositionAdminTestPendingItem[]>(`${BASE}/pending-interactions/admin-test`)
}

export function cancelAdminTestPendingInteraction(interactionId: string) {
  return agentRequest.delete(`${BASE}/pending-interactions/admin-test/${encodeURIComponent(interactionId)}`)
}

export function cancelAllAdminTestPendingInteractions() {
  return agentRequest.post<{ cancelled: number }>(`${BASE}/pending-interactions/admin-test/cancel-all`)
}
