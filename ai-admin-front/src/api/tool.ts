import { controlRequest } from './request'
import type {
  ToolInfo,
  ToolListQuery,
  ToolPageResult,
} from '@/types/tool'

const TOOL_SELECTOR_PAGE_SIZE = 100
const TOOL_SELECTOR_MAX_PAGES = 20

export function getTools(params?: ToolListQuery, options: { errorFeedback?: 'global' | 'local' } = {}) {
  return controlRequest.get<ToolPageResult>('/api/tools', { params, ...options })
}

export async function listAllTools(
  params: ToolListQuery = {},
  maxPages = TOOL_SELECTOR_MAX_PAGES,
  options: { errorFeedback?: 'global' | 'local' } = {},
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
    }, options)
    if (Array.isArray(data?.records)) {
      records.push(...data.records)
    }
    pages = Math.max(1, Number(data?.pages || 1))
    current += 1
    loadedPages += 1
  } while (current <= pages && loadedPages < maxPages)

  return records
}
