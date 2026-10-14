import type { RunDetail, RunSpan, RunSummary } from '@/types/runops'

const UNKNOWN_CODE = 'HTTP_API_DISPATCH_UNKNOWN'
const GENERIC_REJECTION = `[rejected:${UNKNOWN_CODE}]`
const TRANSPORT_REASONS: Record<string, string> = {
  RUNTIME_HTTP_TIMEOUT: 'HTTP 传输超时',
  RUNTIME_HTTP_CONNECT_FAILED: '连接建立失败',
  RUNTIME_HTTP_TLS_FAILED: 'TLS 握手失败',
  RUNTIME_HTTP_FAILED: 'HTTP 传输失败',
}
// Match WorkflowHttpClient's specific SocketTimeout/Connect/SSL catches before its general catch.
const TRANSPORT_EXCEPTION_CLASSES: Record<string, ReadonlySet<string>> = {
  RUNTIME_HTTP_TIMEOUT: new Set(['SocketTimeoutException', 'ConnectTimeoutException']),
  RUNTIME_HTTP_CONNECT_FAILED: new Set(['ConnectException', 'HttpHostConnectException']),
  RUNTIME_HTTP_TLS_FAILED: new Set(['SSLHandshakeException', 'SSLPeerUnverifiedException', 'SSLProtocolException', 'SSLException']),
  RUNTIME_HTTP_FAILED: new Set(['ConnectionRequestTimeoutException', 'NoHttpResponseException',
    'ConnectionClosedException', 'SocketException', 'IOException', 'RuntimeException', 'Exception']),
}

export function isConsoleHttpApiRoot(summary: RunSummary, span?: RunSpan) {
  return summary.runType === 'CONSOLE_HTTP_API' && summary.entryType === 'CONSOLE'
    && summary.runtimeType === 'HTTP_API' && span?.spanType === 'CONSOLE_CAPABILITY'
    && span.runtimeType === 'HTTP_API' && !span.parentSpanId
}

/** Only the server's complete fixed transport grammar can enrich a generic Console root. */
export function consoleHttpApiTransportMessage(summary: RunSummary, span?: RunSpan): string | undefined {
  if (!isConsoleHttpApiRoot(summary, span) || summary.status !== 'FAILED'
      || summary.errorCode !== UNKNOWN_CODE || span?.status !== 'ERROR'
      || span.errorCode !== UNKNOWN_CODE || span.errorMessage !== GENERIC_REJECTION) return undefined
  const value = summary.errorMessage || ''
  const match = /^\[rejected:HTTP_API_DISPATCH_UNKNOWN\] \[transport:code=([A-Z_]+);exceptionClass=([A-Za-z]+);elapsedMs=(0|[1-9]\d{0,9})\]$/.exec(value)
  if (!match || match[0] !== value || !Object.prototype.hasOwnProperty.call(TRANSPORT_REASONS, match[1]!)
      || !TRANSPORT_EXCEPTION_CLASSES[match[1]!]!.has(match[2]!)) return undefined
  const elapsedMs = Number(match[3])
  if (!Number.isSafeInteger(elapsedMs) || elapsedMs > 2_147_483_647) return undefined
  return `${TRANSPORT_REASONS[match[1]!]}，未取得可确认的 HTTP 响应（安全异常类型：${match[2]}，耗时 ${elapsedMs} ms）。本次派发结果仍未确认（UNKNOWN）；缺少响应不能证明请求未送达，请核对原运行和目标状态。`
}

/** A Console root is one recorded attempt; legacy toolCallCount is not a Tool audit count. */
export function consoleHttpApiAuditLabel(detail?: Pick<RunDetail, 'toolCalls'> | null) {
  return `1 次 API 试调用 · ${detail?.toolCalls.length ?? 0} 条 Tool 审计`
}
