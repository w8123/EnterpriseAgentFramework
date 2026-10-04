import { describe, expect, it } from 'vitest'
import { buildApiMarketWorkflowDraft } from './apiMarketWorkflow'
import type { HttpApiConnection, HttpApiDetail } from '@/types/httpApi'
import type { AgentForm, WorkflowCanvasSource } from '@/types/agent'
import { canvasToDefinition, definitionToCanvas } from './studio'

function fixture() {
  const ref = 'http-api:orders:dev:market:' + 'a'.repeat(64), hash = 'b'.repeat(64), revision = 'c'.repeat(64)
  const detail: HttpApiDetail = {
    summary: { id: 1, qualifiedName: ref, projectId: 41, projectCode: 'orders', environment: 'dev',
      httpMethod: 'GET', routeTemplate: '/orders/{orderId}', sourceStatus: 'ACCEPTED', sourceConfirmed: true,
      sourceReason: null, candidateContractHash: hash, acceptedContractHash: hash, sourceSetRevision: revision,
      activeSourceCount: 1, sourceKinds: ['API_MARKET_OPERATION'], acceptedBy: 'fixture', acceptedAt: null },
    contract: null, sources: [],
    acceptedContract: { identity: { method: 'GET', routeTemplate: '/orders/{orderId}' },
      scope: { projectCode: 'orders', environment: 'dev', externalServiceKey: 'api-market:isolated-orders-alpha' },
      parameters: [{ name: 'orderId', location: 'PATH', required: true, schema: { type: 'string' }, contentTypes: [] }],
      requestBody: null, responses: [{ status: '200', schema: { type: 'object', properties: { state: { type: 'string' } } }, contentTypes: ['application/json'] }],
      authentication: { state: 'NONE', schemes: [], requiredHeaderNames: [] }, sideEffect: 'READ_ONLY' },
  }
  const connection: HttpApiConnection = { qualifiedName: ref, projectId: 41, projectCode: 'orders', environment: 'dev',
    origin: 'http://isolated-origin.invalid', previewUrl: 'http://isolated-origin.invalid/orders/{orderId}',
    authMode: 'NONE', credentialRef: null, credentialName: null, credentialRevision: null, revision: 1,
    status: 'CONFIGURED', blockingReason: null,
    verification: { status: 'VERIFIED', reason: 'Current actual Console', apiId: 1, acceptedContractHash: hash,
      sourceSetRevision: revision, connectionRevision: 1, credentialRevision: null, invocationId: 'actual-attempt',
      runId: 7, traceId: 'actual-trace', verifiedAt: '2026-10-01T00:00:00' } }
  const options = { name: '订单查询', keySlug: 'orders-market', projectId: 41, projectCode: 'orders', environment: 'dev' }
  return { detail, connection, options }
}

describe('buildApiMarketWorkflowDraft', () => {
  it('creates API TOOL → variable → END with stable owner mapping and no transport or proof', () => {
    const f = fixture(), draft = buildApiMarketWorkflowDraft(f.detail, f.connection, f.options)
    const api = draft.graphSpec!.nodes[0]
    expect(api.type).toBe('TOOL')
    expect(api.ref?.qualifiedName).toBe(f.detail.summary.qualifiedName)
    expect(api.config?.inputMapping).toEqual({ 'pathParams.orderId': 'params.orderId' })
    expect(api.config?.httpApiAssetId).toBe(1)
    expect(draft.graphSpec!.nodes[1].config?.assignments).toEqual({ api_result: 'nodeOutput.api-node.state' })
    expect(draft.graphSpec!.nodes[1].config?.outputAlias).not.toBe('api_result')
    expect(draft.graphSpec!.nodes[1].config?.outputAlias).toBe('variable_output')
    expect(draft.canvasJson).toContain('"end"')
    const stored = JSON.stringify(draft)
    for (const forbidden of ['isolated-origin', 'marketRef', 'contractHash', 'actual-attempt', 'actual-trace', 'credentialRevision']) {
      expect(stored).not.toContain(forbidden)
    }
    const canvas = definitionToCanvas(draft as unknown as WorkflowCanvasSource)
    const roundTrip = canvasToDefinition(draft as unknown as AgentForm, canvas)
    expect(roundTrip.graphSpec!.nodes.find(node => node.type === 'TOOL')?.ref?.qualifiedName).toBe(f.detail.summary.qualifiedName)
    const variable = roundTrip.graphSpec!.nodes.find(node => node.type === 'VARIABLE_ASSIGN')
    expect(variable?.config?.assignments).toEqual({ api_result: 'nodeOutput.api-node.state' })
    expect(variable?.config?.outputAlias).toBe('variable_output')
  })
  it('refuses configured-only and unknown or failed proof', () => {
    for (const status of ['UNKNOWN', 'FAILED', 'STALE', 'UNVERIFIED'] as const) {
      const f = fixture(); f.connection.verification!.status = status
      expect(() => buildApiMarketWorkflowDraft(f.detail, f.connection, f.options)).toThrow()
    }
    const f = fixture(); f.connection.verification = null
    expect(() => buildApiMarketWorkflowDraft(f.detail, f.connection, f.options)).toThrow('真实 Console')
  })
  it('refuses old source, connection, credential and API proofs independently', () => {
    for (const mutate of [
      (c: HttpApiConnection) => { c.verification!.sourceSetRevision = 'd'.repeat(64) },
      (c: HttpApiConnection) => { c.verification!.connectionRevision = 9 },
      (c: HttpApiConnection) => { c.verification!.credentialRevision = 'd'.repeat(64) },
      (c: HttpApiConnection) => { c.verification!.apiId = 99 },
    ]) {
      const f = fixture(); mutate(f.connection)
      expect(() => buildApiMarketWorkflowDraft(f.detail, f.connection, f.options)).toThrow()
    }
  })
  it('does not cross project/environment, accept drift, unsupported input or non-market source', () => {
    const f = fixture()
    expect(() => buildApiMarketWorkflowDraft(f.detail, f.connection, { ...f.options, projectCode: 'other' })).toThrow()
    expect(() => buildApiMarketWorkflowDraft(f.detail, f.connection, { ...f.options, environment: 'prod' })).toThrow()
    f.detail.summary.sourceKinds = ['CONTROLLER_SCAN']
    expect(() => buildApiMarketWorkflowDraft(f.detail, f.connection, f.options)).toThrow('固定市场来源')
  })
})
