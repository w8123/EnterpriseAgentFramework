import { modelRequest } from './request'
import type { ProviderInfo, ModelChatRequest, ModelChatResponse } from '@/types/model'
import type { ApiResult } from '@/types/import'

export function getProviders() {
  return modelRequest.get<ApiResult<ProviderInfo[]>>('/providers')
}

export function testProvider(name: string) {
  return modelRequest.post<ApiResult<Record<string, unknown>>>(`/providers/${name}/test`)
}

export function modelChat(data: ModelChatRequest) {
  return modelRequest.post<ApiResult<ModelChatResponse>>('/chat', data)
}

/**
 * SSE 流式模型对话 — 返回原始 Response
 */
export function modelChatStream(data: ModelChatRequest): Promise<Response> {
  return fetch('/model/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
}
