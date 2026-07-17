/**
 * 仅在明确“流式不支持”且尚未消费任何响应体时允许 fallback。
 * Abort / 断流 / 业务错误禁止自动重发有副作用的 POST。
 */
export function isSafeStreamUnsupportedStatus(status: number): boolean {
  return status === 404 || status === 405 || status === 415 || status === 501
}

export function isAbortLikeError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const name = (error as { name?: string }).name
  return name === 'AbortError'
}

export class StreamFallbackForbiddenError extends Error {
  readonly causeError?: unknown

  constructor(message: string, cause?: unknown) {
    super(message)
    this.name = 'StreamFallbackForbiddenError'
    this.causeError = cause
  }
}

/**
 * 判断是否允许在流式失败后改走 REST/JSON。
 * - 仅 HTTP 404/405/415/501
 * - 且尚未读取任何业务事件 / 响应体字节
 */
export function canFallbackFromStreamFailure(options: {
  status?: number
  bytesOrEventsConsumed: boolean
  aborted?: boolean
  error?: unknown
}): boolean {
  if (options.aborted || isAbortLikeError(options.error)) return false
  if (options.bytesOrEventsConsumed) return false
  if (options.status == null) return false
  return isSafeStreamUnsupportedStatus(options.status)
}
