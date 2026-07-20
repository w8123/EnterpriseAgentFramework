import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import type { AgentGraphNodeTypeDescriptor } from '@/types/agent'
import type { CanvasNode } from '@/types/studio'
import { useWorkflowStudioCanvasActions } from './useWorkflowStudioCanvasActions'

const elMessage = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
  info: vi.fn(),
}))

vi.mock('element-plus', () => ({
  ElMessage: elMessage,
}))

function descriptor(
  partial: Partial<AgentGraphNodeTypeDescriptor> & Pick<AgentGraphNodeTypeDescriptor, 'type' | 'canvasKind'>,
): AgentGraphNodeTypeDescriptor {
  return {
    canvasCategory: 'flow',
    family: 'FLOW',
    retryable: false,
    aliases: [],
    maturity: 'STABLE',
    runtimeExecutable: true,
    publishable: true,
    studioEnabled: true,
    aiAuthoringEnabled: true,
    unavailableReason: null,
    ...partial,
  }
}

function canvasNode(kind: string, id = `${kind}-1`): CanvasNode {
  return {
    id,
    type: kind,
    position: { x: 100, y: 120 },
    data: {
      kind: kind as any,
      label: kind,
      source: 'CANVAS',
      configVersion: 1,
      inputs: [],
      outputs: [],
    },
  } as unknown as CanvasNode
}

function createActions(overrides: Record<string, unknown> = {}) {
  const nodes = ref<CanvasNode[]>([])
  const edges = ref([])
  const selectedNodeId = ref<string | null>(null)
  const selectedEdgeId = ref<string | null>(null)
  const debugNodeId = ref('')
  const copiedNode = ref<CanvasNode | null>(null)
  const markCanvasDirty = vi.fn()
  const syncJsonFromCanvas = vi.fn()
  const fitView = vi.fn()
  const nextTick = vi.fn(async (fn?: () => void) => {
    fn?.()
  })
  const nodeTypes = ref<AgentGraphNodeTypeDescriptor[]>([
    descriptor({ type: 'LLM', canvasKind: 'llm' }),
    descriptor({ type: 'TOOL', canvasKind: 'tool' }),
    descriptor({
      type: 'INTERACTION',
      canvasKind: 'interaction',
      maturity: 'BETA',
      runtimeExecutable: true,
      publishable: false,
      studioEnabled: false,
      aiAuthoringEnabled: false,
      unavailableReason: 'Workflow interaction pause/resume closure is not complete',
    }),
    descriptor({
      type: 'CODE',
      canvasKind: 'code',
      maturity: 'PLANNED',
      runtimeExecutable: false,
      publishable: false,
      studioEnabled: false,
      aiAuthoringEnabled: false,
      unavailableReason: 'Runtime Handler is not implemented for this node type',
    }),
  ])
  const graphNodeTypeCapabilitiesLoaded = ref(true)
  const deps = {
    studioReadOnly: ref(false),
    nodes,
    edges,
    selectedNodeId,
    selectedEdgeId,
    debugNodeId,
    copiedNode,
    currentDebugNodeId: ref(''),
    nodeTraceStates: computed(() => ({})),
    canCopySelectedNode: computed(() => true),
    selectedNode: computed(() => null),
    selectedEdge: computed(() => null),
    workflowExecutionPath: computed(() => []),
    workflowHitEdgeKeys: computed(() => new Set<string>()),
    workflowExecutionSourceNodeIds: computed(() => new Set<string>()),
    getNodeDebugState: () => null,
    getLastRouteForNode: () => '',
    markCanvasDirty,
    syncJsonFromCanvas,
    activeTab: ref('visual'),
    propertyDetailOpen: ref(false),
    fitView,
    nextTick,
    nodeTypes,
    graphNodeTypeCapabilitiesLoaded,
    ...overrides,
  }
  const api = useWorkflowStudioCanvasActions(deps as any)
  return {
    api,
    nodes,
    edges,
    selectedNodeId,
    selectedEdgeId,
    debugNodeId,
    copiedNode,
    markCanvasDirty,
    syncJsonFromCanvas,
    fitView,
    nextTick,
    nodeTypes,
    graphNodeTypeCapabilitiesLoaded,
  }
}

describe('useWorkflowStudioCanvasActions pasteCopiedNode guard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('blocks paste when catalog is unloaded and leaves canvas untouched', () => {
    const ctx = createActions()
    ctx.graphNodeTypeCapabilitiesLoaded.value = false
    ctx.copiedNode.value = canvasNode('llm')
    ctx.api.pasteCopiedNode()
    expect(ctx.nodes.value).toHaveLength(0)
    expect(ctx.edges.value).toHaveLength(0)
    expect(ctx.selectedNodeId.value).toBeNull()
    expect(ctx.selectedEdgeId.value).toBeNull()
    expect(ctx.debugNodeId.value).toBe('')
    expect(ctx.markCanvasDirty).not.toHaveBeenCalled()
    expect(ctx.syncJsonFromCanvas).not.toHaveBeenCalled()
    expect(ctx.fitView).not.toHaveBeenCalled()
    expect(elMessage.warning).toHaveBeenCalled()
  })

  it('blocks INTERACTION paste when studioEnabled=false', () => {
    const ctx = createActions()
    ctx.copiedNode.value = canvasNode('interaction')
    ctx.api.pasteCopiedNode()
    expect(ctx.nodes.value).toHaveLength(0)
    expect(ctx.markCanvasDirty).not.toHaveBeenCalled()
    expect(ctx.syncJsonFromCanvas).not.toHaveBeenCalled()
    expect(ctx.fitView).not.toHaveBeenCalled()
    expect(elMessage.warning.mock.calls[0]?.[0]).toContain('pause/resume')
  })

  it('blocks PLANNED node paste', () => {
    const ctx = createActions()
    ctx.copiedNode.value = canvasNode('code')
    ctx.api.pasteCopiedNode()
    expect(ctx.nodes.value).toHaveLength(0)
    expect(ctx.markCanvasDirty).not.toHaveBeenCalled()
    expect(ctx.syncJsonFromCanvas).not.toHaveBeenCalled()
    expect(ctx.fitView).not.toHaveBeenCalled()
    expect(elMessage.warning.mock.calls[0]?.[0]).toContain('Runtime Handler')
  })

  it('allows STABLE LLM paste once and mutates canvas correctly', async () => {
    const ctx = createActions()
    ctx.copiedNode.value = canvasNode('llm', 'llm-src')
    ctx.api.pasteCopiedNode()
    expect(ctx.nodes.value).toHaveLength(1)
    expect(ctx.nodes.value[0].data.kind).toBe('llm')
    expect(ctx.nodes.value[0].data.label).toContain('Copy')
    expect(ctx.nodes.value[0].id).not.toBe('llm-src')
    expect(ctx.selectedNodeId.value).toBe(ctx.nodes.value[0].id)
    expect(ctx.selectedEdgeId.value).toBeNull()
    expect(ctx.debugNodeId.value).toBe(ctx.nodes.value[0].id)
    expect(ctx.markCanvasDirty).toHaveBeenCalledTimes(1)
    expect(ctx.syncJsonFromCanvas).toHaveBeenCalledTimes(1)
    expect(ctx.nextTick).toHaveBeenCalledTimes(1)
    expect(ctx.fitView).toHaveBeenCalledTimes(1)
  })
})
