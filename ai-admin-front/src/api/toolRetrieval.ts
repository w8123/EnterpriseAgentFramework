import { agentRequest } from './request'
import type {
  ToolRebuildStartResponse,
  ToolRebuildTask,
  ToolRetrievalSearchRequest,
  ToolRetrievalSearchResponse,
} from '@/types/toolRetrieval'

export function searchToolRetrieval(payload: ToolRetrievalSearchRequest) {
  return agentRequest.post<ToolRetrievalSearchResponse>('/api/tool-retrieval/search', payload)
}

export function startToolRetrievalRebuild() {
  return agentRequest.post<ToolRebuildStartResponse>('/api/tool-retrieval/rebuild')
}

export function getToolRetrievalRebuildStatus(taskId?: string) {
  return agentRequest.get<ToolRebuildTask | null>('/api/tool-retrieval/rebuild/status', {
    params: taskId ? { taskId } : {},
  })
}
