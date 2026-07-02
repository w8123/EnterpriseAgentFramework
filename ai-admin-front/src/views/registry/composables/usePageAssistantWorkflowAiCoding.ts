import { computed, type ComputedRef, type Ref } from 'vue'
import type {
  PageActionRegistryView,
  PageRegistryView,
} from '@/api/embedOps'
import type {
  AiAccessSession,
  PageAssistantOnboardingManifest,
  ScanProject,
} from '@/types/scanProject'
import { buildPageAssistantWorkflowAiCodingPrompt } from '../pageAssistantWorkflowAiCodingPrompt'
import { formatValidationOverallStatus } from '../pageAssistantWizardUtils'

const WORKFLOW_AI_CODING_DRAFT_STEP_KEY = 'workflow-ai-coding-draft'

type WorkflowAiCodingTool = 'Cursor' | 'Codex' | 'Claude Code'

interface UsePageAssistantWorkflowAiCodingDeps {
  workflowAiCodingPromptTool: Ref<WorkflowAiCodingTool>
  project: Ref<ScanProject | null>
  projectCode: ComputedRef<string>
  pageAssistantManifest: Ref<PageAssistantOnboardingManifest | null>
  pageAssistantSession: Ref<AiAccessSession | null>
  aiCodingAccessState: ComputedRef<string>
  selectedPage: ComputedRef<PageRegistryView | null>
  selectedPageKey: Ref<string>
  selectedActions: Ref<PageActionRegistryView[]>
  requirement: Ref<string>
  modelInstanceId: Ref<string>
  defaultRequirement: () => string
  pageAssistantWorkflowName: () => string
  pageAssistantWorkflowKeySlug: () => string
}

export function usePageAssistantWorkflowAiCoding(deps: UsePageAssistantWorkflowAiCodingDeps) {
  const workflowAiCodingReportUrl = computed(() => {
    const sessionId = deps.pageAssistantSession.value?.sessionId
      || deps.pageAssistantManifest.value?.session.sessionId
    if (!deps.project.value?.id || !sessionId) return ''
    return `${window.location.origin}/api/ai-coding/projects/${deps.project.value.id}/page-assistant/sessions/${sessionId}/workflow-ai-coding-result`
  })

  const workflowAiCodingDraftStep = computed(() =>
    deps.pageAssistantSession.value?.steps?.find((step) =>
      step.stepKey === WORKFLOW_AI_CODING_DRAFT_STEP_KEY) || null,
  )

  const workflowAiCodingDraftEvidence = computed(() => {
    const evidence = workflowAiCodingDraftStep.value?.evidence || {}
    return {
      workflowId: typeof evidence.workflowId === 'string' ? evidence.workflowId : '',
      keySlug: typeof evidence.keySlug === 'string' ? evidence.keySlug : '',
      workflowName: typeof evidence.workflowName === 'string' ? evidence.workflowName : '',
      studioUrl: typeof evidence.studioUrl === 'string' ? evidence.studioUrl : '',
      validation: (evidence.validation && typeof evidence.validation === 'object'
        ? evidence.validation
        : {}) as Record<string, unknown>,
      pageAssistantValidation: (evidence.pageAssistantValidation && typeof evidence.pageAssistantValidation === 'object'
        ? evidence.pageAssistantValidation
        : {}) as Record<string, unknown>,
      runtimeVerification: (evidence.runtimeVerification && typeof evidence.runtimeVerification === 'object'
        ? evidence.runtimeVerification
        : {}) as Record<string, unknown>,
    }
  })

  const workflowAiCodingValidationSummary = computed(() =>
    formatValidationOverallStatus(workflowAiCodingDraftEvidence.value.validation),
  )

  const workflowAiCodingPageAssistantValidationSummary = computed(() => {
    const summary = workflowAiCodingDraftEvidence.value.pageAssistantValidation
    const base = formatValidationOverallStatus(summary)
    const matched = Array.isArray(summary.matchedActions) ? summary.matchedActions.filter(Boolean) : []
    const missing = Array.isArray(summary.missingActions) ? summary.missingActions.filter(Boolean) : []
    if (!base && !matched.length && !missing.length) return ''
    const parts = [base || 'UNKNOWN']
    if (matched.length) parts.push(`matched ${matched.length}`)
    if (missing.length) parts.push(`missing ${missing.length}`)
    return parts.join(' · ')
  })

  const workflowAiCodingRuntimeVerificationSummary = computed(() => {
    const verification = workflowAiCodingDraftEvidence.value.runtimeVerification
    const browserRuntime = verification.browserRuntime && typeof verification.browserRuntime === 'object'
      ? verification.browserRuntime as Record<string, unknown>
      : verification
    const status = String(browserRuntime.status || '').trim()
    const checkedActions = Array.isArray(browserRuntime.checkedActions)
      ? browserRuntime.checkedActions.filter(Boolean)
      : Array.isArray(browserRuntime.invokedActions)
        ? browserRuntime.invokedActions.filter(Boolean)
        : []
    const parts = [status || 'UNKNOWN']
    if (checkedActions.length) parts.push(`${checkedActions.length} actions`)
    const message = String(browserRuntime.message || '').trim()
    if (message) parts.push(message)
    return Object.keys(browserRuntime).length ? parts.join(' · ') : ''
  })

  const workflowAiCodingPrompt = computed(() => buildPageAssistantWorkflowAiCodingPrompt({
    toolName: deps.workflowAiCodingPromptTool.value,
    platformUrl: window.location.origin,
    project: {
      id: deps.project.value?.id,
      projectCode: deps.project.value?.projectCode || deps.projectCode.value,
      name: deps.project.value?.name || deps.projectCode.value,
      registryAppKey: deps.pageAssistantManifest.value?.project.registryAppKey || deps.project.value?.registryAppKey,
    },
    aiCodingAccess: {
      enabled: deps.pageAssistantManifest.value?.aiCodingAccess?.enabled,
      accessKey: deps.pageAssistantManifest.value?.aiCodingAccess?.accessKey,
      stateLabel: deps.aiCodingAccessState.value,
    },
    sessionId: deps.pageAssistantSession.value?.sessionId || deps.pageAssistantManifest.value?.session.sessionId,
    reportUrl: workflowAiCodingReportUrl.value,
    page: {
      pageKey: deps.selectedPage.value?.pageKey || deps.selectedPageKey.value,
      pageName: deps.selectedPage.value?.name || deps.selectedPageKey.value,
      routePattern: deps.selectedPage.value?.routePattern || '',
    },
    actions: deps.selectedActions.value.map((action) => ({
      actionKey: action.actionKey,
      title: action.title,
      description: action.description,
      confirmRequired: Boolean(action.confirmRequired),
    })),
    requirement: deps.requirement.value || deps.defaultRequirement(),
    workflowName: deps.pageAssistantWorkflowName(),
    workflowKeySlug: deps.pageAssistantWorkflowKeySlug(),
    modelInstanceId: deps.modelInstanceId.value,
    skillPackageUrl: `${window.location.origin}/api/ai-assist/skills/workflow-ai-coding/latest.zip`,
  }))

  return {
    workflowAiCodingReportUrl,
    workflowAiCodingDraftStep,
    workflowAiCodingDraftEvidence,
    workflowAiCodingValidationSummary,
    workflowAiCodingPageAssistantValidationSummary,
    workflowAiCodingRuntimeVerificationSummary,
    workflowAiCodingPrompt,
  }
}
