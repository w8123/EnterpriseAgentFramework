import { beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, ref } from 'vue'
import {
  formatAiAuthoringFailureMessage,
  isAiEditPreviewSucceeded,
  isWorkingCopyCanvasEmpty,
  useWorkflowStudioProposalActions,
} from './useWorkflowStudioProposalActions'
import { useWorkflowStudioPanelValidation } from './useWorkflowStudioPanelValidation'
import type { WorkflowProposalEditResult } from '@/types/workflow'

function succeededPreview(overrides: Partial<WorkflowProposalEditResult> = {}): WorkflowProposalEditResult {
  return {
    status: 'SUCCEEDED',
    provider: 'AGENTSCOPE_AUTHORING',
    summary: 'ok',
    operations: [{ type: 'ADD_NODE', nodeId: 'answer' }],
    canvasSnapshot: { nodes: [{ id: 'answer' }], edges: [] },
    graphSpec: {
      schemaVersion: 2,
      entryNodeId: 'answer',
      exitNodeIds: ['answer'],
      nodes: [{ id: 'answer', type: 'ANSWER' }],
      edges: [],
    } as any,
    warnings: [],
    placeholderNodes: [],
    validationErrors: [],
    ...overrides,
  }
}

const editWorkflowProposal = vi.hoisted(() => vi.fn())
const elMessage = vi.hoisted(() => ({
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn(),
  info: vi.fn(),
}))

vi.mock('@/api/workflow', () => ({
  editWorkflowProposal,
}))

vi.mock('element-plus', () => ({
  ElMessage: elMessage,
}))

function createDeps(overrides: Record<string, unknown> = {}) {
  const aiEditPreview = ref<WorkflowProposalEditResult | null>(null)
  const aiEditLoading = ref(false)
  const aiEditInstruction = ref('创建一个地铁问答工作流')
  const editGeneration = ref(1)
  const workflowId = ref('wf-1')
  const deps = {
    workflowId,
    studioReadOnly: ref(false),
    studio: ref({ name: 'Demo', projectCode: 'demo' } as any),
    editGeneration,
    graphSpecJson: ref('{"schemaVersion":2,"nodes":[],"edges":[],"entryNodeId":"","exitNodeIds":[]}'),
    canvasJson: ref('{"schemaVersion":1,"layoutVersion":1,"nodes":[],"edges":[]}'),
    nodes: ref([]),
    edges: ref([]),
    selectedNodeId: ref(null),
    selectedEdgeId: ref(null),
    activeTab: ref('visual'),
    validation: ref(null),
    aiEditInstruction,
    aiEditLoading,
    aiEditPreview,
    availableTools: computed(() => []),
    availableCompositions: computed(() => []),
    knowledgeOptions: ref([]),
    resolveAiModelInstanceId: () => 'model-1',
    toolToProposalResource: (tool: any) => tool,
    compositionToProposalResource: (item: any) => item,
    knowledgeToProposalResource: (item: any) => item,
    syncJsonFromCanvas: vi.fn(),
    canvasSnapshot: () => ({ version: 2, nodes: [], edges: [] }),
    applyCanvasFromStudio: vi.fn(),
    autoLayoutWorkflowCanvas: vi.fn(async () => undefined),
    fitCanvas: vi.fn(async () => undefined),
    ...overrides,
  }
  return { deps, aiEditPreview, aiEditLoading, aiEditInstruction, editGeneration, workflowId }
}

describe('useWorkflowStudioProposalActions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('treats only SUCCEEDED clean previews as applyable', () => {
    expect(isAiEditPreviewSucceeded({
      status: 'SUCCEEDED',
      provider: 'AGENTSCOPE_AUTHORING',
      summary: 'ok',
      operations: [],
      canvasSnapshot: {},
      graphSpec: { nodes: [], edges: [] } as any,
      warnings: [],
      placeholderNodes: [],
      validationErrors: [],
    })).toBe(true)
    expect(isAiEditPreviewSucceeded({
      status: 'FAILED',
      provider: 'AGENTSCOPE_AUTHORING',
      summary: 'failed',
      operations: [],
      canvasSnapshot: {},
      graphSpec: { nodes: [], edges: [] } as any,
      warnings: [],
      placeholderNodes: [],
      validationErrors: ['ADD_NODE_ID_REQUIRED: ADD_NODE.node.id is required'],
    })).toBe(false)
  })

  it('clears previous preview immediately when a new request starts', async () => {
    const { deps, aiEditPreview, aiEditLoading } = createDeps()
    aiEditPreview.value = {
      status: 'FAILED',
      provider: 'AGENTSCOPE_AUTHORING',
      summary: '旧失败预览',
      operations: [],
      canvasSnapshot: {},
      graphSpec: { nodes: [], edges: [] } as any,
      warnings: [],
      placeholderNodes: [],
      validationErrors: ['old'],
    }
    let resolveRequest: (value: unknown) => void = () => undefined
    editWorkflowProposal.mockImplementation(() => new Promise((resolve) => {
      resolveRequest = resolve
    }))
    const actions = useWorkflowStudioProposalActions(deps as any)
    const pending = actions.runAiAuthoring()
    expect(aiEditPreview.value).toBeNull()
    expect(aiEditLoading.value).toBe(true)
    expect(deps.aiEditInstruction.value).toBe('')
    resolveRequest({
      data: {
        status: 'SUCCEEDED',
        provider: 'AGENTSCOPE_AUTHORING',
        summary: '新预览',
        operations: [],
        canvasSnapshot: { nodes: [], edges: [] },
        graphSpec: { nodes: [], edges: [] },
        warnings: [],
        placeholderNodes: [],
        validationErrors: [],
      },
    })
    await pending
    expect(elMessage.success).toHaveBeenCalledWith('AI 编排预览已生成')
    expect(elMessage.error).not.toHaveBeenCalled()
  })

  it('treats a Vue DOM event as a submit trigger instead of an instruction override', async () => {
    const { deps, aiEditInstruction } = createDeps()
    editWorkflowProposal.mockResolvedValue({ data: succeededPreview() })
    const actions = useWorkflowStudioProposalActions(deps as any)

    await actions.runAiAuthoring(new MouseEvent('click'))

    expect(editWorkflowProposal).toHaveBeenCalledWith(expect.objectContaining({
      instruction: '创建一个地铁问答工作流',
      currentGraphSpec: {
        schemaVersion: 2,
        nodes: [],
        edges: [],
        entryNodeId: '',
        exitNodeIds: [],
      },
    }))
    expect(editWorkflowProposal.mock.calls[0][0].currentCanvas).not.toHaveProperty('graphSpec')
    expect(aiEditInstruction.value).toBe('')
    expect(elMessage.success).toHaveBeenCalledWith('AI 编排预览已生成')
  })

  it('shows error message for FAILED results and blocks apply', async () => {
    const { deps, aiEditPreview } = createDeps()
    editWorkflowProposal.mockResolvedValue({
      data: {
        status: 'FAILED',
        provider: 'AGENTSCOPE_AUTHORING',
        summary: 'AI 编排生成失败，Agent 已尝试自动修正，但仍未生成合法 Workflow。',
        operations: [],
        canvasSnapshot: {},
        graphSpec: { nodes: [], edges: [] },
        warnings: [],
        placeholderNodes: [],
        validationErrors: ['ADD_NODE requires node.id'],
        attempts: 2,
        failureCode: 'VALIDATION_FAILED',
        authoringId: 'authoring-fail-1',
      },
    })
    const actions = useWorkflowStudioProposalActions(deps as any)
    await actions.runAiAuthoring()
    expect(aiEditPreview.value?.status).toBe('FAILED')
    expect(aiEditPreview.value?.authoringId).toBe('authoring-fail-1')
    expect(elMessage.success).not.toHaveBeenCalled()
    expect(elMessage.error).toHaveBeenCalledWith(
      formatAiAuthoringFailureMessage({
        status: 'FAILED',
        provider: 'AGENTSCOPE_AUTHORING',
        summary: 'AI 编排生成失败，Agent 已尝试自动修正，但仍未生成合法 Workflow。',
        operations: [],
        canvasSnapshot: {},
        graphSpec: { nodes: [], edges: [] } as any,
        warnings: [],
        placeholderNodes: [],
        validationErrors: ['ADD_NODE requires node.id'],
        attempts: 2,
        failureCode: 'VALIDATION_FAILED',
        authoringId: 'authoring-fail-1',
      }),
    )
    expect(formatAiAuthoringFailureMessage({
      status: 'FAILED',
      provider: 'AGENTSCOPE_AUTHORING',
      summary: 'AI 编排生成失败，Agent 已尝试自动修正，但仍未生成合法 Workflow。',
      operations: [],
      canvasSnapshot: {},
      graphSpec: { nodes: [], edges: [] } as any,
      warnings: [],
      placeholderNodes: [],
      validationErrors: [],
      authoringId: 'authoring-fail-1',
    })).toContain('错误编号：authoring-fail-1')
    const applied = await actions.applyAiEditPreview()
    expect(applied).toBe(false)
    expect(elMessage.warning).toHaveBeenCalledWith('当前提案未通过校验，不能应用到工作副本')
  })

  it('ignores stale responses and prevents duplicate submits while loading', async () => {
    const { deps, aiEditLoading, editGeneration } = createDeps()
    let resolveFirst: (value: unknown) => void = () => undefined
    editWorkflowProposal
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveFirst = resolve
      }))
      .mockResolvedValueOnce({
        data: {
          status: 'SUCCEEDED',
          provider: 'AGENTSCOPE_AUTHORING',
          summary: 'second',
          operations: [],
          canvasSnapshot: {},
          graphSpec: { nodes: [], edges: [] },
          warnings: [],
          placeholderNodes: [],
          validationErrors: [],
        },
      })
    const actions = useWorkflowStudioProposalActions(deps as any)
    const first = actions.runAiAuthoring()
    expect(aiEditLoading.value).toBe(true)
    await actions.runAiAuthoring()
    expect(editWorkflowProposal).toHaveBeenCalledTimes(1)
    editGeneration.value = 2
    resolveFirst({
      data: {
        status: 'SUCCEEDED',
        provider: 'AGENTSCOPE_AUTHORING',
        summary: 'stale',
        operations: [],
        canvasSnapshot: {},
        graphSpec: { nodes: [], edges: [] },
        warnings: [],
        placeholderNodes: [],
        validationErrors: [],
      },
    })
    await first
    expect(elMessage.warning).toHaveBeenCalledWith('Workflow 工作副本已变化，已忽略过期的 AI 编排结果')
  })

  it('regenerates from latest working copy after clearing failed preview', async () => {
    const { deps, aiEditPreview, aiEditInstruction } = createDeps()
    aiEditInstruction.value = '重新生成地铁工作流'
    editWorkflowProposal.mockResolvedValue({
      data: succeededPreview({
        summary: 'regen ok',
        operations: [{ type: 'ADD_NODE', nodeId: 'is_metro' }],
        graphSpec: {
          schemaVersion: 2,
          entryNodeId: 'is_metro',
          exitNodeIds: ['is_metro'],
          nodes: [{ id: 'is_metro', type: 'IF_ELSE' }],
          edges: [],
        } as any,
      }),
    })
    const actions = useWorkflowStudioProposalActions(deps as any)
    aiEditPreview.value = {
      status: 'FAILED',
      provider: 'AGENTSCOPE_AUTHORING',
      summary: 'old',
      operations: [],
      canvasSnapshot: {},
      graphSpec: { nodes: [], edges: [] } as any,
      warnings: [],
      placeholderNodes: [],
      validationErrors: ['x'],
    }
    await actions.regenerateAiAuthoring()
    expect(editWorkflowProposal).toHaveBeenCalledTimes(1)
    expect(editWorkflowProposal.mock.calls[0][0].instruction).toBe('重新生成地铁工作流')
    expect(aiEditPreview.value?.status).toBe('SUCCEEDED')
    const applied = await actions.applyAiEditPreview()
    expect(applied).toBe(true)
    expect(deps.autoLayoutWorkflowCanvas).toHaveBeenCalledTimes(1)
    expect(elMessage.success).toHaveBeenCalledWith('AI 编排提案已应用到 Workflow 工作副本，并已自动整理画布')
  })

  it('auto-layouts only when applying onto an empty working-copy canvas', async () => {
    expect(isWorkingCopyCanvasEmpty([], '{"schemaVersion":1,"layoutVersion":1,"nodes":[],"edges":[]}')).toBe(true)
    expect(isWorkingCopyCanvasEmpty([{ id: 'n1' } as any], '{"schemaVersion":1,"layoutVersion":1,"nodes":[],"edges":[]}')).toBe(false)
    expect(isWorkingCopyCanvasEmpty([], '{"schemaVersion":1,"layoutVersion":1,"nodes":[{"id":"n1"}],"edges":[]}')).toBe(false)

    const empty = createDeps()
    editWorkflowProposal.mockResolvedValue({ data: succeededPreview() })
    const emptyActions = useWorkflowStudioProposalActions(empty.deps as any)
    await emptyActions.runAiAuthoring()
    await emptyActions.applyAiEditPreview()
    expect(empty.deps.autoLayoutWorkflowCanvas).toHaveBeenCalledTimes(1)
    expect(empty.deps.fitCanvas).toHaveBeenCalled()
    expect(elMessage.success).toHaveBeenCalledWith('AI 编排提案已应用到 Workflow 工作副本，并已自动整理画布')

    vi.clearAllMocks()
    const occupied = createDeps({
      nodes: ref([{ id: 'manual-1', type: 'answer', position: { x: 10, y: 20 }, data: { label: '手动节点' } }]),
      canvasJson: ref('{"schemaVersion":1,"layoutVersion":1,"nodes":[{"id":"manual-1"}],"edges":[]}'),
    })
    editWorkflowProposal.mockResolvedValue({ data: succeededPreview() })
    const occupiedActions = useWorkflowStudioProposalActions(occupied.deps as any)
    await occupiedActions.runAiAuthoring()
    await occupiedActions.applyAiEditPreview()
    expect(occupied.deps.autoLayoutWorkflowCanvas).not.toHaveBeenCalled()
    expect(occupied.deps.fitCanvas).toHaveBeenCalled()
    expect(elMessage.success).toHaveBeenCalledWith('AI 编排提案已应用到 Workflow 工作副本')
  })

  it('blocks applying an AI preview through the real panel validation gate until fixed', async () => {
    const validation = useWorkflowStudioPanelValidation()
    const { deps } = createDeps({
      ensurePanelValidationClear: validation.ensurePanelValidationClear,
    })
    editWorkflowProposal.mockResolvedValue({ data: succeededPreview() })
    const actions = useWorkflowStudioProposalActions(deps as any)
    await actions.runAiAuthoring()

    validation.setPanelValidation('assign_1', { valid: false, message: 'JSON 无效' })
    expect(await actions.applyAiEditPreview()).toBe(false)
    expect(deps.applyCanvasFromStudio).not.toHaveBeenCalled()

    validation.setPanelValidation('assign_1', { valid: true })
    expect(await actions.applyAiEditPreview()).toBe(true)
    expect(deps.applyCanvasFromStudio).toHaveBeenCalledTimes(1)
  })
})
