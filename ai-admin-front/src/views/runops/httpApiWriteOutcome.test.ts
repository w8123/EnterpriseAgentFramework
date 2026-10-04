import { describe, expect, it } from 'vitest'
import type { RunDetail, RunSummary } from '@/types/runops'
import { isHttpApiWriteResultUnconfirmed, runHasUnconfirmedHttpApiWrite } from './httpApiWriteOutcome'

describe('published HTTP API write result presentation', () => {
  const summary: RunSummary = { traceId: 'mcp-unknown', runType: 'MCP', entryType: 'MCP', status: 'FAILED',
    errorCode: 'HTTP_API_WORKFLOW_RESULT_UNCONFIRMED' }
  it('keeps business uncertainty separate from the real FAILED root lifecycle', () => {
    expect(runHasUnconfirmedHttpApiWrite({ summary, spans: [] })).toBe(true)
    expect(summary.status).toBe('FAILED')
  })
  it('recognizes fixed API-write metadata even when the execution span lifecycle is ERROR', () => {
    expect(isHttpApiWriteResultUnconfirmed({ metadata: { qualifiedName: 'http-api:orders:dev:owner',
      sideEffect: 'WRITE', dispatchStage: 'UNCONFIRMED', outcomeClass: 'UNKNOWN', businessOutcome: 'UNCONFIRMED' } })).toBe(true)
    expect(isHttpApiWriteResultUnconfirmed({ metadata: { outcomeClass: 'UNKNOWN', businessOutcome: 'UNCONFIRMED' } })).toBe(false)
  })
  it('recognizes a Supervisor unconfirmed root only with matching API-write evidence', () => {
    const detail: Pick<RunDetail, 'summary' | 'spans'> = { summary: { ...summary, runType: 'AGENT', errorCode: 'RESULT_UNCONFIRMED' },
      spans: [{ id: 1, status: 'ERROR', errorCode: 'HTTP_API_WORKFLOW_RESULT_UNCONFIRMED' }] }
    expect(runHasUnconfirmedHttpApiWrite(detail)).toBe(true)
    expect(runHasUnconfirmedHttpApiWrite({ ...detail, spans: [] })).toBe(false)
  })
  it.each(['HTTP_API_WORKFLOW_HTTP_FAILED', 'HTTP_API_WORKFLOW_INPUT_INVALID', undefined])(
    'does not turn known rejection or ordinary success %s into an unknown write', (errorCode) => {
      expect(isHttpApiWriteResultUnconfirmed({ errorCode })).toBe(false)
      expect(runHasUnconfirmedHttpApiWrite({ summary: { ...summary, errorCode }, spans: [] })).toBe(false)
    },
  )
  it('does not let a historical unconfirmed span overwrite a normally completed current root', () => {
    expect(runHasUnconfirmedHttpApiWrite({ summary: { ...summary, status: 'COMPLETED', errorCode: undefined },
      spans: [{ id: 1, errorCode: 'HTTP_API_WORKFLOW_RESULT_UNCONFIRMED' }] })).toBe(false)
  })
})
