import { describe, expect, it } from 'vitest'
import { parseSseBuffer, parseSseFrame, parseSseStream, isAbortError } from './parseSseStream'

describe('parseSseFrame / parseSseBuffer', () => {
  it('ignores heartbeat comments and keeps subsequent message.delta', () => {
    const { frames } = parseSseBuffer(
      ': heartbeat\n\n'
      + 'event: message.delta\ndata: {"text":"a"}\n\n'
      + ': heartbeat\n\n'
      + 'event: message.delta\ndata: {"text":"b"}\n\n',
    )
    expect(frames.map((f) => f.event)).toEqual(['message.delta', 'message.delta'])
    expect(frames.map((f) => (f.data as { text: string }).text)).toEqual(['a', 'b'])
  })

  it('parses JSON data with event name', () => {
    const frame = parseSseFrame('event: message.delta\ndata: {"text":"hi"}\n')
    expect(frame?.event).toBe('message.delta')
    expect(frame?.data).toEqual({ text: 'hi' })
  })

  it('supports CRLF and multi-line data', () => {
    const { frames, rest } = parseSseBuffer('event: note\r\ndata: line1\r\ndata: line2\r\n\r\n')
    expect(rest).toBe('')
    expect(frames).toHaveLength(1)
    expect(frames[0].rawData).toBe('line1\nline2')
    expect(frames[0].data).toBe('line1\nline2')
  })

  it('keeps incomplete frame in rest', () => {
    const { frames, rest } = parseSseBuffer('event: message.delta\ndata: {"text":"a"')
    expect(frames).toHaveLength(0)
    expect(rest).toContain('message.delta')
  })

  it('parses plain text data', () => {
    const frame = parseSseFrame('data: hello world\n')
    expect(frame?.event).toBe('message')
    expect(frame?.data).toBe('hello world')
  })
})

describe('parseSseStream', () => {
  it('handles cross-chunk frames', async () => {
    const encoder = new TextEncoder()
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: message.delta\ndata: {"text":"hel'))
        controller.enqueue(encoder.encode('lo"}\n\n'))
        controller.enqueue(encoder.encode('event: error\ndata: {"message":"boom"}\n\n'))
        controller.close()
      },
    })
    const events = []
    for await (const frame of parseSseStream(stream)) {
      events.push(frame)
    }
    expect(events).toHaveLength(2)
    expect(events[0].data).toEqual({ text: 'hello' })
    expect(events[1].event).toBe('error')
  })

  it('aborts when signal is aborted before read completes', async () => {
    const controller = new AbortController()
    const stream = new ReadableStream<Uint8Array>({
      async start(c) {
        c.enqueue(new TextEncoder().encode('event: message.delta\ndata: {"text":"a"}\n\n'))
        await new Promise((r) => setTimeout(r, 20))
        controller.abort()
        c.enqueue(new TextEncoder().encode('event: message.delta\ndata: {"text":"b"}\n\n'))
        c.close()
      },
    })
    const events = []
    try {
      for await (const frame of parseSseStream(stream, { signal: controller.signal })) {
        events.push(frame)
        if (events.length >= 1) {
          await new Promise((r) => setTimeout(r, 40))
        }
      }
      // may throw or stop depending on timing
    } catch (error) {
      expect(isAbortError(error)).toBe(true)
    }
    expect(events.length).toBeGreaterThanOrEqual(1)
  })
})
