<template>
  <div class="page-assistant">
    <PageAssistantHeader @back="goBack" />

    <main class="wizard-shell">
      <section ref="statusStripRef">
        <PageAssistantStepProgress
          :steps="steps"
          :displayed-step="displayedStep"
          @select-step="selectStep"
          @wheel="handleWizardWheel"
        />
      </section>

      <section class="stage-shell" @wheel="handleWizardWheel">
        <div class="stack-card stack-card-back" />
        <div class="stack-card stack-card-middle" />
        <button class="stage-cue stage-cue-up" :disabled="!canGoPrev" type="button" aria-label="向上翻页" @click="goPrevStep">
          <span class="cue-chevron" aria-hidden="true" />
        </button>
        <section :key="displayedStep" ref="pagePanelRef" :class="['focus-panel', stepTransitionName, stepAttentionName]" @wheel.stop>
          <PageAssistantConnectPanel
            v-if="displayedStep === 'connect'"
            :key="'connect'"
            :stats="stats"
            :page-registry="pageRegistry"
            :page-assistant-access-count="pageAssistantAccessCount"
            :page-assistant-access-groups="pageAssistantAccessGroups"
            :page-assistant-sessions="pageAssistantSessions"
            :page-assistant-sessions-loading="pageAssistantSessionsLoading"
            :page-assistant-check-running="pageAssistantCheckRunning"
            :sdk-helper-visible="sdkHelperVisible"
            :sdk-template-copied="sdkTemplateCopied"
            :highlighted-sdk-template="highlightedSdkTemplate"
            :resolve-page-access-title="pageAccessTitle"
            @open-ai-prompt="openAiPromptDialog"
            @copy-sdk-template="copySdkTemplate"
            @update:sdk-helper-visible="sdkHelperVisible = $event"
            @refresh-sessions="loadPageAssistantSessions()"
            @select-access="showSessionDetail"
            @run-card-check="runPageAssistantCardCheck"
            @create-assistant="usePageAssistantAccess"
          />

          <PageAssistantPagePanel
            v-else-if="displayedStep === 'page'"
            :key="'page'"
            :page-registry="pageRegistry"
            :selected-page-identity="selectedPageIdentity"
            :resolve-page-identity="pageIdentity"
            :resolve-action-count="actionCount"
            @open-manual-dialog="manualDialogVisible = true"
            @select-page="selectPage"
          />

          <PageAssistantActionPanel
            v-else-if="displayedStep === 'action'"
            :key="'action'"
            :selected-page="selectedPage"
            :filtered-actions="filteredActions"
            :selected-actions="selectedActions"
            :resolve-action-row-key="actionRowKey"
            :is-action-selected="isActionSelected"
            @select-all="selectAllFilteredActions"
            @clear-all="clearFilteredActions"
            @toggle-action="toggleActionSelection"
          />

          <PageAssistantDraftPanel
            v-else-if="displayedStep === 'draft'"
            :key="'draft'"
            v-model:assistant-goal="assistantGoal"
            v-model:agent-name="agentName"
            v-model:model-instance-id="modelInstanceId"
            v-model:requirement="requirement"
            :selected-page="selectedPage"
            :selected-page-key="selectedPageKey"
            :selected-actions="selectedActions"
            :selected-api-assets="selectedApiAssets"
            :api-assets="apiAssets"
            :model-options="modelOptions"
            :assistant-goal-options="assistantGoalOptions"
            :is-ai-coding-workflow-selected="isAiCodingWorkflowSelected"
            :created-workflow-id="createdWorkflowId"
            :workflow-ai-coding-draft-step="workflowAiCodingDraftStep"
            :workflow-ai-coding-draft-evidence="workflowAiCodingDraftEvidence"
            :workflow-ai-coding-validation-summary="workflowAiCodingValidationSummary"
            :workflow-ai-coding-page-assistant-validation-summary="workflowAiCodingPageAssistantValidationSummary"
            :workflow-ai-coding-runtime-verification-summary="workflowAiCodingRuntimeVerificationSummary"
            :workflow-ai-coding-resetting="workflowAiCodingResetting"
            :draft-preview="draftPreview"
            :draft-source="draftSource"
            :generating="generating"
            @open-workflow-ai-coding-prompt="openWorkflowAiCodingPromptDialog"
            @focus-bind-step="focusStepCard('bind')"
            @open-ai-coding-studio="openAiCodingWorkflowStudio"
            @reset-ai-coding-draft="resetAiCodingWorkflowDraft"
            @use-ai-coding-draft="useAiCodingWorkflowDraft"
            @use-default-requirement="requirement = defaultRequirement()"
            @api-selection-change="selectedApiAssets = $event"
            @switch-to-platform-generation="confirmSwitchToPlatformGeneration"
            @generate-draft="generateDraft"
          />

          <PageAssistantConfirmPanel
            v-else-if="displayedStep === 'confirm'"
            :key="'confirm'"
            :is-ai-coding-workflow-selected="isAiCodingWorkflowSelected"
            :created-workflow-id="createdWorkflowId"
            :selected-page-key="selectedPageKey"
            :selected-page="selectedPage"
            :selected-actions="selectedActions"
            :draft-preview-present="Boolean(draftPreview)"
            :draft-issue-count="draftIssueCount"
            :draft-issues="draftIssues"
            :draft-node-count="draftNodeCount"
            :draft-edge-count="draftEdgeCount"
            :selected-model-label="selectedModelLabel"
            :workflow-name="pageAssistantWorkflowName()"
            :creating-workflow="creatingWorkflow"
            @focus-draft-step="focusStepCard('draft')"
            @go-bind-step="selectStep('bind')"
            @confirm-create-workflow="confirmCreateWorkflow"
          />

          <PageAssistantBindPanel
            v-else-if="displayedStep === 'bind'"
            :key="'bind'"
            :created-workflow-id="createdWorkflowId"
            :project-code="projectCode"
            :page-copilot-agent="pageCopilotAgent"
            :selected-page-key="selectedPageKey"
            :selected-page="selectedPage"
            :selected-actions="selectedActions"
            :is-ai-coding-workflow-selected="isAiCodingWorkflowSelected"
            :binding-agent="bindingAgent"
            @focus-step="focusStepCard"
            @bind-to-page-copilot="bindToPageCopilot"
          />

          <PageAssistantStudioPanel
            v-else
            :key="'studio'"
            :binding-result="bindingResult"
            @enter-workflow-studio="enterWorkflowStudio"
            @focus-step="selectStep"
          />
        </section>
        <button class="stage-cue stage-cue-down" :disabled="!canGoNext" type="button" aria-label="向下翻页" @click="goNextStep">
          <span class="cue-chevron" aria-hidden="true" />
        </button>
      </section>
    </main>

    <PageAssistantManualActionDialog
      :visible="manualDialogVisible"
      :submitting="manualSubmitting"
      :form="manualForm"
      @update:visible="manualDialogVisible = $event"
      @submit="submitManualAction"
    />

    <PageAssistantSessionDetailDialog
      :visible="pageAssistantAccessDetailVisible"
      :session="selectedPageAssistantAccess"
      :page-title="selectedPageAssistantAccess ? pageAccessTitle(selectedPageAssistantAccess) : ''"
      @update:visible="pageAssistantAccessDetailVisible = $event"
      @update:session="selectedPageAssistantAccess = $event"
      @create-assistant="usePageAssistantAccess"
    />

    <PageAssistantPromptDialog
      v-model:visible="aiPromptDialogVisible"
      v-model:ai-prompt-tool="aiPromptTool"
      :page-assistant-session="pageAssistantSession"
      :page-assistant-manifest="pageAssistantManifest"
      :project="project"
      :selected-page="selectedPage"
      :selected-page-key="selectedPageKey"
      :ai-coding-access-state="aiCodingAccessState"
      :page-assistant-progress-text="pageAssistantProgressText"
      :page-assistant-session-steps="pageAssistantSessionSteps"
      :page-assistant-scaffold-command="pageAssistantScaffoldCommand"
      :page-assistant-verify-command="pageAssistantVerifyCommand"
      :page-assistant-onboarding-prompt="pageAssistantOnboardingPrompt"
      :page-assistant-manifest-loading="pageAssistantManifestLoading"
      :page-assistant-check-running="pageAssistantCheckRunning"
      :ai-prompt-copied="aiPromptCopied"
      @refresh-session="refreshPageAssistantSession"
      @run-self-check="runPageAssistantSelfCheck"
      @copy-scaffold-command="copyText(pageAssistantScaffoldCommand, '已复制 scaffold 命令')"
      @copy-verify-command="copyText(pageAssistantVerifyCommand, '已复制 verify 命令')"
      @copy-prompt="copyPageAssistantPrompt"
    />

    <PageAssistantWorkflowPromptDialog
      v-model:visible="workflowAiCodingPromptDialogVisible"
      v-model:workflow-ai-coding-prompt-tool="workflowAiCodingPromptTool"
      :workflow-ai-coding-draft-step="workflowAiCodingDraftStep"
      :workflow-ai-coding-draft-evidence="workflowAiCodingDraftEvidence"
      :workflow-ai-coding-validation-summary="workflowAiCodingValidationSummary"
      :workflow-ai-coding-page-assistant-validation-summary="workflowAiCodingPageAssistantValidationSummary"
      :workflow-ai-coding-runtime-verification-summary="workflowAiCodingRuntimeVerificationSummary"
      :workflow-ai-coding-prompt="workflowAiCodingPrompt"
      :page-assistant-manifest-loading="pageAssistantManifestLoading"
      :workflow-ai-coding-resetting="workflowAiCodingResetting"
      :workflow-ai-coding-prompt-copied="workflowAiCodingPromptCopied"
      @refresh-status="refreshWorkflowAiCodingDraftStatus"
      @open-studio="openAiCodingWorkflowStudio"
      @reset-draft="resetAiCodingWorkflowDraft"
      @use-draft="useAiCodingWorkflowDraft"
      @copy-prompt="copyWorkflowAiCodingPrompt"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import type { PageRegistryView } from '@/api/embedOps'
import PageAssistantActionPanel from '@/views/registry/components/page-assistant/PageAssistantActionPanel.vue'
import PageAssistantBindPanel from '@/views/registry/components/page-assistant/PageAssistantBindPanel.vue'
import PageAssistantConfirmPanel from '@/views/registry/components/page-assistant/PageAssistantConfirmPanel.vue'
import PageAssistantConnectPanel from '@/views/registry/components/page-assistant/PageAssistantConnectPanel.vue'
import PageAssistantDraftPanel from '@/views/registry/components/page-assistant/PageAssistantDraftPanel.vue'
import PageAssistantHeader from '@/views/registry/components/page-assistant/PageAssistantHeader.vue'
import PageAssistantManualActionDialog from '@/views/registry/components/page-assistant/PageAssistantManualActionDialog.vue'
import PageAssistantPagePanel from '@/views/registry/components/page-assistant/PageAssistantPagePanel.vue'
import PageAssistantPromptDialog from '@/views/registry/components/page-assistant/PageAssistantPromptDialog.vue'
import PageAssistantSessionDetailDialog from '@/views/registry/components/page-assistant/PageAssistantSessionDetailDialog.vue'
import PageAssistantStepProgress from '@/views/registry/components/page-assistant/PageAssistantStepProgress.vue'
import PageAssistantStudioPanel from '@/views/registry/components/page-assistant/PageAssistantStudioPanel.vue'
import PageAssistantWorkflowPromptDialog from '@/views/registry/components/page-assistant/PageAssistantWorkflowPromptDialog.vue'
import { usePageAssistantClipboard } from '@/views/registry/composables/usePageAssistantClipboard'
import { usePageAssistantManualAction } from '@/views/registry/composables/usePageAssistantManualAction'
import { usePageAssistantOnboarding } from '@/views/registry/composables/usePageAssistantOnboarding'
import { usePageAssistantSessions } from '@/views/registry/composables/usePageAssistantSessions'
import { usePageAssistantWizardAiActions } from '@/views/registry/composables/usePageAssistantWizardAiActions'
import { usePageAssistantWizardData } from '@/views/registry/composables/usePageAssistantWizardData'
import { usePageAssistantWizardDraftConfig } from '@/views/registry/composables/usePageAssistantWizardDraftConfig'
import { usePageAssistantWizardNavigation } from '@/views/registry/composables/usePageAssistantWizardNavigation'
import { usePageAssistantWizardSteps } from '@/views/registry/composables/usePageAssistantWizardSteps'
import { usePageAssistantWizardUiState } from '@/views/registry/composables/usePageAssistantWizardUiState'
import { usePageAssistantWorkflowAiCoding } from '@/views/registry/composables/usePageAssistantWorkflowAiCoding'
import { usePageAssistantWorkflowLifecycle } from '@/views/registry/composables/usePageAssistantWorkflowLifecycle'
import {
  ASSISTANT_GOAL_OPTIONS,
  buildDraftIssues,
  buildSelectedModelLabel,
  pageIdentity,
  type WizardStepKey,
} from '@/views/registry/pageAssistantWizardViewModel'
import {
  highlightSdkCode,
} from '@/views/registry/pageAssistantWizardUtils'

const sessionLoader = { load: async (_options?: { silent?: boolean }) => {} }
const requirementBuilder = { build: () => '' }
const stepSelector = { fn: (_key: WizardStepKey) => false as boolean }
const copilotLoader = { load: async () => {} }
const platformSwitch = { confirm: async () => false as boolean }

const {
  selectedPageKey,
  selectedPageIdentity,
  selectedActions,
  selectedApiAssets,
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
  bindingResult,
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
  resetWizardProgressFromDraft,
} = usePageAssistantWizardUiState()

const {
  projectCode,
  project,
  pageRegistry,
  pageActions,
  apiAssets,
  modelOptions,
  loadAll,
} = usePageAssistantWizardData({
  selectedPageIdentity,
  selectedPageKey,
  selectedActions,
  modelInstanceId,
  agentName,
  requirement,
  resetWizardProgressFromDraft,
  loadPageAssistantSessions: (options) => sessionLoader.load(options),
  defaultRequirement: () => requirementBuilder.build(),
})

const selectedPage = computed(() =>
  pageRegistry.value.find((page: PageRegistryView) =>
    selectedPageIdentity.value
      ? pageIdentity(page) === selectedPageIdentity.value
      : page.pageKey === selectedPageKey.value,
  ) || null,
)

const filteredActions = computed(() =>
  pageActions.value.filter((action) => !selectedPageKey.value || action.pageKey === selectedPageKey.value),
)

const {
  pageAssistantAccessGroups,
  pageAssistantAccessCount,
  pageAssistantActionKeys,
  pageAssistantSessionSteps,
  pageAssistantAccessDetailVisible,
  pageAssistantProgressText,
  aiCodingAccessState,
  pageAssistantScaffoldCommand,
  pageAssistantVerifyCommand,
  pageAssistantOnboardingPrompt,
} = usePageAssistantOnboarding({
  aiPromptTool,
  project,
  projectCode,
  selectedPage,
  selectedPageKey,
  selectedActions,
  filteredActions,
  pageAssistantManifest,
  pageAssistantSession,
  pageAssistantSessions,
  selectedPageAssistantAccess,
})

const {
  pageAssistantManifestLoading,
  pageAssistantCheckRunning,
  pageAssistantSessionsLoading,
  loadPageAssistantSessions,
  loadPageAssistantManifest,
  refreshPageAssistantSession,
  runPageAssistantSelfCheck,
  runPageAssistantCardCheck,
} = usePageAssistantSessions({
  aiPromptTool,
  project,
  selectedPage,
  selectedPageKey,
  pageActions,
  pageAssistantActionKeys,
  pageAssistantManifest,
  pageAssistantSession,
  pageAssistantSessions,
  selectedPageAssistantAccess,
})

sessionLoader.load = loadPageAssistantSessions

const { goBack, enterWorkflowStudio, openAiCodingWorkflowStudio: navigateToAiCodingStudio } = usePageAssistantWizardNavigation({
  projectCode,
  bindingResult,
  createdWorkflowId,
})

const {
  isAiCodingWorkflowSelected,
  displayedStep,
  canGoPrev,
  canGoNext,
  stats,
  steps,
  focusStepCard,
  selectStep,
  goPrevStep,
  goNextStep,
  handleWizardWheel,
} = usePageAssistantWizardSteps({
  pageRegistry,
  pageActions,
  apiAssets,
  selectedPageKey,
  selectedPage,
  selectedActions,
  focusedStep,
  statusStripRef,
  pagePanelRef,
  stepTransitionName,
  stepAttentionName,
  draftPreview,
  draftSource,
  createdWorkflowId,
  bindingResult,
  pageCopilotAgent,
  resetWizardProgressFromDraft,
})

stepSelector.fn = selectStep

const draftConfig = usePageAssistantWizardDraftConfig({
  projectCode,
  pageActions,
  selectedPageKey,
  selectedPageIdentity,
  selectedPage,
  selectedActions,
  assistantGoal,
  agentName,
  resetWizardProgressFromDraft,
  selectStep: (key) => stepSelector.fn(key),
})

requirementBuilder.build = draftConfig.defaultRequirement

const {
  defaultRequirement,
  pageAssistantWorkflowKeySlug,
  pageAssistantWorkflowName,
  actionCount,
  actionRowKey,
  isActionSelected,
  toggleActionSelection,
  selectAllFilteredActions,
  clearFilteredActions,
  selectPage,
} = draftConfig

const draftIssues = computed(() => buildDraftIssues(draftPreview.value))
const draftIssueCount = computed(() => draftIssues.value.length)
const draftNodeCount = computed(() => draftPreview.value?.graphSpec?.nodes?.length || 0)
const draftEdgeCount = computed(() => draftPreview.value?.graphSpec?.edges?.length || 0)
const selectedModelLabel = computed(() =>
  buildSelectedModelLabel(modelOptions.value, modelInstanceId.value),
)

const {
  workflowAiCodingDraftStep,
  workflowAiCodingDraftEvidence,
  workflowAiCodingValidationSummary,
  workflowAiCodingPageAssistantValidationSummary,
  workflowAiCodingRuntimeVerificationSummary,
  workflowAiCodingPrompt,
} = usePageAssistantWorkflowAiCoding({
  workflowAiCodingPromptTool,
  project,
  projectCode,
  pageAssistantManifest,
  pageAssistantSession,
  aiCodingAccessState,
  selectedPage,
  selectedPageKey,
  selectedActions,
  requirement,
  modelInstanceId,
  defaultRequirement,
  pageAssistantWorkflowName,
  pageAssistantWorkflowKeySlug,
})

const {
  generating,
  creatingWorkflow,
  bindingAgent,
  loadPageCopilotAgent,
  generateDraft,
  confirmCreateWorkflow,
  bindToPageCopilot,
} = usePageAssistantWorkflowLifecycle({
  project,
  projectCode,
  pageRegistry,
  selectedPage,
  selectedPageKey,
  selectedActions,
  selectedApiAssets,
  agentName,
  requirement,
  modelInstanceId,
  draftPreview,
  draftSource,
  createdWorkflowId,
  bindingResult,
  pageCopilotAgent,
  draftIssueCount,
  defaultRequirement,
  pageAssistantWorkflowName,
  pageAssistantWorkflowKeySlug,
  confirmSwitchToPlatformGeneration: () => platformSwitch.confirm(),
  selectStep,
})

copilotLoader.load = loadPageCopilotAgent

const {
  manualDialogVisible,
  manualSubmitting,
  manualForm,
  submitManualAction,
} = usePageAssistantManualAction({
  projectCode,
  selectedPageKey,
  selectedPageIdentity,
  focusedStep,
  loadAll,
  pageIdentity: draftConfig.pageIdentity,
})

const sdkTemplate = computed(() => {
  const pageKey = selectedPageKey.value || manualForm.pageKey || 'example.list'
  const actionKey = selectedActions.value[0]?.actionKey || manualForm.actionKey || 'example.search'
  return `pageBridge.registerAction({\n  pageKey: '${pageKey}',\n  actionKey: '${actionKey}',\n  title: '查询当前列表',\n  inputSchema: {\n    type: 'object',\n    properties: {\n      keyword: { type: 'string', description: '筛选关键字' }\n    }\n  },\n  sampleArgs: { keyword: '示例' },\n  handler: async (args) => {\n    // 调用当前页面已有查询函数，并返回执行结果\n    return await queryList(args)\n  }\n})`
})
const highlightedSdkTemplate = computed(() => highlightSdkCode(sdkTemplate.value))

const {
  sdkTemplateCopied,
  aiPromptCopied,
  workflowAiCodingPromptCopied,
  copySdkTemplate,
  copyPageAssistantPrompt,
  copyWorkflowAiCodingPrompt,
  copyText,
} = usePageAssistantClipboard({
  sdkTemplate,
  pageAssistantOnboardingPrompt,
  workflowAiCodingPrompt,
  pageAssistantSession,
  project,
  loadPageAssistantManifest,
})

const {
  pageAccessTitle,
  openAiPromptDialog,
  openWorkflowAiCodingPromptDialog,
  refreshWorkflowAiCodingDraftStatus,
  openAiCodingWorkflowStudio,
  resetAiCodingWorkflowDraft,
  confirmSwitchToPlatformGeneration,
  useAiCodingWorkflowDraft,
  usePageAssistantAccess,
} = usePageAssistantWizardAiActions({
  project,
  pageRegistry,
  pageActions,
  selectedPageKey,
  selectedPageIdentity,
  selectedPage,
  selectedActions,
  agentName,
  requirement,
  draftPreview,
  draftSource,
  createdWorkflowId,
  bindingResult,
  pageCopilotAgent,
  aiPromptDialogVisible,
  workflowAiCodingPromptDialogVisible,
  workflowAiCodingResetting,
  pageAssistantSession,
  pageAssistantManifest,
  selectedPageAssistantAccess,
  workflowAiCodingPromptCopied,
  workflowAiCodingDraftEvidence,
  resetWizardProgressFromDraft,
  selectStep,
  loadPageAssistantSessions,
  loadPageAssistantManifest,
  refreshPageAssistantSession,
  loadPageCopilotAgent: () => copilotLoader.load(),
  openAiCodingWorkflowStudio: navigateToAiCodingStudio,
  defaultRequirement,
})

platformSwitch.confirm = confirmSwitchToPlatformGeneration

const assistantGoalOptions = ASSISTANT_GOAL_OPTIONS

function showSessionDetail(session: typeof selectedPageAssistantAccess.value) {
  selectedPageAssistantAccess.value = session
  pageAssistantAccessDetailVisible.value = true
}

watch([selectedPage, assistantGoal], () => {
  agentName.value = agentName.value || `${selectedPage.value?.name || project.value?.name || projectCode.value}页面助手`
  requirement.value = defaultRequirement()
})

watch(aiPromptDialogVisible, (visible) => {
  if (visible) {
    void loadPageAssistantManifest()
  }
})

watch([selectedPageKey, pageAssistantActionKeys, aiPromptTool], () => {
  if (aiPromptDialogVisible.value) {
    void loadPageAssistantManifest({ silent: true })
  }
})

watch(displayedStep, (step) => {
  if (step === 'bind' && createdWorkflowId.value) {
    void loadPageCopilotAgent()
  }
})

onMounted(loadAll)
</script>

<style scoped lang="scss">
@use './styles/PageAssistantWizard.scss';
</style>
