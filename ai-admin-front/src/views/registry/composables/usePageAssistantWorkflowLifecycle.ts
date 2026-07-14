import { ref, type ComputedRef, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  attachPageAssistantWorkflowTool,
  createWorkflow,
  generateWorkflowDraft,
  listAgents,
  listWorkflowVersions,
  publishWorkflowVersion,
  saveWorkflowStudio,
} from '@/api/workflow'
import type {
  PageActionRegistryView,
  PageRegistryView,
} from '@/api/embedOps'
import type { ProjectToolInfo, ScanProject } from '@/types/scanProject'
import type {
  Agent,
  PageAssistantWorkflowAttachmentResult,
  WorkflowDefinitionDraft,
  WorkflowDraftGenerationResult,
  WorkflowDraftResource,
} from '@/types/workflow'
import {
  projectApiToolQualifiedName,
  projectApiToolRef,
} from '@/utils/projectApiTools'
import { safeJson } from '../pageAssistantWizardUtils'

type DraftSource = 'NONE' | 'PLATFORM_GENERATED' | 'AI_CODING_RETURNED'
type WizardStepKey = 'connect' | 'page' | 'action' | 'draft' | 'confirm' | 'attach' | 'studio'

function pageCopilotAgentKey(projectCode: string, projectId?: number | null) {
  const source = projectCode.trim() || `project-${projectId}`
  return source.toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '') + '-page-copilot'
}

interface UsePageAssistantWorkflowLifecycleDeps {
  project: Ref<ScanProject | null>
  projectCode: ComputedRef<string>
  pageRegistry: Ref<PageRegistryView[]>
  selectedPage: ComputedRef<PageRegistryView | null>
  selectedPageKey: Ref<string>
  selectedActions: Ref<PageActionRegistryView[]>
  selectedProjectApiTools: Ref<ProjectToolInfo[]>
  agentName: Ref<string>
  requirement: Ref<string>
  modelInstanceId: Ref<string>
  draftPreview: Ref<WorkflowDraftGenerationResult | null>
  draftSource: Ref<DraftSource>
  createdWorkflowId: Ref<string>
  attachmentResult: Ref<PageAssistantWorkflowAttachmentResult | null>
  pageCopilotAgent: Ref<Agent | null>
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
  const attachingAgent = ref(false)

  async function nextWorkflowVersion(workflowId: string) {
    const { data } = await listWorkflowVersions(workflowId)
    const versions = data
      .map((item) => /^v?(\d+)\.(\d+)\.(\d+)$/.exec(item.version || ''))
      .filter((item): item is RegExpExecArray => Boolean(item))
      .map((item) => [Number(item[1]), Number(item[2]), Number(item[3])] as const)
      .sort((left, right) => right[0] - left[0] || right[1] - left[1] || right[2] - left[2])
    if (!versions.length) return 'v1.0.0'
    const [major, minor, patch] = versions[0]
    return `v${major}.${minor}.${patch + 1}`
  }

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

  function projectApiToolToResource(tool: ProjectToolInfo): WorkflowDraftResource {
    return {
      kind: 'TOOL',
      name: projectApiToolRef(tool),
      qualifiedName: projectApiToolQualifiedName(tool, deps.projectCode.value),
      definitionId: tool.globalToolDefinitionId || null,
      projectCode: tool.projectCode || deps.projectCode.value,
      description: tool.aiDescription || tool.description || tool.name,
      metadata: {
        scanToolId: tool.scanToolId,
        endpointPath: tool.endpointPath,
        httpMethod: tool.httpMethod,
        parameters: tool.parameters,
      },
    }
  }

  async function loadPageCopilotAgent() {
    if (!deps.project.value?.id && !deps.projectCode.value) return
    try {
      const { data } = await listAgents({
        projectId: deps.project.value?.id ?? undefined,
        projectCode: deps.projectCode.value,
      })
      const expectedKey = pageCopilotAgentKey(deps.projectCode.value, deps.project.value?.id)
      deps.pageCopilotAgent.value = data.find((agent) => agent.keySlug === expectedKey) || null
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
        tools: deps.selectedProjectApiTools.value.map(projectApiToolToResource),
        currentCanvas: { version: 2, nodes: [], edges: [] },
      })
      deps.draftPreview.value = data
      deps.draftSource.value = 'PLATFORM_GENERATED'
      deps.createdWorkflowId.value = ''
      deps.attachmentResult.value = null
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
      await publishWorkflowVersion(workflow.id, {
        version: await nextWorkflowVersion(workflow.id),
        rolloutPercent: 100,
        note: 'PAGE_ASSISTANT wizard initial publish',
        publishedBy: 'page-assistant-wizard',
      })
      deps.draftSource.value = 'PLATFORM_GENERATED'
      deps.createdWorkflowId.value = workflow.id
      deps.attachmentResult.value = null
      await loadPageCopilotAgent()
      ElMessage.success('页面助手 Workflow 已创建并发布')
      deps.selectStep('attach')
    } catch (error) {
      ElMessage.error((error as Error).message || '创建页面助手 Workflow 失败')
    } finally {
      creatingWorkflow.value = false
    }
  }

  async function attachToPageCopilot() {
    if (!deps.createdWorkflowId.value) return
    attachingAgent.value = true
    try {
      const { data } = await attachPageAssistantWorkflowTool(deps.createdWorkflowId.value, {
        projectId: deps.project.value?.id ?? null,
        projectCode: deps.projectCode.value,
        agentId: deps.pageCopilotAgent.value?.id ?? null,
        modelInstanceId: deps.modelInstanceId.value,
        publishedBy: 'page-assistant-wizard',
      })
      deps.attachmentResult.value = data
      if (!deps.pageCopilotAgent.value) {
        deps.pageCopilotAgent.value = {
          id: data.agentId,
          keySlug: data.agentKeySlug,
          name: `${deps.project.value?.name || deps.projectCode.value} Page Copilot`,
        }
      }
      ElMessage.success('Workflow 已加入 Supervisor 工具目录，Agent 配置已发布')
      deps.selectStep('studio')
    } catch (error) {
      ElMessage.error((error as Error).message || '发布页面副驾驶 Supervisor 配置失败')
    } finally {
      attachingAgent.value = false
    }
  }

  return {
    generating,
    creatingWorkflow,
    attachingAgent,
    loadPageCopilotAgent,
    generateDraft,
    confirmCreateWorkflow,
    attachToPageCopilot,
  }
}
