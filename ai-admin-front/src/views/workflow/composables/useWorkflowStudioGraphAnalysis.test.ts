import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { HttpApiContract } from '@/types/httpApi'
import type { CanvasEdge, CanvasNode } from '@/types/studio'
import { httpApiOutputPorts } from '../httpApiWorkflow'
import { useWorkflowStudioGraphAnalysis } from './useWorkflowStudioGraphAnalysis'

function apiVariables(properties: Record<string, unknown>) {
  const contract: HttpApiContract = {
    identity: { method: 'GET', routeTemplate: '/orders/{orderId}' },
    parameters: [], requestBody: null,
    responses: [{ status: '200', contentTypes: ['application/json'], schema: {
      type: 'object', properties, example: { fake: 'not a declaration' },
    } }],
    authentication: { state: 'NONE', schemes: [], requiredHeaderNames: [] },
    sideEffect: 'READ_ONLY',
  }
  const outputs = httpApiOutputPorts('tool_output', contract)
  const nodes = ref<CanvasNode[]>([{
    id: 'query', type: 'tool', position: { x: 0, y: 0 },
    data: { label: 'GET /orders/{orderId}', kind: 'tool', configVersion: 2,
      outputAlias: 'tool_output', outputs },
  }])
  const analysis = useWorkflowStudioGraphAnalysis({
    nodes, edges: ref<CanvasEdge[]>([]), decorateWorkflowNode: (node) => node,
    markCanvasDirty: vi.fn(), syncJsonFromCanvas: vi.fn(),
    waitForNodeMeasurements: vi.fn(async () => undefined), getNodeMeasurement: () => undefined,
  })
  return analysis.graphVariables.value
}

describe('Workflow graph output candidate labels', () => {
  it('keeps each declared API field and its alias/node category distinguishable', () => {
    const variables = apiVariables({ apiKey: { type: 'string' }, detailLevel: { type: 'string' },
      orderId: { type: 'string' }, state: { type: 'string' } })
    expect(variables.find((item) => item.name === 'nodeOutput.query.state')?.label)
      .toBe('GET /orders/{orderId} · 节点输出 · state（API 响应 schema 声明字段）')
    expect(variables.find((item) => item.name === 'var.tool_output.state')?.label)
      .toBe('GET /orders/{orderId} · 输出变量 · state（API 响应 schema 声明字段）')
    const declared = variables.filter((item) => item.name.startsWith('nodeOutput.query.')
      || item.name.startsWith('var.tool_output.'))
    expect(declared).toHaveLength(8)
    expect(new Set(declared.map((item) => item.label)).size).toBe(declared.length)
  })

  it('retains nested declaration paths without deriving fields from examples', () => {
    const variables = apiVariables({ customer: {
      type: 'object', properties: { name: { type: 'string' } },
    } })
    expect(variables.find((item) => item.name === 'nodeOutput.query.customer.name')?.label)
      .toBe('GET /orders/{orderId} · 节点输出 · customer.name（API 响应 schema 声明字段）')
    expect(variables.find((item) => item.name === 'var.tool_output.customer.name')?.label)
      .toBe('GET /orders/{orderId} · 输出变量 · customer.name（API 响应 schema 声明字段）')
    expect(variables.some((item) => item.name.includes('fake'))).toBe(false)
  })
})
