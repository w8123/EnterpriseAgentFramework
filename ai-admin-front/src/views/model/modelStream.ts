import type {
  ModelStreamEvent,
  ModelStreamState,
  ModelStreamToolCall,
  ModelStreamToolCallDelta,
  TokenUsage,
} from '@/types/model'
import { MODEL_STREAM_INTERRUPTED } from '@/types/model'

export interface ParsedSseFrame {
  event: string
  rawData: string
  data: unknown
  id?: string
}

export function createEmptyModelStreamState(): ModelStreamState {
  return {
    content: '',
    reasoningContent: '',
    toolCalls: [],
    usage: null,
    finishReason: null,
    errorCode: null,
    errorMessage: null,
    terminal: null,
  }
}

/** Parse one complete SSE frame block (already split on blank line). */
export function parseSseFrame(block: string): ParsedSseFrame | null {
  const normalized = block.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const lines = normalized.split('\n')
  let event = 'message'
  let id: string | undefined
  const dataLines: string[] = []

  for (const line of lines) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
      continue
    }
    if (line.startsWith('id:')) {
      id = line.slice(3).trim()
      continue
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }

  if (!dataLines.length && event === 'message') return null
  const rawData = dataLines.join('\n')
  return {
    event,
    rawData,
    data: decodeJsonData(rawData),
    id,
  }
}

function decodeJsonData(rawData: string): unknown {
  const trimmed = rawData.trim()
  if (!trimmed) return ''
  try {
    return JSON.parse(trimmed)
  } catch {
    return { __invalidJson: true, raw: rawData }
  }
}

/** Incremental buffer parse supporting \\n\\n and \\r\\n\\r\\n boundaries. */
export function parseSseBuffer(buffer: string): { frames: ParsedSseFrame[]; rest: string } {
  const frames: ParsedSseFrame[] = []
  // Hold a trailing lone CR until the next chunk arrives so "\r" + "\n" is not
  // prematurely turned into a blank-line boundary between multi-line data fields.
  const holdTrailingCr = buffer.endsWith('\r')
  let rest = holdTrailingCr ? buffer.slice(0, -1) : buffer
  rest = rest.replace(/\r\n/g, '\n').replace(/\r/g, '\n')

  let boundary = rest.indexOf('\n\n')
  while (boundary >= 0) {
    const block = rest.slice(0, boundary)
    rest = rest.slice(boundary + 2)
    const frame = parseSseFrame(block)
    if (frame) frames.push(frame)
    boundary = rest.indexOf('\n\n')
  }
  if (holdTrailingCr) {
    rest += '\r'
  }
  return { frames, rest }
}

export function decodeModelStreamEvent(data: unknown): ModelStreamEvent | null {
  if (!data || typeof data !== 'object') return null
  if ('__invalidJson' in (data as Record<string, unknown>)) {
    return {
      type: 'error',
      code: 'MODEL_STREAM_INVALID_JSON',
      message: '模型流事件不是合法 JSON',
    }
  }
  const record = data as Record<string, unknown>
  const type = String(record.type || '')
  if (!type) return null
  return {
    type,
    text: typeof record.text === 'string' ? record.text : record.text == null ? null : String(record.text),
    toolCall: (record.toolCall as ModelStreamToolCallDelta | null | undefined) ?? null,
    usage: normalizeUsage(record.usage),
    finishReason:
      typeof record.finishReason === 'string'
        ? record.finishReason
        : record.finishReason == null
          ? null
          : String(record.finishReason),
    message: typeof record.message === 'string' ? record.message : null,
    code: typeof record.code === 'string' ? record.code : null,
    raw: record.raw,
  }
}

function normalizeUsage(raw: unknown): TokenUsage | null {
  if (!raw || typeof raw !== 'object') return null
  const u = raw as Record<string, unknown>
  const promptTokens = Number(u.promptTokens ?? u.prompt_tokens)
  const completionTokens = Number(u.completionTokens ?? u.completion_tokens)
  const totalTokens = Number(u.totalTokens ?? u.total_tokens)
  if ([promptTokens, completionTokens, totalTokens].some((n) => Number.isNaN(n))) return null
  return { promptTokens, completionTokens, totalTokens }
}

export function reduceModelStreamEvent(state: ModelStreamState, event: ModelStreamEvent): ModelStreamState {
  if (state.terminal) return state
  switch (event.type) {
    case 'content.delta':
      return { ...state, content: state.content + (event.text || '') }
    case 'reasoning.delta':
      return { ...state, reasoningContent: state.reasoningContent + (event.text || '') }
    case 'tool_call.delta':
      return { ...state, toolCalls: mergeToolCallDelta(state.toolCalls, event.toolCall) }
    case 'usage':
      return event.usage ? { ...state, usage: event.usage } : state
    case 'completed':
      return {
        ...state,
        finishReason: event.finishReason || state.finishReason,
        terminal: 'completed',
      }
    case 'error': {
      const code = event.code || null
      const interrupted = code === MODEL_STREAM_INTERRUPTED
      return {
        ...state,
        errorCode: code,
        errorMessage: event.message || (interrupted ? '模型流在返回最终结果前中断，请重试。' : '模型流失败'),
        terminal: interrupted ? 'interrupted' : 'error',
      }
    }
    default:
      return state
  }
}

export function mergeToolCallDelta(
  existing: ModelStreamToolCall[],
  delta: ModelStreamToolCallDelta | null | undefined,
): ModelStreamToolCall[] {
  if (!delta) return existing
  const index = typeof delta.index === 'number' ? delta.index : existing.length
  const next = existing.map((item) => ({ ...item }))
  const current = next.find((item) => item.index === index)
  if (!current) {
    next.push({
      index,
      id: delta.id || undefined,
      type: delta.type || undefined,
      name: delta.name || undefined,
      arguments: delta.arguments || '',
    })
    next.sort((a, b) => a.index - b.index)
    return next
  }
  if (delta.id) current.id = delta.id
  if (delta.type) current.type = delta.type
  if (delta.name) current.name = delta.name
  if (delta.arguments) current.arguments = (current.arguments || '') + delta.arguments
  return next
}

export function markStreamInterrupted(state: ModelStreamState): ModelStreamState {
  if (state.terminal) return state
  return {
    ...state,
    errorCode: MODEL_STREAM_INTERRUPTED,
    errorMessage: '模型流在返回最终结果前中断，请重试。',
    terminal: 'interrupted',
  }
}

export function markStreamAborted(state: ModelStreamState): ModelStreamState {
  if (state.terminal) return state
  return {
    ...state,
    terminal: 'aborted',
    errorCode: null,
    errorMessage: null,
  }
}

export function formatToolArguments(raw: string): string {
  const text = String(raw || '')
  if (!text.trim()) return ''
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}
