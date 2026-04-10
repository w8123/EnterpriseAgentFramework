import { agentRequest } from './request'
import type { ChatRequest, ChatResponse } from '@/types/chat'

export function sendChat(data: ChatRequest) {
  return agentRequest.post<ChatResponse>('/api/chat', data)
}

export function clearSession(sessionId: string) {
  return agentRequest.delete(`/api/chat/session/${sessionId}`)
}

/**
 * SSE 流式对话 — 返回原始 Response 供 ReadableStream 消费
 */
export function chatStream(data: ChatRequest): Promise<Response> {
  return fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
}
