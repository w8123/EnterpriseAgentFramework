import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import type { AgentForm, WorkflowCanvasSource } from '@/types/agent'
import type { ToolInfo } from '@/types/tool'
import type { CanvasSnapshot, ToolNodeConfig } from '@/types/studio'
import type { HttpApiDetail, HttpApiSummary } from '@/types/httpApi'
import { canvasToDefinition, definitionToCanvas } from '@/utils/studio'
import { applyBusinessMethodSelection, businessMethodOutputPorts } from '@/views/workflow/businessMethodWorkflow'

const businessMethodApi = vi.hoisted(() => ({
  getBusinessMethods: vi.fn(),
  getBusinessMethod: vi.fn(),
}))
const httpApi = vi.hoisted(() => ({ getHttpApi: vi.fn(), getHttpApiConnection: vi.fn(), listHttpApis: vi.fn() }))
const scanProjectApi = vi.hoisted(() => ({ getScanProjectDetail: vi.fn() }))

vi.mock('@/api/tool', () => businessMethodApi)
vi.mock('@/api/httpApi', () => httpApi)
vi.mock('@/api/scanProject', () => scanProjectApi)

import ToolConfigPanel from './ToolConfigPanel.vue'
import NodeConfigPanel from './NodeConfigPanel.vue'

function toolData(overrides: Record<string, unknown> = {}) {
  return {
    label: '调用订单',
    kind: 'tool' as const,
    configVersion: 2 as const,
    outputAlias: 'tool_output',
    inputs: [],
    outputs: [],
    toolConfig: {
      ref: '',
      qualifiedName: null,
      projectCode: null,
      inputMapping: {},
    },
    ...overrides,
  }
}

function workflowBase(overrides: Record<string, unknown> = {}) {
  return {
    keySlug: 'orders-workflow',
    name: '订单 Workflow',
    description: '',
    agentMode: 'WORKFLOW',
    projectId: 7,
    projectCode: 'orders',
    visibility: 'PROJECT',
    allowedRoles: [],
    intentType: 'GENERAL',
    systemPrompt: '',
    tools: [],
    modelInstanceId: '',
    runtimeType: 'LANGGRAPH4J',
    runtimePlacement: 'CENTRAL',
    runtimeConfig: {},
    defaultResourceConfig: {},
    graphSpec: {
      schemaVersion: 2,
      entryNodeId: 'tool',
      exitNodeIds: ['tool'],
      nodes: [],
      edges: [],
    },
    maxSteps: 20,
    enabled: true,
    type: 'single',
    pipelineAgentIds: [],
    knowledgeBaseGroupId: '',
    promptTemplateId: '',
    outputSchemaType: '',
    triggerMode: 'MANUAL',
    useMultiAgentModel: false,
    extra: {},
    allowIrreversible: false,
    ...overrides,
  } as unknown as AgentForm
}

function businessMethod(overrides: Partial<ToolInfo> = {}): ToolInfo {
  return {
    assetType: 'BUSINESS_METHOD',
    name: 'orders.health',
    title: '订单健康检查',
    description: '读取订单服务健康状态',
    parameters: [],
    source: 'sdk',
    qualifiedName: 'orders:health',
    projectId: 7,
    projectCode: 'orders',
    sourceAvailability: 'READY',
    enabled: true,
    ...overrides,
  }
}

describe('ToolConfigPanel business-method regression targets', () => {
  it('does not describe a missing required query mapping as a path mapping', () => {
    const wrapper = mount(ToolConfigPanel, {
      props: { data: toolData(), options: [], credentialOptions: [], paramSourceHints: [], projectId: 7, projectCode: 'orders' },
      global: { plugins: [ElementPlus] },
    })
    const setup = (wrapper.vm as unknown as {
      $: { setupState: { httpApiMappingIssue: (target: string, required: boolean) => string } }
    }).$.setupState
    expect(setup.httpApiMappingIssue('queryParams.detailLevel', true),
      'HTTP_API_QUERY_MAPPING_LABEL_INCORRECT').toBe('必填 API 参数尚未映射。')
    expect(setup.httpApiMappingIssue('pathParams.orderId', true)).toBe('必填 API 参数尚未映射。')
    wrapper.unmount()
  })

  it('offers only typed owner selection and retires scan-derived and generic author selection', () => {
    const wrapper = mount(ToolConfigPanel, {
      props: {
        data: toolData(),
        options: [],
        credentialOptions: [],
        paramSourceHints: [],
        projectId: 7,
        projectCode: 'orders',
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('选择业务方法')
    expect(wrapper.text()).toContain('选择 API')
    expect(wrapper.text()).not.toContain('从项目接口选择')
    expect(wrapper.get('input[aria-label="当前资产引用"]').attributes('readonly')).toBeDefined()
    expect(wrapper.text()).toContain('引用能力')
  })

  it('serializes an explicit zero-argument business method as args={} without catalog metadata', () => {
    const selectedConfig: ToolNodeConfig = {
      ref: 'legacy.generic',
      qualifiedName: 'legacy:generic',
      projectCode: 'orders',
      inputMapping: { retired: 'params.retired' },
      argumentSource: 'inputMapping',
      runtimeConfigExtras: {
        assetType: 'HTTP_API',
        sourceQualifiedName: 'legacy:generic',
        contractHash: 'a'.repeat(64),
        legacyExecutionMode: 'compat-v1',
      },
      nestedConfigExtras: {
        definitionId: 21,
        frontendHash: 'b'.repeat(64),
        legacyNested: { preserve: true },
      },
    }
    applyBusinessMethodSelection(selectedConfig, businessMethod(), 'orders')
    const snapshot: CanvasSnapshot = {
      version: 2,
      nodes: [
        { id: 'start', type: 'start', position: { x: 0, y: 0 }, data: { label: '开始', kind: 'start', configVersion: 2 } },
        {
          id: 'tool',
          type: 'tool',
          position: { x: 180, y: 0 },
          data: toolData({
            toolConfig: selectedConfig,
          }),
        },
        { id: 'end', type: 'end', position: { x: 360, y: 0 }, data: { label: '结束', kind: 'end', configVersion: 2 } },
      ],
      edges: [
        { id: 'start-tool', source: 'start', target: 'tool', condition: 'always' },
        { id: 'tool-end', source: 'tool', target: 'end', condition: 'always' },
      ],
    }

    const saved = canvasToDefinition(workflowBase(), snapshot)
    const node = saved.graphSpec?.nodes.find((item) => item.id === 'tool')
    const config = node?.config as Record<string, unknown>
    const nested = config.toolConfig as Record<string, unknown>

    expect(config.args).toEqual({})
    expect(config).not.toHaveProperty('inputMapping')
    expect(config).not.toHaveProperty('assetType')
    expect(config).not.toHaveProperty('sourceQualifiedName')
    expect(config).not.toHaveProperty('contractHash')
    expect(nested).not.toHaveProperty('assetType')
    expect(nested).not.toHaveProperty('sourceQualifiedName')
    expect(nested).not.toHaveProperty('definitionId')
    expect(nested).not.toHaveProperty('contractHash')
  })

  it('round-trips legacy Tool runtime fields and native mapping values without turning them into catalog metadata', () => {
    const definition = workflowBase({
      graphSpec: {
        schemaVersion: 2,
        entryNodeId: 'legacy',
        exitNodeIds: ['legacy'],
        nodes: [{
          id: 'legacy',
          type: 'TOOL',
          name: 'Legacy Tool',
          ref: { kind: 'TOOL', name: 'legacyTool', qualifiedName: 'orders:legacyTool', projectCode: 'orders' },
          config: {
            inputMapping: {
              filter: { page: 1, enabled: false },
              ids: ['a', 'b'],
              nullable: null,
            },
            contractHash: 'legacy-contract-hash',
            legacyExecutionMode: 'compat-v1',
            toolConfig: {
              definitionId: 901,
              legacyNested: { preserve: true },
            },
          },
        }],
        edges: [],
      },
    }) as unknown as WorkflowCanvasSource

    const snapshot = definitionToCanvas(definition)
    const saved = canvasToDefinition(definition as unknown as AgentForm, snapshot)
    const config = saved.graphSpec?.nodes[0]?.config as Record<string, unknown>

    expect(config.inputMapping).toEqual({
      filter: { page: 1, enabled: false },
      ids: ['a', 'b'],
      nullable: null,
    })
    expect(config.contractHash).toBe('legacy-contract-hash')
    expect(config.legacyExecutionMode).toBe('compat-v1')
    expect((config.toolConfig as Record<string, unknown>).definitionId).toBe(901)
    expect((config.toolConfig as Record<string, unknown>).legacyNested).toEqual({ preserve: true })
  })

  it('keeps a just-selected candidate and its retry action visible when the detail refresh fails', async () => {
    businessMethodApi.getBusinessMethod.mockReset()
    businessMethodApi.getBusinessMethod.mockRejectedValue({ response: { status: 503 } })
    const wrapper = mount(ToolConfigPanel, {
      props: {
        data: toolData(),
        options: [],
        credentialOptions: [],
        paramSourceHints: [],
        projectId: 7,
        projectCode: 'orders',
      },
      global: { plugins: [ElementPlus] },
    })
    const setupState = (wrapper.vm as unknown as {
      $: { setupState: { selectBusinessMethod: (method: ToolInfo) => void } }
    }).$.setupState

    setupState.selectBusinessMethod(businessMethod({ name: 'orders.query', title: '查询订单', qualifiedName: 'orders:query' }))
    await flushPromises()

    expect(wrapper.text()).toContain('查询订单')
    expect(wrapper.text()).toContain('已有引用和映射未被修改')
    expect(wrapper.text()).toContain('重试读取摘要')
    wrapper.unmount()
  })

  it('does not rewrite equivalent business-method ports after a saved node is reopened', async () => {
    const selected = businessMethod({
      name: 'orders.query',
      title: '查询订单',
      qualifiedName: 'orders:query',
      parameters: [
        { name: 'criteria', type: 'object', location: 'INPUT', required: true, description: '查询条件', children: [] },
        { name: 'pageSize', type: 'integer', location: 'INPUT', required: false, description: '分页大小', children: [] },
        { name: 'result.orderId', type: 'string', location: 'RETURN', required: false, description: '订单标识', children: [] },
      ],
    })
    const savedInputs = [
      { id: 'criteria', name: 'criteria', type: 'object', required: true, schema: '', source: 'params.criteria' },
      { id: 'pageSize', name: 'pageSize', type: 'integer', required: false, schema: '', source: 'params.pageSize' },
    ]
    const savedOutputs = businessMethodOutputPorts('order_result', selected.parameters)
      .map((port) => ({ ...port, schema: '', source: port.source || '' }))
    const data = toolData({
      outputAlias: 'order_result',
      inputs: savedInputs,
      outputs: savedOutputs,
      toolConfig: {
        ref: selected.name,
        qualifiedName: selected.qualifiedName,
        projectCode: 'orders',
        inputMapping: { criteria: 'params.criteria', pageSize: 'params.pageSize' },
        argumentSource: 'inputMapping',
      },
    })
    const serializedBeforeOpen = JSON.stringify(data)
    businessMethodApi.getBusinessMethod.mockReset()
    businessMethodApi.getBusinessMethod.mockResolvedValue({ data: selected })
    const wrapper = mount(ToolConfigPanel, {
      props: {
        data,
        options: [],
        credentialOptions: [],
        paramSourceHints: [],
        projectId: 7,
        projectCode: 'orders',
      },
      global: { plugins: [ElementPlus] },
    })

    await flushPromises()
    expect(data.inputs).toBe(savedInputs)
    expect(data.outputs).toBe(savedOutputs)
    expect(JSON.stringify(data)).toBe(serializedBeforeOpen)
    wrapper.unmount()
  })

  it('does not make the reopened node dirty through the parent config panel lifecycle', async () => {
    const selected = businessMethod({
      name: 'orders.query',
      title: '查询订单',
      qualifiedName: 'orders:query',
      parameters: [
        {
          name: 'criteria',
          type: 'object',
          location: 'INPUT',
          required: true,
          description: 'criteria 的来源声明',
          children: [{ name: 'customer.id', type: 'string', location: null, required: true, description: 'customer.id 的来源声明', children: [] }],
        },
        { name: 'pageSize', type: 'integer', location: 'INPUT', required: false, description: 'pageSize 的来源声明', children: [] },
        { name: 'result.orderId', type: 'string', location: 'RETURN', required: false, description: 'result.orderId 的来源声明', children: [] },
        { name: 'result.status', type: 'string', location: 'RETURN', required: false, description: 'result.status 的来源声明', children: [] },
      ],
    })
    const data = toolData({
      label: '查询订单',
      description: '按客户条件查询订单状态。',
      outputAlias: 'order_result',
      inputs: [
        { id: 'criteria', name: 'criteria', type: 'object', required: true, source: 'params.criteria' },
        { id: 'pageSize', name: 'pageSize', type: 'integer', required: false, source: 'params.pageSize' },
      ],
      outputs: businessMethodOutputPorts('order_result', selected.parameters),
      toolConfig: {
        ref: selected.name,
        qualifiedName: selected.qualifiedName,
        projectCode: 'orders',
        credentialRef: '',
        maxRequestTimeMs: 180000,
        inputMapping: { criteria: 'params.criteria', pageSize: 'params.pageSize' },
        mappingNote: '',
        argumentSource: 'inputMapping',
        runtimeConfigExtras: {},
        nestedConfigExtras: {},
      },
    })
    const beforeOpen = JSON.stringify(data)
    businessMethodApi.getBusinessMethod.mockReset()
    businessMethodApi.getBusinessMethod.mockResolvedValue({ data: selected })

    const wrapper = mount(NodeConfigPanel, {
      props: {
        data,
        nodeId: 'query-order',
        canvasNodes: [],
        modelOptions: [],
        modelOptionsLoading: false,
        modelOptionsLoadError: false,
        knowledgeOptions: [],
        toolOptions: [],
        variableOptions: [],
        credentialOptions: [],
        paramSourceHints: [],
        projectId: 7,
        projectCode: 'orders',
        requestScopeKey: 'browser-fixture-session',
      },
      global: { plugins: [ElementPlus] },
    })

    await flushPromises()
    expect(JSON.stringify(data)).toBe(beforeOpen)
    wrapper.unmount()
  })
})

describe('ToolConfigPanel published POST author configuration', () => {
  async function mountedPost() {
    const reference = `http-api:orders:dev:${'b'.repeat(64)}`
    const summary: HttpApiSummary = { id: 41, qualifiedName: reference, projectId: 7, projectCode: 'orders', environment: 'dev',
      httpMethod: 'POST', routeTemplate: '/orders/{orderId}/notes', sourceStatus: 'ACCEPTED', sourceConfirmed: true,
      sourceReason: null, acceptedContractHash: 'a'.repeat(64), candidateContractHash: 'a'.repeat(64),
      sourceSetRevision: 'c'.repeat(64), activeSourceCount: 1, sourceKinds: ['OPENAPI_SCAN'], acceptedBy: 'reviewer', acceptedAt: null }
    const detail: HttpApiDetail = { summary, sources: [], contract: null, acceptedContract: {
      identity: { method: 'POST', routeTemplate: summary.routeTemplate }, sideEffect: 'WRITE',
      authentication: { state: 'NONE', schemes: [], requiredHeaderNames: [] },
      parameters: [{ name: 'orderId', location: 'PATH', required: true, schema: { type: 'string' }, contentTypes: [] }],
      requestBody: { required: true, contentTypes: ['application/json'], schema: { type: 'object', required: ['note'], properties: {
        note: { type: 'string' }, notify: { type: 'boolean', default: true }, quantity: { type: 'integer', default: 3 },
        caption: { type: 'string' }, accessCode: { type: 'string', format: 'password' }, deliveryMark: { type: 'string', writeOnly: true },
      } } }, responses: [{ status: '200', contentTypes: ['application/json'], schema: {
        type: 'object', properties: { state: { type: 'string' } },
      } }],
    } }
    scanProjectApi.getScanProjectDetail.mockResolvedValue({ data: { id: 7, projectCode: 'orders', environment: 'dev' } })
    httpApi.getHttpApi.mockResolvedValue({ data: detail })
    httpApi.getHttpApiConnection.mockResolvedValue({ data: { qualifiedName: reference, projectId: 7,
      projectCode: 'orders', environment: 'dev', status: 'CONFIGURED', revision: 2, blockingReason: null } })
    const data = toolData({ toolConfig: { ref: reference, qualifiedName: reference, projectCode: 'orders', httpApiAssetId: 41,
      inputMapping: { 'pathParams.orderId': 'params.orderId', 'body.note': 'params.note', 'body.notify': false,
        'body.quantity': 0, 'body.caption': '', 'body.accessCode': 'params.accessCode', 'body.deliveryMark': 'params.deliveryMark' } } })
    const wrapper = mount(ToolConfigPanel, { props: { data, options: [], credentialOptions: [], paramSourceHints: [],
      projectId: 7, projectCode: 'orders' }, global: { plugins: [ElementPlus] } })
    await flushPromises()
    return { wrapper, data }
  }

  it('renders native BODY mappings, WRITE/debug restrictions and declared-sensitive variable guidance', async () => {
    const { wrapper, data } = await mountedPost()
    expect(wrapper.text()).toContain('POST /orders/{orderId}/notes')
    expect(wrapper.text()).toContain('Studio 两种调试入口均不调用写 API')
    expect(wrapper.text()).toContain('结果未确认时请先核对原运行')
    expect(wrapper.text()).toContain('API 请求使用服务端受控超时')
    expect(wrapper.text()).not.toContain('最大请求时间')
    expect(wrapper.findAll('.business-method-mapping-row')).toHaveLength(7)
    expect(wrapper.text()).toContain('BODY · boolean')
    expect(wrapper.findAll('.business-method-target small')).toHaveLength(2)
    expect(data.toolConfig.inputMapping).toMatchObject({ 'body.notify': false, 'body.quantity': 0, 'body.caption': '' })
    expect(JSON.stringify(data.toolConfig)).not.toContain('default')
    wrapper.unmount()
  })

  it('uses the mounted mapping handler to reject a sensitive literal and accept an input-variable reference', async () => {
    const { wrapper, data } = await mountedPost()
    const row = wrapper.findAll('.business-method-mapping-row').find((item) => item.text().includes('accessCode'))!
    row.findComponent({ name: 'ElSelect' }).vm.$emit('change', 'do-not-save-raw-secret')
    await flushPromises()
    expect((data.toolConfig.inputMapping as Record<string, unknown>)['body.accessCode']).toBe('params.accessCode')
    expect(JSON.stringify(data)).not.toContain('do-not-save-raw-secret')
    row.findComponent({ name: 'ElSelect' }).vm.$emit('change', 'params.accessCode')
    await flushPromises()
    expect((data.inputs as Array<{ id: string; source?: string }>).find((item) => item.id === 'body.accessCode')?.source).toBe('params.accessCode')
    const snapshot: CanvasSnapshot = { version: 2, nodes: [{ id: 'post', type: 'tool', position: { x: 0, y: 0 }, data }], edges: [] }
    const saved = canvasToDefinition(workflowBase(), snapshot)
    const node = saved.graphSpec?.nodes.find((item) => item.id === 'post')
    expect(node?.config?.inputMapping).toMatchObject({ 'body.notify': false, 'body.quantity': 0, 'body.caption': '' })
    expect(JSON.stringify(saved)).not.toContain('do-not-save-raw-secret')
    wrapper.unmount()
  })
})
