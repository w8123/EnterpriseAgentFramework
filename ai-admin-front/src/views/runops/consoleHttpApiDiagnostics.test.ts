import { describe, expect, it } from 'vitest'
import type { RunSpan, RunSummary } from '@/types/runops'
import { consoleHttpApiAuditLabel, consoleHttpApiTransportMessage } from './consoleHttpApiDiagnostics'

function fixture(code = 'RUNTIME_HTTP_TLS_FAILED', exceptionClass = 'SSLHandshakeException', elapsedMs = '5008') {
  const summary: RunSummary = { traceId: 'console-safe-transport', runType: 'CONSOLE_HTTP_API', entryType: 'CONSOLE',
    runtimeType: 'HTTP_API', status: 'FAILED', errorCode: 'HTTP_API_DISPATCH_UNKNOWN',
    errorMessage: `[rejected:HTTP_API_DISPATCH_UNKNOWN] [transport:code=${code};exceptionClass=${exceptionClass};elapsedMs=${elapsedMs}]` }
  const span: RunSpan = { id: 1, spanId: 'root', spanType: 'CONSOLE_CAPABILITY', runtimeType: 'HTTP_API', status: 'ERROR',
    errorCode: 'HTTP_API_DISPATCH_UNKNOWN', errorMessage: '[rejected:HTTP_API_DISPATCH_UNKNOWN]' }
  return { summary, span }
}

describe('Console HTTP API safe diagnostic and audit semantics', () => {
  it.each([
    ['RUNTIME_HTTP_TLS_FAILED', 'SSLHandshakeException', 'TLS 握手失败'],
    ['RUNTIME_HTTP_CONNECT_FAILED', 'ConnectException', '连接建立失败'],
    ['RUNTIME_HTTP_TIMEOUT', 'SocketTimeoutException', 'HTTP 传输超时'],
    ['RUNTIME_HTTP_TIMEOUT', 'ConnectTimeoutException', 'HTTP 传输超时'],
    ['RUNTIME_HTTP_FAILED', 'IOException', 'HTTP 传输失败'],
  ])('describes only fixed safe %s facts while retaining UNKNOWN semantics', (code, exceptionClass, reason) => {
    const f = fixture(code, exceptionClass)
    const message = consoleHttpApiTransportMessage(f.summary, f.span)!
    expect(message).toContain(reason)
    expect(message).toContain(exceptionClass)
    expect(message).toContain('5008 ms')
    expect(message).toContain('结果仍未确认（UNKNOWN）')
    expect(message).toContain('不能证明请求未送达')
    expect(message).not.toContain('证书不可信')
  })

  it.each(['0', '2147483647'])('accepts the bounded elapsed value %s', elapsed => {
    const f = fixture('RUNTIME_HTTP_TLS_FAILED', 'SSLHandshakeException', elapsed)
    expect(consoleHttpApiTransportMessage(f.summary, f.span)).toContain(`${elapsed} ms`)
  })

  it.each([
    '[rejected:HTTP_API_DISPATCH_UNKNOWN]',
    '[rejected:HTTP_API_DISPATCH_UNKNOWN] Connection reset at https://secret.invalid/?token=value',
    fixture('RUNTIME_HTTP_TLS_FAILED', 'CustomSecretException').summary.errorMessage!,
    fixture('RUNTIME_HTTP_OTHER', 'SSLHandshakeException').summary.errorMessage!,
    fixture('RUNTIME_HTTP_TLS_FAILED', 'SSLHandshakeException', '2147483648').summary.errorMessage!,
    fixture('RUNTIME_HTTP_TLS_FAILED', 'SSLHandshakeException', '-1').summary.errorMessage!,
    fixture('RUNTIME_HTTP_TLS_FAILED', 'SSLHandshakeException', '01').summary.errorMessage!,
    fixture('RUNTIME_HTTP_TLS_FAILED', 'SSLHandshakeException', '99999999999').summary.errorMessage!,
    fixture('RUNTIME_HTTP_TLS_FAILED', 'SSLHandshakeException', '1e3').summary.errorMessage!,
    fixture('RUNTIME_HTTP_TLS_FAILED', 'SSLHandshakeException', 'NaN').summary.errorMessage!,
    fixture().summary.errorMessage! + '\n',
    fixture().summary.errorMessage! + ' url=https://secret.invalid',
  ])('rejects missing, arbitrary, appended or out-of-contract text: %s', value => {
    const f = fixture(); f.summary.errorMessage = value
    expect(consoleHttpApiTransportMessage(f.summary, f.span)).toBeUndefined()
  })

  it.each([
    ['RUNTIME_HTTP_TLS_FAILED', 'SocketTimeoutException'],
    ['RUNTIME_HTTP_TIMEOUT', 'SSLHandshakeException'],
    ['RUNTIME_HTTP_FAILED', 'ConnectTimeoutException'],
    ['RUNTIME_HTTP_CONNECT_FAILED', 'SSLPeerUnverifiedException'],
    ['RUNTIME_HTTP_FAILED', 'SSLException'],
    ['RUNTIME_HTTP_FAILED', 'ConnectException'],
  ])('rejects a known but contradictory code/class pair: %s / %s', (code, exceptionClass) => {
    const f = fixture(code, exceptionClass)
    expect(consoleHttpApiTransportMessage(f.summary, f.span)).toBeUndefined()
  })

  it.each([
    (f: ReturnType<typeof fixture>) => { f.summary.runType = 'WORKFLOW' },
    (f: ReturnType<typeof fixture>) => { f.summary.entryType = 'API' },
    (f: ReturnType<typeof fixture>) => { f.summary.errorCode = 'OTHER_ERROR' },
    (f: ReturnType<typeof fixture>) => { f.span.parentSpanId = 'parent' },
    (f: ReturnType<typeof fixture>) => { f.span.spanType = 'WORKFLOW_NODE' },
    (f: ReturnType<typeof fixture>) => { f.span.errorCode = 'OTHER_ERROR' },
    (f: ReturnType<typeof fixture>) => { f.span.errorMessage = 'More specific child failure' },
  ])('does not enrich a different run, child, code or already-specific span', mutate => {
    const f = fixture(); mutate(f)
    expect(consoleHttpApiTransportMessage(f.summary, f.span)).toBeUndefined()
  })

  it('counts actual audit rows independently of the legacy attempt count', () => {
    expect(consoleHttpApiAuditLabel({ toolCalls: [] })).toBe('1 次 API 试调用 · 0 条 Tool 审计')
    expect(consoleHttpApiAuditLabel({ toolCalls: [{ id: 1, success: false }] })).toBe('1 次 API 试调用 · 1 条 Tool 审计')
  })
})
