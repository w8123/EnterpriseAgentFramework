import { controlRequest } from './request'
import type { ToolInfo, ToolListQuery, ToolPageResult, ToolTestResult, ToolUpsertRequest } from '@/types/tool'

const TOOL_SELECTOR_PAGE_SIZE = 100
const TOOL_SELECTOR_MAX_PAGES = 20

export function getTools(params?: ToolListQuery) {
  return controlRequest.get<ToolPageResult>('/api/tools', { params })
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

export function getToolDetail(name: string) {
  return controlRequest.get<ToolInfo>(`/api/tools/${name}`)
}

export function createTool(data: ToolUpsertRequest) {
  return controlRequest.post<ToolInfo>('/api/tools', data)
}

export function updateTool(name: string, data: ToolUpsertRequest) {
  return controlRequest.put<ToolInfo>(`/api/tools/${name}`, data)
}

export function deleteTool(name: string) {
  return controlRequest.delete(`/api/tools/${name}`)
}

export function toggleTool(name: string, enabled: boolean) {
  return controlRequest.put<ToolInfo>(`/api/tools/${name}/toggle`, { enabled })
}

export function testTool(name: string, args: Record<string, unknown>) {
  return controlRequest.post<ToolTestResult>(`/api/tools/${name}/test`, { args })
}
