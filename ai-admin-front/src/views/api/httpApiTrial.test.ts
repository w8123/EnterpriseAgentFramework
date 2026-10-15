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

  it('validates nested DTOs and declared root arrays without flattening or wrapping them', () => {
    const detail = { acceptedContract: { requestBody: { required: true, schema: { type: 'array', minItems: 1,
      items: { type: 'object', required: ['moduleId', 'directors'], properties: {
        moduleId: { type: 'string', minLength: 1 }, directors: { type: 'array', items: {
          type: 'object', required: ['userId'], properties: { userId: { type: 'string' }, enabled: { type: 'boolean' } },
        } },
      } },
    } } } } as unknown as HttpApiDetail
    const valid = [{ moduleId: 'LM-test', directors: [{ userId: 'U-test', enabled: false }] }]
    expect(validateHttpApiBody(detail, valid)).toBe('')
    expect(validateHttpApiBody(detail, [])).not.toBe('')
    expect(validateHttpApiBody(detail, [{ moduleId: 'LM-test', directors: [{ userId: 'U-test', extra: true }] }])).not.toBe('')
    expect(validateHttpApiBody(detail, [{ moduleId: 'LM-test', directors: [{ userId: null }] }])).not.toBe('')
    expect(validateHttpApiBody(detail, { body: valid })).not.toBe('')
    expect(validateHttpApiBody(detail, undefined)).not.toBe('')
  })

  it('keeps scalar JSON, nullable nested fields and optional body absence distinct', () => {
    const declaration = (schema: Record<string, unknown>, required = true) => ({ acceptedContract: {
      requestBody: { required, schema, contentTypes: ['application/json'] },
    } } as unknown as HttpApiDetail)
    expect(validateHttpApiBody(declaration({ type: 'string' }), 'declared-string')).toBe('')
    expect(validateHttpApiBody(declaration({ type: 'boolean' }), false)).toBe('')
    expect(validateHttpApiBody(declaration({ type: 'number' }), 0)).toBe('')
    expect(validateHttpApiBody(declaration({ type: 'object', properties: { note: { type: 'string', nullable: true } } }), { note: null })).toBe('')
    expect(validateHttpApiBody(declaration({ type: 'object', properties: {} }, false), undefined)).toBe('')
    expect(validateHttpApiBody(declaration({ type: 'number' }), Number.POSITIVE_INFINITY)).not.toBe('')
  })

  it('adapts nested and root-array sensitivity from the accepted schema', () => {
    const detail = { acceptedContract: { requestBody: { schema: { type: 'array', items: { type: 'object', properties: {
      accessCode: { type: 'string', format: 'password' }, details: { type: 'object', properties: {
        deliveryMark: { type: 'string', writeOnly: true },
      } },
    } } } } } } as unknown as HttpApiDetail
    const root = httpApiBodyParameters(detail)[0]!
    expect(root.name).toBe('$body')
    expect(root.children?.find(item => item.name === 'accessCode')?.metadata?.sensitive).toBe(true)
    expect(root.children?.find(item => item.name === 'details')?.children?.[0]?.metadata?.sensitive).toBe(true)
  })

  it('bounds deep and oversized JSON while accepting escaped text and declared maps', () => {
    const detail = (schema: Record<string, unknown>) => ({ acceptedContract: { requestBody: { required: true, schema } } } as unknown as HttpApiDetail)
    expect(validateHttpApiBody(detail({ type: 'string' }), 'first\nsecond')).toBe('')
    expect(validateHttpApiBody(detail({ type: 'string' }), 'x'.repeat(32769))).not.toBe('')
    expect(validateHttpApiBody(detail({ type: 'array', items: { type: 'integer' } }), Array(1025).fill(0))).not.toBe('')
    expect(validateHttpApiBody(detail({ type: 'object', additionalProperties: true }), { labels: { first: ['ok', false] } })).toBe('')
    expect(validateHttpApiBody(detail({ type: 'object', additionalProperties: { type: 'integer' } }), { count: false })).not.toBe('')
    let schema: Record<string, unknown> = { type: 'integer' }, input: unknown = 0
    for (let index = 0; index < 7; index++) { schema = { type: 'array', items: schema }; input = [input] }
    expect(validateHttpApiBody(detail(schema), input)).not.toBe('')
  })

  it('identifies public ACL rejection without exposing server messages or claiming a business write never happened', () => {
    const rejected = { response: { status: 403, data: { message: 'PRIVATE_SERVER_SENTINEL' } } }
    expect(httpApiTrialFailure(rejected, 'invoke')).toContain('API ACL')
    expect(httpApiTrialFailure(rejected, 'query')).toContain('原调用 ID 已保留')
    expect(httpApiTrialFailure(rejected, 'invoke')).not.toContain('PRIVATE_SERVER_SENTINEL')
    expect(httpApiTrialFailure(new Error('offline'), 'invoke')).toContain('结果未确认')
  })
})
