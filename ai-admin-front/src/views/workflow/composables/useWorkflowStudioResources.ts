import { computed, getCurrentScope, onScopeDispose, ref, watch, type Ref } from 'vue'
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
import { normalizeActiveModelInstances } from '@/utils/modelSelection'

export interface UseWorkflowStudioResourcesDeps {
  studio: Ref<WorkflowWorkingCopyState | null>
  aiModelInstanceId: Ref<string>
  selectedToolName: Ref<string>
}

export function useWorkflowStudioResources(deps: UseWorkflowStudioResourcesDeps) {
  const nodeTypesLoading = ref(false)
  const nodeTypes = ref<WorkflowGraphNodeTypeDescriptor[]>([])
  const modelOptions = ref<ModelInstance[]>([])
  const modelOptionsLoading = ref(false)
  const modelOptionsLoadError = ref(false)
  const knowledgeOptions = ref<KnowledgeBase[]>([])
  const toolOptions = ref<ToolInfo[]>([])
  const credentialOptions = ref<WorkflowCredential[]>([])
  const paramSourceHints = ref<ApiGraphParamSourceHint[]>([])
  const graphNodeTypeCapabilitiesLoaded = ref(false)
  let toolOptionsSequence = 0
  let paramSourceHintsSequence = 0
  let modelOptionsSequence = 0
  let knowledgeOptionsSequence = 0
  let graphResourceScope = ''
  let graphModelsRequested = false
  let graphKnowledgeRequested = false

  // Catalogs belong to the document/project, not a particular saved draft object.
  const resourceScope = () => [
    deps.studio.value?.workflowId, deps.studio.value?.projectId, deps.studio.value?.projectCode,
  ].join('\u0000')
  const invalidateCatalogRequests = () => {
    ++modelOptionsSequence
    ++knowledgeOptionsSequence
  }
  function resetGraphResourceScope(scope: string) {
    graphResourceScope = scope
    graphModelsRequested = false
    graphKnowledgeRequested = false
    invalidateCatalogRequests()
    modelOptions.value = []
    knowledgeOptions.value = []
    modelOptionsLoading.value = false
    modelOptionsLoadError.value = false
  }
  // Observe real switches synchronously, including away/back in one Vue tick.
  watch(resourceScope, resetGraphResourceScope, { flush: 'sync' })
  if (getCurrentScope()) onScopeDispose(() => {
    invalidateCatalogRequests()
    modelOptionsLoading.value = false
  })

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

  async function loadToolOptions(state: WorkflowWorkingCopyState | null = deps.studio.value) {
    const requestSequence = ++toolOptionsSequence
    const requestedProjectId = state?.projectId ?? null
    const requestedProjectCode = state?.projectCode ?? null
    const isCurrentRequestScope = () => requestSequence === toolOptionsSequence
      && (deps.studio.value?.projectId ?? null) === requestedProjectId
      && (deps.studio.value?.projectCode ?? null) === requestedProjectCode
    toolOptions.value = []
    try {
      const tools = await listAllTools({
        enabled: true,
        ...(requestedProjectId ? { projectId: requestedProjectId } : {}),
      })
      if (!isCurrentRequestScope()) {
        return
      }
      toolOptions.value = tools
    } catch {
      if (!isCurrentRequestScope()) {
        return
      }
      toolOptions.value = []
    }
  }

  async function loadModelOptions() {
    const sequence = ++modelOptionsSequence
    const scope = resourceScope()
    const isCurrent = () => sequence === modelOptionsSequence && scope === resourceScope()
    modelOptionsLoading.value = true
    modelOptionsLoadError.value = false
    try {
      const { data } = await getModelInstances({ modelType: 'LLM' })
      if (!isCurrent()) return
      modelOptions.value = normalizeActiveModelInstances(data, 'LLM')
      if (!deps.aiModelInstanceId.value && deps.studio.value?.defaultModelInstanceId) {
        deps.aiModelInstanceId.value = deps.studio.value.defaultModelInstanceId
      }
    } catch {
      if (!isCurrent()) return
      modelOptions.value = []
      modelOptionsLoadError.value = true
    } finally {
      if (sequence === modelOptionsSequence) modelOptionsLoading.value = false
    }
  }

  async function loadKnowledgeOptions() {
    const sequence = ++knowledgeOptionsSequence
    const scope = resourceScope()
    const isCurrent = () => sequence === knowledgeOptionsSequence && scope === resourceScope()
    try {
      const { data } = await getKnowledgeList()
      if (!isCurrent()) return
      knowledgeOptions.value = Array.isArray(data) ? data : []
    } catch {
      if (!isCurrent()) return
      knowledgeOptions.value = []
    }
  }

  // A deterministic method/API graph does not use these optional catalogs.
  // Explicit model-picker retries still call loadModelOptions; catalog failure
  // remains failure, not a fabricated successful empty result.
  async function loadGraphResourceOptions(kinds: readonly string[]) {
    const state = deps.studio.value
    if (!state?.workflowId) return
    const scope = resourceScope()
    if (scope !== graphResourceScope) {
      resetGraphResourceScope(scope)
    }
    const requests: Promise<void>[] = []
    if (!graphModelsRequested && kinds.some((kind) => ['llm', 'classifier', 'parameter'].includes(kind))) {
      graphModelsRequested = true
      requests.push(loadModelOptions())
    }
    if (!graphKnowledgeRequested && kinds.some((kind) => ['knowledge', 'knowledgeWrite'].includes(kind))) {
      graphKnowledgeRequested = true
      requests.push(loadKnowledgeOptions())
    }
    await Promise.all(requests)
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
    // API Graph hints belong to scanned HTTP/API definitions. A Business
    // Method already owns its parameter contract in the dedicated picker;
    // asking the API Graph to resolve its catalog storage name both duplicates
    // that contract and can target no scan row at all.
    if (!tool?.projectId || !tool.name || tool.assetType === 'BUSINESS_METHOD') {
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
    modelOptionsLoading,
    modelOptionsLoadError,
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
    loadGraphResourceOptions,
    loadCredentialOptions,
    handleCredentialCreated,
    refreshParamSourceHints,
  }
}
