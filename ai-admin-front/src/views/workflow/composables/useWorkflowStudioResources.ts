import { computed, ref, type Ref } from 'vue'
import { getApiGraphParamHints, type ApiGraphParamSourceHint } from '@/api/apiGraph'
import { getKnowledgeList } from '@/api/knowledge'
import { getModelInstances } from '@/api/model'
import { listAllTools } from '@/api/tool'
import { getWorkflowGraphNodeTypes } from '@/api/workflow'
import { listWorkflowCredentials } from '@/api/workflowCredential'
import type { KnowledgeBase } from '@/types/knowledge'
import type { ModelInstance } from '@/types/model'
import type { ToolInfo } from '@/types/tool'
import type { WorkflowCredential } from '@/types/workflowCredential'
import type { WorkflowGraphNodeTypeDescriptor, WorkflowWorkingCopyState } from '@/types/workflow'

export interface UseWorkflowStudioResourcesDeps {
  studio: Ref<WorkflowWorkingCopyState | null>
  aiModelInstanceId: Ref<string>
  selectedToolName: Ref<string>
}

function normalizeModelInstanceList(payload: unknown): ModelInstance[] {
  if (Array.isArray(payload)) {
    return payload as ModelInstance[]
  }
  if (payload !== null && typeof payload === 'object' && 'data' in payload) {
    const wrapped = (payload as { data?: unknown }).data
    return Array.isArray(wrapped) ? (wrapped as ModelInstance[]) : []
  }
  return []
}

function isActiveModelInstance(item: ModelInstance) {
  return String(item.status ?? '').toUpperCase() === 'ACTIVE'
}

export function useWorkflowStudioResources(deps: UseWorkflowStudioResourcesDeps) {
  const nodeTypesLoading = ref(false)
  const nodeTypes = ref<WorkflowGraphNodeTypeDescriptor[]>([])
  const modelOptions = ref<ModelInstance[]>([])
  const knowledgeOptions = ref<KnowledgeBase[]>([])
  const toolOptions = ref<ToolInfo[]>([])
  const credentialOptions = ref<WorkflowCredential[]>([])
  const paramSourceHints = ref<ApiGraphParamSourceHint[]>([])
  const graphNodeTypeCapabilitiesLoaded = ref(false)
  let paramSourceHintsSequence = 0

  const availableTools = computed(() =>
    toolOptions.value.filter((tool) => tool.enabled),
  )

  const authoringModelOptions = computed(() => {
    const llmOptions = modelOptions.value.filter((item) => item.modelType === 'LLM')
    return llmOptions.length ? llmOptions : modelOptions.value
  })

  const selectedAiEditModel = computed(() => {
    const id = deps.aiModelInstanceId.value
      || deps.studio.value?.defaultModelInstanceId
      || authoringModelOptions.value[0]?.id
      || ''
    if (!id) return null
    return authoringModelOptions.value.find((item) => item.id === id)
      || modelOptions.value.find((item) => item.id === id)
      || null
  })

  const selectedToolInfo = computed(() =>
    toolOptions.value.find((tool) => tool.name === deps.selectedToolName.value) ?? null,
  )

  async function loadNodeTypes() {
    nodeTypesLoading.value = true
    try {
      const { data } = await getWorkflowGraphNodeTypes()
      nodeTypes.value = Array.isArray(data) ? data : []
      graphNodeTypeCapabilitiesLoaded.value = true
    } catch {
      nodeTypes.value = []
      graphNodeTypeCapabilitiesLoaded.value = false
    } finally {
      nodeTypesLoading.value = false
    }
  }

  async function loadToolOptions() {
    try {
      toolOptions.value = await listAllTools({ enabled: true })
    } catch {
      toolOptions.value = []
    }
  }

  async function loadModelOptions() {
    try {
      const { data } = await getModelInstances({ modelType: 'LLM' })
      modelOptions.value = normalizeModelInstanceList(data).filter((item) => isActiveModelInstance(item))
      if (!deps.aiModelInstanceId.value && deps.studio.value?.defaultModelInstanceId) {
        deps.aiModelInstanceId.value = deps.studio.value.defaultModelInstanceId
      }
    } catch {
      modelOptions.value = []
    }
  }

  async function loadKnowledgeOptions() {
    try {
      const { data } = await getKnowledgeList()
      knowledgeOptions.value = Array.isArray(data?.data) ? data.data : []
    } catch {
      knowledgeOptions.value = []
    }
  }

  async function loadCredentialOptions(
    state: WorkflowWorkingCopyState | null = deps.studio.value,
    shouldApply: () => boolean = () => true,
  ) {
    try {
      const { data } = await listWorkflowCredentials({
        projectId: state?.projectId || null,
        projectCode: state?.projectCode || null,
      })
      if (!shouldApply()) return
      credentialOptions.value = Array.isArray(data) ? data : []
    } catch {
      if (!shouldApply()) return
      credentialOptions.value = []
    }
  }

  function handleCredentialCreated(credential: WorkflowCredential) {
    credentialOptions.value = [
      credential,
      ...credentialOptions.value.filter((item) => item.credentialRef !== credential.credentialRef),
    ]
  }

  async function refreshParamSourceHints() {
    const requestSequence = ++paramSourceHintsSequence
    paramSourceHints.value = []
    const tool = selectedToolInfo.value
    if (!tool?.projectId || !tool.name) {
      return
    }
    try {
      const { data } = await getApiGraphParamHints(tool.projectId, tool.name)
      if (requestSequence !== paramSourceHintsSequence) return
      paramSourceHints.value = Array.isArray(data) ? data : []
    } catch {
      if (requestSequence !== paramSourceHintsSequence) return
      paramSourceHints.value = []
    }
  }

  return {
    nodeTypes,
    modelOptions,
    knowledgeOptions,
    credentialOptions,
    paramSourceHints,
    graphNodeTypeCapabilitiesLoaded,
    availableTools,
    authoringModelOptions,
    selectedAiEditModel,
    selectedToolInfo,
    loadNodeTypes,
    loadToolOptions,
    loadModelOptions,
    loadKnowledgeOptions,
    loadCredentialOptions,
    handleCredentialCreated,
    refreshParamSourceHints,
  }
}
