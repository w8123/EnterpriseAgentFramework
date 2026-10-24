<template>
  <div class="studio-page">
    <StudioHeader
      :display-name="studioDisplayName"
      :visibility-label="workflowVisibilityLabel"
      :validation-label="studioValidationLabel"
      :validation-tag-type="studioValidationTagType"
      :key-slug="studioKeySlug"
      :project-code="studio?.projectCode || ''"
      :save-state-class="saveStateClass"
      :save-badge-text="saveBadgeText"
      :read-only="studioReadOnly"
      :view-only="!canWriteWorkflow"
      :hide-run="!canDebugWorkflow"
      :debug-loading="debugLoading"
      :read-only-trial="hasReadOnlyTrialTarget"
      :validating="validating"
      :saving="saving"
      :release-checking="releaseChecking"
      :show-page-assistant-sync="isPageAssistantWorkflow && !!studio?.activeVersion"
      :page-assistant-syncing="pageAssistantSyncing"
      @back="router.push('/workflows')"
      @run="handleStudioRun"
      @validate="validateRuntime()"
      @command="handleHeaderCommand"
      @save="handleSaveStudio"
      @sync-page-assistant="syncPublishedPageAssistant()"
      @publish="publishWorkflow"
    />

    <div
      class="studio-body"
      :class="{ 'property-collapsed': propertyPanelCollapsed }"
      :style="{ '--studio-property-width': `${propertyPanelWidth}px` }"
    >
      <StudioPalette
        :read-only="studioReadOnly"
        @open-search="nodeSearchOpen = true"
      />

      <section class="canvas-wrap">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :fit-view-on-init="true"
          :default-viewport="{ zoom: 0.9 }"
          :min-zoom="0.2"
          :max-zoom="1.2"
          :default-edge-options="defaultEdgeOptions"
          :connection-line-options="connectionLineOptions"
          :elevate-edges-on-select="true"
          :delete-key-code="['Delete', 'Backspace']"
          :nodes-draggable="!studioReadOnly"
          :nodes-connectable="!studioReadOnly"
          class="studio-canvas"
          @connect="onConnect"
          @node-click="onNodeClick"
          @node-double-click="onNodeDoubleClick"
          @edge-click="onEdgeClick"
          @pane-click="clearSelection"
          @nodes-change="handleCanvasNodesChange"
          @edges-change="handleCanvasEdgesChange"
        >
          <Background />
          <MiniMap />

          <template v-for="kind in studioCanvasNodeKinds" :key="kind" #[`node-${kind}`]="nodeProps">
            <StudioNodeCard
              :model="studioNodeCardModel(kind, nodeProps.id, nodeProps.data, nodeCardContext)"
              :collapsed="nodeProps.data.collapsed"
              :output-alias="nodeProps.data.outputAlias"
              :run-class="nodeRunClass(nodeProps.id)"
              :run-label="nodeDebugState(nodeProps.id) ? nodeRunLabel(nodeProps.id) : ''"
              @open-trace="openNodeTrace(nodeProps.id)"
            />
          </template>
        </VueFlow>

        <StudioCanvasToolbar
          v-if="!canvasOperatorCollapsed"
          :read-only="studioReadOnly"
          :can-undo="canUndo"
          :can-redo="canRedo"
          :can-copy="canCopySelectedNode"
          :can-paste="!!copiedNode"
          :has-selected-node="!!selectedNode"
          :layout-running="layoutRunning"
          @search="openCanvasSearch"
          @undo="undoCanvas"
          @redo="redoCanvas"
          @copy="copySelectedNode"
          @paste="pasteCopiedNode"
          @toggle-collapse="toggleSelectedNodeCollapsed"
          @layout="handleAutoLayout"
          @fit="handleFitView"
          @zoom-out="handleZoomOut"
          @zoom-in="handleZoomIn"
        />

        <StudioAiEditBar
          v-if="canWriteWorkflow && !aiEditMinimized"
          v-model:instruction="aiEditInstruction"
          v-model:model-instance-id="aiModelInstanceId"
          :read-only="studioReadOnly"
          :loading="aiEditLoading"
          :debug-open="debugOpen"
          :preview="aiEditPreview"
          :preview-failed="aiEditPreviewFailed"
          :operation-groups="aiEditOperationGroups"
          :model-options="authoringModelOptions"
          :model-options-loading="modelOptionsLoading"
          :model-options-load-error="modelOptionsLoadError"
          :selected-model-label="selectedAiEditModelLabel"
          :resolved-model-provider="selectedAiEditModel?.provider || ''"
          @run="runAiAuthoring()"
          @apply="applyAiEditPreview"
          @clear="clearAiEditPreview"
          @regenerate="regenerateAiAuthoring"
          @minimize="aiEditMinimized = true"
          @load-models="loadModelOptions"
        />

        <div v-if="canvasSearchOpen" class="canvas-search-panel">
          <el-input
            ref="canvasSearchInputRef"
            v-model="canvasSearchKeyword"
            :prefix-icon="Search"
            clearable
            placeholder="搜索节点名称、类型、描述或引用"
            @keyup.enter="focusNextCanvasSearch"
            @keyup.esc="closeCanvasSearch"
          />
          <span class="canvas-search-count">
            {{ canvasSearchMatches.length ? `${canvasSearchIndex + 1}/${canvasSearchMatches.length}` : canvasSearchKeyword ? '无结果' : '搜索画布' }}
          </span>
          <el-button text :icon="ArrowUp" :disabled="!canvasSearchMatches.length" @click="focusPrevCanvasSearch" />
          <el-button text :icon="ArrowDown" :disabled="!canvasSearchMatches.length" @click="focusNextCanvasSearch" />
          <el-button text :icon="Close" @click="closeCanvasSearch" />
        </div>

        <StudioInspector
          v-if="activeInspectorPanel"
          :panel="activeInspectorPanel"
          :validation-label="studioValidationLabel"
          :validation-tag-type="studioValidationTagType"
          :validation-valid="!!validation?.valid"
          :validation-request-error="validationRequestError"
          :graph-lint-items="graphLintItems"
          :runtime-validation-items="runtimeValidationItems"
          :graph-variables="graphVariables"
          @close="activeInspectorPanel = null"
          @focus-lint="focusLintItem"
          @focus-runtime="focusRuntimeValidationItem"
          @select-node="selectedNodeId = $event"
        />
        <StudioStatusBar
          :tools-open="!canvasOperatorCollapsed"
          :inspector-panel="activeInspectorPanel"
          :validation-label="studioValidationLabel"
          :validation-tag-type="studioValidationTagType"
          :variable-count="graphVariables.length"
          :ai-open="canWriteWorkflow && !aiEditMinimized"
          :hide-ai="!canWriteWorkflow"
          @toggle-tools="toggleCanvasOperator"
          @toggle-inspector="toggleInspectorPanel"
          @toggle-ai="toggleAiEditBar"
        />
      </section>

      <StudioPropertyPanel
        v-model:collapsed="propertyPanelCollapsed"
        v-model:width="propertyPanelWidth"
        v-model:tab="propertyPanelTab"
        v-model:debug-message="nodeDebugMessage"
        v-model:debug-state-text="nodeDebugStateText"
        :read-only="studioReadOnly"
        :read-only-reason="canWriteWorkflow ? '' : '你的角色只能查看这个 Workflow，不能编辑或发布。'"
        :node="selectedNode"
        :edge="selectedEdge"
        :edge-label="edgeDisplayLabel(selectedEdge?.condition || selectedEdge?.label, selectedEdge) || ''"
        :edge-condition-options="selectedEdgeConditionOptions"
        :edge-allows-custom-route="selectedEdgeAllowsCustomRoute"
        :canvas-nodes="nodes"
        :meta="workflowMeta"
        :model-options="authoringModelOptions"
        :model-options-loading="modelOptionsLoading"
        :model-options-load-error="modelOptionsLoadError"
        :kind-color="kindColor"
        :node-icon="nodeIcon"
        :debug-loading="nodeDebugLoading"
        :debug-result="nodeDebugResult"
        :trace="selectedNodeTrace"
        :can-read-published-runs="canReadPublishedRuns"
        @mark-dirty="markCanvasDirty"
        @edge-condition-change="syncSelectedEdgeLabel"
        @delete-node="deleteSelectedNode"
        @load-model-options="loadModelOptions"
        @run-node-debug="handleRunNodeDebug"
        @open-published-runs="openPublishedRuns"
      >
        <template #node-config="{ node }">
          <NodeConfigPanel
            :key="node.id"
            :data="node.data"
            :node-id="node.id"
            :canvas-nodes="nodes"
            :model-options="modelOptions"
            :model-options-loading="modelOptionsLoading"
            :model-options-load-error="modelOptionsLoadError"
            :knowledge-options="knowledgeOptions"
            :tool-options="availableTools"
            :tool-options-loading="toolOptionsLoading"
            :tool-options-load-error="toolOptionsLoadError"
            :variable-options="variableOptions"
            :credential-options="credentialOptions"
            :project-id="studio?.projectId || null"
            :project-code="studio?.projectCode || null"
            :request-scope-key="businessMethodRequestScope"
            :node-type-options="nodeTypes"
            @credential-created="handleCredentialCreated"
            @create-call-node="handleCreateInteractionCallNode"
            @retry-methods="loadToolOptions()"
            @validation-change="handlePanelValidationChange"
            @reload-model-options="loadModelOptions"
          />
        </template>
      </StudioPropertyPanel>
    </div>

    <AppDialog
      v-model="nodeSearchOpen"
      title="添加节点"
      width="840px"
      class="node-picker-dialog"
      destroy-on-close
    >
      <div class="node-picker-container">
        <!-- 顶部通栏搜索 -->
        <div class="node-picker-search-bar">
          <el-input
            v-model="nodeSearchKeyword"
            :prefix-icon="Search"
            clearable
            autofocus
            placeholder="搜索节点名称、功能或场景（例如：大模型、接口、条件、审批）..."
            class="node-picker-search-input"
          />
        </div>

        <!-- 主体：左侧分类导航 + 右侧内容面板 -->
        <div class="node-picker-body">
          <!-- 左侧分类侧边栏 -->
          <aside class="node-picker-sidebar">
            <div class="sidebar-category-list">
              <button
                class="sidebar-nav-item"
                :class="{ active: selectedCategoryFilter === 'ALL' }"
                type="button"
                @click="selectedCategoryFilter = 'ALL'"
              >
                <span class="nav-item-icon">
                  <el-icon><Grid /></el-icon>
                </span>
                <span class="nav-item-label">全部节点</span>
                <span class="nav-item-count">{{ totalNodeCount }}</span>
              </button>

              <div class="sidebar-divider"></div>

              <button
                v-for="group in paletteGroups"
                :key="group.title"
                class="sidebar-nav-item"
                :class="{ active: selectedCategoryFilter === group.title }"
                type="button"
                @click="selectedCategoryFilter = group.title"
              >
                <span class="nav-item-icon">
                  <el-icon><component :is="group.icon" /></el-icon>
                </span>
                <span class="nav-item-label">{{ group.title }}</span>
                <span class="nav-item-count">{{ group.items.length }}</span>
              </button>
            </div>
          </aside>

          <!-- 右侧卡片内容区 -->
          <main class="node-picker-content">
            <div v-if="displayedPaletteGroups.length === 0" class="node-picker-empty">
              <el-empty description="未找到匹配的节点，请更换搜索词" :image-size="72" />
            </div>

            <div
              v-for="group in displayedPaletteGroups"
              :key="group.title"
              class="node-group-section"
            >
              <!-- 极简现代分组标题行 -->
              <div
                class="node-group-header"
                @click="toggleGroupCollapse(group.title)"
              >
                <div class="group-title-left">
                  <el-icon
                    class="group-collapse-arrow"
                    :class="{ rotated: isGroupExpanded(group.title) }"
                  >
                    <ArrowRight />
                  </el-icon>
                  <span class="group-title-text">{{ group.title }}</span>
                  <span class="group-title-count">{{ group.items.length }}</span>
                </div>
                <div class="group-title-action">
                  {{ isGroupExpanded(group.title) ? '收起' : '展开' }}
                </div>
              </div>

              <!-- 组内卡片网格 -->
              <div
                v-show="isGroupExpanded(group.title)"
                class="node-card-grid"
              >
                <button
                  v-for="item in group.items"
                  :key="item.kind"
                  class="node-card"
                  type="button"
                  @click="addNodeFromSearch(item.kind)"
                >
                  <div
                    class="node-icon-box"
                    :style="{ background: kindColor(item.kind).bg, color: kindColor(item.kind).border }"
                  >
                    <el-icon><component :is="item.icon" /></el-icon>
                  </div>
                  <div class="node-details">
                    <div class="node-title-row">
                      <span class="node-name">{{ item.label }}</span>
                      <span class="node-tag">{{ item.meta }}</span>
                    </div>
                    <div class="node-desc" :title="item.hint">{{ item.hint }}</div>
                  </div>
                </button>
              </div>
            </div>
          </main>
        </div>
      </div>
    </AppDialog>

    <WorkflowStudioReadOnlyTrialDrawer
      v-model:open="readOnlyTrialOpen"
      :studio="studio"
      :dirty="saving || visualDirty || workflowMetaDirty() || (jsonDrawerVisible && sourceBufferChanged)"
      :saving="saving"
      :authorized="readOnlyTrialAuthorized"
      :session-scope="platformSessionId + ':' + workflowId"
      @open-runops="router.push('/runops/' + $event)"
      @focus-node="focusReadOnlyTrialNode"
      @save="handleSaveStudio"
    />

    <WorkflowStudioDebugDrawer
      v-model:open="debugOpen"
      v-model:replay-trace-input="replayTraceInput"
      v-model:selected-recent-trace-id="selectedRecentTraceId"
      v-model:debug-message="debugMessage"
      :variable-preview="variablePreview"
      :run-result="debugRunResult"
      :result="debugResult"
      :session="debugSession"
      :conversation-snapshot="debugConversationSnapshot"
      :initial-chat-field="workflowInitialChatField"
      :initial-ui-request="workflowInitialUiRequest"
      :debug-input-params="debugInputParams"
      :steps="debugSessionSteps"
      :selected-step-index="selectedDebugStepIndex"
      :current-trace-id="currentTraceId"
      :trace-nodes="traceNodes"
      :recent-runs="studioRecentRuns"
      :recent-runs-placeholder="studioRecentRunsPlaceholder"
      :replay-summary="workflowReplaySummary"
      :node-trace-list="nodeTraceList"
      :ops-items="debugOpsItems"
      :debug-loading="debugLoading"
      :trace-replay-loading="traceReplayLoading"
      :recent-runs-loading="recentRunsLoading"
      :conversation-busy="isDebugConversationBusy"
      :recent-run-label="recentRunLabel"
      :is-step-running="isDebugStepRunning"
      :waiting-output="debugWaitingOutput"
      @run-published="handleRunPublishedDebug"
      @load-trace-replay="handleLoadTraceReplay()"
      @clear-trace-replay="clearTraceReplay"
      @recent-trace-change="handleRecentTraceChange"
      @load-recent-runs="loadRecentStudioRuns"
      @open-runops="router.push('/runops/' + $event)"
      @open-node-trace="openNodeTrace"
      @cancel-session="handleCancelDebugSession"
      @restore-session="handleRestoreDebugSession"
      @interaction-submit="handleDebugInteractionSubmit"
      @interaction-cancel="handleDebugInteractionCancel"
      @run-working-copy="handleRunWorkingCopyDebug"
      @clear-session-view="clearDebugSessionView"
      @select-step="selectDebugStep"
    />

    <WorkflowStudioEvalTargetDialog
      v-model:open="evalTargetOpen"
      :targets="evalTargets"
      :hidden-count="hiddenEvalTargetCount"
      :load-error="evalTargetsLoadError"
      @select="openAgentEval"
      @open-overview="openEvalOverview"
    />

    <WorkflowStudioSourceDrawer
      v-model:open="jsonDrawerVisible"
      v-model:active-tab="activeTab"
      v-model:graph-spec-source="graphSpecSourceBuffer"
      v-model:canvas-source="canvasSourceBuffer"
      :read-only="studioReadOnly"
      :applying="jsonApplying"
      :changed="sourceBufferChanged"
      @reset="resetSourceBuffer"
      @apply="applySourceBuffer"
    />


    <WorkflowStudioPublishDialog
      v-model:open="publishDialogOpen"
      :publishing="publishing"
      :release-checking="releaseChecking"
      :release-validation-ready="releaseValidationReady"
      :release-errors="releaseErrors"
      :release-warnings="releaseWarnings"
      :publish-warnings="publishWarnings"
      :form="publishForm"
      :before-close="handlePublishDialogBeforeClose"
      :release-validation-key="releaseValidationKey"
      @publish="handlePublishWorkflow"
    />

    <WorkflowStudioVersionsDialog
      v-model="versionsDialogOpen"
      :workflow-id="workflowId"
      :workflow-name="studioDisplayName"
      :project-code="studio?.projectCode || ''"
      :base-revision="studio?.updatedAt"
      @rollback-success="handleRollbackSuccess"
    />
  </div>
</template>
<script setup lang="ts">
import AppDialog from '@/components/common/AppDialog.vue'
import { useWorkflowStudioTraceProjection } from '@/views/workflow/composables/useWorkflowStudioTraceProjection'
import { debugWaitingOutput } from '@/views/workflow/composables/workflowStudioTrace'
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowDown,
  ArrowRight,
  ArrowUp,
  Close,
  Delete,
  Grid,
  Search,
} from '@element-plus/icons-vue'
import { VueFlow, useVueFlow, ConnectionLineType } from '@vue-flow/core'
import type { EdgeMouseEvent, NodeMouseEvent } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { MiniMap } from '@vue-flow/minimap'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/minimap/dist/style.css'
import {
  attachPageAssistantWorkflowTool,
  getWorkflowDebugSession,
  getWorkflowDebugSessionByCreationKey,
} from '@/api/workflow'
import type { TraceNode } from '@/types/trace'
import type { RunDetail, RunSummary } from '@/types/runops'
import type { ChatResponse } from '@/types/chat'
import type {
  WorkflowDebugRunResult,
  WorkflowProposalEditResult,
  WorkflowNodeDebugResult,
  WorkflowPublishRequest,
  WorkflowReleaseValidationItem,
  WorkflowRuntimeValidationResult,
  WorkflowWorkingCopyState,
  WorkflowDebugSessionView,
  WorkflowDebugStepResult,
} from '@/types/workflow'
import type {
  CanvasEdge,
  CanvasNode,
  CanvasNodeKind,
  InteractionCallNodeRequest,
  StudioVariableOption,
} from '@/types/studio'
import type { ModelInstance } from '@/types/model'
import {
  createWorkflowCanvasNode,
  workflowCanvasToSaveRequest,
  workflowStudioToCanvas,
} from '@/utils/workflowStudio'
import { resolveStudioNodeCreation } from '@/utils/studioNodeRegistry'
import { runMatchesCurrentWorkflow } from '@/utils/workflowRunOps'
import StudioNodeCard from '@/views/workflow/studio-canvas/StudioNodeCard.vue'
import StudioAiEditBar from '@/views/workflow/studio-canvas/StudioAiEditBar.vue'
import StudioInspector from '@/views/workflow/studio-canvas/StudioInspector.vue'
import StudioHeader from '@/views/workflow/studio-canvas/StudioHeader.vue'
import StudioPalette from '@/views/workflow/studio-canvas/StudioPalette.vue'
import StudioCanvasToolbar from '@/views/workflow/studio-canvas/StudioCanvasToolbar.vue'
import StudioPropertyPanel from '@/views/workflow/studio-canvas/StudioPropertyPanel.vue'
import {
  loadPropertyWidth,
  savePropertyWidth,
  type StudioPropertyTab,
} from '@/views/workflow/studio-canvas/studioPropertyPanel'
import StudioStatusBar from '@/views/workflow/studio-canvas/StudioStatusBar.vue'
import {
  studioNodeCardModel,
  type StudioNodeCardContext,
} from '@/views/workflow/studio-canvas/studioNodeCardModel'
import type { StudioFieldSchema } from '@/types/studio'
import {
  WORKFLOW_LAYOUT_DEFAULT_HANDLE_KEY,
  type WorkflowNodeMeasurement,
} from '@/utils/workflowAutoLayout'
import NodeConfigPanel from '@/views/workflow/studio-panels/NodeConfigPanel.vue'
import WorkflowStudioDebugDrawer from '@/views/workflow/studio-overlays/WorkflowStudioDebugDrawer.vue'
import WorkflowStudioReadOnlyTrialDrawer from '@/views/workflow/studio-overlays/WorkflowStudioReadOnlyTrialDrawer.vue'
import { hasReadOnlyTrialTarget as graphHasReadOnlyTrialTarget } from '@/views/workflow/composables/useWorkflowStudioReadOnlyTrial'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_WORKFLOW_DEBUG, PLATFORM_PERMISSION_RUNOPS_READ,
  PLATFORM_PERMISSION_CAPABILITY_INVOKE, PLATFORM_PERMISSION_WORKFLOW_WRITE } from '@/auth/platformAccess'
import WorkflowStudioEvalTargetDialog from '@/views/workflow/studio-overlays/WorkflowStudioEvalTargetDialog.vue'
import WorkflowStudioPublishDialog from '@/views/workflow/studio-overlays/WorkflowStudioPublishDialog.vue'
import WorkflowStudioSourceDrawer from '@/views/workflow/studio-overlays/WorkflowStudioSourceDrawer.vue'
import WorkflowStudioVersionsDialog from '@/views/workflow/studio-overlays/WorkflowStudioVersionsDialog.vue'
import { useWorkflowStudioNodeMetadata } from '@/views/workflow/composables/useWorkflowStudioNodeMetadata'
import { useWorkflowStudioPalette } from '@/views/workflow/composables/useWorkflowStudioPalette'
import { useWorkflowStudioDebugSession } from '@/views/workflow/composables/useWorkflowStudioDebugSession'
import { isPlatformAuthenticated, platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { useWorkflowStudioSessionLifecycle } from '@/views/workflow/composables/useWorkflowStudioSessionLifecycle'
import { useWorkflowStudioCanvasSearch } from '@/views/workflow/composables/useWorkflowStudioCanvasSearch'
import { useWorkflowStudioRuntimeValidation } from '@/views/workflow/composables/useWorkflowStudioRuntimeValidation'
import { useWorkflowStudioPersistence } from '@/views/workflow/composables/useWorkflowStudioPersistence'
import { useWorkflowStudioPanelValidation } from '@/views/workflow/composables/useWorkflowStudioPanelValidation'
import { useWorkflowStudioRelease } from '@/views/workflow/composables/useWorkflowStudioRelease'
import { notifyStaleAgentReferences } from '@/views/workflow/composables/workflowAgentReferenceNotice'
import {
  useWorkflowStudioCanvasActions,
} from '@/views/workflow/composables/useWorkflowStudioCanvasActions'
import {
  useWorkflowStudioDebugRun,
} from '@/views/workflow/composables/useWorkflowStudioDebugRun'
import { useWorkflowStudioProposalContext } from '@/views/workflow/composables/useWorkflowStudioProposalContext'
import { useWorkflowStudioProposalActions } from '@/views/workflow/composables/useWorkflowStudioProposalActions'
import { useWorkflowStudioEvalLink } from '@/views/workflow/composables/useWorkflowStudioEvalLink'
import { useWorkflowStudioHistory } from '@/views/workflow/composables/useWorkflowStudioHistory'
import { useWorkflowStudioResources } from '@/views/workflow/composables/useWorkflowStudioResources'
import {
  useWorkflowStudioGraphAnalysis,
  type GraphLintItem,
} from '@/views/workflow/composables/useWorkflowStudioGraphAnalysis'
import { formatJson, normalizeJson } from '@/views/workflow/composables/workflowStudioJson'

const route = useRoute()
const router = useRouter()
const { fitView, setCenter, zoomIn, zoomOut, getViewport, findNode } = useVueFlow()
const workflowId = computed(() => String(route.params.workflowId || route.params.id || ''))
const studio = ref<WorkflowWorkingCopyState | null>(null)
const graphSpecJson = ref('')
const canvasJson = ref('')
const emptyWorkspaceGraphSpec = formatJson('{"schemaVersion":2,"nodes":[],"edges":[],"entryNodeId":"","exitNodeIds":[]}')
const emptyWorkspaceCanvas = formatJson('{"schemaVersion":1,"layoutVersion":1,"nodes":[],"edges":[]}')
const activeTab = ref('visual')
const loading = ref(false)
const saving = ref(false)
const validating = ref(false)
const publishing = ref(false)
const pageAssistantSyncing = ref(false)
const releaseChecking = ref(false)
const releaseValidationReady = ref(false)
const layoutRunning = ref(false)
const canvasSearchOpen = ref(false)
const canvasSearchKeyword = ref('')
const canvasSearchIndex = ref(0)
const canvasSearchInputRef = ref()
const nodes = ref<CanvasNode[]>([])
const edges = ref<CanvasEdge[]>([])
const selectedNodeId = ref<string | null>(null)
const selectedEdgeId = ref<string | null>(null)
const visualDirty = ref(false)
const editGeneration = ref(0)
const copiedNode = ref<CanvasNode | null>(null)
const historyPast = ref<string[]>([])
const historyFuture = ref<string[]>([])
const historyApplying = ref(false)
const historyReady = ref(false)
const validation = ref<WorkflowRuntimeValidationResult | null>(null)
const validationRequestError = ref('')
const debugNodeId = ref('')
const debugMessage = ref('这是一条测试消息')
const nodeDebugMessage = ref('这是一条节点测试消息')
const nodeDebugStateJson = ref('{}')
const nodeDebugStateText = ref('{\n  "input": "这是一条节点测试消息"\n}')
const nodeDebugLoading = ref(false)
const debugLoading = ref(false)
const nodeDebugResult = ref<WorkflowNodeDebugResult | null>(null)
const debugRunResult = ref<WorkflowDebugRunResult | null>(null)
const debugSession = ref<WorkflowDebugSessionView | null>(null)
const debugResult = ref<ChatResponse | null>(null)
const debugInputParams = reactive<Record<string, unknown>>({})
const currentTraceId = ref('')
const traceNodes = ref<TraceNode[]>([])
const runOpsDetail = ref<RunDetail | null>(null)
const replayTraceInput = ref('')
const selectedRecentTraceId = ref('')
const traceReplayLoading = ref(false)
const recentRunsLoading = ref(false)
const recentRuns = ref<RunSummary[]>([])
const selectedDebugStepIndex = ref<number | null>(null)
const currentDebugNodeId = ref('')
const debugPlaybackToken = ref(0)
const aiModelInstanceId = ref('')
const aiEditInstruction = ref('')
const aiEditLoading = ref(false)
const aiEditMinimized = ref(false)
const aiEditPreview = ref<WorkflowProposalEditResult | null>(null)
const propertyPanelCollapsed = ref(true)
const canvasOperatorCollapsed = ref(true)
type InspectorPanel = 'validation' | 'variables'
const activeInspectorPanel = ref<InspectorPanel | null>(null)

function toggleInspectorPanel(panel: InspectorPanel) {
  const nextPanel = activeInspectorPanel.value === panel ? null : panel
  activeInspectorPanel.value = nextPanel
  if (nextPanel) {
    aiEditMinimized.value = true
    canvasOperatorCollapsed.value = true
  }
}

function toggleAiEditBar() {
  const nextMinimized = !aiEditMinimized.value
  aiEditMinimized.value = nextMinimized
  if (!nextMinimized) {
    activeInspectorPanel.value = null
    canvasOperatorCollapsed.value = true
  }
}

function toggleCanvasOperator() {
  const nextCollapsed = !canvasOperatorCollapsed.value
  canvasOperatorCollapsed.value = nextCollapsed
  if (!nextCollapsed) {
    activeInspectorPanel.value = null
    aiEditMinimized.value = true
  }
}

const nodeSearchOpen = ref(false)
const nodeSearchKeyword = ref('')
const propertyPanelTab = ref<StudioPropertyTab>('config')
const propertyPanelWidth = ref(loadPropertyWidth())
watch(propertyPanelWidth, savePropertyWidth)
const jsonDrawerVisible = ref(false)
const graphSpecSourceBuffer = ref('')
const canvasSourceBuffer = ref('')
const jsonApplying = ref(false)
const debugOpen = ref(false)
const readOnlyTrialOpen = ref(false)
// D28: a draft that calls business methods or HTTP APIs can only be really run as a whole-draft
// read-only trial (ordinary debug refuses them), so the single 调试 entry routes there automatically.
const hasReadOnlyTrialTarget = computed(() => graphHasReadOnlyTrialTarget(graphSpecJson.value)
  || graphHasReadOnlyTrialTarget(studio.value?.graphSpecJson || ''))
const readOnlyTrialAuthorized = computed(() => [PLATFORM_PERMISSION_WORKFLOW_DEBUG,
  PLATFORM_PERMISSION_CAPABILITY_INVOKE].every((permission) => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants, permission, 'PROJECT', 'BUILD', studio.value?.projectCode,
)))
const publishDialogOpen = ref(false)
const versionsDialogOpen = ref(false)
const releaseErrors = ref<WorkflowReleaseValidationItem[]>([])
const releaseWarnings = ref<WorkflowReleaseValidationItem[]>([])
const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth)
const lastSavedAt = ref('--:--')
const publishForm = reactive<WorkflowPublishRequest>({
  version: 'v1.0.0',
  rolloutPercent: 100,
  note: '',
})
const workflowMeta = reactive({
  name: '',
  keySlug: '',
  workflowKind: '',
  description: '',
  defaultModelInstanceId: '',
})
const selectedToolName = computed(() => {
  const node = nodes.value.find((item) => item.id === selectedNodeId.value) || null
  return node?.data.kind === 'tool' ? node.data.toolConfig?.ref || '' : ''
})

const {
  nodeTypes,
  modelOptions,
  modelOptionsLoading,
  modelOptionsLoadError,
  knowledgeOptions,
  credentialOptions,
  toolOptionsLoading,
  toolOptionsLoadError,
  graphNodeTypeCapabilitiesLoaded,
  availableTools,
  authoringModelOptions,
  selectedAiEditModel,
  loadNodeTypes,
  loadToolOptions,
  loadModelOptions,
  loadGraphResourceOptions,
  loadCredentialOptions,
  handleCredentialCreated,
} = useWorkflowStudioResources({
  studio,
  aiModelInstanceId,
  selectedToolName,
})

// 没有本项目 workflow:write 的角色（开发人员、管理人员）以只读画布查看。
const canWriteWorkflow = computed(() => !studio.value || hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_WORKFLOW_WRITE, 'PROJECT', 'BUILD',
  studio.value.projectCode,
))

const canDebugWorkflow = computed(() => !studio.value || hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_WORKFLOW_DEBUG, 'PROJECT', 'BUILD',
  studio.value.projectCode,
))

const studioReadOnly = computed(() => {
  const authority = studio.value?.definitionAuthority
  const creationChannel = studio.value?.creationChannel
  return !canWriteWorkflow.value
    || authority === 'SDK' || authority === 'SYSTEM' || creationChannel === 'AI_QUICK_ACCESS'
})

const selectedAiEditModelLabel = computed(() =>
  selectedAiEditModel.value ? modelOptionLabel(selectedAiEditModel.value) : '',
)

const sourceBufferChanged = computed(() =>
  graphSpecSourceBuffer.value !== graphSpecJson.value
  || canvasSourceBuffer.value !== canvasJson.value,
)


const runtimeValidationItems = computed(() => [
  ...(validation.value?.errors || []).map((item) => ({ ...item, level: 'error' as const })),
  ...(validation.value?.warnings || []).map((item) => ({ ...item, level: 'warning' as const })),
])

const studioValidationLabel = computed(() => {
  const localBlockers = graphLintErrors.value.length
  const runtimeBlockers = validation.value?.errors.length || 0
  const warningCount = graphLintWarnings.value.length + (validation.value?.warnings?.length || 0)
  if (localBlockers || runtimeBlockers) return `${localBlockers + runtimeBlockers} 个阻断`
  if (validationRequestError.value) return '校验不可用'
  if (validation.value?.valid && warningCount) return `${warningCount} 个提醒`
  if (validation.value?.valid) return '可发布'
  if (warningCount) return `${warningCount} 个画布提醒`
  return '待校验'
})

const studioValidationTagType = computed(() => {
  if (graphLintErrors.value.length || validation.value?.errors.length || validationRequestError.value) return 'danger'
  if (graphLintWarnings.value.length || validation.value?.warnings?.length) return 'warning'
  return validation.value?.valid ? 'success' : 'info'
})

const saveBadgeText = computed(() => {
  if (saving.value) return '保存中'
  if (visualDirty.value || workflowMetaDirty()) return '待保存'
  if (studio.value?.hasUnpublishedChanges) {
    return studio.value.activeVersion ? '已保存，待发布' : '草稿已保存'
  }
  if (studio.value?.activeVersion?.version) return `已发布 ${studio.value.activeVersion.version}`
  if (studio.value?.status === 'ACTIVE') return '已发布'
  return lastSavedAt.value === '--:--' ? '已保存' : `已保存 ${lastSavedAt.value}`
})

const saveStateClass = computed(() => ({
  'is-saving': saving.value,
  'is-pending': !saving.value && (visualDirty.value || workflowMetaDirty()),
  'is-unpublished': !saving.value
    && !visualDirty.value
    && !workflowMetaDirty()
    && !!studio.value?.hasUnpublishedChanges,
  'is-saved': !saving.value
    && !visualDirty.value
    && !workflowMetaDirty()
    && !studio.value?.hasUnpublishedChanges,
}))


const defaultEdgeOptions = {
  type: 'smoothstep',
  markerEnd: 'arrowclosed',
  interactionWidth: 18,
}

const connectionLineOptions = {
  type: ConnectionLineType.SmoothStep,
}

function workflowHandleCenters(
  handles?: Array<{ id?: string | null; y: number; height: number }>,
) {
  return Object.fromEntries(
    (handles || []).map((handle) => [
      handle.id?.trim() || WORKFLOW_LAYOUT_DEFAULT_HANDLE_KEY,
      handle.y + handle.height / 2,
    ]),
  )
}

function getWorkflowNodeMeasurement(nodeId: string): WorkflowNodeMeasurement | undefined {
  const graphNode = findNode(nodeId)
  const width = graphNode?.dimensions.width || 0
  const height = graphNode?.dimensions.height || 0
  if (width <= 0 || height <= 0) return undefined
  return {
    width,
    height,
    sourceHandleCenters: workflowHandleCenters(graphNode?.handleBounds.source),
    targetHandleCenters: workflowHandleCenters(graphNode?.handleBounds.target),
  }
}

async function waitForWorkflowNodeMeasurements() {
  await nextTick()
  await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
  await nextTick()
}

const workflowVisibilityLabel = computed(() => {
  if (studio.value?.status === 'ARCHIVED') return '已归档'
  const activeVersion = studio.value?.activeVersion?.version
  if (!activeVersion) return studio.value?.status === 'ACTIVE' ? '已发布' : '尚未发布'
  return studio.value?.hasUnpublishedChanges
    ? `当前发布 ${activeVersion}`
    : `已发布 ${activeVersion}`
})

const isPageAssistantWorkflow = computed(() => studio.value?.workflowKind === 'PAGE_ASSISTANT')

const studioDisplayName = computed(() => studio.value?.name || workflowMeta.name || '未命名')

const studioKeySlug = computed(() => studio.value?.keySlug || workflowMeta.keySlug || '')

const {
  paletteGroups,
  filteredPaletteGroups,
  kindColor,
  nodeIcon,
} = useWorkflowStudioPalette({
  nodeSearchKeyword,
  nodeTypes,
  graphNodeTypeCapabilitiesLoaded,
})

const { nodeKindLabel } = useWorkflowStudioNodeMetadata()

// Every node kind renders through StudioNodeCard; VueFlow still needs one slot per node type.
const studioCanvasNodeKinds: CanvasNodeKind[] = [
  'start', 'end', 'userInput', 'interaction', 'pageAction', 'llm', 'tool', 'knowledge', 'condition',
  'variable', 'template', 'parameter', 'http', 'answer', 'code', 'classifier', 'aggregate', 'approval',
  'loop', 'knowledgeWrite', 'documentExtract', 'mcp',
]
const nodeCardContext = computed<StudioNodeCardContext>(() => ({
  defaultModelInstanceId: workflowMeta.defaultModelInstanceId,
  modelOptions: modelOptions.value,
  projectCode: studio.value?.projectCode,
}))

const {
  forgetDebugSession,
  debugCreationKey,
  setDebugCreationKey,
  debugSessionScope,
  applyDebugSession,
  clearDebugSessionView,
  loadStoredDebugSession,
} = useWorkflowStudioDebugSession({
  ownerScope: computed(() => isPlatformAuthenticated.value && platformSessionUser.value
    ? `platform:${platformSessionUser.value.userId}` : ''),
  workflowId,
  workflowKeySlug: computed(() => workflowMeta.keySlug),
  debugSession,
  debugRunResult,
  selectedDebugStepIndex,
  currentDebugNodeId,
  currentTraceId,
  replayTraceInput,
  selectedRecentTraceId,
  refreshWorkflowNodeClasses: () => refreshWorkflowNodeClasses(),
  getDebugSessionById: async (sessionId: string) => {
    const { data } = await getWorkflowDebugSession(sessionId)
    return data
  },
  getDebugSessionByCreationKey: async (key: string) => {
    const { data } = await getWorkflowDebugSessionByCreationKey(key)
    return data
  },
})

const {
  canvasSearchMatches,
  openCanvasSearch,
  closeCanvasSearch,
  focusCanvasSearchMatch,
  focusNextCanvasSearch,
  focusPrevCanvasSearch,
} = useWorkflowStudioCanvasSearch({
  canvasSearchOpen,
  canvasSearchKeyword,
  canvasSearchIndex,
  canvasSearchInputRef,
  nodes,
  selectedNodeId,
  selectedEdgeId,
  setCenter,
}, nextTick)

const {
  hasPanelValidationErrors,
  setPanelValidation,
  clearPanelValidation,
  prunePanelValidation,
  firstPanelValidationMessage,
} = useWorkflowStudioPanelValidation()

function ensurePanelValidationClear() {
  if (!hasPanelValidationErrors.value) return true
  ElMessage.error(firstPanelValidationMessage() || '节点配置存在未修正错误，请先修复后再保存/发布/应用')
  return false
}

const {
  resetWorkflowSessionState: resetWorkflowSessionStateBase,
  clearWorkflowDocumentState: clearWorkflowDocumentStateBase,
} = useWorkflowStudioSessionLifecycle({
  closeCanvasSearch,
  selectedNodeId,
  selectedEdgeId,
  debugNodeId,
  currentDebugNodeId,
  selectedDebugStepIndex,
  nodeDebugResult,
  debugRunResult,
  debugSession,
  debugResult,
  aiEditPreview,
  validation,
  visualDirty,
  historyPast,
  historyFuture,
  historyReady,
  studio,
  graphSpecJson,
  canvasJson,
  nodes,
  edges,
  emptyGraphSpecJson: emptyWorkspaceGraphSpec,
  emptyCanvasJson: emptyWorkspaceCanvas,
  resetHistorySnapshot: () => resetHistorySnapshot(),
  forgetDebugSession,
})

function resetWorkflowSessionState() {
  clearPanelValidation()
  resetWorkflowSessionStateBase()
}

function clearWorkflowDocumentState() {
  clearPanelValidation()
  clearWorkflowDocumentStateBase()
}

const selectedNodeTrace = computed(() =>
  selectedNodeId.value ? nodeTraceStates.value[selectedNodeId.value] || null : null,
)

const canReadPublishedRuns = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_RUNOPS_READ, 'PROJECT', 'OPERATE', studio.value?.projectCode,
))

function openPublishedRuns() {
  if (!workflowId.value || !canReadPublishedRuns.value) return
  router.push({ path: '/runops', query: {
    projectCode: studio.value?.projectCode || '', keyword: workflowId.value,
  } })
}

const selectedNode = computed(() =>
  nodes.value.find((node) => node.id === selectedNodeId.value) || null,
)

const selectedEdge = computed(() =>
  edges.value.find((edge) => edge.id === selectedEdgeId.value) || null,
)

const selectedEdgeSourceNode = computed(() =>
  selectedEdge.value ? nodes.value.find((node) => node.id === selectedEdge.value?.source) || null : null,
)

const selectedEdgeRouteOptions = computed(() => sourceRouteOptions(selectedEdgeSourceNode.value))

function sourceRouteOptions(source?: CanvasNode | null) {
  if (source?.data.kind === 'knowledge' && source.data.knowledgeConfig?.evidencePolicy === 'REQUIRED') {
    return [
      { value: 'route:evidence', label: '有证据' },
      { value: 'route:no_evidence', label: '无证据' },
    ]
  }
  if (source?.data.kind === 'condition') {
    const groups = source.data.conditionConfig?.groups || []
    return groups
      .filter((group) => group.id?.trim())
      .map((group) => ({
        value: `route:${group.id.trim()}`,
        label: group.label || `路由：${group.id}`,
      }))
  }
  if (source?.data.kind === 'classifier') {
    return (source.data.classifierConfig?.classes || [])
      .filter((item) => item.id?.trim())
      .map((item) => ({
        value: `route:${item.id.trim()}`,
        label: item.label || item.id,
      }))
  }
  if (source?.data.kind === 'approval') {
    return [
      { value: 'route:approved', label: '批准' },
      { value: 'route:rejected', label: '拒绝' },
      { value: 'route:timeout', label: '超时' },
    ]
  }
  // LOOP FOREACH v1: single always outgoing edge; no continue/done routes.
  return []
}

const selectedEdgeConditionOptions = computed(() => {
  if (selectedEdgeSourceNode.value?.data.kind === 'knowledge'
    && selectedEdgeSourceNode.value.data.knowledgeConfig?.evidencePolicy === 'REQUIRED') {
    return selectedEdgeRouteOptions.value
  }
  // Only conditions the Runtime evaluates; release validation rejects anything else on non-branch nodes.
  const routes = selectedEdgeRouteOptions.value
  return [
    { value: 'always', label: '默认' },
    { value: 'error', label: '失败时' },
    { value: 'empty', label: '结果为空' },
    { value: 'not_empty', label: '有结果' },
    ...(routes.length ? [{ value: 'else', label: '其他分支' }] : []),
    ...routes,
  ]
})

// Interaction, page-action and loop nodes emit routes Studio cannot list ahead of time.
const selectedEdgeAllowsCustomRoute = computed(() =>
  ['interaction', 'pageAction', 'loop'].includes(selectedEdgeSourceNode.value?.data.kind || ''),
)

const canCopySelectedNode = computed(() =>
  !!selectedNode.value && !['start', 'end'].includes(selectedNode.value.data.kind),
)
const canDeleteSelection = computed(() =>
  !!selectedEdge.value || (!!selectedNode.value && !['start', 'end'].includes(selectedNode.value.data.kind)),
)

const debugInputFields = computed<StudioFieldSchema[]>(() => {
  const userInputNode = nodes.value.find((node) => node.data.kind === 'userInput')
  return (userInputNode?.data.userInputConfig?.fields || [])
    .filter((field) => !!field.name?.trim())
})

const debugSessionSteps = computed<WorkflowDebugStepResult[]>(() =>
  debugSession.value?.steps || debugRunResult.value?.steps || [],
)

const {
  workflowExecutionPath,
  workflowExecutionNodeIds,
  workflowHitEdgeKeys,
  workflowReplaySummary,
  nodeTraceStates,
  nodeTraceList,
  debugOpsItems,
  lastRouteForNode,
} = useWorkflowStudioTraceProjection({
  nodes,
  debugRunResult,
  nodeDebugResult,
  debugResult,
  runOpsDetail,
  traceNodes,
})

const studioRecentRuns = computed(() => {
  const currentWorkflowId = workflowId.value
  const currentWorkflowName = (studio.value?.name || workflowMeta.name || '').trim()
  const currentWorkflowKeySlug = (studio.value?.keySlug || workflowMeta.keySlug || '').trim()
  const matched = recentRuns.value.filter((run) =>
    runMatchesCurrentWorkflow(run, currentWorkflowId, currentWorkflowName, currentWorkflowKeySlug),
  )
  return matched.length ? matched : recentRuns.value
})

const studioRecentRunsPlaceholder = computed(() => {
  const currentWorkflowId = workflowId.value
  const currentWorkflowName = (studio.value?.name || workflowMeta.name || '').trim()
  const currentWorkflowKeySlug = (studio.value?.keySlug || workflowMeta.keySlug || '').trim()
  const matchedCount = recentRuns.value.filter((run) =>
    runMatchesCurrentWorkflow(run, currentWorkflowId, currentWorkflowName, currentWorkflowKeySlug),
  ).length
  if (!recentRuns.value.length) {
    return '选择最近运行'
  }
  if (matchedCount > 0) {
    return '当前 Workflow 最近运行'
  }
  return '最近运行（含其他 Workflow）'
})

const variablePreview = computed(() => {
  const flowNodes = nodes.value
    .filter((n) => n.data.kind !== 'start' && n.data.kind !== 'end')
    .map((n) => ({
      id: n.id,
      kind: n.data.kind,
      ref: n.data.toolConfig?.ref || '',
      outputAlias: n.data.outputAlias || '',
      inputSchema: n.data.inputSchema || {},
      outputSchema: n.data.outputSchema || {},
      inputFields: n.data.userInputConfig?.fields || [],
      interactionFields: n.data.interactionConfig?.fields || [],
      inputMapping: n.data.toolConfig?.inputMapping || n.data.mcpConfig?.inputMapping || n.data.inputMapping || {},
      outputs: n.data.outputs || [],
      assignments: n.data.assignments || {},
      parameters: n.data.parameterConfig?.fields || [],
    }))
  return {
    context: {
      userId: '$context.userId',
      tenantId: '$context.tenantId',
      roles: '$context.roles',
    },
    nodes: flowNodes,
  }
})

function markCanvasDirty() {
  if (loading.value || historyApplying.value || !historyReady.value) return
  editGeneration.value += 1
  visualDirty.value = true
  validation.value = null
  validationRequestError.value = ''
}

function handleCanvasNodesChange(changes: Array<{ type?: string }>) {
  if (changes.some((change) => ['add', 'remove', 'position'].includes(change.type || ''))) {
    markCanvasDirty()
  }
}

function handleCanvasEdgesChange(changes: Array<{ type?: string }>) {
  if (changes.some((change) => ['add', 'remove'].includes(change.type || ''))) {
    markCanvasDirty()
  }
}

const {
  decorateWorkflowEdge,
  decorateWorkflowNode,
  refreshWorkflowNodeClasses,
  canvasSnapshot,
  serializeWorkflowEdge,
  onConnect,
  copySelectedNode,
  pasteCopiedNode,
  deleteSelection,
  deleteSelectedNode,
  syncSelectedEdgeLabel,
  applySelectedEdgeCondition,
  edgeDisplayLabel,
  stripTransientNodeClasses,
} = useWorkflowStudioCanvasActions({
  studioReadOnly,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  debugNodeId,
  copiedNode,
  currentDebugNodeId,
  nodeTraceStates,
  canCopySelectedNode,
  selectedNode,
  selectedEdge,
  workflowExecutionPath,
  workflowHitEdgeKeys,
  workflowExecutionSourceNodeIds: workflowExecutionNodeIds,
  getNodeDebugState: (nodeId) => nodeDebugStateForCanvas(nodeId),
  getLastRouteForNode: lastRouteForNode,
  markCanvasDirty,
  syncJsonFromCanvas: () => syncJsonFromCanvas(),
  activeTab,
  fitView,
  nextTick,
  nodeTypes,
  graphNodeTypeCapabilitiesLoaded,
})

const {
  canvasStats,
  graphVariables,
  graphLintItems,
  graphLintErrors,
  graphLintWarnings,
  autoLayoutWorkflowCanvas,
} = useWorkflowStudioGraphAnalysis({
  nodes,
  edges,
  workflowInputSchemaJson: computed(() => studio.value?.inputSchemaJson ?? null),
  decorateWorkflowNode,
  markCanvasDirty,
  syncJsonFromCanvas,
  waitForNodeMeasurements: waitForWorkflowNodeMeasurements,
  getNodeMeasurement: getWorkflowNodeMeasurement,
})

const {
  canUndo,
  canRedo,
  currentSnapshotText,
  pushHistorySnapshot,
  resetHistorySnapshot,
  undoCanvas,
  redoCanvas,
} = useWorkflowStudioHistory({
  historyPast,
  historyFuture,
  historyApplying,
  historyReady,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  visualDirty,
  editGeneration,
  stripTransientNodeClasses,
  decorateWorkflowNode,
  decorateWorkflowEdge,
  serializeWorkflowEdge,
  syncJsonFromCanvas: () => syncJsonFromCanvas(),
  nextTick,
})

function applyCanvasFromStudio(state: WorkflowWorkingCopyState) {
  clearPanelValidation()
  const snapshot = workflowStudioToCanvas(state)
  nodes.value = (snapshot.nodes || []).map(decorateWorkflowNode)
  edges.value = (snapshot.edges || []).map(decorateWorkflowEdge)
  selectedNodeId.value = null
  selectedEdgeId.value = null
  visualDirty.value = false
  nextTick(() => fitView({ padding: 0.2, duration: 240 }))
}

function syncJsonFromCanvas() {
  if (!studio.value) return
  const saveRequest = workflowCanvasToSaveRequest({
    ...studio.value,
    graphSpecJson: graphSpecJson.value,
    canvasJson: canvasJson.value,
  }, canvasSnapshot())
  graphSpecJson.value = formatJson(saveRequest.graphSpecJson)
  canvasJson.value = formatJson(saveRequest.canvasJson || '{"schemaVersion":1,"layoutVersion":1,"nodes":[],"edges":[]}')
  validation.value = null
}

function handlePanelValidationChange(payload: { nodeId?: string; valid: boolean; message?: string }) {
  if (!payload?.nodeId) return
  setPanelValidation(payload.nodeId, {
    valid: !!payload.valid,
    message: payload.message,
  })
}

const {
  workflowMetaDirty,
  loadStudio,
  saveStudio,
} = useWorkflowStudioPersistence({
  workflowId,
  studioReadOnly,
  saving,
  loading,
  studio,
  graphSpecJson,
  canvasJson,
  nodes,
  workflowMeta,
  visualDirty,
  editGeneration,
  lastSavedAt,
  validation,
  aiModelInstanceId,
  applyCanvasFromStudio,
  syncJsonFromCanvas,
  resetHistorySnapshot: () => resetHistorySnapshot(),
  loadCredentialOptions,
  clearWorkflowDocumentState,
  ensurePanelValidationClear,
})

function handleStudioRun() {
  if (hasReadOnlyTrialTarget.value) {
    readOnlyTrialOpen.value = true
    return
  }
  void handleDebug()
}

function focusReadOnlyTrialNode(nodeId: string) {
  if (!nodeId || !nodes.value.some((node) => node.id === nodeId)) return
  selectedNodeId.value = nodeId
  selectedEdgeId.value = null
  focusDebugNode(nodeId)
}

async function handleSaveStudio() {
  const saved = await saveStudio()
  if (saved) {
    await validateRuntime({ silent: true, syncCanvas: false })
  }
  return saved
}

const {
  resolveAiModelInstanceId,
  toolToProposalResource,
  knowledgeToProposalResource,
} = useWorkflowStudioProposalContext({
  aiModelInstanceId,
  studio,
  authoringModelOptions,
})

const {
  buildDebugBaseRequest,
  nodeDebugState: resolveNodeDebugState,
  nodeRunClass: resolveNodeRunClass,
  nodeRunLabel: resolveNodeRunLabel,
  focusDebugNode,
  selectDebugStep,
  openNodeTrace,
  handleDebug,
  handleRunWorkingCopyDebug,
  handleDebugInteractionSubmit,
  handleDebugInteractionCancel,
  handleCancelDebugSession,
  handleRestoreDebugSession,
  loadRecentStudioRuns,
  handleLoadTraceReplay,
  handleRecentTraceChange,
  clearTraceReplay,
  recentRunLabel,
  handleRunPublishedDebug,
  handleRunNodeDebug,
  isDebugStepRunning,
  debugConversationSnapshot,
  workflowInitialUiRequest,
  workflowInitialChatField,
  isDebugConversationBusy,
  disposeDebugConversation,
} = useWorkflowStudioDebugRun({
  debugCreationKey,
  setDebugCreationKey,
  debugSessionScope,
  workflowId,
  studio,
  workflowMeta,
  graphSpecJson,
  canvasJson,
  nodes,
  debugOpen,
  propertyPanelCollapsed,
  debugLoading,
  nodeDebugLoading,
  traceReplayLoading,
  recentRunsLoading,
  debugNodeId,
  debugMessage,
  nodeDebugMessage,
  nodeDebugStateJson,
  debugInputParams,
  currentTraceId,
  traceNodes,
  runOpsDetail,
  replayTraceInput,
  selectedRecentTraceId,
  recentRuns,
  selectedDebugStepIndex,
  currentDebugNodeId,
  debugPlaybackToken,
  nodeDebugResult,
  debugRunResult,
  debugSession,
  debugResult,
  selectedNodeId,
  selectedEdgeId,
  selectedNode,
  nodeDebugStateText,
  debugInputFields,
  resolveAiModelInstanceId,
  syncJsonFromCanvas,
  canvasSnapshot,
  refreshWorkflowNodeClasses,
  applyDebugSession,
  forgetDebugSession,
  loadStoredDebugSession,
  getViewport,
  setCenter,
  nextTick,
  findNodePosition: (nodeId) => {
    const node = nodes.value.find((item) => item.id === nodeId)
    return node ? node.position : null
  },
  parseOptionalObject,
})

const {
  evalTargetOpen,
  evalTargetsLoadError,
  evalTargets,
  hiddenEvalTargetCount,
  openWorkflowEval,
  openAgentEval,
} = useWorkflowStudioEvalLink({
  workflowId,
  openPath: (path) => { void router.push(path) },
})

function openEvalOverview() {
  evalTargetOpen.value = false
  void router.push('/runops/evals')
}

const {
  aiEditOperationGroups,
  aiEditPreviewFailed,
  runAiAuthoring,
  regenerateAiAuthoring,
  clearAiEditPreview,
  applyAiEditPreview,
  operationKey,
  operationTarget,
} = useWorkflowStudioProposalActions({
  workflowId,
  studioReadOnly,
  studio,
  editGeneration,
  graphSpecJson,
  canvasJson,
  nodes,
  edges,
  selectedNodeId,
  selectedEdgeId,
  activeTab,
  validation,
  aiEditInstruction,
  aiEditLoading,
  aiEditPreview,
  availableTools,
  knowledgeOptions,
  resolveAiModelInstanceId,
  toolToProposalResource,
  knowledgeToProposalResource,
  syncJsonFromCanvas,
  canvasSnapshot,
  applyCanvasFromStudio,
  autoLayoutWorkflowCanvas,
  fitCanvas: handleFitView,
  ensurePanelValidationClear,
})


async function syncPublishedPageAssistant(options: { silent?: boolean } = {}) {
  const current = studio.value
  if (!current || current.workflowKind !== 'PAGE_ASSISTANT') return false

  const projectId = current.projectId ?? null
  const projectCode = current.projectCode?.trim() || null
  if (!projectId && !projectCode) {
    if (!options.silent) {
      ElMessage.error('当前页面助手缺少项目归属，无法同步到页面副驾驶。')
    }
    return false
  }

  pageAssistantSyncing.value = true
  try {
    const { data } = await attachPageAssistantWorkflowTool(workflowId.value, {
      projectId,
      projectCode,
      modelInstanceId: current.defaultModelInstanceId || workflowMeta.defaultModelInstanceId || null,
      publishedBy: 'ReachAI Workflow Studio',
    })
    if (!options.silent) {
      ElMessage.success(`已同步到页面副驾驶（${data.agentKeySlug} 配置 v${data.configVersionNo}）`)
    }
    return true
  } catch (error) {
    if (!options.silent) {
      const response = (error as { response?: { data?: { message?: string; error?: string } } })?.response
      ElMessage.error(response?.data?.message || response?.data?.error || '页面副驾驶同步失败')
      return false
    }
    throw error
  } finally {
    pageAssistantSyncing.value = false
  }
}

const {
  publishWarnings,
  publishWorkflow,
  releaseValidationKey,
  handlePublishWorkflow,
} = useWorkflowStudioRelease({
  workflowId,
  studioReadOnly,
  studio,
  nodes,
  graphLintErrors,
  graphLintWarnings,
  editGeneration,
  publishing,
  releaseChecking,
  releaseValidationReady,
  publishDialogOpen,
  releaseErrors,
  releaseWarnings,
  publishForm,
  validateWorkingCopy: () => validateRuntime({ silent: true }),
  saveStudio,
  loadStudio,
  ensurePanelValidationClear,
  syncPublishedPageAssistant: () => syncPublishedPageAssistant({ silent: true }),
  afterPublished: (publishedWorkflowId) => notifyStaleAgentReferences(
    publishedWorkflowId,
    () => { versionsDialogOpen.value = true },
  ),
})

function handlePublishDialogBeforeClose(done: () => void) {
  if (!publishing.value) done()
}

const primaryOverlayRefs = [
  debugOpen,
  evalTargetOpen,
  jsonDrawerVisible,
  publishDialogOpen,
  versionsDialogOpen,
]

watch(
  () => primaryOverlayRefs.map((item) => item.value),
  (visible, previous) => {
    const openedIndex = visible.findIndex((shown, index) => shown && !previous[index])
    if (openedIndex < 0) return
    const publishIndex = primaryOverlayRefs.indexOf(publishDialogOpen)
    if (publishing.value && publishDialogOpen.value && openedIndex !== publishIndex) {
      primaryOverlayRefs[openedIndex].value = false
      return
    }
    primaryOverlayRefs.forEach((item, index) => {
      if (index !== openedIndex && item.value) item.value = false
    })
  },
)

function nodeDebugStateForCanvas(nodeId: string) {
  return resolveNodeDebugState(nodeId, nodeTraceStates.value)
}

function nodeDebugState(nodeId: string) {
  return nodeDebugStateForCanvas(nodeId)
}

function nodeRunClass(nodeId: string) {
  return resolveNodeRunClass(nodeId, nodeTraceStates.value)
}

function nodeRunLabel(nodeId: string) {
  return resolveNodeRunLabel(nodeId, nodeTraceStates.value)
}

const variableOptions = computed<StudioVariableOption[]>(() => {
  const options: StudioVariableOption[] = [
    { value: 'params', label: '用户输入 · 全部参数', group: '用户输入', description: '用户输入节点写入的 params 对象' },
    { value: 'sys.userId', label: '系统变量 · 当前用户 ID', group: '系统变量', description: '运行上下文用户标识' },
    { value: 'sys.tenantId', label: '系统变量 · 租户 ID', group: '系统变量', description: '运行上下文租户标识' },
    { value: 'sys.roles', label: '系统变量 · 用户角色', group: '系统变量', description: '当前用户角色列表' },
    { value: 'input', label: '运行态 · 原始输入消息', group: '运行态变量', description: '本次运行的原始消息' },
    { value: 'answer', label: '运行态 · 最终回答', group: '运行态变量', description: '当前已生成的 answer' },
    { value: 'lastOutput', label: '运行态 · 上一步输出', group: '运行态变量', description: '便捷变量，适合快速串联原型' },
    { value: 'lastRoute', label: '运行态 · 命中分支', group: '运行态变量', description: '条件或意图分类节点最近一次路由' },
  ]
  for (const item of graphVariables.value) {
    if (!item.name || item.name === selectedNodeId.value) continue
    options.push({
      value: item.name,
      label: item.label || `${item.source} · ${item.name}`,
      group: item.group || '节点输出',
      description: item.description || item.name,
      nodeId: item.nodeId,
      source: item.source,
    })
    if (item.group !== '用户输入' && !item.name.startsWith('nodeOutput.') && !item.name.startsWith('var.')) {
      options.push({
        value: `var.${item.name}`,
        label: `${item.source} · 业务别名 ${item.name}`,
        group: '节点输出',
        description: `var.${item.name}`,
        nodeId: item.nodeId,
        source: item.source,
      })
    }
    options.push({
      value: `nodeOutput.${item.nodeId}`,
      label: `${item.source} · 节点原始输出`,
      group: '节点输出',
      description: `nodeOutput.${item.nodeId}`,
      nodeId: item.nodeId,
      source: item.source,
    })
  }
  const seen = new Set<string>()
  return options.filter((item) => {
    if (!item.value || seen.has(item.value)) return false
    seen.add(item.value)
    return true
  })
})

const businessMethodRequestScope = computed(() => [
  platformSessionId.value || '',
  workflowId.value || '',
  studio.value?.projectId || '',
  studio.value?.projectCode || '',
].join('\u0000'))

onMounted(async () => {
  updateViewportWidth()
  // The generic catalog is project-authorized.  Load the working copy first so
  // its project identity is available before the selector issues its request.
  await loadStudio()
  await Promise.all([
    loadNodeTypes(),
    loadToolOptions(studio.value),
    loadGraphResourceOptions(nodes.value.map((node) => node.data.kind)),
  ])
  if (route.query.intent === 'api-query-template' || route.query.scanToolId || route.query.projectApiTool) {
    ElMessage.warning('旧扫描 API 查询模板已退场；请添加 API 节点并用“选择 API”，再由展示输出交互消费节点结果。原图保持不变。')
  }
  focusReferenceNode()
  resetHistorySnapshot()
  await validateRuntime({ silent: true, syncCanvas: false })
  window.addEventListener('resize', updateViewportWidth)
  window.addEventListener('keydown', handleStudioShortcut)
})

onUnmounted(() => {
  clearPanelValidation()
  window.removeEventListener('resize', updateViewportWidth)
  window.removeEventListener('keydown', handleStudioShortcut)
  disposeDebugConversation()
})

watch(
  () => [studio.value?.workflowId, studio.value?.projectId, studio.value?.projectCode,
    ...nodes.value.map((node) => node.data.kind)].join('\u0000'),
  () => { void loadGraphResourceOptions(nodes.value.map((node) => node.data.kind)) },
)

watch(debugOpen, (open) => {
  if (!open) disposeDebugConversation()
})

watch(
  () => nodes.value.map((node) => node.id).sort().join('\u0000'),
  () => prunePanelValidation(nodes.value.map((node) => node.id)),
)

watch(
  () => currentSnapshotText(),
  () => {
    if (!historyReady.value || historyApplying.value) return
    pushHistorySnapshot()
    markCanvasDirty()
  },
  { flush: 'post' },
)

watch(
  () => [
    workflowMeta.name,
    workflowMeta.keySlug,
    workflowMeta.workflowKind,
    workflowMeta.description,
    workflowMeta.defaultModelInstanceId,
  ].join('\u0000'),
  () => {
    if (studio.value && workflowMetaDirty()) {
      editGeneration.value += 1
      validation.value = null
      validationRequestError.value = ''
    }
  },
)

watch(
  canvasSearchKeyword,
  () => {
    canvasSearchIndex.value = 0
    if (canvasSearchOpen.value && canvasSearchMatches.value.length) {
      focusCanvasSearchMatch(0)
    }
  },
)

watch(
  () => [
    currentDebugNodeId.value,
    debugSessionSteps.value.length,
    debugSessionSteps.value.map((step) => `${step.nodeId}:${step.status}:${step.nextNodeId || ''}`).join('|'),
  ].join('::'),
  () => refreshWorkflowNodeClasses(),
)

function updateViewportWidth() {
  viewportWidth.value = window.innerWidth
}

watch(
  workflowId,
  async (nextId, prevId) => {
    if (!nextId || nextId === prevId) return
    resetWorkflowSessionState()
    validationRequestError.value = ''
    await loadStudio()
    await validateRuntime({ silent: true, syncCanvas: false })
  },
)

const { validateRuntime } = useWorkflowStudioRuntimeValidation({
  workflowId, studio, graphSpecJson,
  defaultModelInstanceId: computed(() => workflowMeta.defaultModelInstanceId),
  editGeneration, nodes, validating, validation, validationRequestError, syncJsonFromCanvas,
})

function insertNodeTemplate(type: string) {
  if (!studio.value) {
    ElMessage.warning('请先加载 Workflow')
    return
  }
  const kind = normalizeCanvasKind(type)
  const decision = resolveStudioNodeCreation(kind, nodeTypes.value, graphNodeTypeCapabilitiesLoaded.value)
  if (!decision.allowed) {
    ElMessage.warning(decision.reason || '当前节点类型不可新增')
    return
  }
  const node = createWorkflowCanvasNode(kind, {
    x: 180 + nodes.value.length * 32,
    y: 160 + nodes.value.length * 18,
  }, studio.value)
  nodes.value.push(decorateWorkflowNode(node))
  selectedNodeId.value = node.id
  selectedEdgeId.value = null
  debugNodeId.value = node.id
  propertyPanelCollapsed.value = false
  markCanvasDirty()
  syncJsonFromCanvas()
  activeTab.value = 'visual'
  nextTick(() => fitView({ padding: 0.2, duration: 240 }))
}

function handleAddNode(kind: CanvasNodeKind) {
  if (studioReadOnly.value) return
  insertNodeTemplate(kind)
}

function addNodeFromSearch(kind: CanvasNodeKind) {
  handleAddNode(kind)
  nodeSearchOpen.value = false
}

const selectedCategoryFilter = ref('ALL')
const collapsedGroupTitles = ref<string[]>([])

const totalNodeCount = computed(() => {
  return paletteGroups.value.reduce((acc, g) => acc + g.items.length, 0)
})

const displayedPaletteGroups = computed(() => {
  const groups = filteredPaletteGroups.value
  if (selectedCategoryFilter.value === 'ALL') {
    return groups
  }
  return groups.filter((g) => g.title === selectedCategoryFilter.value)
})

function isGroupExpanded(title: string) {
  if (nodeSearchKeyword.value.trim()) return true
  return !collapsedGroupTitles.value.includes(title)
}

function toggleGroupCollapse(title: string) {
  const index = collapsedGroupTitles.value.indexOf(title)
  if (index >= 0) {
    collapsedGroupTitles.value.splice(index, 1)
  } else {
    collapsedGroupTitles.value.push(title)
  }
}

watch(nodeSearchOpen, (open) => {
  if (open) {
    nodeSearchKeyword.value = ''
    selectedCategoryFilter.value = 'ALL'
  }
})

function refreshCanvasFromJson() {
  if (!studio.value) return
  const state: WorkflowWorkingCopyState = {
    ...studio.value,
    graphSpecJson: graphSpecJson.value,
    canvasJson: canvasJson.value,
  }
  applyCanvasFromStudio(state)
}

function resetSourceBuffer() {
  graphSpecSourceBuffer.value = graphSpecJson.value
  canvasSourceBuffer.value = canvasJson.value
}

function openJsonDrawer() {
  if (nodes.value.length) {
    syncJsonFromCanvas()
  }
  resetSourceBuffer()
  activeTab.value = 'graph'
  jsonDrawerVisible.value = true
}

async function applySourceBuffer() {
  if (studioReadOnly.value) {
    ElMessage.info('代码托管 Workflow 当前为只读草稿，请修改后重启同步。')
    return
  }
  jsonApplying.value = true
  try {
    const graph = normalizeJson(graphSpecSourceBuffer.value, 'GraphSpec')
    const canvas = normalizeJson(canvasSourceBuffer.value || '{}', 'Canvas')
    graphSpecJson.value = formatJson(graph)
    canvasJson.value = formatJson(canvas)
    refreshCanvasFromJson()
    markCanvasDirty()
    resetHistorySnapshot()
    const data = await validateRuntime({ silent: true })
    resetSourceBuffer()
    if (!data) {
      ElMessage.warning('源码已应用到当前工作副本，但运行时校验尚未完成')
      return
    }
    jsonDrawerVisible.value = false
    if (data.valid) {
      ElMessage.success('源码已校验并应用到当前工作副本')
    } else {
      ElMessage.warning(`源码已应用，仍有 ${data.errors.length} 个发布阻断项`)
    }
  } catch (err) {
    ElMessage.error('Workflow 源码应用失败：' + (err as Error).message)
  } finally {
    jsonApplying.value = false
  }
}

function handleCreateInteractionCallNode(request: InteractionCallNodeRequest) {
  const method = availableTools.value.find(item => item.assetId === request.assetId
    && item.assetType === request.assetType && item.projectCode === request.projectCode
    && item.qualifiedName === request.qualifiedName)
  if (!method) {
    ElMessage.warning('业务方法来源已变化，请重新选择后生成调用节点。')
    return
  }
  if (studioReadOnly.value) return
  if (!studio.value || !selectedNode.value || selectedNode.value.data.kind !== 'interaction') return
  const kind: CanvasNodeKind = 'tool'
  const node = createWorkflowCanvasNode(kind, {
    x: selectedNode.value.position.x + 320,
    y: selectedNode.value.position.y,
  }, studio.value)
  const outputAlias = request.outputAlias || node.data.outputAlias || `${kind}_output`
  node.data.label = request.label || `调用业务方法 ${request.ref}`
  node.data.description = request.description || node.data.description || ''
  node.data.outputAlias = outputAlias
  node.data.inputs = Object.keys(request.inputMapping || {}).map((key) => ({
    id: key,
    name: key,
    type: 'any',
    required: false,
    source: request.inputMapping[key],
  }))
  node.data.outputs = [{ id: outputAlias, name: outputAlias, type: 'any' }]
  node.data.toolConfig = {
    ...(node.data.toolConfig || { inputMapping: {} }),
    ref: request.ref,
    assetReference: { kind: 'TOOL', assetType: method.assetType, assetId: method.assetId,
      name: method.name, qualifiedName: method.qualifiedName, projectCode: method.projectCode },
    qualifiedName: request.qualifiedName || null,
    projectCode: request.projectCode || null,
    inputMapping: request.inputMapping,
    mappingNote: `由交互节点 ${selectedNode.value.id} 自动生成，可继续手动调整。`,
  }
  nodes.value.push(node)
  edges.value.push(decorateWorkflowEdge({
    id: `e-${selectedNode.value.id}-${node.id}-${Date.now()}`,
    source: selectedNode.value.id,
    target: node.id,
    condition: 'always',
    label: 'always',
  }))
  const binding = selectedNode.value.data.interactionConfig?.binding
  if (binding) {
    binding.callNodeId = node.id
  }
  selectedNodeId.value = node.id
  selectedEdgeId.value = null
  markCanvasDirty()
  nextTick(() => fitView({ padding: 0.2, duration: 240 }))
}

function onNodeClick(event: NodeMouseEvent) {
  selectedNodeId.value = event.node?.id || null
  selectedEdgeId.value = null
  if (selectedNodeId.value) {
    debugNodeId.value = selectedNodeId.value
    propertyPanelCollapsed.value = false
  }
}

function onNodeDoubleClick(event: NodeMouseEvent) {
  selectedNodeId.value = event.node?.id || null
  selectedEdgeId.value = null
  propertyPanelCollapsed.value = false
  if (selectedNodeId.value) {
    debugNodeId.value = selectedNodeId.value
    propertyPanelTab.value = 'config'
  }
}

function clearSelection() {
  selectedNodeId.value = null
  selectedEdgeId.value = null
}

async function handleFitView() {
  await nextTick()
  fitView({ padding: 0.18, duration: 260 })
}

function handleZoomIn() {
  zoomIn({ duration: 180 })
}

function handleZoomOut() {
  zoomOut({ duration: 180 })
}

async function handleAutoLayout() {
  if (layoutRunning.value) return
  layoutRunning.value = true
  try {
    await autoLayoutWorkflowCanvas()
    await handleFitView()
  } catch (err) {
    ElMessage.error('自动整理失败：' + (err as Error).message)
  } finally {
    layoutRunning.value = false
  }
}

function toggleSelectedNodeCollapsed() {
  if (!selectedNode.value) return
  selectedNode.value.data.collapsed = !selectedNode.value.data.collapsed
  nodes.value = nodes.value.map((node) =>
    node.id === selectedNode.value?.id ? decorateWorkflowNode({ ...node }) : node,
  )
  markCanvasDirty()
  syncJsonFromCanvas()
}

function isInputTarget(target: EventTarget | null) {
  const el = target as HTMLElement | null
  if (!el) return false
  const tag = el.tagName?.toLowerCase()
  return tag === 'input' || tag === 'textarea' || tag === 'select' || el.isContentEditable
}

function handleStudioShortcut(event: KeyboardEvent) {
  if (isInputTarget(event.target)) return
  const key = event.key.toLowerCase()
  const mod = event.ctrlKey || event.metaKey
  if (mod && key === 's') {
    event.preventDefault()
    void handleSaveStudio()
    return
  }
  if (mod && key === 'f') {
    event.preventDefault()
    openCanvasSearch()
    return
  }
  if (mod && key === 'z' && event.shiftKey) {
    event.preventDefault()
    redoCanvas()
    return
  }
  if (mod && key === 'z') {
    event.preventDefault()
    undoCanvas()
    return
  }
  if (mod && key === 'y') {
    event.preventDefault()
    redoCanvas()
    return
  }
  if (mod && key === 'c') {
    event.preventDefault()
    copySelectedNode()
    return
  }
  if (mod && key === 'v') {
    event.preventDefault()
    pasteCopiedNode()
    return
  }
  if (mod && key === 'o') {
    event.preventDefault()
    void handleAutoLayout()
    return
  }
  if (mod && key === '1') {
    event.preventDefault()
    void handleFitView()
    return
  }
  if ((key === 'delete' || key === 'backspace') && canDeleteSelection.value) {
    event.preventDefault()
    deleteSelection()
  }
}

function onEdgeClick(event: EdgeMouseEvent) {
  selectedEdgeId.value = event.edge?.id || null
  selectedNodeId.value = null
  propertyPanelCollapsed.value = false
}

function focusLintItem(item: GraphLintItem) {
  if (item.nodeId) {
    selectedNodeId.value = item.nodeId
    selectedEdgeId.value = null
    propertyPanelCollapsed.value = false
    void focusDebugNode(item.nodeId)
    return
  }
  if (item.edgeId) {
    selectedEdgeId.value = item.edgeId
    selectedNodeId.value = null
    propertyPanelCollapsed.value = false
  }
}

function focusRuntimeValidationItem(target?: string | null) {
  const normalized = String(target || '').trim()
  if (!normalized) return
  const directNode = nodes.value.find((node) => node.id === normalized)
  const prefixedNodeId = normalized.replace(/^nodes?[.:/]/i, '').split(/[.:/]/)[0]
  const node = directNode || nodes.value.find((item) => item.id === prefixedNodeId)
  if (!node) return
  selectedNodeId.value = node.id
  selectedEdgeId.value = null
  propertyPanelCollapsed.value = false
  void focusDebugNode(node.id)
}

function focusReferenceNode() {
  const nodeId = typeof route.query.nodeId === 'string' ? route.query.nodeId : ''
  if (nodeId && nodes.value.some(node => node.id === nodeId)) {
    selectedNodeId.value = nodeId
    selectedEdgeId.value = null
    propertyPanelCollapsed.value = false
    void focusDebugNode(nodeId)
  }
}
watch(() => route.query.nodeId, focusReferenceNode)

function normalizeCanvasKind(type: string): CanvasNodeKind {
  const normalized = type.trim()
  const upper = normalized.toUpperCase()
  if (upper === 'USER_INPUT') return 'userInput'
  if (upper === 'INTERACTION') return 'interaction'
  if (upper === 'PAGE_ACTION') return 'pageAction'
  if (upper === 'LLM') return 'llm'
  if (upper === 'TOOL') return 'tool'
  if (upper === 'KNOWLEDGE_RETRIEVAL') return 'knowledge'
  if (upper === 'IF_ELSE') return 'condition'
  if (upper === 'VARIABLE_ASSIGN') return 'variable'
  if (upper === 'TEMPLATE') return 'template'
  if (upper === 'PARAMETER_EXTRACT') return 'parameter'
  if (upper === 'HTTP_REQUEST') return 'http'
  if (upper === 'ANSWER') return 'answer'
  if (upper === 'CODE') return 'code'
  if (upper === 'INTENT_CLASSIFIER') return 'classifier'
  if (upper === 'VARIABLE_AGGREGATOR') return 'aggregate'
  if (upper === 'HUMAN_APPROVAL') return 'approval'
  if (upper === 'LOOP') return 'loop'
  if (upper === 'KNOWLEDGE_WRITE') return 'knowledgeWrite'
  if (upper === 'DOCUMENT_EXTRACT') return 'documentExtract'
  if (upper === 'MCP_CALL') return 'mcp'
  const allowed: CanvasNodeKind[] = [
    'start',
    'end',
    'userInput',
    'interaction',
    'pageAction',
    'llm',
    'tool',
    'knowledge',
    'condition',
    'variable',
    'template',
    'parameter',
    'http',
    'answer',
    'code',
    'classifier',
    'aggregate',
    'approval',
    'loop',
    'knowledgeWrite',
    'documentExtract',
    'mcp',
  ]
  if (!allowed.includes(normalized as CanvasNodeKind)) {
    throw new Error(`Canvas node kind must use a canonical value: ${type}`)
  }
  return normalized as CanvasNodeKind
}


function handleHeaderCommand(command: string | number | object) {
  if (command === 'eval') {
    void openWorkflowEval()
    return
  }
  if (command === 'json') {
    openJsonDrawer()
    return
  }
  if (command === 'fit') {
    void handleFitView()
    return
  }
  if (command === 'layout') {
    void handleAutoLayout()
    return
  }
  if (command === 'node-debug') {
    propertyPanelCollapsed.value = false
    propertyPanelTab.value = 'test'
    if (selectedNodeId.value) {
      debugNodeId.value = selectedNodeId.value
    }
    return
  }
  if (command === 'versions') {
    versionsDialogOpen.value = true
    return
  }
}

async function handleRollbackSuccess() {
  await loadStudio()
}

function parseOptionalObject(value: string, label: string) {
  if (!value.trim()) return undefined
  const parsed = JSON.parse(value)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(`${label} must be a JSON object`)
  }
  return parsed as Record<string, unknown>
}

function modelOptionLabel(item: ModelInstance) {
  return `${item.name || item.id} / ${item.provider || '-'} / ${item.modelName || '-'}`
}


</script>

<style scoped lang="scss">
.node-panel,
.inspect-panel {
  padding: 16px;
  background: var(--surface-glass-panel);
}

.node-panel {
  border-right: 1px solid var(--el-border-color-lighter);
}

.canvas-search-panel {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) auto auto auto auto;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-light);
}

.canvas-search-count {
  min-width: 76px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: right;
}

.canvas-wrap {
  height: 650px;
  min-height: 520px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
}

.issue {
  padding: 10px;
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: 6px;
  background: var(--el-color-danger-light-9);
}

.issue strong {
  display: block;
  margin-bottom: 4px;
  font-size: 12px;
}

.issue span,
.issue p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
}

@media (max-width: 1080px) {
  .node-panel,
  .inspect-panel {
    border: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
}

.studio-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  // Studio 也铺主题壁纸；画布、工具层和配置面板各自是磨砂玻璃，节点与连线仍落在高白纱的画布上。
  background: var(--surface-page-background);
  background-size: cover;
  background-position: var(--theme-wallpaper-position, center);
  background-repeat: no-repeat;
  color: var(--text-primary);
}

.studio-body {
  flex: 1;
  min-height: 0;
  position: relative;
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) var(--studio-property-width, 480px);
  overflow: hidden;
  transition: grid-template-columns 0.2s ease;
}

.studio-body:has(.property-panel.resizing) {
  transition: none;
  user-select: none;
}

.studio-body.property-collapsed {
  grid-template-columns: 72px minmax(0, 1fr) 58px;
}

.studio-page .canvas-wrap {
  position: relative;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  border: 0;
  border-radius: 0;
  background: var(--surface-glass-canvas);
}

.studio-canvas {
  width: 100%;
  height: 100%;
}

.studio-canvas :deep(.vue-flow__pane) {
  cursor: grab;
}

.studio-canvas :deep(.vue-flow__pane.dragging) {
  cursor: grabbing;
}

.studio-canvas :deep(.vue-flow__node) {
  border: 0;
  background: transparent;
  box-shadow: none;
}

.studio-canvas :deep(.vue-flow__node.selected .studio-node) {
  box-shadow: 0 0 0 3px rgb(var(--brand-primary-rgb) / 0.18), 0 12px 28px rgba(46, 54, 80, 0.12);
}

.studio-canvas :deep(.vue-flow__edge-path) {
  stroke: #7b879a;
  stroke-width: 2;
}

.studio-canvas :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: var(--brand-primary);
  stroke-width: 2.5;
}

.studio-canvas :deep(.vue-flow__controls) {
  left: 22px;
  bottom: 28px;
  border: 1px solid var(--border-divider);
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 12px 32px rgba(31, 41, 55, 0.12);
}

.studio-canvas :deep(.vue-flow__minimap) {
  right: 34px;
  bottom: 34px;
  border: 1px solid var(--border-divider);
  border-color: var(--border-glass-edge);
  border-radius: 12px;
  background: var(--surface-glass-frost);
  box-shadow: var(--shadow-glass-frost);
  -webkit-backdrop-filter: blur(var(--glass-blur-frost)) saturate(var(--glass-saturation));
  backdrop-filter: blur(var(--glass-blur-frost)) saturate(var(--glass-saturation));
}

// MiniMap 的遮罩和节点默认是浅灰填充，随主题改用语义色。
.studio-canvas :deep(.vue-flow__minimap-mask) {
  fill: color-mix(in srgb, var(--surface-page-canvas) 62%, transparent);
}

.studio-canvas :deep(.vue-flow__minimap-node) {
  fill: var(--surface-pressed);
}

.studio-canvas :deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid var(--border-divider);
  background: var(--text-primary);
}

.canvas-search-panel {
  position: absolute;
  top: 76px;
  right: 24px;
  z-index: 5;
  display: grid;
  grid-template-columns: minmax(220px, 1fr) auto auto auto auto;
  width: min(620px, calc(100% - 48px));
  margin: 0;
  box-shadow: 0 14px 36px rgba(31, 41, 55, 0.12);
}

/* 现代双栏节点选择面板 (Linear / Dify 风格) */
:deep(.node-picker-dialog) {
  overflow: hidden;
}

:deep(.node-picker-dialog .el-dialog__header) {
  margin-right: 0;
  padding: 16px 20px 14px;
  border-bottom: 1px solid var(--border-glass-edge);
}

:deep(.node-picker-dialog .el-dialog__title) {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

:deep(.node-picker-dialog .el-dialog__body) {
  padding: 0;
}

.node-picker-container {
  display: flex;
  flex-direction: column;
  background: transparent;
}

.node-picker-search-bar {
  padding: 14px 20px;
  background: transparent;
  border-bottom: 1px solid var(--border-glass-edge);
}

.node-picker-search-input :deep(.el-input__wrapper) {
  background-color: var(--surface-glass-control);
  box-shadow: none;
  border: 1px solid var(--border-glass-edge);
  border-radius: 10px;
  padding: 4px 12px;
  transition: all 0.2s ease;
}

.node-picker-search-input :deep(.el-input__wrapper.is-focus) {
  background-color: var(--surface-glass-selected);
  border-color: rgb(var(--brand-primary-rgb) / 0.4);
  box-shadow: 0 0 0 3px rgb(var(--brand-primary-rgb) / 0.12);
}

.node-picker-body {
  display: flex;
  height: 480px;
}

/* 左侧分类导航 */
.node-picker-sidebar {
  width: 196px;
  flex-shrink: 0;
  background: var(--surface-glass-inset);
  border-right: 1px solid var(--border-glass-edge);
  padding: 12px 10px;
  overflow-y: auto;
}

.sidebar-category-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sidebar-divider {
  height: 1px;
  background: var(--border-glass-edge);
  margin: 6px 4px;
}

.sidebar-nav-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  transition: all 0.15s ease;
}

.sidebar-nav-item:hover {
  background: var(--surface-glass-selected);
  color: var(--text-primary);
}

.sidebar-nav-item.active {
  background: var(--brand-soft-bg);
  color: var(--brand-on-surface);
  font-weight: 600;
}

.nav-item-icon {
  display: flex;
  align-items: center;
  font-size: 15px;
  color: inherit;
}

.nav-item-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-item-count {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 10px;
  background: var(--surface-glass-inset);
  box-shadow: var(--glass-rim-double);
  color: var(--text-muted);
  font-size: 11px;
  font-weight: 500;
}

.sidebar-nav-item.active .nav-item-count {
  background: var(--brand-soft-bg);
  color: var(--brand-on-surface);
}

/* 右侧内容面板 */
.node-picker-content {
  flex: 1;
  min-width: 0;
  padding: 16px 20px;
  overflow-y: auto;
}

.node-picker-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.node-group-section {
  margin-bottom: 22px;
}

.node-group-section:last-child {
  margin-bottom: 8px;
}

.node-group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 2px 10px;
  cursor: pointer;
  user-select: none;
}

.group-title-left {
  display: flex;
  align-items: center;
  gap: 6px;
}

.group-collapse-arrow {
  font-size: 12px;
  color: var(--text-disabled);
  transition: transform 0.18s ease;
}

.group-collapse-arrow.rotated {
  transform: rotate(90deg);
}

.group-title-text {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.2px;
}

.group-title-count {
  font-size: 11px;
  color: var(--text-disabled);
  font-weight: 500;
}

.group-title-action {
  font-size: 11.5px;
  color: var(--text-disabled);
  opacity: 0;
  transition: opacity 0.15s ease;
}

.node-group-header:hover .group-title-action {
  opacity: 1;
  color: var(--brand-on-surface);
}

.node-card-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

/* 单个节点微卡片 */
.node-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--border-glass-edge);
  border-radius: 12px;
  background: var(--surface-glass-inset);
  cursor: pointer;
  text-align: left;
  transition: all 0.18s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: var(--glass-rim-double);
}

.node-card:hover {
  border-color: rgb(var(--brand-primary-rgb) / 0.34);
  background: var(--surface-glass-selected);
  box-shadow: 0 6px 16px -2px rgba(15, 23, 42, 0.08), 0 2px 6px -1px rgba(15, 23, 42, 0.03);
  transform: translateY(-1.5px);
}

.node-card:active {
  transform: translateY(0);
}

.node-icon-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border-radius: 10px;
  font-size: 18px;
}

.node-details {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.node-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.node-name {
  color: var(--text-primary);
  font-size: 13.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-tag {
  flex-shrink: 0;
  color: var(--text-secondary);
  background: var(--surface-glass-inset);
  border: 1px solid var(--border-glass-edge);
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
}

.node-desc {
  color: var(--text-muted);
  font-size: 12px;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.studio-canvas :deep(.vue-flow__handle.classifier-route-handle) {
  top: 50%;
  right: -9px;
  width: 13px;
  height: 13px;
  border: 3px solid rgb(var(--brand-primary-rgb) / 0.4);
  background: var(--brand-primary);
  transform: translateY(-50%);
}

.studio-canvas :deep(.vue-flow__node.loop-body-member .studio-node) {
  position: relative;
  box-shadow: inset 0 0 0 1px rgb(var(--brand-primary-rgb) / 0.45), var(--shadow-glass-node), var(--glass-node-rim);
}

.studio-canvas :deep(.vue-flow__node.loop-body-member .studio-node)::after {
  content: 'LOOP body';
  position: absolute;
  top: 6px;
  right: 8px;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 10px;
  line-height: 1.4;
  color: var(--brand-on-surface);
  background: rgb(var(--brand-primary-rgb) / 0.12);
  pointer-events: none;
}

.studio-canvas :deep(.vue-flow__node.selected .studio-node) {
  border-color: var(--node-color);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--node-color) 24%, transparent), var(--shadow-glass-node), var(--glass-node-rim);
}

.studio-canvas :deep(.vue-flow__node.selected .studio-node::before) {
  height: 5px;
}

.studio-canvas :deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid var(--border-divider);
  background: var(--surface-pressed);
  box-shadow: 0 0 0 1px #64748b, 0 2px 6px rgba(15, 23, 42, 0.16);
}

.studio-canvas :deep(.vue-flow__node.selected .vue-flow__handle) {
  background: var(--surface-solid-panel);
  box-shadow: 0 0 0 2px rgb(var(--brand-primary-rgb) / 1.0), 0 0 0 6px rgb(var(--brand-primary-rgb) / 0.12);
}

:global(.vue-flow__edge.edge-route-hit .vue-flow__edge-path) {
  stroke: var(--status-success);
  stroke-width: 3;
}

:global(.vue-flow__edge.edge-route-miss .vue-flow__edge-path) {
  stroke: #cbd5e1;
  stroke-dasharray: 5 5;
}

:global(.vue-flow__edge.edge-route-hit .vue-flow__edge-text) {
  fill: var(--status-success);
  font-weight: 700;
}

@media (max-width: 1120px) {
  .studio-body,
  .studio-body.property-collapsed {
    grid-template-columns: 74px minmax(0, 1fr);
    height: auto;
  }

  .property-panel {
    position: absolute;
    top: 0;
    right: 0;
    z-index: 8;
    width: min(var(--studio-property-width, 480px), 90vw);
    height: 100%;
    box-shadow: -16px 0 42px rgba(31, 41, 55, 0.12);
  }

  .studio-body.property-collapsed .property-panel {
    width: 64px;
  }

  .ai-edit-bar.debug-drawer-open {
    right: 22px;
  }
}

@media (max-width: 760px) {
  .studio-body,
  .studio-body.property-collapsed {
    grid-template-columns: 1fr;
  }

  .palette {
    position: absolute;
    z-index: 10;
    width: 74px;
    height: 100%;
  }

  .workflow-inspector {
    display: none;
  }
}
</style>
