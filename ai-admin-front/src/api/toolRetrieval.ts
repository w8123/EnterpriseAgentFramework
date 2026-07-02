import { controlRequest } from './request'
import type {
  ToolRebuildStartResponse,
  ToolRebuildTask,
  ToolRetrievalRebuildRequest,
  ToolRetrievalSearchRequest,
  ToolRetrievalSearchResponse,
} from '@/types/toolRetrieval'

export function searchToolRetrieval(payload: ToolRetrievalSearchRequest) {
  return controlRequest.post<ToolRetrievalSearchResponse>('/api/tool-retrieval/search', payload)
}

export function startToolRetrievalRebuild(payload?: ToolRetrievalRebuildRequest) {
  return controlRequest.post<ToolRebuildStartResponse>('/api/tool-retrieval/rebuild', payload ?? {})
}

export function getToolRetrievalRebuildStatus(taskId?: string) {
  return controlRequest.get<ToolRebuildTask | null>('/api/tool-retrieval/rebuild/status', {
    params: taskId ? { taskId } : {},
  })
}
