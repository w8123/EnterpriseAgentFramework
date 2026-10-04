import { describe, expect, it } from 'vitest'
import type { HttpApiConnection, HttpApiDetail, HttpApiSummary } from '@/types/httpApi'
import type { ToolNodeConfig } from '@/types/studio'
import type { CanvasSnapshot } from '@/types/studio'
import type { AgentForm, WorkflowCanvasSource } from '@/types/agent'
import { canvasToDefinition, definitionToCanvas } from '@/utils/studio'
import { applyHttpApiSelection, httpApiInputTargets, httpApiOutputFields,
  httpApiOutputPorts, httpApiReadinessReason } from './httpApiWorkflow'

const HASH = 'a'.repeat(64)
const REF = `http-api:orders:dev:${'b'.repeat(64)}`
const summary: HttpApiSummary = {
  id: 41, qualifiedName: REF, projectId: 7, projectCode: 'orders', environment: 'dev',
  httpMethod: 'GET', routeTemplate: '/orders/{orderId}', sourceStatus: 'ACCEPTED',
  sourceConfirmed: true, sourceReason: null, candidateContractHash: HASH,
  acceptedContractHash: HASH, sourceSetRevision: 'c'.repeat(64), activeSourceCount: 1,
  sourceKinds: ['OPENAPI_SCAN'], acceptedBy: 'reviewer', acceptedAt: null,
}
const detail: HttpApiDetail = {
  summary, sources: [], contract: null,
  acceptedContract: {
    identity: { method: 'GET', routeTemplate: '/orders/{orderId}' },
    parameters: [
      { name: 'orderId', location: 'PATH', required: true, schema: { type: 'string' }, contentTypes: [] },
      { name: 'expanded', location: 'QUERY', required: false, schema: { type: 'boolean' }, contentTypes: [] },
    ],
    requestBody: null,
    responses: [{ status: '200', contentTypes: ['application/json'], schema: {
      type: 'object', properties: { orderId: { type: 'string' }, customer: {
        type: 'object', properties: { name: { type: 'string' } },
      } }, example: { secret: 'must not appear' },
    } }],
    authentication: { state: 'NONE', schemes: [], requiredHeaderNames: [] }, sideEffect: 'READ_ONLY',
  },
}
const connection: HttpApiConnection = {
  qualifiedName: REF, projectId: 7, projectCode: 'orders', environment: 'dev',
  origin: 'https://orders.invalid', authMode: 'NONE', credentialRef: null,
  credentialName: null, credentialRevision: null, revision: 2,
  status: 'CONFIGURED', blockingReason: null, previewUrl: null,
}
const scope = { projectId: 7, projectCode: 'orders', environment: 'dev' }

describe('Workflow HTTP API author contract', () => {
  it('allows internal POST WRITE flat native body and marks declaration-sensitive inputs', () => {
    const writeSummary = { ...summary, httpMethod: 'POST' }
    const writeDetail: HttpApiDetail = { ...detail, summary: writeSummary, acceptedContract: {
      ...detail.acceptedContract!, identity: { ...detail.acceptedContract!.identity, method: 'POST' }, sideEffect: 'WRITE',
      requestBody: { required: true, contentTypes: ['application/json'], schema: {
        type: 'object', required: ['note'], additionalProperties: false, properties: {
          note: { type: 'string' }, notify: { type: 'boolean', default: true }, quantity: { type: 'integer' },
          rate: { type: 'number' }, caption: { type: 'string' }, accessCode: { type: 'string', format: 'password' },
          deliveryMark: { type: 'string', writeOnly: true },
        },
      } },
    } }
    expect(httpApiReadinessReason(writeSummary, writeDetail, connection, scope)).toBe('')
    const targets = httpApiInputTargets(writeDetail.acceptedContract)
    expect(targets.find((item) => item.key === 'body.note')).toMatchObject({ location: 'BODY', required: true })
    expect(targets.find((item) => item.key === 'body.notify')).toMatchObject({ type: 'boolean', required: false })
    expect(targets.find((item) => item.key === 'body.accessCode')?.sensitive).toBe(true)
    expect(targets.find((item) => item.key === 'body.deliveryMark')?.sensitive).toBe(true)
    const config: ToolNodeConfig = { inputMapping: {} }
    applyHttpApiSelection(config, writeDetail)
    expect(config.inputMapping['body.notify']).toBe('')
    expect(JSON.stringify(config)).not.toContain('default')
    expect(httpApiReadinessReason(writeSummary, { ...writeDetail, acceptedContract: {
      ...writeDetail.acceptedContract!, sideEffect: 'READ_ONLY',
    } }, connection, scope)).toContain('WRITE')
    const market = { ...writeSummary, sourceKinds: ['API_MARKET_OPERATION'] }
    expect(httpApiReadinessReason(market, { ...writeDetail, summary: market }, connection, scope)).toContain('市场 WRITE')
    for (const field of [{ type: 'array' }, { type: 'object' }, { type: 'string', nullable: true }]) {
      expect(httpApiReadinessReason(writeSummary, { ...writeDetail, acceptedContract: {
        ...writeDetail.acceptedContract!, requestBody: { ...writeDetail.acceptedContract!.requestBody!, schema: {
          type: 'object', properties: { note: field },
        } },
      } }, connection, scope)).toContain('不支持')
    }
  })

  it('requires accepted current source, matching scope and Runtime-configured connection', () => {
    expect(httpApiReadinessReason(summary, detail, connection, scope)).toBe('')
    expect(httpApiReadinessReason({ ...summary, projectId: 8 }, detail, connection, scope)).toContain('项目及环境')
    expect(httpApiReadinessReason({ ...summary, environment: 'prod' }, detail, connection, scope)).toContain('项目及环境')
    expect(httpApiReadinessReason({ ...summary, sourceStatus: 'CONTRACT_DRIFT' }, detail, connection, scope)).toContain('来源')
    expect(httpApiReadinessReason({ ...summary, sourceStatus: 'CONFLICT' }, detail, connection, scope)).toContain('来源')
    expect(httpApiReadinessReason({ ...summary, sourceConfirmed: false }, detail, connection, scope)).toContain('来源')
    expect(httpApiReadinessReason(summary, detail, { ...connection, status: 'BLOCKED', blockingReason: '凭据已停用' }, scope)).toBe('凭据已停用')
    expect(httpApiReadinessReason(summary, detail, { ...connection, environment: 'prod' }, scope)).toContain('连接身份')
    expect(httpApiReadinessReason(summary, { ...detail, summary: { ...summary, sourceSetRevision: 'd'.repeat(64) } }, connection, scope)).toContain('详情已变化')
    expect(httpApiReadinessReason(summary, { ...detail, summary: { ...summary, sourceConfirmed: false,
      sourceStatus: 'SOURCE_UNCONFIRMED' } }, connection, scope)).toContain('详情已变化')
  })

  it('maps declared path/query and JSON properties only, without response examples', () => {
    expect(httpApiInputTargets(detail.acceptedContract).map((item) => [item.key, item.required])).toEqual([
      ['pathParams.orderId', true], ['queryParams.expanded', false],
    ])
    expect(httpApiOutputFields(detail.acceptedContract)).toEqual(['orderId', 'customer', 'customer.name'])
    expect(httpApiOutputPorts('order', detail.acceptedContract).map((item) => item.id)).toEqual([
      'order', 'order.orderId', 'order.customer', 'order.customer.name',
    ])
    expect(httpApiOutputFields({ ...detail.acceptedContract!, responses: [
      { status: '200', contentTypes: ['application/json'], schema: { example: { fake: true } } },
    ] })).toEqual([])
    expect(httpApiOutputFields({ ...detail.acceptedContract!, responses: [
      { status: 'DEFAULT', contentTypes: ['application/json'], schema: {
        type: 'object', properties: { state: { type: 'string' } },
      } },
    ] })).toEqual(['state'])
  })

  it('writes only stable API identity and explicit mapping, never caller pin, origin or credential', () => {
    const config: ToolNodeConfig = { ref: 'legacy', qualifiedName: 'legacy', projectCode: 'orders',
      credentialRef: 'old-credential', inputMapping: { unrelated: 'lastOutput' },
      runtimeConfigExtras: { contractHash: HASH, origin: 'https://old.invalid' },
      nestedConfigExtras: { definitionId: 3 }, }
    applyHttpApiSelection(config, detail)
    expect(config).toMatchObject({ ref: REF, qualifiedName: REF, projectCode: 'orders',
      httpApiAssetId: 41, credentialRef: '', inputMapping: {
        'pathParams.orderId': '', 'queryParams.expanded': '',
      }, runtimeConfigExtras: {}, nestedConfigExtras: {} })
    const wire = JSON.stringify(config)
    expect(wire).not.toContain(HASH)
    expect(wire).not.toContain('old.invalid')
    expect(wire).not.toContain('old-credential')
    config.inputMapping['pathParams.orderId'] = 'params.orderId'
    applyHttpApiSelection(config, detail)
    expect(config.inputMapping['pathParams.orderId']).toBe('params.orderId')
  })

  it('round-trips GraphSpec API ref and mappings while keeping legacy TOOL kind and no frontend pin', () => {
    const config: ToolNodeConfig = { inputMapping: {} }
    applyHttpApiSelection(config, detail)
    config.inputMapping['pathParams.orderId'] = 'params.orderId'
    config.inputMapping['queryParams.expanded'] = 'nodeOutput.previous.enabled'
    const snapshot: CanvasSnapshot = { version: 2, nodes: [{ id: 'api-node', type: 'tool',
      position: { x: 0, y: 0 }, data: { label: 'GET /orders/{orderId}', kind: 'tool', configVersion: 2,
        outputAlias: 'order', inputs: [], outputs: httpApiOutputPorts('order', detail.acceptedContract),
        toolConfig: config } }], edges: [] }
    const base = { projectId: 7, projectCode: 'orders', graphSpec: { schemaVersion: 2,
      entryNodeId: 'api-node', exitNodeIds: ['api-node'], nodes: [], edges: [] } } as unknown as AgentForm
    const saved = canvasToDefinition(base, snapshot)
    const node = saved.graphSpec!.nodes[0]
    expect(node.type).toBe('TOOL')
    expect(node.ref).toMatchObject({ kind: 'TOOL', name: REF, qualifiedName: REF, projectCode: 'orders' })
    expect(node.config).toMatchObject({ httpApiAssetId: 41,
      inputMapping: { 'pathParams.orderId': 'params.orderId',
        'queryParams.expanded': 'nodeOutput.previous.enabled' }, outputAlias: 'order' })
    const wire = JSON.stringify(node)
    expect(wire).not.toContain(HASH)
    expect(wire).not.toContain('https://')
    expect(wire).not.toContain('definitionId')
    const reopened = definitionToCanvas(saved as unknown as WorkflowCanvasSource)
    const again = canvasToDefinition(base, reopened).graphSpec!.nodes[0]
    expect(again.ref).toMatchObject(node.ref!)
    expect(again.config).toMatchObject({ httpApiAssetId: 41,
      inputMapping: { 'pathParams.orderId': 'params.orderId',
        'queryParams.expanded': 'nodeOutput.previous.enabled' } })
  })
})
