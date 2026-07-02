<template>
  <div class="page-container api-catalog-workbench">
    <ScanProjectHeader
      :project="project"
      :asset-summary-items="assetSummaryItems"
      :batch-starting="batchStarting"
      :reconcile-loading="reconcileLoading"
      :loading="loading"
      @go-back="goBack"
      @start-batch-generate="startBatchGenerate"
      @open-model-generate-panel="openModelGeneratePanel"
      @open-scan-rules-panel="openScanRulesPanel"
      @open-ops-panel="openOpsPanel"
      @reconcile="handleReconcile"
      @refresh="refreshAll"
    />

    <ScanProjectOverviewCards :cards="workbenchSummaryCards" />

    <el-tabs v-model="activeWorkbenchTab" class="workbench-tabs">
      <el-tab-pane label="接口目录" name="tools" />
      <el-tab-pane label="模块管理" name="modules" />
      <el-tab-pane label="接口图谱" name="apiGraph" />
    </el-tabs>

    <el-collapse v-model="detailPanelActive" class="scan-detail-sections">
      <ScanProjectModulesPanel
        v-if="project && activeWorkbenchTab === 'modules'"
        :modules="modules"
        :selected-module-ids="selectedModuleIds"
        :module-doc-map="moduleDocMap"
        @module-selection-change="onModuleSelectionChange"
        @open-merge-dialog="openMergeDialog"
        @regenerate-module="regenerateModule"
        @open-edit-doc="openEditDoc"
        @open-rename-dialog="openRenameDialog"
      />

      <ScanProjectToolsPanel
        v-if="project && activeWorkbenchTab === 'tools'"
        v-model:interface-collapse-active="interfaceCollapseActive"
        :loading="loading"
        :tools="tools"
        :visible-tool-module-groups="visibleToolModuleGroups"
        :hidden-module-group-count="hiddenModuleGroupCount"
        :tool-doc-map="toolDocMap"
        :sensitive-scan-starting="sensitiveScanStarting"
        :sensitive-task-polling="sensitiveTaskPolling"
        :export-scan-tools-excel-loading="exportScanToolsExcelLoading"
        :batch-module-promote-loading="batchModulePromoteLoading"
        :tool-detail-loading="toolDetailLoading"
        :rescan-source-loading="rescanSourceLoading"
        :promote-loading="promoteLoading"
        :push-to-global-loading="pushToGlobalLoading"
        :unpromote-loading="unpromoteLoading"
        :parameter-rows="parameterRows"
        :render-md="renderMd"
        :scan-tool-row-class-name="scanToolRowClassName"
        :tool-parameter-count="toolParameterCount"
        :tool-doc-summary="toolDocSummary"
        :sensitive-cell-tooltip="sensitiveCellTooltip"
        :tool-link-label="toolLinkLabel"
        :tool-link-tag-type="toolLinkTagType"
        @start-sensitive-data-scan="startSensitiveDataScanFlow"
        @export-excel="handleExportScanToolsExcel"
        @batch-toggle="batchToggle"
        @promote-module-to-global="handlePromoteModuleToGlobal"
        @tool-expand-change="onToolExpandChange"
        @enabled-change="handleEnabledChange"
        @flag-change="handleFlagChange"
        @open-diff="openDiffDialog"
        @open-edit="openEditDialog"
        @rescan-from-source="handleRescanToolFromSource"
        @open-test="openTest"
        @promote-to-global="handlePromoteToGlobal"
        @push-to-global="handlePushToGlobalTool"
        @unpromote-from-global="handleUnpromoteFromGlobal"
        @regenerate-tool="regenerateTool"
        @open-edit-doc="openEditDoc"
        @show-more-groups="showMoreToolModuleGroups"
      />

      <ScanProjectApiGraphPanel
        v-if="project && activeWorkbenchTab === 'apiGraph'"
        :project-id="projectId"
        :api-graph-mounted="apiGraphMounted"
        :panel-expanded="activeWorkbenchTab === 'apiGraph'"
      />
    </el-collapse>

    <ScanProjectModelGenerateDrawer
      v-model:visible="modelGenerateDrawerVisible"
      v-model:semantic-model-instance-id="semanticModelInstanceId"
      v-model:ai-generation-mode="aiGenerationMode"
      :semantic-model-instances="semanticModelInstances"
      :batch-starting="batchStarting"
      :task-running="taskRunning"
      :task-percent="taskPercent"
      :task-failed="taskFailed"
      :task-failed-title="taskFailedTitle"
      @start-batch-generate="startBatchGenerate"
      @save-ai-generation-settings="saveAiGenerationSettings"
    />

    <ScanProjectScanRulesDrawer
      v-model:visible="scanRulesDrawerVisible"
      :project="project"
      :scan-settings-form="scanSettingsForm"
      :is-open-api-mode="isOpenApiMode"
      :description-source-labels="descriptionSourceLabels"
      :param-source-labels="paramSourceLabels"
      :all-http-methods="allHttpMethods"
      :scan-settings-saving="scanSettingsSaving"
      @set-description-source-enabled="setDescriptionSourceEnabled"
      @set-param-description-source-enabled="setParamDescriptionSourceEnabled"
      @move-description-order="moveDescriptionOrder"
      @move-param-order="moveParamOrder"
      @save-scan-settings="handleSaveScanSettings"
    />

    <ScanProjectOpsDrawer
      v-model:visible="opsDrawerVisible"
      :project="project"
      :rescan-loading="rescanLoading"
      :rebuild-embedding-loading="rebuildEmbeddingLoading"
      :scan-settings-saving="scanSettingsSaving"
      @rescan="handleRescan"
      @rebuild-embeddings="handleRebuildEmbeddings"
      @save-scan-settings="handleSaveScanSettings"
      @save-ai-generation-settings="saveAiGenerationSettings"
    />

    <ScanProjectSemanticDialogs
      v-model:doc-edit-visible="docEditVisible"
      v-model:doc-edit-content="docEditContent"
      v-model:merge-dialog-visible="mergeDialogVisible"
      v-model:merge-target-id="mergeTargetId"
      v-model:merge-display-name="mergeDisplayName"
      v-model:rename-dialog-visible="renameDialogVisible"
      v-model:rename-value="renameValue"
      :merge-selected-modules="mergeSelectedModules"
      :merge-source-modules="mergeSourceModules"
      :doc-edit-saving="docEditSaving"
      :merge-saving="mergeSaving"
      :rename-saving="renameSaving"
      @submit-doc-edit="submitDocEdit"
      @submit-merge="submitMerge"
      @submit-rename="submitRename"
    />

    <ScanProjectToolEditDialog
      v-model:form-dialog-visible="formDialogVisible"
      v-model:test-dialog-visible="testDialogVisible"
      :form="form"
      :http-methods="httpMethods"
      :parameter-locations="parameterLocations"
      :testing-tool="testingTool"
      :test-args="testArgs"
      :test-result="testResult"
      :saving="saving"
      :test-running="testRunning"
      @save="handleSave"
      @test="handleTest"
      @add-parameter="addParameter"
      @remove-parameter="removeParameter"
    />

    <ScanProjectToolDiffDialog
      v-model:visible="diffDialogVisible"
      :diff-dialog-row="diffDialogRow"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted } from 'vue'
import type { ProjectToolInfo } from '@/types/scanProject'
import ScanProjectApiGraphPanel from '@/views/scan/components/scan-project/ScanProjectApiGraphPanel.vue'
import ScanProjectHeader from '@/views/scan/components/scan-project/ScanProjectHeader.vue'
import ScanProjectModelGenerateDrawer from '@/views/scan/components/scan-project/ScanProjectModelGenerateDrawer.vue'
import ScanProjectModulesPanel from '@/views/scan/components/scan-project/ScanProjectModulesPanel.vue'
import ScanProjectOpsDrawer from '@/views/scan/components/scan-project/ScanProjectOpsDrawer.vue'
import ScanProjectOverviewCards from '@/views/scan/components/scan-project/ScanProjectOverviewCards.vue'
import ScanProjectScanRulesDrawer from '@/views/scan/components/scan-project/ScanProjectScanRulesDrawer.vue'
import ScanProjectSemanticDialogs from '@/views/scan/components/scan-project/ScanProjectSemanticDialogs.vue'
import ScanProjectToolDiffDialog from '@/views/scan/components/scan-project/ScanProjectToolDiffDialog.vue'
import ScanProjectToolEditDialog from '@/views/scan/components/scan-project/ScanProjectToolEditDialog.vue'
import ScanProjectToolsPanel from '@/views/scan/components/scan-project/ScanProjectToolsPanel.vue'
import {
  useScanProjectDetailData,
  type ScanProjectRefreshSideEffects,
} from '@/views/scan/composables/useScanProjectDetailData'
import { useScanProjectDetailNavigation } from '@/views/scan/composables/useScanProjectDetailNavigation'
import { useScanProjectUiState } from '@/views/scan/composables/useScanProjectUiState'
import { useScanProjectSettings } from '@/views/scan/composables/useScanProjectSettings'
import { useScanProjectSemanticDocs } from '@/views/scan/composables/useScanProjectSemanticDocs'
import { useScanProjectSummary } from '@/views/scan/composables/useScanProjectSummary'
import { useScanProjectToolDetails } from '@/views/scan/composables/useScanProjectToolDetails'
import { useScanProjectToolEditor, parameterRows } from '@/views/scan/composables/useScanProjectToolEditor'
import { useScanProjectToolOperations } from '@/views/scan/composables/useScanProjectToolOperations'

const refreshSideEffects: ScanProjectRefreshSideEffects = {
  loadSemanticAssets: async () => {},
  applyProjectLoaded: () => {},
  onToolsLoaded: () => {},
  onRefreshFailed: () => {},
}

const {
  activeWorkbenchTab,
  detailPanelActive,
  apiGraphMounted,
  interfaceCollapseActive,
} = useScanProjectUiState()

const {
  projectId,
  project,
  tools,
  loading,
  refreshAll,
} = useScanProjectDetailData({
  sideEffects: () => refreshSideEffects,
})

const {
  diffDialogVisible,
  diffDialogRow,
  toolDetailLoading,
  resetToolDetailCache,
  scanToolRowClassName,
  ensureToolDetail,
  handleToolExpandChange,
  toolLinkLabel,
  toolLinkTagType,
  openDiffDialog,
} = useScanProjectToolDetails({
  projectId,
  tools,
})

function onToolExpandChange(row: ProjectToolInfo, expanded: boolean) {
  void handleToolExpandChange(row, expanded ? [row] : [])
}

const {
  scanSettingsDrawerVisible: _scanSettingsDrawerVisible,
  authDrawerVisible: _authDrawerVisible,
  aiSettingsDrawerVisible: _aiSettingsDrawerVisible,
  modelGenerateDrawerVisible,
  scanRulesDrawerVisible,
  opsDrawerVisible,
  authSaving: _authSaving,
  authForm: _authForm,
  scanSettingsForm,
  scanSettingsSaving,
  descriptionSourceLabels,
  paramSourceLabels,
  allHttpMethods,
  isOpenApiMode,
  lastScannedDisplay: _lastScannedDisplay,
  projectAccessLabel,
  syncScanSettingsFormFromProject,
  syncAuthFormFromProject,
  setDescriptionSourceEnabled,
  setParamDescriptionSourceEnabled,
  moveDescriptionOrder,
  moveParamOrder,
  handleSaveScanSettings,
  saveAuthSettings: _saveAuthSettings,
} = useScanProjectSettings({
  projectId,
  project,
  refreshAll,
})

void _scanSettingsDrawerVisible
void _authDrawerVisible
void _aiSettingsDrawerVisible
void _authSaving
void _authForm
void _lastScannedDisplay
void _saveAuthSettings

const {
  saving,
  formDialogVisible,
  form,
  httpMethods,
  parameterLocations,
  testDialogVisible,
  testingTool,
  testArgs,
  testResult,
  testRunning,
  openEditDialog,
  addParameter,
  removeParameter,
  handleSave,
  handleEnabledChange,
  handleFlagChange,
  batchToggle,
  openTest,
  handleTest,
} = useScanProjectToolEditor({
  projectId,
  tools,
  ensureToolDetail,
  refreshAll,
})

const {
  modules,
  projectDoc,
  moduleDocMap,
  toolDocMap,
  selectedModuleIds,
  batchStarting,
  sensitiveScanStarting,
  sensitiveTask: _sensitiveTask,
  sensitiveTaskPolling,
  task,
  semanticModelInstances,
  semanticModelInstanceId,
  aiGenerationMode,
  docEditVisible,
  docEditContent,
  docEditSaving,
  mergeDialogVisible,
  mergeSelectedModules,
  mergeSourceModules,
  mergeTargetId,
  mergeDisplayName,
  mergeSaving,
  renameDialogVisible,
  renameValue,
  renameSaving,
  taskPercent,
  taskRunning,
  taskFailed,
  taskLabel: _taskLabel,
  taskTotalTokens: _taskTotalTokens,
  taskStageTagType: _taskStageTagType,
  taskFailedTitle,
  clearSemanticAssets,
  loadSemanticAssetsForRefresh,
  renderMd,
  sensitiveCellTooltip,
  toolDocSummary,
  loadSemanticModelInstances,
  restoreAiGenerationSettings,
  saveAiGenerationSettings,
  reloadAiTab,
  reloadSemanticUi: _reloadSemanticUi,
  resumeBatchTaskIfAny,
  startBatchGenerate,
  stopPollingTask,
  resumeSensitiveTaskIfAny,
  startSensitiveDataScanFlow,
  stopPollingSensitiveTask,
  regenerateModule,
  regenerateTool,
  openEditDoc,
  submitDocEdit,
  onModuleSelectionChange,
  openMergeDialog,
  submitMerge,
  openRenameDialog,
  submitRename,
} = useScanProjectSemanticDocs({
  projectId,
  project,
  tools,
  aiSettingsDrawerVisible: _aiSettingsDrawerVisible,
  modelGenerateDrawerVisible,
  opsDrawerVisible,
  refreshAll,
})

void _sensitiveTask
void _taskLabel
void _taskTotalTokens
void _taskStageTagType
void _reloadSemanticUi
void task

const {
  rescanLoading,
  rebuildEmbeddingLoading,
  reconcileLoading,
  promoteLoading,
  pushToGlobalLoading,
  unpromoteLoading,
  batchModulePromoteLoading,
  rescanSourceLoading,
  exportScanToolsExcelLoading,
  handleReconcile,
  handleRebuildEmbeddings,
  handleRescan,
  handleRescanToolFromSource,
  handlePromoteToGlobal,
  handlePushToGlobalTool,
  handleUnpromoteFromGlobal,
  handlePromoteModuleToGlobal,
  handleExportScanToolsExcel,
} = useScanProjectToolOperations({
  projectId,
  project,
  tools,
  refreshAll,
  reloadAiTab,
})

const {
  semanticCompletionPercent: _semanticCompletionPercent,
  linkedToolCount: _linkedToolCount,
  workbenchSummaryCards,
  assetSummaryItems,
  toolModuleGroups: _toolModuleGroups,
  visibleToolModuleGroups,
  hiddenModuleGroupCount,
  showMoreToolModuleGroups,
  resetVisibleToolModuleGroups,
  toolParameterCount,
} = useScanProjectSummary({
  project,
  tools,
  modules,
  projectDoc,
  moduleDocMap,
  toolDocMap,
  projectAccessLabel,
})

void _semanticCompletionPercent
void _linkedToolCount
void _toolModuleGroups

refreshSideEffects.loadSemanticAssets = loadSemanticAssetsForRefresh
refreshSideEffects.applyProjectLoaded = () => {
  syncAuthFormFromProject()
  syncScanSettingsFormFromProject()
}
refreshSideEffects.onToolsLoaded = () => {
  resetVisibleToolModuleGroups()
  resetToolDetailCache()
}
refreshSideEffects.onRefreshFailed = clearSemanticAssets

const {
  goBack,
  openModelGeneratePanel,
  openScanRulesPanel,
  openOpsPanel,
} = useScanProjectDetailNavigation({
  aiSettingsDrawerVisible: _aiSettingsDrawerVisible,
  modelGenerateDrawerVisible,
  scanRulesDrawerVisible,
  opsDrawerVisible,
})

onMounted(() => {
  restoreAiGenerationSettings()
  void refreshAll()
  void loadSemanticModelInstances()
  void resumeBatchTaskIfAny()
  void resumeSensitiveTaskIfAny()
})
onUnmounted(() => {
  stopPollingTask()
  stopPollingSensitiveTask()
})
</script>

<style scoped lang="scss">
@use './styles/ScanProjectDetail.scss';
</style>
