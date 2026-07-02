import { ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  bindPageAssistantWorkflow,
  createWorkflow,
  generateWorkflowDraft,
  listAgentEntries,
  saveWorkflowStudio,
} from '@/api/workflow'
import type {
  PageActionRegistryView,
  PageRegistryView,
} from '@/api/embedOps'
import type { ApiAssetItem } from '@/types/apiAsset'
import type { ScanProject } from '@/types/scanProject'
import type {
  AgentEntry,
  PageAssistantWorkflowBindingResult,
  WorkflowDefinitionDraft,
  WorkflowDraftGenerationResult,
  WorkflowDraftResource,
} from '@/types/workflow'
import { safeJson } from '../pageAssistantWizardUtils'

type DraftSource = 'NONE' | 'PLATFORM_GENERATED' | 'AI_CODING_RETURNED'
type WizardStepKey = 'connect' | 'page' | 'action' | 'draft' | 'confirm' | 'bind' | 'studio'

interface UsePageAssistantWorkflowLifecycleDeps {
  project: Ref<ScanProject | null>
  projectCode: ComputedRef<string>
  pageRegistry: Ref<PageRegistryView[]>
  selectedPage: ComputedRef<PageRegistryView | null>
  selectedPageKey: Ref<string>
  selectedActions: Ref<PageActionRegistryView[]>
  selectedApiAssets: Ref<ApiAssetItem[]>
  agentName: Ref<string>
  requirement: Ref<string>
  modelInstanceId: Ref<string>
  draftPreview: Ref<WorkflowDraftGenerationResult | null>
  draftSource: Ref<DraftSource>
  createdWorkflowId: Ref<string>
  bindingResult: Ref<PageAssistantWorkflowBindingResult | null>
  pageCopilotAgent: Ref<AgentEntry | null>
  draftIssueCount: ComputedRef<number>
  defaultRequirement: () => string
  pageAssistantWorkflowName: () => string
  pageAssistantWorkflowKeySlug: () => string
  confirmSwitchToPlatformGeneration: () => Promise<boolean>
  selectStep: (key: WizardStepKey) => boolean
}

export function usePageAssistantWorkflowLifecycle(deps: UsePageAssistantWorkflowLifecycleDeps) {
  const generating = ref(false)
  const creatingWorkflow = ref(false)
  const bindingAgent = ref(false)

  function pageActionToResource(action: PageActionRegistryView): WorkflowDraftResource {
    const page = deps.pageRegistry.value.find((item) => item.pageKey === action.pageKey)
    return {
      kind: 'PAGE_ACTION',
      name: action.actionKey,
      qualifiedName: `${action.pageKey}/${action.actionKey}`,
      projectCode: action.projectCode,
      description: action.title || action.description || action.actionKey,
      metadata: {
        pageKey: action.pageKey,
        routePattern: page?.routePattern || '',
        actionKey: action.actionKey,
        confirmRequired: Boolean(action.confirmRequired),
        inputSchema: safeJson(action.inputSchemaJson),
        outputSchema: safeJson(action.outputSchemaJson),
        sampleArgs: safeJson(action.sampleArgsJson),
      },
    }
  }

  function apiAssetToResource(asset: ApiAssetItem): WorkflowDraftResource {
    return {
      kind: 'TOOL',
      name: asset.globalToolName || asset.name,
      qualifiedName: asset.globalToolQualifiedName || asset.globalToolName || asset.name,
      definitionId: asset.globalToolDefinitionId || null,
      projectCode: asset.projectCode,
      description: asset.aiDescription || asset.description || asset.name,
      metadata: {
        endpointPath: asset.endpointPath,
        httpMethod: asset.httpMethod,
        parameters: asset.parameters,
      },
    }
  }

  async function loadPageCopilotAgent() {
    if (!deps.project.value?.id && !deps.projectCode.value) return
    try {
      const { data } = await listAgentEntries({
        projectId: deps.project.value?.id ?? undefined,
        projectCode: deps.projectCode.value,
        agentKind: 'PAGE_COPILOT',
      })
      deps.pageCopilotAgent.value = data[0] || null
    } catch {
      deps.pageCopilotAgent.value = null
    }
  }

  async function generateDraft() {
    if (!deps.selectedActions.value.length) {
      ElMessage.warning('请至少选择一个页面动作')
      return
    }
    if (!deps.modelInstanceId.value) {
      ElMessage.warning('请选择模型实例')
      return
    }
    if (deps.draftSource.value === 'AI_CODING_RETURNED' && deps.createdWorkflowId.value) {
      const confirmed = await deps.confirmSwitchToPlatformGeneration()
      if (!confirmed) return
    }
    generating.value = true
    try {
      const { data } = await generateWorkflowDraft({
        agentId: 'new',
        agentName: deps.agentName.value,
        projectCode: deps.projectCode.value,
        modelInstanceId: deps.modelInstanceId.value,
        draftScenario: 'PAGE_ASSISTANT',
        requirement: deps.requirement.value || deps.defaultRequirement(),
        pageActions: deps.selectedActions.value.map(pageActionToResource),
        tools: deps.selectedApiAssets.value.map(apiAssetToResource),
        currentCanvas: { version: 2, nodes: [], edges: [] },
      })
      deps.draftPreview.value = data
      deps.draftSource.value = 'PLATFORM_GENERATED'
      deps.createdWorkflowId.value = ''
      deps.bindingResult.value = null
      deps.pageCopilotAgent.value = null
      deps.selectStep('confirm')
      if (data.validationErrors?.length) {
        ElMessage.warning('草稿已返回，但仍有校验问题，请查看提示')
      } else {
        ElMessage.success('Workflow 草稿已生成')
      }
    } catch (error) {
      ElMessage.error((error as Error).message || '生成 Workflow 草稿失败')
    } finally {
      generating.value = false
    }
  }

  async function confirmCreateWorkflow() {
    const draft = deps.draftPreview.value
    if (!draft) return
    if (deps.draftIssueCount.value) {
      ElMessage.warning('草稿仍有校验问题或占位节点，请修复后再创建 Workflow')
      return
    }
    creatingWorkflow.value = true
    try {
      const graphSpecJson = JSON.stringify(draft.graphSpec)
      const canvasJson = JSON.stringify(draft.canvasSnapshot || { version: 2, nodes: [], edges: [] })
      const extraJson = JSON.stringify({
        pageAssistant: {
          source: 'PAGE_ASSISTANT_WIZARD',
          pageKey: deps.selectedPageKey.value,
          pageName: deps.selectedPage.value?.name || deps.selectedPageKey.value,
          routePattern: deps.selectedPage.value?.routePattern || '',
          actionKeys: deps.selectedActions.value.map((item) => item.actionKey),
        },
      })
      const workflowDraft: WorkflowDefinitionDraft = {
        name: deps.pageAssistantWorkflowName(),
        keySlug: deps.pageAssistantWorkflowKeySlug(),
        description: deps.requirement.value || deps.defaultRequirement(),
        projectId: deps.project.value?.id ?? null,
        projectCode: deps.projectCode.value,
        workflowType: 'PAGE_ASSISTANT',
        runtimeType: 'LANGGRAPH4J',
        graphSpec: draft.graphSpec,
        graphSpecJson,
        canvasJson,
        defaultModelInstanceId: deps.modelInstanceId.value,
        status: 'DRAFT',
        managedBy: 'PAGE_ASSISTANT',
        extraJson,
      }
      const { data: workflow } = await createWorkflow(workflowDraft)
      await saveWorkflowStudio(workflow.id, { graphSpecJson, canvasJson, extraJson })
      deps.draftSource.value = 'PLATFORM_GENERATED'
      deps.createdWorkflowId.value = workflow.id
      deps.bindingResult.value = null
      await loadPageCopilotAgent()
      ElMessage.success('页面助手 Workflow 草稿已创建')
      deps.selectStep('bind')
    } catch (error) {
      ElMessage.error((error as Error).message || '创建页面助手 Workflow 失败')
    } finally {
      creatingWorkflow.value = false
    }
  }

  async function bindToPageCopilot() {
    if (!deps.createdWorkflowId.value) return
    bindingAgent.value = true
    try {
      const { data } = await bindPageAssistantWorkflow(deps.createdWorkflowId.value, {
        projectId: deps.project.value?.id ?? null,
        projectCode: deps.projectCode.value,
        agentId: deps.pageCopilotAgent.value?.id ?? null,
        pageKey: deps.selectedPageKey.value,
        routePattern: deps.selectedPage.value?.routePattern || '',
        actionKeys: deps.selectedActions.value.map((item) => item.actionKey).filter(Boolean),
      })
      deps.bindingResult.value = data
      if (!deps.pageCopilotAgent.value) {
        deps.pageCopilotAgent.value = {
          id: data.agentId,
          keySlug: data.agentKeySlug,
          name: `${deps.project.value?.name || deps.projectCode.value} Page Copilot`,
          agentKind: 'PAGE_COPILOT',
        }
      }
      ElMessage.success('页面助手 Workflow 已挂载到页面副驾驶 Agent')
      deps.selectStep('studio')
    } catch (error) {
      ElMessage.error((error as Error).message || '挂载页面副驾驶 Agent 失败')
    } finally {
      bindingAgent.value = false
    }
  }

  return {
    generating,
    creatingWorkflow,
    bindingAgent,
    loadPageCopilotAgent,
    generateDraft,
    confirmCreateWorkflow,
    bindToPageCopilot,
  }
}
