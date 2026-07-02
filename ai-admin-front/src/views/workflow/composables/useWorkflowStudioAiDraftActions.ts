import { ElMessage } from 'element-plus'
import { computed, type ComputedRef, type Ref } from 'vue'
import { editWorkflowDraft, generateWorkflowDraft } from '@/api/workflow'
import type { CompositionInfo } from '@/types/composition'
import type { KnowledgeBase } from '@/types/knowledge'
import type { ToolInfo } from '@/types/tool'
import type { CanvasEdge, CanvasNode, CanvasSnapshot } from '@/types/studio'
import type {
  WorkflowDraftEditOperation,
  WorkflowDraftEditOperationType,
  WorkflowDraftEditResult,
  WorkflowDraftGenerationResult,
  WorkflowDraftResource,
  WorkflowStudioState,
} from '@/types/workflow'
import { formatJson, readJsonObject } from '@/views/workflow/composables/workflowStudioJson'

export interface UseWorkflowStudioAiDraftActionsDeps {
  workflowId: Readonly<Ref<string>>
  studioReadOnly: Readonly<Ref<boolean>>
  studio: Ref<WorkflowStudioState | null>
  graphSpecJson: Ref<string>
  canvasJson: Ref<string>
  nodes: Ref<CanvasNode[]>
  edges: Ref<CanvasEdge[]>
  selectedNodeId: Ref<string | null>
  selectedEdgeId: Ref<string | null>
  activeTab: Ref<string>
  validation: Ref<unknown>
  aiModelInstanceId: Ref<string>
  aiRequirement: Ref<string>
  aiEditInstruction: Ref<string>
  aiDraftLoading: Ref<boolean>
  aiEditLoading: Ref<boolean>
  aiDraftPreview: Ref<WorkflowDraftGenerationResult | null>
  aiEditPreview: Ref<WorkflowDraftEditResult | null>
  aiDraftDialogOpen: Ref<boolean>
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
}

function workflowEditOperationLabel(type: WorkflowDraftEditOperationType) {
  const labels: Record<WorkflowDraftEditOperationType, string> = {
    ADD_NODE: '新增节点',
    UPDATE_NODE: '修改节点',
    DELETE_NODE: '删除节点',
    ADD_EDGE: '新增连线',
    UPDATE_EDGE: '修改连线',
    DELETE_EDGE: '删除连线',
  }
  return labels[type] || type
}

function operationTarget(item: WorkflowDraftEditOperation) {
  const node = item.node as { id?: string; data?: { label?: string } } | undefined
  const edge = item.edge as { id?: string; source?: string; target?: string } | undefined
  if (item.nodeId) return item.nodeId
  if (item.edgeId) return item.edgeId
  if (node?.data?.label) return node.data.label
  if (node?.id) return node.id
  if (edge?.source || edge?.target) return `${edge?.source || '?'} → ${edge?.target || '?'}`
  return workflowEditOperationLabel(item.type)
}

export function useWorkflowStudioAiDraftActions(deps: UseWorkflowStudioAiDraftActionsDeps) {
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

  const activeAiPreview = computed(() => deps.aiDraftPreview.value || deps.aiEditPreview.value)

  const aiDraftPreviewNodes = computed(() => {
    const snapshot = deps.aiDraftPreview.value?.canvasSnapshot as { nodes?: unknown } | undefined
    return Array.isArray(snapshot?.nodes) ? snapshot.nodes as CanvasNode[] : []
  })

  const aiDraftPreviewEdges = computed(() => {
    const snapshot = deps.aiDraftPreview.value?.canvasSnapshot as { edges?: unknown } | undefined
    return Array.isArray(snapshot?.edges) ? snapshot.edges as CanvasEdge[] : []
  })

  const aiDraftPreviewNodeLabels = computed(() => {
    const labels = new Map<string, string>()
    for (const node of aiDraftPreviewNodes.value) {
      labels.set(node.id, node.data?.label || node.id)
    }
    return labels
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
    ]
    return order
      .map((type) => ({
        type,
        label: workflowEditOperationLabel(type),
        items: operations.filter((item) => item.type === type),
      }))
      .filter((group) => group.items.length)
  })

  const aiPreviewPlaceholderNodes = computed(() => activeAiPreview.value?.placeholderNodes || [])

  const draftPreviewTitle = computed(() => {
    if (deps.aiEditPreview.value) return deps.aiEditPreview.value.summary || 'AI edit preview'
    return deps.aiDraftPreview.value?.graphSpec?.name || 'AI draft preview'
  })

  const draftPreviewSummary = computed(() => {
    const preview = activeAiPreview.value
    if (!preview) return '生成后可应用到当前 Workflow 草稿'
    if ('summary' in preview && typeof preview.summary === 'string' && preview.summary.trim()) {
      return preview.summary
    }
    const graphSpec = preview.graphSpec as unknown as { description?: string } | undefined
    return typeof graphSpec?.description === 'string' && graphSpec.description.trim()
      ? graphSpec.description
      : '生成后可应用到当前 Workflow 草稿'
  })

  const draftPreviewIssues = computed(() => {
    const preview = activeAiPreview.value
    return [
      ...(preview?.validationErrors || []),
      ...(preview?.warnings || []),
      ...(preview?.placeholderNodes || []).map((item) => `${item.label}: ${item.reason}`),
    ]
  })

  const aiEditScopeLabel = computed(() => {
    const nodeCount = selectedNodeIdsForAi.value.length
    const edgeCount = selectedEdgeIdsForAi.value.length
    if (!nodeCount && !edgeCount) return 'Whole workflow'
    const parts = [
      nodeCount ? `${nodeCount} node${nodeCount > 1 ? 's' : ''}` : '',
      edgeCount ? `${edgeCount} edge${edgeCount > 1 ? 's' : ''}` : '',
    ].filter(Boolean)
    return `Selected ${parts.join(' / ')}`
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

  function openAiDraftDialog() {
    deps.aiModelInstanceId.value = deps.aiModelInstanceId.value
      || deps.studio.value?.defaultModelInstanceId
      || deps.resolveAiModelInstanceId()
      || ''
    if (!deps.aiRequirement.value.trim()) {
      deps.aiRequirement.value = deps.studio.value?.description || '基于当前 Workflow 目标，生成或补全可执行流程。'
    }
    deps.aiDraftPreview.value = null
    deps.aiDraftDialogOpen.value = true
  }

  async function generateAiDraft() {
    const requirement = deps.aiRequirement.value.trim()
    if (!requirement) {
      ElMessage.warning('请先输入流程需求')
      return
    }
    const modelInstanceId = deps.resolveAiModelInstanceId()
    if (!modelInstanceId) {
      ElMessage.warning('请先选择或配置可用的 LLM 模型实例')
      return
    }
    deps.aiDraftLoading.value = true
    try {
      const { data } = await generateWorkflowDraft({
        workflowId: deps.workflowId.value,
        workflowName: deps.studio.value?.name || undefined,
        agentName: deps.studio.value?.name || undefined,
        projectCode: deps.studio.value?.projectCode || null,
        requirement,
        modelInstanceId,
        currentCanvas: parseCurrentCanvas(),
        tools: deps.availableTools.value.map((tool) => deps.toolToDraftResource(tool)),
        capabilities: deps.availableCompositions.value.map((item) => deps.compositionToDraftResource(item)),
        knowledgeBases: deps.knowledgeOptions.value.map((item) => deps.knowledgeToDraftResource(item)),
      })
      deps.aiDraftPreview.value = data
      deps.aiEditPreview.value = null
      ElMessage.success('AI 流程草稿预览已生成')
    } catch (err) {
      ElMessage.error('生成流程草稿失败：' + (err as Error).message)
    } finally {
      deps.aiDraftLoading.value = false
    }
  }

  async function editAiDraft() {
    const instruction = deps.aiEditInstruction.value.trim()
    if (!instruction) {
      ElMessage.warning('请先输入要修改的流程指令')
      return
    }
    const modelInstanceId = deps.resolveAiModelInstanceId()
    if (!modelInstanceId) {
      ElMessage.warning('请先选择或配置可用的 LLM 模型实例')
      return
    }
    deps.aiEditLoading.value = true
    try {
      const { data } = await editWorkflowDraft({
        workflowId: deps.workflowId.value,
        workflowName: deps.studio.value?.name || undefined,
        agentName: deps.studio.value?.name || undefined,
        projectCode: deps.studio.value?.projectCode || null,
        instruction,
        modelInstanceId,
        currentCanvas: parseCurrentCanvas(),
        selectedNodeIds: selectedNodeIdsForAi.value,
        selectedEdgeIds: selectedEdgeIdsForAi.value,
        tools: deps.availableTools.value.map((tool) => deps.toolToDraftResource(tool)),
        capabilities: deps.availableCompositions.value.map((item) => deps.compositionToDraftResource(item)),
        knowledgeBases: deps.knowledgeOptions.value.map((item) => deps.knowledgeToDraftResource(item)),
      })
      deps.aiEditPreview.value = data
      deps.aiDraftPreview.value = null
      ElMessage.success('AI 修改预览已生成')
    } catch (err) {
      ElMessage.error((err as Error).message)
    } finally {
      deps.aiEditLoading.value = false
    }
  }

  function applyPreviewGraph(
    graphSpec: unknown,
    canvasSnapshotValue: unknown,
  ) {
    deps.graphSpecJson.value = formatJson(JSON.stringify(graphSpec))
    deps.canvasJson.value = formatJson(JSON.stringify(canvasSnapshotValue || { nodes: [], edges: [] }))
    if (deps.studio.value) {
      deps.applyCanvasFromStudio({
        ...deps.studio.value,
        graphSpecJson: deps.graphSpecJson.value,
        canvasJson: deps.canvasJson.value,
      })
    }
    deps.validation.value = null
    deps.aiDraftPreview.value = null
    deps.aiEditPreview.value = null
    deps.activeTab.value = 'visual'
  }

  function applyAiPreview() {
    const preview = activeAiPreview.value
    if (!preview) {
      ElMessage.warning('请先生成 AI 修改预览')
      return
    }
    if (preview.validationErrors?.length) {
      ElMessage.warning('请先修复 AI 修改预览中的校验问题')
      return
    }
    applyPreviewGraph(preview.graphSpec, preview.canvasSnapshot || { nodes: [], edges: [] })
    ElMessage.success('AI 修改已应用到 Workflow 草稿')
  }

  function clearAiPreview() {
    deps.aiDraftPreview.value = null
    deps.aiEditPreview.value = null
  }

  function clearAiEditPreview() {
    deps.aiEditPreview.value = null
  }

  function applyAiEditPreview() {
    if (deps.studioReadOnly.value) {
      ElMessage.info('代码托管 Workflow 当前为只读草稿，请修改后重启同步。')
      return
    }
    if (!deps.aiEditPreview.value) {
      ElMessage.warning('请先生成 AI 修改预览')
      return
    }
    if (deps.aiEditPreview.value.validationErrors?.length) {
      ElMessage.warning('请先修复 AI 修改预览中的校验问题')
      return
    }
    const preview = deps.aiEditPreview.value
    applyPreviewGraph(preview.graphSpec, preview.canvasSnapshot || { nodes: [], edges: [] })
    ElMessage.success('AI 修改已应用到 Workflow 草稿')
  }

  function handleApplyAiDraft() {
    applyAiPreview()
    deps.aiDraftDialogOpen.value = false
  }

  function operationKey(item: WorkflowDraftEditOperation) {
    return `${item.type}:${item.nodeId || item.edgeId || operationTarget(item)}:${item.reason || ''}`
  }

  function previewNodeLabel(nodeId?: string) {
    if (!nodeId) return '?'
    return aiDraftPreviewNodeLabels.value.get(nodeId) || nodeId
  }

  return {
    selectedNodeIdsForAi,
    selectedEdgeIdsForAi,
    activeAiPreview,
    aiDraftPreviewNodes,
    aiDraftPreviewEdges,
    aiDraftPreviewNodeLabels,
    aiEditOperationGroups,
    aiPreviewPlaceholderNodes,
    draftPreviewTitle,
    draftPreviewSummary,
    draftPreviewIssues,
    aiEditScopeLabel,
    parseCurrentCanvas,
    openAiDraftDialog,
    generateAiDraft,
    editAiDraft,
    applyAiPreview,
    clearAiPreview,
    clearAiEditPreview,
    applyAiEditPreview,
    handleApplyAiDraft,
    operationKey,
    previewNodeLabel,
    workflowEditOperationLabel,
    operationTarget,
  }
}
