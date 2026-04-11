import { ref } from 'vue'

export interface SSEOptions {
  onChunk?: (chunk: string) => void
  onDone?: (fullText: string) => void
  onError?: (error: Error) => void
}

/**
 * 解析 SSE text/event-stream 行缓冲区，提取 data 字段的文本内容。
 *
 * SSE 规范：
 *   event: message\n
 *   data: 具体内容\n
 *   \n            <- 空行表示一个事件结束
 *
 * 只取 `data:` 行的值，跳过 `event:`、`id:`、`:` (注释) 等行。
 * 若 data 值为 "[DONE]" 则表示流结束（OpenAI 风格），直接忽略。
 */
function extractDataFromSSELines(raw: string): string {
  let result = ''
  const lines = raw.split('\n')
  for (const line of lines) {
    if (line.startsWith('data:')) {
      const value = line.slice(5).trimStart()
      if (value === '[DONE]') continue
      result += value
    }
    // event:、id:、注释行(:开头)、空行 — 全部忽略
  }
  return result
}

/**
 * 通用 SSE 流式输出 composable。
 * 后端返回 text/event-stream，前端通过 fetch + ReadableStream 消费。
 * 自动解析 SSE 协议，content 中只保留纯文本内容。
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
      // 跨 chunk 的行缓冲（防止一行被拆成两个 chunk）
      let lineBuffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        lineBuffer += decoder.decode(value, { stream: true })

        // 按换行符切割，保留最后一个不完整行留到下次
        const newlineIdx = lineBuffer.lastIndexOf('\n')
        if (newlineIdx === -1) continue

        const completePart = lineBuffer.slice(0, newlineIdx + 1)
        lineBuffer = lineBuffer.slice(newlineIdx + 1)

        const parsed = extractDataFromSSELines(completePart)
        if (parsed) {
          content.value += parsed
          options?.onChunk?.(parsed)
        }
      }

      // 处理最后残留的缓冲
      if (lineBuffer) {
        const parsed = extractDataFromSSELines(lineBuffer)
        if (parsed) {
          content.value += parsed
          options?.onChunk?.(parsed)
        }
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
