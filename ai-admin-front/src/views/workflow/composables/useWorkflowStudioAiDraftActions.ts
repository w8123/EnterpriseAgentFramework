import { ElMessage } from 'element-plus'
import { computed, type ComputedRef, type Ref } from 'vue'
import { editWorkflowDraft } from '@/api/workflow'
import type { CompositionInfo } from '@/types/composition'
import type { KnowledgeBase } from '@/types/knowledge'
import type { ToolInfo } from '@/types/tool'
import type { CanvasEdge, CanvasNode, CanvasSnapshot } from '@/types/studio'
import type {
  WorkflowDraftEditOperation,
  WorkflowDraftEditOperationType,
  WorkflowDraftEditResult,
  WorkflowDraftResource,
  WorkflowStudioState,
} from '@/types/workflow'
import { formatJson, readJsonObject } from '@/views/workflow/composables/workflowStudioJson'

export interface UseWorkflowStudioAiDraftActionsDeps {
  workflowId: Readonly<Ref<string>>
  studioReadOnly: Readonly<Ref<boolean>>
  studio: Ref<WorkflowStudioState | null>
  editGeneration: Readonly<Ref<number>>
  graphSpecJson: Ref<string>
  canvasJson: Ref<string>
  nodes: Ref<CanvasNode[]>
  edges: Ref<CanvasEdge[]>
  selectedNodeId: Ref<string | null>
  selectedEdgeId: Ref<string | null>
  activeTab: Ref<string>
  validation: Ref<unknown>
  aiEditInstruction: Ref<string>
  aiEditLoading: Ref<boolean>
  aiEditPreview: Ref<WorkflowDraftEditResult | null>
  availableTools: ComputedRef<ToolInfo[]>
  availableCompositions: ComputedRef<CompositionInfo[]>
  knowledgeOptions: Ref<KnowledgeBase[]>
  resolveAiModelInstanceId: () => string
  toolToDraftResource: (tool: ToolInfo) => WorkflowDraftResource
  compositionToDraftResource: (composition: CompositionInfo) => WorkflowDraftResource
  knowledgeToDraftResource: (knowledge: KnowledgeBase) => WorkflowDraftResource
  syncJsonFromCanvas: () => void
  canvasSnapshot: () => CanvasSnapshot
  applyCanvasFromStudio: (state: WorkflowStudioState) => void
  autoLayoutWorkflowCanvas: () => Promise<void>
  fitCanvas: () => Promise<void>
}

function workflowEditOperationLabel(type: WorkflowDraftEditOperationType) {
  const labels: Record<WorkflowDraftEditOperationType, string> = {
    ADD_NODE: '新增节点',
    UPDATE_NODE: '修改节点',
    DELETE_NODE: '删除节点',
    ADD_EDGE: '新增连线',
    UPDATE_EDGE: '修改连线',
    DELETE_EDGE: '删除连线',
    SET_ENTRY: '设置入口',
    SET_FINISH: '设置结束',
  }
  return labels[type] || type
}

function operationTarget(item: WorkflowDraftEditOperation) {
  const node = item.node as { id?: string; name?: string; data?: { label?: string } } | undefined
  const edge = item.edge as { id?: string; from?: string; to?: string; source?: string; target?: string } | undefined
  if (item.nodeId) return item.nodeId
  if (item.edgeId) return item.edgeId
  if (node?.data?.label) return node.data.label
  if (node?.name) return node.name
  if (node?.id) return node.id
  const source = edge?.from || edge?.source
  const target = edge?.to || edge?.target
  if (source || target) return `${source || '?'} → ${target || '?'}`
  if (item.patch && typeof item.patch.entry === 'string') return item.patch.entry
  return workflowEditOperationLabel(item.type)
}

export function isAiEditPreviewSucceeded(preview: WorkflowDraftEditResult | null | undefined) {
  if (!preview) return false
  const status = String(preview.status || '').toUpperCase()
  if (status === 'FAILED') return false
  if (preview.validationErrors?.length) return false
  if (status === 'SUCCEEDED') return true
  // Backward compatible: older payloads without status succeed only when validation is clean.
  return !preview.validationErrors?.length
}

export function formatAiAuthoringFailureMessage(preview: WorkflowDraftEditResult | null | undefined) {
  const authoringId = String(preview?.authoringId || '').trim()
  const summary = String(preview?.summary || '').trim()
  if (summary) {
    if (authoringId && !summary.includes(authoringId)) {
      return `${summary}（错误编号：${authoringId}）`
    }
    return summary
  }
  if (authoringId) {
    return `AI 编排执行异常，请重新生成。如问题持续出现，请联系管理员并提供错误编号：${authoringId}。`
  }
  return 'AI 编排生成失败，Agent 已尝试自动修正，但仍未生成合法 Workflow。'
}

/** True when the current draft canvas has no editable nodes (first entry / cleared). */
export function isDraftCanvasEmpty(nodes: CanvasNode[], canvasJson: string) {
  if (nodes.length > 0) return false
  const canvas = readJsonObject(canvasJson, { nodes: [] }) as { nodes?: unknown }
  return !Array.isArray(canvas.nodes) || canvas.nodes.length === 0
}

export function useWorkflowStudioAiDraftActions(deps: UseWorkflowStudioAiDraftActionsDeps) {
  let previewRequestSequence = 0
  let editLoadingSequence = 0
  let lastAiAuthoringInstruction = ''
  let previewContext: { workflowId: string; editGeneration: number } | null = null

  function isPreviewContextCurrent() {
    return !!previewContext
      && previewContext.workflowId === deps.workflowId.value
      && previewContext.editGeneration === deps.editGeneration.value
  }

  function clearPreviews() {
    deps.aiEditPreview.value = null
    previewContext = null
  }

  const selectedNodeIdsForAi = computed(() => {
    const ids = new Set<string>()
    for (const node of deps.nodes.value) {
      if ((node as CanvasNode & { selected?: boolean }).selected) ids.add(node.id)
    }
    if (deps.selectedNodeId.value) ids.add(deps.selectedNodeId.value)
    return Array.from(ids)
  })

  const selectedEdgeIdsForAi = computed(() => {
    const ids = new Set<string>()
    for (const edge of deps.edges.value) {
      if ((edge as CanvasEdge & { selected?: boolean }).selected) ids.add(edge.id)
    }
    if (deps.selectedEdgeId.value) ids.add(deps.selectedEdgeId.value)
    return Array.from(ids)
  })

  const aiEditOperationGroups = computed(() => {
    const operations = deps.aiEditPreview.value?.operations || []
    const order: WorkflowDraftEditOperationType[] = [
      'ADD_NODE',
      'UPDATE_NODE',
      'DELETE_NODE',
      'ADD_EDGE',
      'UPDATE_EDGE',
      'DELETE_EDGE',
      'SET_ENTRY',
      'SET_FINISH',
    ]
    return order
      .map((type) => ({
        type,
        label: workflowEditOperationLabel(type),
        items: operations.filter((item) => item.type === type),
      }))
      .filter((group) => group.items.length)
  })

  const aiEditPreviewFailed = computed(() => {
    const preview = deps.aiEditPreview.value
    if (!preview) return false
    return !isAiEditPreviewSucceeded(preview)
  })

  function parseCurrentCanvas() {
    if (deps.nodes.value.length) {
      deps.syncJsonFromCanvas()
    }
    const canvas = deps.nodes.value.length
      ? deps.canvasSnapshot()
      : readJsonObject(deps.canvasJson.value, { version: 2, nodes: [], edges: [] })
    return {
      ...canvas,
      graphSpec: readJsonObject(deps.graphSpecJson.value, {}),
    }
  }

  async function runAiAuthoring(instructionOverride?: string) {
    const instruction = (instructionOverride ?? deps.aiEditInstruction.value).trim()
    if (!instruction) {
      ElMessage.warning('请先描述要创建、修改或修复的 Workflow')
      return
    }
    const modelInstanceId = deps.resolveAiModelInstanceId()
    if (!modelInstanceId) {
      ElMessage.warning('请先选择或配置可用的 LLM 模型实例')
      return
    }
    if (deps.aiEditLoading.value) {
      return
    }

    lastAiAuthoringInstruction = instruction
    // A request has been accepted; keep its immutable value locally for regenerate,
    // while returning the visible composer to an empty state for the next instruction.
    if (instructionOverride === undefined) {
      deps.aiEditInstruction.value = ''
    }

    // Clear previous preview immediately once the new request is accepted.
    clearPreviews()

    const requestWorkflowId = deps.workflowId.value
    const currentCanvas = parseCurrentCanvas()
    const currentGraphSpec = readJsonObject(deps.graphSpecJson.value, {})
    const requestEditGeneration = deps.editGeneration.value
    const requestSequence = ++previewRequestSequence
    const loadingSequence = ++editLoadingSequence
    const isRequestCurrent = () => (
      requestSequence === previewRequestSequence
      && requestWorkflowId === deps.workflowId.value
      && requestEditGeneration === deps.editGeneration.value
    )
    deps.aiEditLoading.value = true
    try {
      const { data } = await editWorkflowDraft({
        workflowId: requestWorkflowId,
        workflowName: deps.studio.value?.name || undefined,
        agentName: deps.studio.value?.name || undefined,
        projectCode: deps.studio.value?.projectCode || null,
        instruction,
        modelInstanceId,
        currentCanvas,
        currentGraphSpec,
        selectedNodeIds: selectedNodeIdsForAi.value,
        selectedEdgeIds: selectedEdgeIdsForAi.value,
        tools: deps.availableTools.value.map((tool) => deps.toolToDraftResource(tool)),
        capabilities: deps.availableCompositions.value.map((item) => deps.compositionToDraftResource(item)),
        knowledgeBases: deps.knowledgeOptions.value.map((item) => deps.knowledgeToDraftResource(item)),
      })
      if (!isRequestCurrent()) {
        ElMessage.warning('Workflow 草稿已变化，已忽略过期的 AI 编排结果')
        return
      }
      deps.aiEditPreview.value = data
      previewContext = { workflowId: requestWorkflowId, editGeneration: requestEditGeneration }
      if (isAiEditPreviewSucceeded(data)) {
        ElMessage.success('AI 编排预览已生成')
      } else {
        ElMessage.error(formatAiAuthoringFailureMessage(data))
      }
    } catch (err) {
      if (!isRequestCurrent()) return
      ElMessage.error((err as Error).message)
    } finally {
      if (loadingSequence === editLoadingSequence) deps.aiEditLoading.value = false
    }
  }

  async function regenerateAiAuthoring() {
    if (deps.aiEditLoading.value) return
    clearPreviews()
    const instruction = lastAiAuthoringInstruction || deps.aiEditInstruction.value.trim()
    await runAiAuthoring(instruction)
  }

  async function applyPreviewGraph(
    graphSpec: unknown,
    canvasSnapshotValue: unknown,
    options?: { autoLayout?: boolean },
  ) {
    const shouldAutoLayout = !!options?.autoLayout
    deps.graphSpecJson.value = formatJson(JSON.stringify(graphSpec))
    deps.canvasJson.value = formatJson(JSON.stringify(canvasSnapshotValue || { nodes: [], edges: [] }))
    if (deps.studio.value) {
      deps.applyCanvasFromStudio({
        ...deps.studio.value,
        graphSpecJson: deps.graphSpecJson.value,
        canvasJson: deps.canvasJson.value,
      })
    }
    deps.activeTab.value = 'visual'
    // Only auto-layout when the draft canvas was empty before apply, so existing
    // hand-placed nodes are not reshuffled.
    if (shouldAutoLayout) {
      await deps.autoLayoutWorkflowCanvas()
    }
    await deps.fitCanvas()
    deps.validation.value = null
    deps.aiEditPreview.value = null
    previewContext = null
  }

  function clearAiEditPreview() {
    deps.aiEditPreview.value = null
    previewContext = null
  }

  async function applyAiEditPreview() {
    if (deps.studioReadOnly.value) {
      ElMessage.info('代码托管 Workflow 当前为只读草稿，请修改后重启同步。')
      return false
    }
    if (!deps.aiEditPreview.value) {
      ElMessage.warning('请先生成 AI 编排预览')
      return false
    }
    if (!isPreviewContextCurrent()) {
      clearPreviews()
      ElMessage.warning('当前 Workflow 草稿已变化，请重新生成 AI 编排预览')
      return false
    }
    if (!isAiEditPreviewSucceeded(deps.aiEditPreview.value)) {
      ElMessage.warning('当前方案未通过校验，不能应用到草稿')
      return false
    }
    const preview = deps.aiEditPreview.value
    // Decide before replacing the draft with the AI preview.
    const shouldAutoLayout = isDraftCanvasEmpty(deps.nodes.value, deps.canvasJson.value)
    try {
      await applyPreviewGraph(
        preview.graphSpec,
        preview.canvasSnapshot || { nodes: [], edges: [] },
        { autoLayout: shouldAutoLayout },
      )
      ElMessage.success(shouldAutoLayout
        ? 'AI 编排方案已应用到 Workflow 草稿，并已自动整理画布'
        : 'AI 编排方案已应用到 Workflow 草稿')
      return true
    } catch (err) {
      ElMessage.error((shouldAutoLayout
        ? '应用 AI 流程并自动整理失败：'
        : '应用 AI 流程失败：') + (err as Error).message)
      return false
    }
  }

  function operationKey(item: WorkflowDraftEditOperation) {
    return `${item.type}:${item.nodeId || item.edgeId || operationTarget(item)}:${item.reason || ''}`
  }

  return {
    aiEditOperationGroups,
    aiEditPreviewFailed,
    runAiAuthoring,
    regenerateAiAuthoring,
    clearAiEditPreview,
    applyAiEditPreview,
    operationKey,
    operationTarget,
    isAiEditPreviewSucceeded,
    formatAiAuthoringFailureMessage,
    isDraftCanvasEmpty,
  }
}
