import { controlRequest } from './request'
import type {
  ToolRetrievalSearchRequest,
  ToolRetrievalSearchResponse,
} from '@/types/toolRetrieval'

export function searchToolRetrieval(payload: ToolRetrievalSearchRequest) {
  return controlRequest.post<ToolRetrievalSearchResponse>('/api/tool-retrieval/search', payload)
}
