import { describe, expect, it } from 'vitest'
import type { HttpApiConnection, HttpApiDetail, HttpApiInvocationOutcome, HttpApiParameter } from '@/types/httpApi'
import { buildHttpApiTrialParameters, canStartNewHttpApiAttempt, httpApiBodyParameters, httpApiTrialFailure, validateHttpApiBody, isCurrentHttpApiEvidence } from './httpApiTrial'

const parameters: HttpApiParameter[] = [
  { location: 'PATH', name: 'orderId', required: true, schema: { type: 'string' }, contentTypes: [] },
  { location: 'QUERY', name: 'expanded', required: false, schema: { type: 'boolean' }, contentTypes: [] },
  { location: 'QUERY', name: 'page', required: false, schema: { type: 'integer' }, contentTypes: [] },
]

describe('HTTP API trial input and current evidence', () => {
  it('keeps declared path and query types, including explicit false', () => {
    expect(buildHttpApiTrialParameters(parameters, {
      'PATH:orderId': ' O-1 ', 'QUERY:expanded': 'false', 'QUERY:page': '2',
    })).toEqual({ pathParams: { orderId: 'O-1' }, queryParams: { expanded: false, page: 2 }, errors: {} })
  })

  it('prevents invalid input from becoming a dispatch payload', () => {
    const result = buildHttpApiTrialParameters(parameters, {
      'PATH:orderId': ' ', 'QUERY:page': '2.5', 'QUERY:extra': 'ignored',
    })
    expect(result.errors).toEqual({ 'PATH:orderId': '请输入必填参数', 'QUERY:page': '请输入整数' })
    expect(result.queryParams).not.toHaveProperty('extra')
    expect(result.pathParams).not.toHaveProperty('orderId')
  })

  it('marks a prior result historical as soon as contract, source, connection or credential changes', () => {
    const detail = { summary: { acceptedContractHash: 'a', sourceSetRevision: 'b' } } as HttpApiDetail
    const connection = { status: 'CONFIGURED', revision: 1, credentialRevision: 'c' } as HttpApiConnection
    const outcome = { expectedContractHash: 'a', sourceSetRevision: 'b',
      connectionRevision: 1, credentialRevision: 'c' } as HttpApiInvocationOutcome
    expect(isCurrentHttpApiEvidence(detail, connection, outcome, true)).toBe(true)
    expect(isCurrentHttpApiEvidence(detail, { ...connection, revision: 2 }, outcome, true)).toBe(false)
    expect(isCurrentHttpApiEvidence(detail, { ...connection, credentialRevision: 'd' }, outcome, true)).toBe(false)
    expect(isCurrentHttpApiEvidence({ ...detail, summary: { ...detail.summary, sourceSetRevision: 'next' } },
      connection, outcome, true)).toBe(false)
    expect(isCurrentHttpApiEvidence(detail, connection, outcome, false)).toBe(false)
  })

  it('allows an explicit warned new attempt for an unknown or lost result but not while dispatching', () => {
    expect(canStartNewHttpApiAttempt(null)).toBe(true)
    expect(canStartNewHttpApiAttempt({ status: 'UNKNOWN', terminal: true } as HttpApiInvocationOutcome)).toBe(true)
    expect(canStartNewHttpApiAttempt({ status: 'DISPATCHING', terminal: false } as HttpApiInvocationOutcome)).toBe(false)
    expect(canStartNewHttpApiAttempt({ status: 'HTTP_FAILED', terminal: true } as HttpApiInvocationOutcome)).toBe(true)
  })

  it('adapts flat body fields and validates values without substituting defaults', () => {
    const detail = { acceptedContract: { requestBody: { schema: { type: 'object', required: ['note'], properties: {
      note: { type: 'string', minLength: 1, maxLength: 5 }, count: { type: 'integer', minimum: 1, maximum: 3, default: 2 },
      urgent: { type: 'boolean', default: true }, kind: { type: 'string', enum: ['NEW'] }, fixed: { type: 'string', const: 'one' },
    } } } } } as unknown as HttpApiDetail
    expect(httpApiBodyParameters(detail).map(item => [item.name, item.required])).toContainEqual(['note', true])
    const valid = { note: 'one', urgent: false }
    expect(validateHttpApiBody(detail, valid)).toBe(''); expect(valid).not.toHaveProperty('count')
    expect(valid.urgent).toBe(false); expect(valid).not.toHaveProperty('kind')
    for (const body of [{ note: '' }, { note: null }, { note: 'one', count: 0 }, { note: 'one', count: 1.2 },
      { note: 'one', urgent: 'false' }, { note: 'one', kind: 'OLD' }, { note: 'one', fixed: 'two' },
      { note: 'one', extra: true }, { note: { nested: true } }]) expect(validateHttpApiBody(detail, body)).not.toBe('')
  })

  it('rejects an own optional null without deleting it, while preserving absence, false, zero and an allowed empty string', () => {
    const detail = { acceptedContract: { requestBody: { schema: { required: ['note'], properties: {
      note: { type: 'string' }, count: { type: 'integer', minimum: 0, default: 2 }, urgent: { type: 'boolean', default: true },
      caption: { type: 'string' },
    } } } } } as unknown as HttpApiDetail
    const explicitNull = { note: 'one', count: null }
    expect(validateHttpApiBody(detail, explicitNull)).toContain('null')
    expect(Object.prototype.hasOwnProperty.call(explicitNull, 'count')).toBe(true)
    expect(explicitNull.count).toBeNull()
    expect(validateHttpApiBody(detail, { note: 'one' })).toBe('')
    expect(validateHttpApiBody(detail, { note: 'one', count: 0, urgent: false, caption: '' })).toBe('')
    expect(validateHttpApiBody(detail, { count: 0 })).not.toBe('')
    expect(validateHttpApiBody(detail, { note: null })).not.toBe('')
  })

  it('derives password-format and writeOnly sensitivity only from the accepted body schema', () => {
    const detail = { acceptedContract: { requestBody: { schema: { properties: {
      accessCode: { type: 'string', format: 'password' }, deliveryMark: { type: 'string', writeOnly: true },
      caption: { type: 'string', writeOnly: false },
    } } } }, contract: { requestBody: { schema: { properties: { caption: { type: 'string', format: 'password' } } } } },
    } as unknown as HttpApiDetail
    expect(httpApiBodyParameters(detail).map(field => [field.name, field.metadata?.sensitive]))
      .toEqual([['accessCode', true], ['deliveryMark', true], ['caption', false]])
  })

  it('uses the shared editor sensitive-value guard for security-shaped body fields', () => {
    const detail = { acceptedContract: { requestBody: { schema: { properties: {
      apiKey: { type: 'string' }, password: { type: 'string' }, note: { type: 'string' },
    } } } } } as unknown as HttpApiDetail
    expect(httpApiBodyParameters(detail).map(field => [field.name, field.metadata?.sensitive]))
      .toEqual([['apiKey', true], ['password', true], ['note', false]])
  })

  it('identifies public ACL rejection without exposing server messages or claiming a business write never happened', () => {
    const rejected = { response: { status: 403, data: { message: 'PRIVATE_SERVER_SENTINEL' } } }
    expect(httpApiTrialFailure(rejected, 'invoke')).toContain('API ACL')
    expect(httpApiTrialFailure(rejected, 'query')).toContain('原调用 ID 已保留')
    expect(httpApiTrialFailure(rejected, 'invoke')).not.toContain('PRIVATE_SERVER_SENTINEL')
    expect(httpApiTrialFailure(new Error('offline'), 'invoke')).toContain('结果未确认')
  })
})
