import { describe, expect, it, vi } from 'vitest'
import {
  createEmptyModelStreamState,
  decodeModelStreamEvent,
  formatToolArguments,
  markStreamAborted,
  markStreamInterrupted,
  mergeToolCallDelta,
  parseSseBuffer,
  parseSseFrame,
  reduceModelStreamEvent,
} from './modelStream'
import { MODEL_STREAM_INTERRUPTED } from '@/types/model'

describe('modelStream SSE parsing', () => {
  it('parses frames split across chunks via buffer', () => {
    const first = parseSseBuffer('data: {"type":"content.delta","text":"hel')
    expect(first.frames).toEqual([])
    const second = parseSseBuffer(`${first.rest}lo"}\n\n`)
    expect(second.frames).toHaveLength(1)
    expect(decodeModelStreamEvent(second.frames[0].data)).toMatchObject({
      type: 'content.delta',
      text: 'hello',
    })
  })

  it('parses multiple events in one chunk', () => {
    const chunk =
      'data: {"type":"content.delta","text":"A"}\n\n' +
      'data: {"type":"content.delta","text":"B"}\n\n'
    const { frames } = parseSseBuffer(chunk)
    expect(frames).toHaveLength(2)
  })

  it('normalizes CRLF event boundaries', () => {
    const { frames } = parseSseBuffer(
      'data: {"type":"usage","usage":{"promptTokens":1,"completionTokens":2,"totalTokens":3}}\r\n\r\n',
    )
    expect(frames).toHaveLength(1)
    expect(decodeModelStreamEvent(frames[0].data)?.usage).toEqual({
      promptTokens: 1,
      completionTokens: 2,
      totalTokens: 3,
    })
  })

  it('preserves trailing CR across chunks so CR/LF split does not false-split a frame', () => {
    // Chunk ends after the CR of CRLF blank-line separator. Converting that lone
    // CR to LF early would turn "...}\n\r" into "...}\n\n" and emit a premature frame.
    const first = parseSseBuffer('data: {"type":"content.delta","text":"hello"}\n\r')
    expect(first.frames).toEqual([])
    expect(first.rest.endsWith('\r')).toBe(true)

    const second = parseSseBuffer(`${first.rest}\n`)
    expect(second.frames).toHaveLength(1)
    expect(decodeModelStreamEvent(second.frames[0].data)).toMatchObject({
      type: 'content.delta',
      text: 'hello',
    })
  })

  it('keeps multiline data as one event when CR/LF of blank line is split across chunks', () => {
    const chunk1 = 'data: {"type":"content.delta",\r\ndata: "text":"ab"}\r'
    const first = parseSseBuffer(chunk1)
    expect(first.frames).toEqual([])
    expect(first.rest.endsWith('\r')).toBe(true)

    const second = parseSseBuffer(`${first.rest}\n\r\n`)
    expect(second.frames).toHaveLength(1)
    const event = decodeModelStreamEvent(second.frames[0].data)
    // Joined multiline data should parse as one JSON object after join with \n
    expect(event?.type).toBe('content.delta')
    expect(event?.text).toBe('ab')
  })

  it('supports real multiline data and ignores comments', () => {
    const frame = parseSseFrame(
      ': keep-alive\nevent: message\ndata: {"type":"content.delta",\ndata: "text":"x"}\n',
    )
    expect(frame).not.toBeNull()
    expect(frame!.rawData).toBe('{"type":"content.delta",\n"text":"x"}')
    expect(decodeModelStreamEvent(frame!.data)?.text).toBe('x')
  })

  it('parses a structured event split into arbitrary byte chunks', () => {
    const full =
      'data: {"type":"tool_call.delta","toolCall":{"index":0,"id":"c1","name":"search","arguments":"{\\"q\\":"}}\n\n'
    let rest = ''
    const frames = []
    for (const piece of [full.slice(0, 17), full.slice(17, 41), full.slice(41)]) {
      const parsed = parseSseBuffer(rest + piece)
      frames.push(...parsed.frames)
      rest = parsed.rest
    }
    expect(frames).toHaveLength(1)
    expect(decodeModelStreamEvent(frames[0].data)?.type).toBe('tool_call.delta')
  })

  it('marks invalid JSON as structured error event', () => {
    const frame = parseSseFrame('data: {not-json}\n')
    const event = decodeModelStreamEvent(frame!.data)
    expect(event?.type).toBe('error')
    expect(event?.code).toBe('MODEL_STREAM_INVALID_JSON')
  })
})

describe('modelStream reducer', () => {
  it('keeps content and reasoning separated', () => {
    let state = createEmptyModelStreamState()
    state = reduceModelStreamEvent(state, { type: 'content.delta', text: '答' })
    state = reduceModelStreamEvent(state, { type: 'reasoning.delta', text: '想' })
    state = reduceModelStreamEvent(state, { type: 'content.delta', text: '案' })
    expect(state.content).toBe('答案')
    expect(state.reasoningContent).toBe('想')
  })

  it('aggregates tool call argument fragments by index', () => {
    let state = createEmptyModelStreamState()
    state = reduceModelStreamEvent(state, {
      type: 'tool_call.delta',
      toolCall: { index: 0, id: 'call_1', type: 'function', name: 'search', arguments: '{"q":"' },
    })
    state = reduceModelStreamEvent(state, {
      type: 'tool_call.delta',
      toolCall: { index: 0, arguments: 'hi"}' },
    })
    expect(state.toolCalls).toEqual([
      { index: 0, id: 'call_1', type: 'function', name: 'search', arguments: '{"q":"hi"}' },
    ])
    expect(formatToolArguments(state.toolCalls[0].arguments)).toContain('"q"')
  })

  it('applies usage and completed once', () => {
    let state = createEmptyModelStreamState()
    state = reduceModelStreamEvent(state, {
      type: 'usage',
      usage: { promptTokens: 3, completionTokens: 4, totalTokens: 7 },
    })
    state = reduceModelStreamEvent(state, { type: 'completed', finishReason: 'stop' })
    state = reduceModelStreamEvent(state, { type: 'content.delta', text: 'ignored' })
    expect(state.usage?.totalTokens).toBe(7)
    expect(state.terminal).toBe('completed')
    expect(state.content).toBe('')
    expect(state.finishReason).toBe('stop')
  })

  it('handles error without completed', () => {
    let state = createEmptyModelStreamState()
    state = reduceModelStreamEvent(state, { type: 'content.delta', text: 'partial' })
    state = reduceModelStreamEvent(state, {
      type: 'error',
      code: 'MODEL_UPSTREAM_ERROR',
      message: 'boom',
    })
    expect(state.terminal).toBe('error')
    expect(state.content).toBe('partial')
    expect(state.errorCode).toBe('MODEL_UPSTREAM_ERROR')
  })

  it('uses MODEL_STREAM_INTERRUPTED code for interrupted terminal', () => {
    const state = reduceModelStreamEvent(createEmptyModelStreamState(), {
      type: 'error',
      code: MODEL_STREAM_INTERRUPTED,
      message: '模型流在返回最终结果前中断，请重试。',
    })
    expect(state.terminal).toBe('interrupted')
    expect(state.errorCode).toBe(MODEL_STREAM_INTERRUPTED)
  })

  it('marks EOF without terminal as interrupted', () => {
    const state = markStreamInterrupted(createEmptyModelStreamState())
    expect(state.terminal).toBe('interrupted')
    expect(state.errorCode).toBe(MODEL_STREAM_INTERRUPTED)
  })

  it('marks abort without interrupted error code', () => {
    const state = markStreamAborted({
      ...createEmptyModelStreamState(),
      content: 'partial',
    })
    expect(state.terminal).toBe('aborted')
    expect(state.errorCode).toBeNull()
    expect(state.content).toBe('partial')
  })

  it('mergeToolCallDelta creates sorted indexes', () => {
    const merged = mergeToolCallDelta([], { index: 1, name: 'b', arguments: '2' })
    const again = mergeToolCallDelta(merged, { index: 0, name: 'a', arguments: '1' })
    expect(again.map((item) => item.index)).toEqual([0, 1])
  })
})

function sseChunks(parts: string[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  let i = 0
  return new ReadableStream({
    pull(controller) {
      if (i >= parts.length) {
        controller.close()
        return
      }
      controller.enqueue(encoder.encode(parts[i++]))
    },
  })
}

describe('useModelStream fetch lifecycle', () => {
  it('does not complete assistant twice and treats non-2xx as error', async () => {
    const terminals: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        statusText: 'Bad Gateway',
        body: null,
      }),
    )
    const { useModelStream } = await import('./useModelStream')
    const stream = useModelStream()
    await stream.start(
      { messages: [{ role: 'user', content: 'hi' }] },
      {
        onTerminal: (state) => terminals.push(state.terminal || ''),
      },
    )
    expect(terminals).toEqual(['error'])
    expect(stream.state.value.errorCode).toBe('HTTP_502')
    vi.unstubAllGlobals()
  })

  it('abort does not surface as interrupted', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((_url: string, init?: RequestInit) => {
        return new Promise((_resolve, reject) => {
          const signal = init?.signal
          if (!signal) {
            reject(new Error('missing abort signal'))
            return
          }
          if (signal.aborted) {
            const err = new Error('Aborted')
            err.name = 'AbortError'
            reject(err)
            return
          }
          signal.addEventListener('abort', () => {
            const err = new Error('Aborted')
            err.name = 'AbortError'
            reject(err)
          })
        })
      }),
    )
    const { useModelStream } = await import('./useModelStream')
    const stream = useModelStream()
    const done = stream.start({ messages: [{ role: 'user', content: 'hi' }] })
    await Promise.resolve()
    stream.stop()
    await done
    expect(stream.state.value.terminal).toBe('aborted')
    expect(stream.state.value.errorCode).toBeNull()
    vi.unstubAllGlobals()
  })

  it('consumes a full success stream once and clears previous usage', async () => {
    const terminals: string[] = []
    const cancelSpy = vi.fn().mockResolvedValue(undefined)
    const releaseSpy = vi.fn()

    const body = sseChunks([
      'data: {"type":"content.delta","text":"答"}\n\n',
      'data: {"type":"reasoning.delta","text":"想"}\n\n',
      'data: {"type":"tool_call.delta","toolCall":{"index":0,"id":"c1","name":"search","arguments":"{\\"q\\":\\""}}\n\n',
      'data: {"type":"tool_call.delta","toolCall":{"index":0,"arguments":"hi\\"}"}}\n\n',
      'data: {"type":"usage","usage":{"promptTokens":2,"completionTokens":3,"totalTokens":5}}\n\n',
      'data: {"type":"completed","finishReason":"stop"}\n\n',
    ])
    const originalGetReader = body.getReader.bind(body)
    body.getReader = ((...args: Parameters<ReadableStream<Uint8Array>['getReader']>) => {
      const reader = originalGetReader(...args) as ReadableStreamDefaultReader<Uint8Array>
      return {
        read: reader.read.bind(reader),
        cancel: (...cancelArgs: unknown[]) => {
          cancelSpy(...cancelArgs)
          return reader.cancel(...(cancelArgs as []))
        },
        releaseLock: () => {
          releaseSpy()
          reader.releaseLock()
        },
      } as ReadableStreamDefaultReader<Uint8Array>
    }) as ReadableStream<Uint8Array>['getReader']

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        body,
      }),
    )

    const { useModelStream } = await import('./useModelStream')
    const stream = useModelStream()
    // Simulate leftover usage from a previous request before start clears it.
    stream.state.value = {
      ...createEmptyModelStreamState(),
      usage: { promptTokens: 99, completionTokens: 99, totalTokens: 198 },
    }

    await stream.start(
      { messages: [{ role: 'user', content: 'hi' }] },
      { onTerminal: (s) => terminals.push(s.terminal || '') },
    )

    expect(terminals).toEqual(['completed'])
    expect(stream.state.value.content).toBe('答')
    expect(stream.state.value.reasoningContent).toBe('想')
    expect(stream.state.value.toolCalls[0]?.arguments).toBe('{"q":"hi"}')
    expect(stream.state.value.usage).toEqual({
      promptTokens: 2,
      completionTokens: 3,
      totalTokens: 5,
    })
    expect(stream.state.value.finishReason).toBe('stop')
    expect(cancelSpy).toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('notifies terminal only once when error event is followed by stream close', async () => {
    const terminals: string[] = []
    const body = sseChunks([
      'data: {"type":"content.delta","text":"partial"}\n\n',
      'data: {"type":"error","code":"MODEL_UPSTREAM_ERROR","message":"boom"}\n\n',
    ])
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        body,
      }),
    )
    const { useModelStream } = await import('./useModelStream')
    const stream = useModelStream()
    await stream.start(
      { messages: [{ role: 'user', content: 'hi' }] },
      { onTerminal: (s) => terminals.push(s.terminal || '') },
    )
    expect(terminals).toEqual(['error'])
    expect(stream.state.value.content).toBe('partial')
    expect(stream.state.value.errorCode).toBe('MODEL_UPSTREAM_ERROR')
    vi.unstubAllGlobals()
  })
})
