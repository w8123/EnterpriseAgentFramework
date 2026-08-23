import { controlRequest } from './request'
import type { AgentResult, PendingHumanApproval } from '@/types/agent'
import type { ChatRequest } from '@/types/chat'
import { parseSseStream } from '@/conversation/core/parseSseStream'
import { getPlatformToken } from '@/utils/platformAuth'

export {
  listAgents,
  getAgent,
  createAgent,
  updateAgent,
  deleteAgent,
} from './workflow'

export function listPendingHumanApprovals(params?: { agentId?: string; userId?: string; limit?: number }) {
  return controlRequest.get<PendingHumanApproval[]>('/api/runtime/interactions/human-approvals', { params })
}

export interface AgentExecutionStreamEvent {
  event: string
  data: unknown
}

export interface AgentExecutionStreamHandlers {
  onEvent?: (event: AgentExecutionStreamEvent) => void
  onMessageDelta?: (text: string) => void
}

/**
 * 执行 Supervisor Agent 并消费结构化 SSE 事件。
 * 事件包含 execution.started、supervisor.step、message.delta、ui.requested、execution.completed。
 */
export async function executeAgentStream(
  data: ChatRequest,
  handlers: AgentExecutionStreamHandlers = {},
  signal?: AbortSignal,
): Promise<AgentResult> {
  const token = getPlatformToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream',
  }
  if (token) headers.Authorization = `Bearer ${token}`

  const response = await fetch('/api/runtime/agents/execute/stream', {
    method: 'POST',
    headers,
    body: JSON.stringify(data),
    signal,
  })
  if (!response.ok || !response.body) {
    throw new Error(`Agent 流式执行失败：HTTP ${response.status}`)
  }
  const contentType = response.headers.get('content-type') || ''
  if (!contentType.toLowerCase().includes('text/event-stream')) {
    const rawBody = await response.text()
    let detail = rawBody.trim()
    try {
      const payload = JSON.parse(rawBody) as { message?: unknown }
      if (payload?.message) detail = String(payload.message)
    } catch {
      // 非 JSON 响应保留原始文本，便于识别代理、鉴权或路由错误。
    }
    throw new Error(detail || `Agent 流式执行返回了非 SSE 响应：${contentType || 'unknown'}`)
  }

  let completed: AgentResult | undefined
  let streamError: Error | undefined

  for await (const frame of parseSseStream(response.body, { signal })) {
    const streamEvent = { event: frame.event, data: frame.data }
    handlers.onEvent?.(streamEvent)
    if (frame.event === 'message.delta') {
      const payload = frame.data
      const text = payload && typeof payload === 'object' && 'text' in payload
        ? String((payload as { text?: unknown }).text ?? '')
        : String(payload ?? '')
      if (text) handlers.onMessageDelta?.(text)
    } else if (frame.event === 'execution.completed') {
      completed = frame.data as AgentResult
    } else if (frame.event === 'execution.error') {
      const payload = frame.data
      const message = payload && typeof payload === 'object' && 'message' in payload
        ? String((payload as { message?: unknown }).message ?? 'Agent 流式执行失败')
        : String(payload || 'Agent 流式执行失败')
      streamError = new Error(message)
    }
    if (streamError) throw streamError
  }
  if (!completed) throw new Error('Agent 流式执行未返回 completion 事件')
  return completed
}

export function clearAgentSession(sessionId: string) {
  return controlRequest.delete(`/api/runtime/agents/sessions/${encodeURIComponent(sessionId)}`)
}
