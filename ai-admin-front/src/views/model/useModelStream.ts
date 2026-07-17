import { computed, ref } from 'vue'
import type { ModelChatRequest, ModelStreamState } from '@/types/model'
import {
  createEmptyModelStreamState,
  decodeModelStreamEvent,
  markStreamAborted,
  markStreamInterrupted,
  parseSseBuffer,
  reduceModelStreamEvent,
} from './modelStream'

export interface ModelStreamOptions {
  onEvent?: (state: ModelStreamState) => void
  onTerminal?: (state: ModelStreamState) => void
  onError?: (error: Error, state: ModelStreamState) => void
}

const STREAM_EVENTS_URL = '/model/chat/stream/events'

/**
 * Structured model stream consumer for POST /model/chat/stream/events.
 * No fallback to text stream or sync chat.
 */
export function useModelStream() {
  const state = ref<ModelStreamState>(createEmptyModelStreamState())
  const isStreaming = ref(false)
  const error = ref<Error | null>(null)
  let abortController: AbortController | null = null
  let userAborted = false
  let terminalNotified = false

  const content = computed(() => state.value.content)
  const reasoningContent = computed(() => state.value.reasoningContent)
  const toolCalls = computed(() => state.value.toolCalls)
  const usage = computed(() => state.value.usage)

  async function start(body: ModelChatRequest, options?: ModelStreamOptions) {
    // Clear previous round (including usage) before any network I/O.
    state.value = createEmptyModelStreamState()
    error.value = null
    userAborted = false
    terminalNotified = false
    isStreaming.value = true
    abortController = new AbortController()

    let reader: ReadableStreamDefaultReader<Uint8Array> | null = null

    try {
      const response = await fetch(STREAM_EVENTS_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        body: JSON.stringify(body),
        signal: abortController.signal,
      })

      if (!response.ok) {
        const message = `HTTP ${response.status}: ${response.statusText || '模型流请求失败'}`
        const next = reduceModelStreamEvent(state.value, {
          type: 'error',
          code: `HTTP_${response.status}`,
          message,
        })
        state.value = next
        const err = new Error(message)
        error.value = err
        options?.onError?.(err, next)
        notifyTerminal(options, next)
        return
      }

      if (!response.body) {
        const next = markStreamInterrupted(state.value)
        state.value = next
        const err = new Error(next.errorMessage || '模型流中断')
        error.value = err
        options?.onError?.(err, next)
        notifyTerminal(options, next)
        return
      }

      reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const parsed = parseSseBuffer(buffer)
        buffer = parsed.rest
        for (const frame of parsed.frames) {
          applyFrame(frame.data, options)
          if (state.value.terminal) {
            notifyTerminal(options, state.value)
            return
          }
        }
      }

      if (buffer.trim()) {
        const parsed = parseSseBuffer(`${buffer}\n\n`)
        for (const frame of parsed.frames) {
          applyFrame(frame.data, options)
          if (state.value.terminal) {
            notifyTerminal(options, state.value)
            return
          }
        }
      }

      if (!state.value.terminal) {
        const next = markStreamInterrupted(state.value)
        state.value = next
        const err = new Error(next.errorMessage || '模型流中断')
        error.value = err
        options?.onError?.(err, next)
        notifyTerminal(options, next)
      }
    } catch (e) {
      if (userAborted || (e as Error).name === 'AbortError') {
        const next = markStreamAborted(state.value)
        state.value = next
        notifyTerminal(options, next)
        return
      }
      const err = e instanceof Error ? e : new Error(String(e))
      error.value = err
      const next = reduceModelStreamEvent(state.value, {
        type: 'error',
        code: 'MODEL_STREAM_NETWORK_ERROR',
        message: err.message,
      })
      state.value = next
      options?.onError?.(err, next)
      notifyTerminal(options, next)
    } finally {
      await releaseReader(reader)
      isStreaming.value = false
      abortController = null
    }
  }

  function notifyTerminal(options: ModelStreamOptions | undefined, next: ModelStreamState) {
    if (terminalNotified) return
    terminalNotified = true
    options?.onTerminal?.(next)
  }

  async function releaseReader(reader: ReadableStreamDefaultReader<Uint8Array> | null) {
    if (!reader) return
    try {
      await reader.cancel()
    } catch {
      // Reader may already be closed after terminal/abort.
    }
    try {
      reader.releaseLock()
    } catch {
      // ignore
    }
  }

  function applyFrame(data: unknown, options?: ModelStreamOptions) {
    if (state.value.terminal) return
    const event = decodeModelStreamEvent(data)
    if (!event) return
    state.value = reduceModelStreamEvent(state.value, event)
    options?.onEvent?.(state.value)
  }

  function stop() {
    userAborted = true
    abortController?.abort()
  }

  return {
    state,
    content,
    reasoningContent,
    toolCalls,
    usage,
    isStreaming,
    error,
    start,
    stop,
  }
}
