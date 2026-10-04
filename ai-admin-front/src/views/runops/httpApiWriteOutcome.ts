import type { RunDetail, RunSpan, RunSummary } from '@/types/runops'

export const HTTP_API_WRITE_UNCONFIRMED_GUIDANCE =
  '请求可能已写入业务系统。请先核对原运行和目标业务状态，勿自动重试；显式发起新运行会是一次新的调用。'

/** Lifecycle FAILED/ERROR is not proof that an after-dispatch business write did not occur. */
export function isHttpApiWriteResultUnconfirmed(value?: Pick<RunSpan | RunSummary, 'errorCode' | 'metadata'> | null) {
  if (!value) return false
  if (value.errorCode === 'HTTP_API_WORKFLOW_RESULT_UNCONFIRMED') return true
  const metadata = value.metadata
  return metadata?.sideEffect === 'WRITE' && metadata?.dispatchStage === 'UNCONFIRMED'
    && metadata?.outcomeClass === 'UNKNOWN' && metadata?.businessOutcome === 'UNCONFIRMED'
    && typeof metadata?.qualifiedName === 'string' && metadata.qualifiedName.startsWith('http-api:')
}

export function runHasUnconfirmedHttpApiWrite(detail?: Pick<RunDetail, 'summary' | 'spans'> | null) {
  if (!detail) return false
  if (isHttpApiWriteResultUnconfirmed(detail.summary)) return true
  // Supervisor's root code is generic; require a matching API-write span, not a historical failed tool alone.
  return detail.summary.errorCode === 'RESULT_UNCONFIRMED'
    && detail.spans.some(isHttpApiWriteResultUnconfirmed)
}
