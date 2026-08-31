import { describe, expect, it } from 'vitest'
import { buildApiMarketWorkflowDraft } from './apiMarketWorkflow'
import type { ApiMarketEntryDetail } from '@/types/apiMarket'
import type { AgentForm, WorkflowCanvasSource } from '@/types/agent'
import { canvasToDefinition, definitionToCanvas } from './studio'

describe('buildApiMarketWorkflowDraft', () => {
  it('creates an editable USER_INPUT to HTTP_REQUEST GraphSpec with provenance', () => {
    const detail: ApiMarketEntryDetail = {
      entry: {
        id: 1,
        entryKey: 'open-meteo',
        title: 'Open-Meteo',
        summary: '天气预报',
        categoryCode: 'WEATHER',
        tags: ['天气'],
        authType: 'NONE',
        pricingType: 'FREE',
        httpsSupported: true,
        publicationStatus: 'PUBLISHED',
        verificationStatus: 'VERIFIED',
        specStatus: 'VALID',
        featured: true,
        popularityScore: 98,
        source: {
          id: 1,
          sourceKey: 'official',
          name: '官方文档',
          sourceType: 'OFFICIAL',
          status: 'ACTIVE',
          entryCount: 1,
        },
      },
      verifications: [],
      versions: [{
        id: 2,
        versionKey: 'v1',
        baseUrl: 'https://api.open-meteo.com',
        specHash: 'sha256:test',
        publicationStatus: 'PUBLISHED',
        operations: [{
          id: 3,
          operationKey: 'forecast',
          title: '天气预报',
          httpMethod: 'GET',
          path: '/v1/forecast',
          sideEffect: 'READ_ONLY',
          authRequired: false,
          requestSchema: {
            type: 'object',
            properties: {
              latitude: { type: 'number', location: 'query', description: '纬度' },
            },
            required: ['latitude'],
          },
          exampleParams: { latitude: 39.9 },
          status: 'ACTIVE',
        }],
      }],
    }

    const version = detail.versions[0]
    const operation = version.operations[0]
    const draft = buildApiMarketWorkflowDraft(detail, version, operation, {
      name: '天气查询',
      keySlug: 'weather-query',
      projectId: 7,
      projectCode: 'demo',
      integrationId: 9,
    })

    expect(draft.graphSpec?.entryNodeId).toBe('api_input')
    expect(draft.graphSpec?.exitNodeIds).toEqual(['api_forecast'])
    const input = draft.graphSpec?.nodes.find(node => node.type === 'USER_INPUT')
    const http = draft.graphSpec?.nodes.find(node => node.type === 'HTTP_REQUEST')
    expect((input?.config?.fields as Array<Record<string, unknown>>)?.[0]?.source).toBe('latitude')
    expect(http?.config?.url).toBe('https://api.open-meteo.com/v1/forecast')
    expect(http?.config?.queryParams).toEqual({ latitude: '{{params.latitude}}' })
    expect((http?.config?.marketRef as Record<string, unknown>).specHash).toBe('sha256:test')
    expect((http?.config?.marketRef as Record<string, unknown>).integrationId).toBe(9)
    expect((http?.config?.marketRef as Record<string, unknown>).credentialRequired).toBe(false)

    const canvas = definitionToCanvas(draft as unknown as WorkflowCanvasSource)
    const httpCanvasNode = canvas.nodes.find(node => node.data.kind === 'http')
    expect(httpCanvasNode?.data.marketRef?.entryKey).toBe('open-meteo')
    const roundTripped = canvasToDefinition(draft as unknown as AgentForm, canvas)
    const roundTrippedHttp = roundTripped.graphSpec?.nodes.find(node => node.type === 'HTTP_REQUEST')
    expect((roundTrippedHttp?.config?.marketRef as Record<string, unknown>).integrationId).toBe(9)
  })
})
