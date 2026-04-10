import { ref } from 'vue'

export interface SSEOptions {
  onChunk?: (chunk: string) => void
  onDone?: (fullText: string) => void
  onError?: (error: Error) => void
}

/**
 * 通用 SSE 流式输出 composable。
 * 后端返回 text/event-stream，前端通过 fetch + ReadableStream 消费。
 */
export function useSSE() {
  const content = ref('')
  const isStreaming = ref(false)
  const error = ref<Error | null>(null)
  let abortController: AbortController | null = null

  async function start(url: string, body: object, options?: SSEOptions) {
    content.value = ''
    error.value = null
    isStreaming.value = true
    abortController = new AbortController()

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: abortController.signal,
      })

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
      }

      const reader = response.body!.getReader()
      const decoder = new TextDecoder()

      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        const chunk = decoder.decode(value, { stream: true })
        content.value += chunk
        options?.onChunk?.(chunk)
      }

      options?.onDone?.(content.value)
    } catch (e) {
      if ((e as Error).name !== 'AbortError') {
        error.value = e as Error
        options?.onError?.(e as Error)
      }
    } finally {
      isStreaming.value = false
      abortController = null
    }
  }

  function stop() {
    abortController?.abort()
  }

  return { content, isStreaming, error, start, stop }
}
