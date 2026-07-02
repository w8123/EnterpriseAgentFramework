import { controlRequest } from './request'
import type { ChatRequest, ChatResponse } from '@/types/chat'

export function sendChat(data: ChatRequest) {
  return controlRequest.post<ChatResponse>('/api/chat', data)
}

export function clearSession(sessionId: string) {
  return controlRequest.delete(`/api/chat/session/${sessionId}`)
}

/**
 * SSE 娴佸紡瀵硅瘽 鈥?杩斿洖鍘熷 Response 渚?ReadableStream 娑堣垂
 */
export function chatStream(data: ChatRequest): Promise<Response> {
  return fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
}
