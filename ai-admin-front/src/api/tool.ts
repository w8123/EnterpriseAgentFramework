import { controlRequest } from './request'
import type {
  ToolInfo,
  ToolListQuery,
  ToolPageResult,
} from '@/types/tool'

const TOOL_SELECTOR_PAGE_SIZE = 100
const TOOL_SELECTOR_MAX_PAGES = 20

export function getTools(params?: ToolListQuery) {
  return controlRequest.get<ToolPageResult>('/api/tools', { params })
}

export function getTool(name: string) {
  return controlRequest.get<ToolInfo>(`/api/tools/${encodeURIComponent(name)}`)
}

export async function listAllTools(
  params: ToolListQuery = {},
  maxPages = TOOL_SELECTOR_MAX_PAGES,
): Promise<ToolInfo[]> {
  const records: ToolInfo[] = []
  let current = Math.max(1, params.current ?? 1)
  let pages = 1
  let loadedPages = 0

  do {
    const { data } = await getTools({
      ...params,
      current,
      size: TOOL_SELECTOR_PAGE_SIZE,
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
