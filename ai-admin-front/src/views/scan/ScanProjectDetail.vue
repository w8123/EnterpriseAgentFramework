<template>
  <div class="registry-workbench-page api-catalog-workbench project-workbench-page">
    <ScanProjectHeader
      :project="project"
      :stage-advice="stageAdvice"
      :primary-action-loading="primaryActionLoading"
      :reconcile-loading="reconcileLoading"
      :loading="loading"
      @primary-action="handleGovernanceAction"
      @open-model-generate-panel="openModelGeneratePanel"
      @open-ops-panel="openOpsPanel"
      @reconcile="handleReconcile"
      @open-import-dialog="openImportDialog"
    />

    <ScanProjectAddInterfaceDialog
      v-model:visible="addInterfaceDialogVisible"
      v-model:active-tab="addInterfaceDialogTab"
      v-model:settings-visible="addInterfaceSettingsVisible"
      :project="project"
      :sync-loading="sdkScanLoading"
      :sync-result="sdkScanResult"
      :sync-error="sdkScanError"
      :rescan-loading="rescanLoading"
      :scan-settings-form="scanSettingsForm"
      :is-open-api-mode="isOpenApiMode"
      :description-source-labels="descriptionSourceLabels"
      :param-source-labels="paramSourceLabels"
      :all-http-methods="allHttpMethods"
      :scan-settings-saving="scanSettingsSaving"
      @scan-sdk="scanSdkCapabilities"
      @sdk-guide="openSdkAccessGuide"
      @rescan="handleRescan"
      @set-description-source-enabled="setDescriptionSourceEnabled"
      @set-param-description-source-enabled="setParamDescriptionSourceEnabled"
      @move-description-order="moveDescriptionOrder"
      @move-param-order="moveParamOrder"
      @save-scan-settings="handleSaveScanSettings"
    />

    <ScanProjectOverviewCards :stages="governanceStages" />

    <div v-if="project" class="api-asset-shortcut glass-surface-control">
      <p>这里仅展示来源发现与同步。请到所属项目目录接纳契约、配置连接、受控试调用，再在 Workflow 选择对应资产；无需复制 Tool。此处不推断单项资产身份。</p>
      <template v-if="ownerRoutes && canReadOwnerCatalogs">
        <router-link :to="ownerRoutes.businessMethods">业务方法目录</router-link>
        <router-link :to="ownerRoutes.apis">API 目录</router-link>
      </template>
      <span v-else>当前账号无该项目目录读取权限，或项目身份尚未确定。</span>
    </div>

    <el-tabs v-model="activeWorkbenchTab" class="workbench-tabs">
      <el-tab-pane label="模块管理" name="modules" />
      <el-tab-pane label="接口目录" name="tools" />
      <el-tab-pane label="接口图谱" name="apiGraph" />
    </el-tabs>

    <div class="scan-detail-sections">
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
        :stage-advice="stageAdvice"
        :visible-tool-module-groups="visibleToolModuleGroups"
        :hidden-module-group-count="hiddenModuleGroupCount"
        :tool-doc-map="toolDocMap"
        :sensitive-scan-starting="sensitiveScanStarting"
        :sensitive-task-polling="sensitiveTaskPolling"
        :export-scan-tools-excel-loading="exportScanToolsExcelLoading"
        :tool-detail-loading="toolDetailLoading"
        :rescan-source-loading="rescanSourceLoading"
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
        @tool-expand-change="onToolExpandChange"
        @open-diff="openDiffDialog"
        @rescan-from-source="handleRescanToolFromSource"
        @regenerate-tool="regenerateTool"
        @open-edit-doc="openEditDoc"
        @show-more-groups="showMoreToolModuleGroups"
        @empty-primary-action="handleGovernanceAction(stageAdvice.primaryAction)"
        @empty-secondary-action="stageAdvice.secondaryAction && handleGovernanceAction(stageAdvice.secondaryAction)"
      />

      <ScanProjectApiGraphPanel
        v-if="project && activeWorkbenchTab === 'apiGraph'"
        :project-id="projectId"
        :api-graph-mounted="apiGraphMounted"
        :panel-expanded="activeWorkbenchTab === 'apiGraph'"
      />
    </div>

    <ScanProjectModelGenerateDrawer
      v-model:visible="modelGenerateDrawerVisible"
      v-model:semantic-model-instance-id="semanticModelInstanceId"
      v-model:ai-generation-mode="aiGenerationMode"
      :semantic-model-instances="semanticModelInstances"
      :semantic-model-instances-loading="semanticModelInstancesLoading"
      :semantic-model-instances-load-error="semanticModelInstancesLoadError"
      :batch-starting="batchStarting"
      :task-running="taskRunning"
      :task-percent="taskPercent"
      :task-failed="taskFailed"
      :task-failed-title="taskFailedTitle"
      @start-batch-generate="startBatchGenerate"
      @save-ai-generation-settings="saveAiGenerationSettings"
      @refresh-model-instances="loadSemanticModelInstances"
    />

    <ScanProjectOpsDrawer
      v-model:visible="opsDrawerVisible"
      :rebuild-embedding-loading="rebuildEmbeddingLoading"
      @rebuild-embeddings="handleRebuildEmbeddings"
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


    <ScanProjectToolDiffDialog
      v-model:visible="diffDialogVisible"
      :diff-dialog-row="diffDialogRow"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { triggerSdkCapabilityScan } from '@/api/scanProject'
import type { ProjectToolInfo, SdkCapabilityScanResult } from '@/types/scanProject'
import ScanProjectAddInterfaceDialog from '@/views/scan/components/scan-project/ScanProjectAddInterfaceDialog.vue'
import ScanProjectApiGraphPanel from '@/views/scan/components/scan-project/ScanProjectApiGraphPanel.vue'
import ScanProjectHeader from '@/views/scan/components/scan-project/ScanProjectHeader.vue'
import ScanProjectModelGenerateDrawer from '@/views/scan/components/scan-project/ScanProjectModelGenerateDrawer.vue'
import ScanProjectModulesPanel from '@/views/scan/components/scan-project/ScanProjectModulesPanel.vue'
import ScanProjectOpsDrawer from '@/views/scan/components/scan-project/ScanProjectOpsDrawer.vue'
import ScanProjectOverviewCards from '@/views/scan/components/scan-project/ScanProjectOverviewCards.vue'
import ScanProjectSemanticDialogs from '@/views/scan/components/scan-project/ScanProjectSemanticDialogs.vue'
import ScanProjectToolDiffDialog from '@/views/scan/components/scan-project/ScanProjectToolDiffDialog.vue'
import ScanProjectToolsPanel from '@/views/scan/components/scan-project/ScanProjectToolsPanel.vue'
import {
  useScanProjectDetailData,
  type ScanProjectRefreshSideEffects,
} from '@/views/scan/composables/useScanProjectDetailData'
import { useScanProjectDetailNavigation } from '@/views/scan/composables/useScanProjectDetailNavigation'
import { useScanProjectUiState } from '@/views/scan/composables/useScanProjectUiState'
import { useScanProjectSettings } from '@/views/scan/composables/useScanProjectSettings'
import { useScanProjectSemanticDocs } from '@/views/scan/composables/useScanProjectSemanticDocs'
import { useScanProjectSummary, type ApiGovernanceAction } from '@/views/scan/composables/useScanProjectSummary'
import { useScanProjectToolDetails } from '@/views/scan/composables/useScanProjectToolDetails'
import { parameterRows } from '@/views/scan/scanProjectParameterRows'
import { scanProjectOwnerRoutes } from '@/views/scan/scanProjectOwnerRoutes'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_READ } from '@/auth/platformAccess'
import { platformSessionUser } from '@/auth/platformSession'
import { useScanProjectToolOperations } from '@/views/scan/composables/useScanProjectToolOperations'

const refreshSideEffects: ScanProjectRefreshSideEffects = {
  loadSemanticAssets: async () => {},
  applyProjectLoaded: () => {},
  onToolsLoaded: () => {},
  onRefreshFailed: () => {},
}
const router = useRouter()
const addInterfaceDialogVisible = ref(false)
const addInterfaceDialogTab = ref<'sdk' | 'aiCoding'>('sdk')
const addInterfaceSettingsVisible = ref(false)
const sdkScanLoading = ref(false)
const sdkScanResult = ref<SdkCapabilityScanResult | null>(null)
const sdkScanError = ref<string | null>(null)

const {
  activeWorkbenchTab,
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

const ownerRoutes = computed(() => scanProjectOwnerRoutes(project.value))
const canReadOwnerCatalogs = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_READ, 'PROJECT', null, project.value?.projectCode,
))

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
  scanRulesDrawerVisible: _scanRulesDrawerVisible,
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
void _scanRulesDrawerVisible
void _authSaving
void _authForm
void _lastScannedDisplay
void _saveAuthSettings


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
  semanticModelInstancesLoading,
  semanticModelInstancesLoadError,
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
  rescanSourceLoading,
  exportScanToolsExcelLoading,
  handleReconcile,
  handleRebuildEmbeddings,
  handleRescan,
  handleRescanToolFromSource,
  handleExportScanToolsExcel,
} = useScanProjectToolOperations({
  projectId,
  project,
  tools,
  refreshAll,
})

const {
  semanticCompletionPercent: _semanticCompletionPercent,
  linkedToolCount: _linkedToolCount,
  governanceStages,
  stageAdvice,
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

const primaryActionLoading = computed(() => {
  const action = stageAdvice.value.primaryAction
  if (action === 'scan') return rescanLoading.value
  if (action === 'generateAi') return batchStarting.value
  if (action === 'reconcile') return reconcileLoading.value
  if (action === 'scanSensitive') return sensitiveScanStarting.value || sensitiveTaskPolling.value
  if (action === 'refresh') return loading.value
  return false
})

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
  openModelGeneratePanel,
  openOpsPanel,
} = useScanProjectDetailNavigation({
  aiSettingsDrawerVisible: _aiSettingsDrawerVisible,
  modelGenerateDrawerVisible,
  scanRulesDrawerVisible: _scanRulesDrawerVisible,
  opsDrawerVisible,
})

function openSdkAccessGuide() {
  const projectCode = project.value?.projectCode?.trim()
  if (!projectCode) {
    void router.push('/registry/projects')
    return
  }
  void router.push({ name: 'SdkAccessWizard', params: { projectCode } })
}

function openToolsCatalog() {
  activeWorkbenchTab.value = 'tools'
}

function openImportDialog(tab: 'sdk' | 'aiCoding' = 'sdk') {
  addInterfaceDialogTab.value = tab
  addInterfaceSettingsVisible.value = false
  addInterfaceDialogVisible.value = true
}

function openScanRulesPanel() {
  addInterfaceDialogTab.value = 'sdk'
  addInterfaceSettingsVisible.value = true
  addInterfaceDialogVisible.value = true
}

async function scanSdkCapabilities() {
  sdkScanLoading.value = true
  sdkScanError.value = null
  try {
    const { data } = await triggerSdkCapabilityScan(projectId.value)
    sdkScanResult.value = data
    await refreshAll()
    ElMessage.success(`已扫描并同步 ${data.capabilityCount} 个 SDK 接口`)
  } catch (error) {
    const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    const message = responseMessage || (error instanceof Error ? error.message : 'SDK 扫描同步失败')
    sdkScanError.value = message
    ElMessage.error(message)
  } finally {
    sdkScanLoading.value = false
  }
}

function handleGovernanceAction(action: ApiGovernanceAction) {
  if (action === 'importApi') {
    openImportDialog()
  } else if (action === 'scan') {
    void handleRescan()
  } else if (action === 'generateAi') {
    void startBatchGenerate(false)
  } else if (action === 'reconcile') {
    void handleReconcile()
  } else if (action === 'viewOwnerCatalog') {
    if (!ownerRoutes.value || !canReadOwnerCatalogs.value) {
      ElMessage.warning('当前账号无该项目资产目录读取权限，或项目身份尚未确定。')
      return
    }
    void router.push(project.value?.projectKind === 'REGISTERED' ? ownerRoutes.value.businessMethods : ownerRoutes.value.apis)
  } else if (action === 'scanSensitive') {
    void startSensitiveDataScanFlow()
  } else if (action === 'refresh') {
    void refreshAll()
  } else if (action === 'scanRules') {
    openScanRulesPanel()
  } else if (action === 'modelSettings') {
    openModelGeneratePanel()
  } else if (action === 'ops') {
    openOpsPanel()
  } else {
    openToolsCatalog()
  }
}

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
.api-asset-shortcut { display: flex; align-items: center; justify-content: space-between;
  flex-wrap: wrap; gap: 12px; padding: 12px 16px; border: 1px solid var(--border-divider);
  border-radius: var(--radius-md); color: var(--text-secondary); }
.api-asset-shortcut p { margin: 0; }
.api-asset-shortcut a { color: var(--brand-active); font-weight: 600; text-decoration: none; }
.api-asset-shortcut a:hover { text-decoration: underline; }
.api-asset-shortcut a:focus-visible { outline: 2px solid var(--brand-active); outline-offset: 3px; }
</style>
