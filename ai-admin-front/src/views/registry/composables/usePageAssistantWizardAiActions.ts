import { type ComputedRef, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resetPageAssistantWorkflowAiCodingResult } from '@/api/scanProject'
import type { PageActionRegistryView, PageRegistryView } from '@/api/embedOps'
import type { ScanProject, PageAssistantSessionSummary } from '@/types/scanProject'
import type { AgentEntry, PageAssistantWorkflowBindingResult, WorkflowDraftGenerationResult } from '@/types/workflow'
import {
  pageAccessTitle,
  pageIdentity,
  type DraftSource,
  type WizardStepKey,
} from '@/views/registry/pageAssistantWizardViewModel'

export interface UsePageAssistantWizardAiActionsDeps {
  project: Ref<ScanProject | null>
  pageRegistry: Ref<PageRegistryView[]>
  pageActions: Ref<PageActionRegistryView[]>
  selectedPageKey: Ref<string>
  selectedPageIdentity: Ref<string>
  selectedPage: ComputedRef<PageRegistryView | null>
  selectedActions: Ref<PageActionRegistryView[]>
  agentName: Ref<string>
  requirement: Ref<string>
  draftPreview: Ref<WorkflowDraftGenerationResult | null>
  draftSource: Ref<DraftSource>
  createdWorkflowId: Ref<string>
  bindingResult: Ref<PageAssistantWorkflowBindingResult | null>
  pageCopilotAgent: Ref<AgentEntry | null>
  aiPromptDialogVisible: Ref<boolean>
  workflowAiCodingPromptDialogVisible: Ref<boolean>
  workflowAiCodingResetting: Ref<boolean>
  pageAssistantSession: Ref<{ sessionId?: string } | null>
  pageAssistantManifest: Ref<{ session: { sessionId?: string } } | null>
  selectedPageAssistantAccess: Ref<PageAssistantSessionSummary | null>
  workflowAiCodingPromptCopied: Ref<boolean>
  workflowAiCodingDraftEvidence: ComputedRef<{ workflowId?: string; studioUrl?: string }>
  resetWizardProgressFromDraft: () => void
  selectStep: (key: WizardStepKey) => boolean
  loadPageAssistantSessions: (options?: { silent?: boolean }) => Promise<void>
  loadPageAssistantManifest: (options?: { silent?: boolean }) => Promise<void>
  refreshPageAssistantSession: () => Promise<void>
  loadPageCopilotAgent: () => Promise<void>
  openAiCodingWorkflowStudio: (studioUrl?: string | null, workflowId?: string | null) => boolean
  defaultRequirement: () => string
}

export function usePageAssistantWizardAiActions(deps: UsePageAssistantWizardAiActionsDeps) {
  function resolvePageAccessTitle(session: PageAssistantSessionSummary) {
    return pageAccessTitle(session, deps.pageRegistry.value)
  }

  function openAiPromptDialog() {
    if (deps.aiPromptDialogVisible.value) {
      void deps.loadPageAssistantManifest()
      return
    }
    deps.aiPromptDialogVisible.value = true
  }

  async function openWorkflowAiCodingPromptDialog() {
    if (!deps.selectedPageKey.value && !deps.selectedPage.value) {
      ElMessage.warning('请先选择页面')
      return
    }
    if (!deps.selectedActions.value.length) {
      ElMessage.warning('请先选择至少一个页面动作')
      return
    }
    deps.workflowAiCodingPromptDialogVisible.value = true
    await deps.loadPageAssistantManifest({ silent: true })
    await deps.refreshPageAssistantSession()
  }

  async function refreshWorkflowAiCodingDraftStatus() {
    await deps.refreshPageAssistantSession()
  }

  function openAiCodingWorkflowStudioFromDraft() {
    const evidence = deps.workflowAiCodingDraftEvidence.value
    if (!evidence.workflowId) {
      ElMessage.warning('尚未收到 AI Coding 回传的 workflowId')
      return
    }
    deps.openAiCodingWorkflowStudio(evidence.studioUrl, evidence.workflowId)
  }

  async function resetAiCodingWorkflowDraft() {
    const sessionId = deps.pageAssistantSession.value?.sessionId || deps.pageAssistantManifest.value?.session.sessionId
    if (!deps.project.value?.id || !sessionId) {
      ElMessage.warning('请先创建页面助手 AI 接入会话')
      return
    }
    const workflowId = deps.workflowAiCodingDraftEvidence.value.workflowId
    try {
      await ElMessageBox.confirm(
        workflowId
          ? `将删除 Workflow 草稿 ${workflowId} 并清空本次 AI Coding 回传结果；如果该 Workflow 已发布或已绑定，后端会拒绝删除。`
          : '将清空本次 AI Coding 回传结果，然后可以复制提示词重新生成。',
        '删除并重新生成',
        {
          type: 'warning',
          confirmButtonText: '删除并重新生成',
          cancelButtonText: '取消',
          confirmButtonClass: 'el-button--danger',
        },
      )
    } catch {
      return
    }
    deps.workflowAiCodingResetting.value = true
    try {
      const { data } = await resetPageAssistantWorkflowAiCodingResult(
        deps.project.value.id,
        sessionId,
        true,
      )
      deps.pageAssistantSession.value = data
      if (deps.createdWorkflowId.value === workflowId || deps.draftSource.value === 'AI_CODING_RETURNED') {
        deps.createdWorkflowId.value = ''
        deps.bindingResult.value = null
        deps.pageCopilotAgent.value = null
        deps.draftSource.value = 'NONE'
      }
      deps.workflowAiCodingPromptCopied.value = false
      void deps.loadPageAssistantSessions({ silent: true })
      ElMessage.success('已删除旧草稿结果，可以重新复制提示词生成')
    } catch (error) {
      ElMessage.error((error as Error).message || '删除并重新生成失败')
    } finally {
      deps.workflowAiCodingResetting.value = false
    }
  }

  async function confirmSwitchToPlatformGeneration() {
    try {
      await ElMessageBox.confirm(
        '这不会删除 AI Coding 已创建的 Workflow，但会取消当前选择并重新生成平台草稿。',
        '改用平台生成',
        { type: 'warning', confirmButtonText: '确认改用', cancelButtonText: '取消' },
      )
      deps.createdWorkflowId.value = ''
      deps.bindingResult.value = null
      deps.pageCopilotAgent.value = null
      deps.draftPreview.value = null
      deps.draftSource.value = 'NONE'
      return true
    } catch {
      return false
    }
  }

  async function useAiCodingWorkflowDraft() {
    const workflowId = deps.workflowAiCodingDraftEvidence.value.workflowId
    if (!workflowId) {
      ElMessage.warning('尚未收到 AI Coding 回传的 workflowId')
      return
    }
    deps.draftPreview.value = null
    deps.createdWorkflowId.value = workflowId
    deps.draftSource.value = 'AI_CODING_RETURNED'
    deps.bindingResult.value = null
    deps.workflowAiCodingPromptDialogVisible.value = false
    await deps.loadPageCopilotAgent()
    ElMessage.success('已选用 AI Coding 生成的 Workflow，请继续挂载智能体')
    deps.selectStep('bind')
  }

  function usePageAssistantAccess(session: PageAssistantSessionSummary) {
    const pageKey = session.targetPageKey || ''
    if (!pageKey) {
      ElMessage.warning('该接入任务还没有绑定 pageKey')
      return
    }
    const page = deps.pageRegistry.value.find((item) => item.pageKey === pageKey)
    deps.selectedPageKey.value = pageKey
    deps.selectedPageIdentity.value = page ? pageIdentity(page) : ''
    deps.selectedActions.value = deps.pageActions.value.filter((action) => action.pageKey === pageKey && action.status === 'ACTIVE')
    if (!deps.selectedActions.value.length) {
      deps.selectedActions.value = deps.pageActions.value.filter((action) => action.pageKey === pageKey)
    }
    deps.agentName.value = deps.agentName.value || `${page?.name || pageKey}页面助手`
    deps.requirement.value = deps.defaultRequirement()
    deps.selectedPageAssistantAccess.value = null
    deps.resetWizardProgressFromDraft()
    deps.selectStep(deps.selectedActions.value.length ? 'draft' : 'action')
    ElMessage.success('已带入页面和动作，可继续创建页面助手')
  }

  return {
    pageAccessTitle: resolvePageAccessTitle,
    openAiPromptDialog,
    openWorkflowAiCodingPromptDialog,
    refreshWorkflowAiCodingDraftStatus,
    openAiCodingWorkflowStudio: openAiCodingWorkflowStudioFromDraft,
    resetAiCodingWorkflowDraft,
    confirmSwitchToPlatformGeneration,
    useAiCodingWorkflowDraft,
    usePageAssistantAccess,
  }
}
