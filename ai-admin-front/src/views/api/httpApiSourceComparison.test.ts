import { describe, expect, it } from 'vitest'
import type { HttpApiContract, HttpApiSource } from '@/types/httpApi'
import { compareHttpApiContracts, compareHttpApiSources } from './httpApiSourceComparison'

function contract(type = 'string'): HttpApiContract {
  return {
    identity: { method: 'GET', routeTemplate: '/orders/{orderId}' },
    parameters: [], requestBody: null,
    responses: [{ status: 'DEFAULT', contentTypes: ['application/json'],
      schema: { type: 'object', properties: { state: { type } } } }],
    authentication: { state: 'UNKNOWN', schemes: [], requiredHeaderNames: [] },
    sideEffect: 'READ_ONLY',
  }
}
function source(id: number, facts: HttpApiContract | null = contract(), status = 'EQUIVALENT'): HttpApiSource {
  return { id, sourceKind: id === 1 ? 'CONTROLLER_SCAN' : 'OPENAPI_SCAN', sourceKey: `source-${id}`,
    sourceLocation: null, sourceRevision: null, sourceContractHash: `hash-${id}`, status,
    observedAt: null, confirmedInLatestInventory: true, reason: null, latestInventoryAt: null,
    inventoryComplete: true, confirmedAt: null, contract: facts }
}

describe('owner-provided API source comparison', () => {
  it('uses owner accepted/candidate facts to explain before and after, without editing either', () => {
    const accepted = contract(); const candidate = contract('integer')
    const before = JSON.stringify([accepted, candidate])
    expect(compareHttpApiContracts(accepted, candidate)).toMatchObject({ available: true, truncated: false,
      differences: [{ path: '/responses/0/schema/properties/state/type', label: '响应 DEFAULT / state / 类型', before: 'string', after: 'integer' }] })
    expect(JSON.stringify([accepted, candidate])).toBe(before)
    expect(compareHttpApiContracts(null, candidate)).toMatchObject({ available: false, differences: [] })
  })
  it('keeps accepted/candidate truncation explicit as well as cross-source truncation', () => {
    const accepted = contract(); const candidate = contract()
    accepted.responses[0].schema.properties = Object.fromEntries(Array.from({ length: 80 }, (_, i) => [`f${i}`, { type: 'string' }]))
    candidate.responses[0].schema.properties = Object.fromEntries(Array.from({ length: 80 }, (_, i) => [`f${i}`, { type: 'integer' }]))
    expect(compareHttpApiContracts(accepted, candidate)).toMatchObject({ available: true, truncated: true })
    expect(compareHttpApiContracts(accepted, candidate).differences).toHaveLength(64)
  })
  it('ignores object key order without calculating equivalence or modifying owner facts', () => {
    const second = contract()
    second.identity = { routeTemplate: '/orders/{orderId}', method: 'GET' }
    const sources = [source(1), source(2, second)]
    const before = JSON.stringify(sources)
    expect(compareHttpApiSources(sources)).toMatchObject({ available: true, differences: [], truncated: false })
    expect(JSON.stringify(sources)).toBe(before)
    expect(sources.map(item => item.sourceContractHash)).toEqual(['hash-1', 'hash-2'])
  })

  it('explains a response leaf using both actual source values', () => {
    const result = compareHttpApiSources([source(1), source(2, contract('integer'))])
    expect(result.differences).toEqual([{ path: '/responses/0/schema/properties/state/type',
      label: '响应 DEFAULT / state / 类型', values: [{ sourceId: 1, text: 'string' }, { sourceId: 2, text: 'integer' }] }])
  })

  it('distinguishes authentication state from a business property named state', () => {
    const second = contract()
    second.authentication.state = 'NONE'
    expect(compareHttpApiSources([source(1), source(2, second)]).differences[0]?.label).toBe('认证 / 声明状态')
  })

  it('excludes removed history and refuses to guess when an active contract is absent', () => {
    expect(compareHttpApiSources([source(1), source(2, contract('integer'), 'REMOVED')]))
      .toMatchObject({ available: true, differences: [], active: [source(1)] })
    expect(compareHttpApiSources([source(1), source(2, null)]))
      .toMatchObject({ available: false, differences: [] })
    expect(compareHttpApiSources([]).available).toBe(false)
  })

  it('labels an absent declaration and limits long values without rewriting their source', () => {
    const first = contract(); first.responses[0].schema.title = 'x'.repeat(400)
    const sources = [source(1, first), source(2)]
    const difference = compareHttpApiSources(sources).differences[0]
    expect(difference?.values[0]?.text).toBe('x'.repeat(256) + '…（展开来源契约查看完整值）')
    expect(difference?.values[1]?.text).toBe('未声明')
    expect(first.responses[0].schema.title).toHaveLength(400)
  })

  it('bounds source count and field differences with explicit truncation', () => {
    expect(compareHttpApiSources(Array.from({ length: 17 }, (_, index) => source(index + 1))).available).toBe(false)
    const first = contract(); const second = contract()
    first.responses[0].schema.properties = Object.fromEntries(Array.from({ length: 80 }, (_, index) => [`f${index}`, { type: 'string' }]))
    second.responses[0].schema.properties = Object.fromEntries(Array.from({ length: 80 }, (_, index) => [`f${index}`, { type: 'integer' }]))
    const result = compareHttpApiSources([source(1, first), source(2, second)])
    expect(result.available).toBe(true)
    expect(result.truncated).toBe(true)
    expect(result.differences).toHaveLength(64)
  })
})
