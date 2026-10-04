import { ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { ToolInfo, ToolParameter } from '@/types/tool'
import type { CanvasEdge, CanvasNode } from '@/types/studio'
import { useWorkflowStudioGraphAnalysis } from './composables/useWorkflowStudioGraphAnalysis'
import {
  businessMethodInputTargets,
  businessMethodMappingIssue,
  businessMethodOutputCandidates,
  businessMethodOutputFields,
  businessMethodOutputPorts,
  isSelectableBusinessMethod,
  missingBusinessMethodMappings,
  reconcileBusinessMethodInputMapping,
} from './businessMethodWorkflow'

function parameter(
  name: string,
  type = 'string',
  location: string | null = null,
  children: ToolParameter[] = [],
  required = false,
): ToolParameter {
  return { name, type, location, children, required, description: `${name} description` }
}

function businessMethod(overrides: Partial<ToolInfo> = {}): ToolInfo {
  return {
    assetType: 'BUSINESS_METHOD',
    name: 'orders.query',
    title: '查询订单',
    description: '查询订单详情',
    parameters: [],
    source: 'sdk',
    qualifiedName: 'orders:query',
    projectId: 7,
    projectCode: 'orders',
    sourceAvailability: 'READY',
    enabled: true,
    ...overrides,
  }
}

describe('Workflow Studio business-method mapping helpers', () => {
  it('offers the existing scalar draft-trial data path without inventing business fields', () => {
    const ports = businessMethodOutputPorts('scalar_result', [], 'java.lang.String')
    expect(ports.find((port) => port.id === 'scalar_result.data')).toMatchObject({
      name: 'scalar_result.data', source: '只读试运行返回值（标量）',
    })
    expect(businessMethodOutputFields([])).toEqual([])
    expect(businessMethodOutputCandidates('scalar', 'scalar_result', [], 'java.lang.String'))
      .toContainEqual({ value: 'nodeOutput.scalar.data', label: '只读试运行 · 标量返回值', path: 'data' })
    expect(businessMethodOutputPorts('dto', [], 'example.OrderResponse').map((port) => port.id)).toEqual(['dto'])
    expect(businessMethodOutputPorts('unknown', [], null).map((port) => port.id)).toEqual(['unknown'])
    const analysis = useWorkflowStudioGraphAnalysis({
      nodes: ref<CanvasNode[]>([{
        id: 'scalar', type: 'tool', position: { x: 0, y: 0 },
        data: { label: '标量只读方法', kind: 'tool', configVersion: 2, outputAlias: 'scalar_result', outputs: ports },
      }]),
      edges: ref<CanvasEdge[]>([]), decorateWorkflowNode: (node) => node,
      markCanvasDirty: vi.fn(), syncJsonFromCanvas: vi.fn(),
      waitForNodeMeasurements: vi.fn(async () => undefined), getNodeMeasurement: () => undefined,
    })
    expect(analysis.graphVariables.value.find((item) => item.name === 'nodeOutput.scalar.data')?.label)
      .toBe('标量只读方法 · 只读试运行返回值（标量）')
    expect(analysis.graphVariables.value.map((item) => item.name)).not.toContain('var.scalar_result.data')
  })

  it('maps only top-level input targets while retaining declared DTO, Map, array, and output shapes', () => {
    const parameters = [
      parameter('orderNo', 'string', 'INPUT', [], true),
      parameter('regions', 'array', 'INPUT'),
      parameter('criteria', 'object', 'INPUT', [
        parameter('customer.id', 'string', null, [], true),
        parameter('customer.level', 'string'),
      ], true),
      parameter('filters', 'map', 'INPUT'),
      parameter('items', 'array', 'INPUT'),
      parameter('result.orderId', 'string', 'RETURN'),
      parameter('result.status', 'string', 'RETURN'),
    ]

    expect(businessMethodInputTargets(parameters).map((item) => item.name)).toEqual([
      'orderNo', 'regions', 'criteria', 'filters', 'items',
    ])
    expect(businessMethodInputTargets(parameters).find((item) => item.name === 'criteria')?.children.map((item) => item.name)).toEqual([
      'customer',
    ])
    expect(businessMethodOutputFields(parameters).map((item) => item.path)).toEqual([
      'result', 'result.orderId', 'result.status',
    ])
    expect(businessMethodOutputCandidates('query', 'order_result', parameters).map((item) => item.value)).toEqual([
      'nodeOutput.query',
      'var.order_result',
      'nodeOutput.query.result',
      'var.order_result.result',
      'nodeOutput.query.result.orderId',
      'var.order_result.result.orderId',
      'nodeOutput.query.result.status',
      'var.order_result.result.status',
    ])
    expect(businessMethodOutputCandidates('query', 'order_result', [])).toEqual([
      { value: 'nodeOutput.query', label: '节点输出 · 根对象' },
      { value: 'var.order_result', label: '业务别名 · order_result' },
    ])
  })

  it('preserves matching author mappings, removes obsolete fields, and defaults new roots to params', () => {
    const targets = businessMethodInputTargets([
      parameter('criteria', 'object', 'INPUT', [parameter('id', 'string')], true),
      parameter('pageSize', 'integer', 'INPUT'),
    ])
    const result = reconcileBusinessMethodInputMapping({
      criteria: 'nodeOutput.extract.criteria',
      retired: 'params.retired',
    }, targets)

    expect(result).toEqual({
      mapping: { criteria: 'nodeOutput.extract.criteria', pageSize: 'params.pageSize' },
      preserved: 1,
      added: 1,
      removed: 1,
    })
    expect(missingBusinessMethodMappings(targets, { criteria: '' })).toEqual(['criteria'])
  })

  it('offers declared downstream fields to graph analysis and accepts only same-project READY business methods', () => {
    const parameters = [parameter('result.status', 'string', 'RESPONSE')]
    const extract: CanvasNode = {
      id: 'extract',
      type: 'tool',
      position: { x: 0, y: 0 },
      data: {
        label: '提取订单状态',
        kind: 'tool',
        configVersion: 2,
        outputAlias: 'extract_result',
        inputs: [],
        outputs: businessMethodOutputPorts('extract_result', parameters),
        toolConfig: { ref: 'orders.extract', inputMapping: {} },
      },
    }
    const tool: CanvasNode = {
      id: 'query',
      type: 'tool',
      position: { x: 0, y: 0 },
      data: {
        label: '查询订单',
        kind: 'tool',
        configVersion: 2,
        outputAlias: 'order_result',
        inputs: [{
          id: 'status',
          name: 'status',
          type: 'string',
          required: true,
          source: 'var.extract_result.result.status',
        }],
        outputs: businessMethodOutputPorts('order_result', parameters),
        toolConfig: { ref: 'orders.query', inputMapping: { status: 'var.extract_result.result.status' } },
      },
    }
    const analysis = useWorkflowStudioGraphAnalysis({
      nodes: ref([extract, tool]),
      edges: ref<CanvasEdge[]>([]),
      decorateWorkflowNode: (node) => node,
      markCanvasDirty: vi.fn(),
      syncJsonFromCanvas: vi.fn(),
      waitForNodeMeasurements: vi.fn(async () => undefined),
      getNodeMeasurement: () => undefined,
    })

    expect(analysis.graphVariables.value.map((item) => item.name)).toEqual(expect.arrayContaining([
      'var.order_result',
      'var.order_result.result.status',
      'nodeOutput.query',
      'nodeOutput.query.result.status',
    ]))
    expect(analysis.graphVariables.value.map((item) => item.name)).not.toContain('nodeOutput.query.invented')
    expect(analysis.graphLintErrors.value.some((item) => item.message.includes('不存在的变量'))).toBe(false)
    expect(isSelectableBusinessMethod(businessMethod(), 7)).toBe(true)
    expect(isSelectableBusinessMethod(businessMethod({ projectId: 8 }), 7)).toBe(false)
    expect(isSelectableBusinessMethod(businessMethod({ assetType: 'HTTP_API' }), 7)).toBe(false)
    expect(isSelectableBusinessMethod(businessMethod({ sourceAvailability: 'CONTRACT_DRIFT' }), 7)).toBe(false)
  })

  it('recognizes declared Workflow root inputs when validating business-method mappings', () => {
    const tool: CanvasNode = {
      id: 'query',
      type: 'tool',
      position: { x: 0, y: 0 },
      data: {
        label: '查询订单',
        kind: 'tool',
        configVersion: 2,
        inputs: [
          { id: 'orderNo', name: 'orderNo', type: 'string', required: true, source: 'orderNo' },
          { id: 'request', name: 'request', type: 'object', required: true, source: 'request' },
        ],
        outputs: [],
        toolConfig: {
          ref: 'orders.query',
          inputMapping: { orderNo: 'orderNo', request: 'request' },
        },
      },
    }
    const analysis = useWorkflowStudioGraphAnalysis({
      nodes: ref([tool]),
      edges: ref<CanvasEdge[]>([]),
      workflowInputSchemaJson: ref(JSON.stringify({
        type: 'object',
        properties: { orderNo: { type: 'string' }, request: { type: 'object' } },
      })),
      decorateWorkflowNode: (node) => node,
      markCanvasDirty: vi.fn(),
      syncJsonFromCanvas: vi.fn(),
      waitForNodeMeasurements: vi.fn(async () => undefined),
      getNodeMeasurement: () => undefined,
    })

    expect(analysis.graphVariables.value.map((item) => item.name)).toEqual(expect.arrayContaining(['orderNo', 'request']))
    expect(analysis.graphLintErrors.value.some((item) => item.message.includes('不存在的变量'))).toBe(false)
  })

  it('keeps dynamic legacy paths valid while their node or business alias roots still exist', () => {
    const legacyTool: CanvasNode = {
      id: 'legacy-tool',
      type: 'tool',
      position: { x: 0, y: 0 },
      data: {
        label: '旧通用 Tool',
        kind: 'tool',
        configVersion: 2,
        outputAlias: 'legacy_output',
        inputs: [],
        outputs: [],
        toolConfig: { ref: 'legacy.generic', inputMapping: {} },
      },
    }
    const businessMethodTool: CanvasNode = {
      id: 'query-orders',
      type: 'tool',
      position: { x: 180, y: 0 },
      data: {
        label: '查询订单',
        kind: 'tool',
        configVersion: 2,
        outputAlias: 'query_result',
        inputs: [],
        outputs: businessMethodOutputPorts('query_result', [parameter('result.status', 'string', 'RETURN')]),
        toolConfig: { ref: 'orders.query', inputMapping: {} },
      },
    }
    const consumer: CanvasNode = {
      id: 'consumer',
      type: 'tool',
      position: { x: 360, y: 0 },
      data: {
        label: '下游调用',
        kind: 'tool',
        configVersion: 2,
        inputs: [
          { id: 'legacyPayload', name: 'legacyPayload', type: 'any', required: true, source: 'nodeOutput.legacy-tool.dynamic.payload.id' },
          { id: 'businessPayload', name: 'businessPayload', type: 'any', required: true, source: 'var.query_result.dynamic.payload.id' },
        ],
        outputs: [],
        toolConfig: {
          ref: 'consumer',
          inputMapping: {
            legacyPayload: 'nodeOutput.legacy-tool.dynamic.payload.id',
            businessPayload: 'var.query_result.dynamic.payload.id',
          },
        },
      },
    }
    const analysis = useWorkflowStudioGraphAnalysis({
      nodes: ref([legacyTool, businessMethodTool, consumer]),
      edges: ref<CanvasEdge[]>([]),
      decorateWorkflowNode: (node) => node,
      markCanvasDirty: vi.fn(),
      syncJsonFromCanvas: vi.fn(),
      waitForNodeMeasurements: vi.fn(async () => undefined),
      getNodeMeasurement: () => undefined,
    })

    expect(analysis.graphVariables.value.map((item) => item.name)).not.toContain('nodeOutput.legacy-tool.dynamic.payload.id')
    expect(analysis.graphVariables.value.map((item) => item.name)).not.toContain('var.query_result.dynamic.payload.id')
    expect(analysis.graphLintErrors.value.some((item) => item.message.includes('nodeOutput.legacy-tool.dynamic.payload.id'))).toBe(false)
    expect(analysis.graphLintErrors.value.some((item) => item.message.includes('var.query_result.dynamic.payload.id'))).toBe(false)

    const rootsRemoved = useWorkflowStudioGraphAnalysis({
      nodes: ref([consumer]),
      edges: ref<CanvasEdge[]>([]),
      decorateWorkflowNode: (node) => node,
      markCanvasDirty: vi.fn(),
      syncJsonFromCanvas: vi.fn(),
      waitForNodeMeasurements: vi.fn(async () => undefined),
      getNodeMeasurement: () => undefined,
    })
    expect(rootsRemoved.graphLintErrors.value.some((item) => item.message.includes('nodeOutput.legacy-tool.dynamic.payload.id'))).toBe(true)
    expect(rootsRemoved.graphLintErrors.value.some((item) => item.message.includes('var.query_result.dynamic.payload.id'))).toBe(true)
  })

  it('uses declared fields as picker hints while mapping validity follows surviving roots', () => {
    const availableValues = [
      'nodeOutput.legacy-tool',
      'var.query_result',
      'var.query_result.result.status',
    ]

    expect(businessMethodMappingIssue(
      'criteria',
      'nodeOutput.legacy-tool.dynamic.payload.id',
      true,
      availableValues,
    )).toBe('')
    expect(businessMethodMappingIssue(
      'criteria',
      'var.query_result.dynamic.payload.id',
      true,
      availableValues,
    )).toBe('')
    expect(businessMethodMappingIssue(
      'criteria',
      'nodeOutput.removed.dynamic.payload.id',
      true,
      availableValues,
    )).toContain('已不存在')
    expect(businessMethodMappingIssue(
      'criteria',
      'var.renamed.dynamic.payload.id',
      true,
      availableValues,
    )).toContain('已不存在')
  })
})
