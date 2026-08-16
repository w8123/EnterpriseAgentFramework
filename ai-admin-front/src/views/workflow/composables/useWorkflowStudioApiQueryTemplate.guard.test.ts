import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { WorkflowGraphNodeTypeDescriptor } from '@/types/agent'
import type { ProjectToolInfo } from '@/types/scanProject'
import { useWorkflowStudioApiQueryTemplate } from './useWorkflowStudioApiQueryTemplate'

const elMessage = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
  info: vi.fn(),
}))

const getScanProjectTools = vi.hoisted(() => vi.fn())
const routeQuery = vi.hoisted(() => ({
  intent: 'api-query-template',
  scanToolId: '12',
  projectApiTool: 'demo.search',
  projectApiName: 'demo search',
}))

vi.mock('element-plus', () => ({
  ElMessage: elMessage,
}))

vi.mock('@/api/scanProject', () => ({
  getScanProjectTools,
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({
    query: routeQuery,
  }),
}))

function descriptor(
  partial: Partial<WorkflowGraphNodeTypeDescriptor> & Pick<WorkflowGraphNodeTypeDescriptor, 'type' | 'canvasKind'>,
): WorkflowGraphNodeTypeDescriptor {
  return {
    canvasCategory: 'flow',
    family: 'FLOW',
    retryable: false,
    maturity: 'STABLE',
    runtimeExecutable: true,
    publishable: true,
    studioEnabled: true,
    aiAuthoringEnabled: true,
    unavailableReason: null,
    ...partial,
  }
}

function currentCatalog(openBlockingInteraction = false): WorkflowGraphNodeTypeDescriptor[] {
  return [
    descriptor({ type: 'TOOL', canvasKind: 'tool' }),
    descriptor({
      type: 'PAGE_ACTION',
      canvasKind: 'pageAction',
      maturity: 'BETA',
    }),
    descriptor({
      type: 'INTERACTION',
      canvasKind: 'interaction',
      maturity: 'BETA',
      runtimeExecutable: true,
      publishable: false,
      studioEnabled: true,
      aiAuthoringEnabled: true,
      enabledVariants: openBlockingInteraction
        ? ['PRESENT_OUTPUT', 'COLLECT_INPUT']
        : ['PRESENT_OUTPUT'],
      unavailableReason: openBlockingInteraction
        ? null
        : 'Only PRESENT_OUTPUT is enabled; pause/resume variants remain closed',
    }),
  ]
}

function selectableTool(): ProjectToolInfo {
  return {
    scanToolId: 12,
    name: 'demo.search',
    title: '查询演示数据',
    description: 'demo',
    parameters: [],
    source: 'scanner',
    enabled: true,
    globalToolDefinitionId: 99,
    globalToolName: 'demo.search',
    toolLinkStatus: 'LINKED',
    httpMethod: 'GET',
    endpointPath: '/api/demo/search',
    projectId: 7,
    projectCode: 'demo',
  }
}

function createTemplate(overrides: Record<string, unknown> = {}) {
  const nodes = ref([])
  const edges = ref([])
  const selectedNodeId = ref<string | null>(null)
  const selectedEdgeId = ref<string | null>(null)
  const propertyPanelCollapsed = ref(true)
  const markCanvasDirty = vi.fn()
  const syncJsonFromCanvas = vi.fn()
  const nodeTypes = ref(currentCatalog(false))
  const graphNodeTypeCapabilitiesLoaded = ref(true)
  const studio = ref({
    workflowId: 'wf-1',
    projectId: 7,
    projectCode: 'demo',
    name: 'Demo',
    keySlug: 'demo',
    graphSpecJson: '{"schemaVersion":2,"nodes":[],"edges":[],"entryNodeId":"","exitNodeIds":[]}',
    canvasJson: '{}',
  } as any)
  const deps = {
    studio,
    nodes,
    edges,
    selectedNodeId,
    selectedEdgeId,
    propertyPanelCollapsed,
    decorateWorkflowNode: (node: any) => node,
    decorateWorkflowEdge: (edge: any) => edge,
    markCanvasDirty,
    syncJsonFromCanvas,
    nodeTypes,
    graphNodeTypeCapabilitiesLoaded,
    ...overrides,
  }
  const api = useWorkflowStudioApiQueryTemplate(deps as any)
  return {
    api,
    nodes,
    edges,
    selectedNodeId,
    selectedEdgeId,
    propertyPanelCollapsed,
    markCanvasDirty,
    syncJsonFromCanvas,
    nodeTypes,
    graphNodeTypeCapabilitiesLoaded,
    studio,
  }
}

describe('useWorkflowStudioApiQueryTemplate capability guard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    routeQuery.intent = 'api-query-template'
    routeQuery.scanToolId = '12'
    getScanProjectTools.mockResolvedValue({ data: [selectableTool()] })
  })

  it('blocks open/apply/generate while COLLECT_INPUT remains closed', async () => {
    const ctx = createTemplate()
    expect(ctx.api.apiQueryTemplateAvailable.value).toBe(false)

    ctx.api.openApiQueryTemplateDialog()
    expect(ctx.api.apiQueryTemplateOpen.value).toBe(false)
    expect(getScanProjectTools).not.toHaveBeenCalled()
    expect(elMessage.warning).toHaveBeenCalled()

    elMessage.warning.mockClear()
    ctx.api.applyProjectApiRouteContext()
    expect(ctx.api.apiQueryTemplateOpen.value).toBe(false)
    expect(getScanProjectTools).not.toHaveBeenCalled()
    // Bootstrap path must stay quiet when template capability is closed.
    expect(elMessage.warning).not.toHaveBeenCalled()

    const beforeNodes = ctx.nodes.value.length
    const beforeEdges = ctx.edges.value.length
    ctx.api.generateApiQueryTemplate(selectableTool())
    expect(ctx.nodes.value).toHaveLength(beforeNodes)
    expect(ctx.edges.value).toHaveLength(beforeEdges)
    expect(ctx.selectedNodeId.value).toBeNull()
    expect(ctx.selectedEdgeId.value).toBeNull()
    expect(ctx.propertyPanelCollapsed.value).toBe(true)
    expect(ctx.markCanvasDirty).not.toHaveBeenCalled()
    expect(ctx.syncJsonFromCanvas).not.toHaveBeenCalled()
  })

  it('allows generate only when a synthetic catalog also opens COLLECT_INPUT', () => {
    const ctx = createTemplate()
    ctx.nodeTypes.value = currentCatalog(true)
    expect(ctx.api.apiQueryTemplateAvailable.value).toBe(true)

    ctx.api.openApiQueryTemplateDialog()
    expect(ctx.api.apiQueryTemplateOpen.value).toBe(true)

    ctx.api.generateApiQueryTemplate(selectableTool())
    expect(ctx.nodes.value).toHaveLength(4)
    expect(ctx.nodes.value.map((node: any) => node.data.kind)).toEqual([
      'interaction',
      'pageAction',
      'tool',
      'interaction',
    ])
    expect(ctx.edges.value).toHaveLength(3)
    const displayNode = ctx.nodes.value[3] as any
    expect(displayNode.data.interactionConfig.component).toBe('LIST_CARD')
    expect(displayNode.data.interactionConfig.presentation).toEqual({ mode: 'card_only' })
    expect(displayNode.data.interactionConfig.renderSchema).toMatchObject({
      version: '1.0',
      initialVisibleCount: 5,
      showCount: true,
    })
    expect(ctx.markCanvasDirty).toHaveBeenCalledTimes(1)
    expect(ctx.syncJsonFromCanvas).toHaveBeenCalledTimes(1)
    expect(ctx.api.apiQueryTemplateOpen.value).toBe(false)
    expect(elMessage.success).toHaveBeenCalled()
  })
})
