export interface ParsedSseFrame {
  event: string
  data: unknown
  rawData: string
  id?: string
}

export interface ParseSseOptions {
  signal?: AbortSignal
}

function parseDataPayload(rawData: string): unknown {
  const trimmed = rawData.trim()
  if (!trimmed) return ''
  try {
    return JSON.parse(trimmed)
  } catch {
    return rawData
  }
}

/**
 * 将已完整的 SSE frame 文本解析为事件。
 * 支持多行 data:、event:、id:。
 */
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
    data: parseDataPayload(rawData),
    id,
  }
}

/**
 * 增量缓冲解析：返回已完成 frames 与剩余 buffer。
 * 同时识别 \n\n 与 \r\n\r\n 分帧。
 */
export function parseSseBuffer(buffer: string): { frames: ParsedSseFrame[]; rest: string } {
  const frames: ParsedSseFrame[] = []
  let rest = buffer.replace(/\r\n/g, '\n').replace(/\r/g, '\n')

  let boundary = rest.indexOf('\n\n')
  while (boundary >= 0) {
    const block = rest.slice(0, boundary)
    rest = rest.slice(boundary + 2)
    const frame = parseSseFrame(block)
    if (frame) frames.push(frame)
    boundary = rest.indexOf('\n\n')
  }

  return { frames, rest }
}

/**
 * 从 ReadableStream 解析 SSE，产出 { event, data }。
 * 支持跨 chunk 不完整 frame、Abort、纯文本/JSON data。
 */
export async function* parseSseStream(
  stream: ReadableStream<Uint8Array>,
  options: ParseSseOptions = {},
): AsyncGenerator<ParsedSseFrame, void, undefined> {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  const signal = options.signal

  const throwIfAborted = () => {
    if (signal?.aborted) {
      const err = new DOMException('The operation was aborted.', 'AbortError')
      throw err
    }
  }

  try {
    while (true) {
      throwIfAborted()
      const { done, value } = await reader.read()
      if (done) break
      throwIfAborted()
      buffer += decoder.decode(value, { stream: true })
      const parsed = parseSseBuffer(buffer)
      buffer = parsed.rest
      for (const frame of parsed.frames) {
        yield frame
      }
    }
    buffer += decoder.decode()
    if (buffer.trim()) {
      const frame = parseSseFrame(buffer.trim())
      if (frame) yield frame
    }
  } finally {
    try {
      reader.releaseLock()
    } catch {
      // ignore
    }
  }
}

export function isAbortError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const name = (error as { name?: string }).name
  return name === 'AbortError'
}
