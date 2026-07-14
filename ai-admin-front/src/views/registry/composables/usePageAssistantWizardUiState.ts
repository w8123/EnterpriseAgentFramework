import { ref } from 'vue'
import type { Agent, PageAssistantWorkflowAttachmentResult, WorkflowDraftGenerationResult } from '@/types/workflow'
import type {
  AiAccessSession,
  PageAssistantOnboardingManifest,
  PageAssistantSessionSummary,
  ProjectToolInfo,
} from '@/types/scanProject'
import type { PageActionRegistryView } from '@/api/embedOps'
import type {
  AssistantGoal,
  DraftSource,
  WizardStepKey,
} from '@/views/registry/pageAssistantWizardViewModel'

export function usePageAssistantWizardUiState() {
  const selectedPageKey = ref('')
  const selectedPageIdentity = ref('')
  const selectedActions = ref<PageActionRegistryView[]>([])
  const selectedProjectApiTools = ref<ProjectToolInfo[]>([])
  const modelInstanceId = ref('')
  const focusedStep = ref<WizardStepKey | ''>('connect')
  const statusStripRef = ref<HTMLElement | null>(null)
  const pagePanelRef = ref<HTMLElement | null>(null)
  const sdkHelperVisible = ref(false)
  const assistantGoal = ref<AssistantGoal>('query')
  const agentName = ref('')
  const requirement = ref('')
  const draftPreview = ref<WorkflowDraftGenerationResult | null>(null)
  const draftSource = ref<DraftSource>('NONE')
  const createdWorkflowId = ref('')
  const attachmentResult = ref<PageAssistantWorkflowAttachmentResult | null>(null)
  const pageCopilotAgent = ref<Agent | null>(null)
  const aiPromptDialogVisible = ref(false)
  const aiPromptTool = ref<'Cursor' | 'Codex' | 'Claude Code'>('Cursor')
  const workflowAiCodingPromptDialogVisible = ref(false)
  const workflowAiCodingPromptTool = ref<'Cursor' | 'Codex' | 'Claude Code'>('Cursor')
  const workflowAiCodingResetting = ref(false)
  const pageAssistantManifest = ref<PageAssistantOnboardingManifest | null>(null)
  const pageAssistantSession = ref<AiAccessSession | null>(null)
  const pageAssistantSessions = ref<PageAssistantSessionSummary[]>([])
  const selectedPageAssistantAccess = ref<PageAssistantSessionSummary | null>(null)
  const stepTransitionName = ref('')
  const stepAttentionName = ref('')

  function clearPersistedWizardState(options: { includePreview?: boolean } = {}) {
    if (options.includePreview !== false) {
      draftPreview.value = null
    }
    createdWorkflowId.value = ''
    draftSource.value = 'NONE'
    attachmentResult.value = null
    pageCopilotAgent.value = null
  }

  function resetWizardProgressFromDraft() {
    clearPersistedWizardState()
  }

  return {
    selectedPageKey,
    selectedPageIdentity,
    selectedActions,
    selectedProjectApiTools,
    modelInstanceId,
    focusedStep,
    statusStripRef,
    pagePanelRef,
    sdkHelperVisible,
    assistantGoal,
    agentName,
    requirement,
    draftPreview,
    draftSource,
    createdWorkflowId,
    attachmentResult,
    pageCopilotAgent,
    aiPromptDialogVisible,
    aiPromptTool,
    workflowAiCodingPromptDialogVisible,
    workflowAiCodingPromptTool,
    workflowAiCodingResetting,
    pageAssistantManifest,
    pageAssistantSession,
    pageAssistantSessions,
    selectedPageAssistantAccess,
    stepTransitionName,
    stepAttentionName,
    clearPersistedWizardState,
    resetWizardProgressFromDraft,
  }
}
