<template>
  <div class="source-workbench api-catalog-workbench project-list-controls" :class="{ 'source-graph-workbench': workspace === 'graph' }">
    <SourceImportDialog
      v-model:visible="addInterfaceDialogVisible"
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
      :can-write="canWriteSemanticDocs"
      @scan-sdk="scanSdkCapabilities"
      @sdk-guide="openSdkAccessGuide"
      @rescan="canWriteSemanticDocs && handleRescan()"
      @set-description-source-enabled="setDescriptionSourceEnabled"
      @set-param-description-source-enabled="setParamDescriptionSourceEnabled"
      @move-description-order="moveDescriptionOrder"
      @move-param-order="moveParamOrder"
      @save-scan-settings="canWriteSemanticDocs && handleSaveScanSettings()"
    />

    <el-alert v-if="sourceError" :title="sourceError" type="error" :closable="false" show-icon>
      <template #default><el-button @click="refreshAll">重新加载项目来源</el-button></template>
    </el-alert>
    <div v-else-if="loading && !project" role="status">正在读取项目接入信息…</div>
    <el-alert v-if="sourcePartialError" :title="sourcePartialError" type="warning" :closable="false" show-icon>
      <template #default><el-button @click="refreshAll">重新加载整理结果</el-button></template>
    </el-alert>
    <div class="scan-detail-sections">
      <section v-if="project && workspace === 'sources'" class="source-task-summary glass-surface-panel" aria-label="接入与变更任务">
        <header class="source-task-summary__header">
          <div><h2>接入与变更</h2><p>先处理需要确认的变化，再按需核对来源记录。</p></div>
          <SourceActions :stage-advice="stageAdvice" :primary-action-loading="primaryActionLoading"
            :reconcile-loading="reconcileLoading" :loading="loading" :workspace="workspace"
            :source-count="sourceStats.total" :sensitive-scan-starting="sensitiveScanStarting"
            :sensitive-task-polling="sensitiveTaskPolling" :export-loading="exportScanToolsExcelLoading"
            :sensitive-status-error="sensitiveStatusError"
            :can-write="canWriteSemanticDocs"
            @primary-action="handleGovernanceAction" @open-model-generate-panel="openModelGeneratePanel"
            @reconcile="handleReconcile" @open-import-dialog="openImportDialog"
            @start-sensitive-data-scan="startSensitiveDataScanFlow" @export-excel="handleExportScanToolsExcel" />
        </header>
        <el-alert v-if="sdkScanResult" type="success" :closable="false" show-icon
          :title="`最近同步已返回 ${sdkScanResult.capabilityCount} 条声明；接纳结果与待处理变化请在下方核对。`" />
        <el-alert v-if="sdkScanError" :title="sdkScanError" type="error" :closable="false" show-icon />
        <p v-if="project.lastScannedAt || project.registryStatusSummary" class="source-task-summary__unavailable" role="status">
          <span v-if="project.lastScannedAt">最近来源更新：{{ project.lastScannedAt }}</span>
          <span v-if="project.registryStatusSummary"> · {{ project.registryStatusSummary }}</span>
        </p>
        <ProjectSourceChanges v-if="project.projectCode" ref="sourceChangesRef" :project="project" density="compact" presentation="inline" />
        <p v-else class="source-task-summary__unavailable">当前来源尚未关联注册项目，可先核对来源记录；正式 API 的接纳结果在对应能力中查看。</p>
      </section>
      <SourceSemanticDocsPanel v-if="project && !sourcePartialError && workspace === 'semantics'" :docs="semanticDocs"
        :project-id="projectId" :project-doc="projectDoc" :loading="semanticLoading" :project-generating="projectDocGenerating"
        :generating-keys="semanticGeneratingKeys" :can-write="canWriteSemanticDocs"
        @edit="openEditDoc" @generate="regenerateSemanticDoc" @generate-project="regenerateProject">
        <template #toolbar>
          <el-button type="primary" @click="openModelGeneratePanel">批量补充说明</el-button>
          <el-button :loading="semanticLoading" @click="refreshAll">刷新说明</el-button>
        </template>
      </SourceSemanticDocsPanel>

      <section v-if="project && sourceContextActive" class="source-context glass-surface-panel" aria-label="当前能力的来源">
        <div class="source-task-return"><strong>{{ sourceContextTitle || '当前能力来源' }}</strong><el-button text @click="showAllSources">查看全部来源</el-button></div>
        <p v-if="sourceContextLoading" role="status">正在读取对应来源…</p>
        <p v-else-if="sourceContextError" role="alert">{{ sourceContextError }} <el-button @click="refreshSourceContext">重新读取</el-button></p>
        <p v-else-if="!loading && !sourceEvidenceTools.length" role="status">当前没有可核对的来源记录，来源关联可能已变化。请返回对应能力重读接入信息。</p>
        <p v-else role="status">当前聚焦 {{ sourceTotal }} 条来源记录；更多操作中的 Excel 导出仍包含整个项目。</p>
      </section>
      <SourceObservationsPanel
        v-if="project && !sourceError && workspace === 'sources' && (!sourceContextActive || (!sourceContextLoading && !sourceContextError && sourceEvidenceTools.length))"
        v-model:interface-collapse-active="interfaceCollapseActive"
        :project-id="projectId"
        :focus-observation-id="sourceContextActive && sourceContextIds.length === 1 ? sourceContextIds[0] : undefined"
        :loading="loading"
        :tools="sourceEvidenceTools"
        :can-write="canWriteSemanticDocs"
        :can-read-owner="canReadOwnerCatalogs"
        :generating-keys="semanticGeneratingKeys"
        :stage-advice="stageAdvice"
        :visible-tool-module-groups="toolModuleGroups"
        :total="sourceTotal" :project-total="sourceStats.total" :modules="sourceModules"
        :current="sourceCurrent" :page-size="sourceSize" :keyword="sourceQuery.filters.value.keyword" :module-filter="sourceQuery.filters.value.moduleId"
        :tool-doc-map="toolDocMap"
        :semantic-loading="semanticLoading"
        :semantic-error="sourcePartialError"
        :sensitive-scan-starting="sensitiveScanStarting"
        :sensitive-task-polling="sensitiveTaskPolling"
        :sensitive-status-error="sensitiveStatusError"
        :export-scan-tools-excel-loading="exportScanToolsExcelLoading"
        :tool-detail-loading="toolDetailLoading"
        :tool-detail-error="toolDetailError"
        :rescan-source-loading="rescanSourceLoading"
        :parameter-rows="parameterRows"
        :render-md="renderMd"
        :scan-tool-row-class-name="scanToolRowClassName"
        :tool-parameter-count="toolParameterCount"
        :tool-link-label="toolLinkLabel"
        :tool-link-tag-type="toolLinkTagType"
        @start-sensitive-data-scan="startSensitiveDataScanFlow"
        @retry-sensitive-status="resumeSensitiveTaskIfAny"
        @export-excel="handleExportScanToolsExcel"
        @tool-expand-change="onToolExpandChange"
        @open-diff="openDiffDialog"
        @rescan-from-source="handleRescanToolFromSource"
        @regenerate-tool="regenerateTool"
        @open-edit-doc="openEditDoc"
        @search="sourceQuery.applyField('keyword', $event)" @filter-module="sourceQuery.applyField('moduleId', $event)"
        @change-page="sourceQuery.changePage($event)"
        @empty-primary-action="handleGovernanceAction(stageAdvice.primaryAction)"
        @empty-secondary-action="stageAdvice.secondaryAction && handleGovernanceAction(stageAdvice.secondaryAction)"
      >
        <template #toolbar><span class="source-evidence-hint">核对发现证据与对应能力</span></template>
      </SourceObservationsPanel>

      <SourceGraphPanel
        v-if="project && workspace === 'graph'"
        :project-id="projectId"
        :api-graph-mounted="true"
        :panel-expanded="true"
        :can-write="canWriteSemanticDocs"
        @return-to-catalog="returnToCatalog"
      />
    </div>

    <SourceSemanticTasksDrawer
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
      :task-status-error="taskStatusError"
      :can-write="canWriteSemanticDocs"
      @start-batch-generate="startBatchGenerate"
      @save-ai-generation-settings="saveAiGenerationSettings"
      @refresh-model-instances="loadSemanticModelInstances"
      @retry-task-status="resumeBatchTaskIfAny"
    />

    <SourceSemanticDialogs
      v-model:doc-edit-visible="docEditVisible"
      v-model:doc-edit-content="docEditContent"
      :doc-edit-saving="docEditSaving"
      :can-write="canWriteSemanticDocs"
      @submit-doc-edit="submitDocEdit"
    />


    <SourceObservationDiffDialog
      v-model:visible="diffDialogVisible"
      :diff-dialog-row="diffDialogRow"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { triggerSdkCapabilityScan } from '@/api/scanProject'
import type { ProjectToolInfo, SdkCapabilityScanResult, SourcePageQuery } from '@/types/scanProject'
import { useCatalogQuery } from '@/composables/useCatalogQuery'
import SourceImportDialog from '@/views/capability/source/components/SourceImportDialog.vue'
import SourceGraphPanel from '@/views/capability/source/components/SourceGraphPanel.vue'
import SourceActions from '@/views/capability/source/components/SourceActions.vue'
import SourceSemanticTasksDrawer from '@/views/capability/source/components/SourceSemanticTasksDrawer.vue'
import ProjectSourceChanges from '@/views/registry/components/ProjectSourceChanges.vue'
import SourceSemanticDocsPanel from '@/views/capability/source/components/SourceSemanticDocsPanel.vue'
import SourceSemanticDialogs from '@/views/capability/source/components/SourceSemanticDialogs.vue'
import SourceObservationDiffDialog from '@/views/capability/source/components/SourceObservationDiffDialog.vue'
import SourceObservationsPanel from '@/views/capability/source/components/SourceObservationsPanel.vue'
import {
  useSourceWorkbenchData,
  type ScanProjectRefreshSideEffects,
} from '@/views/capability/source/composables/useSourceWorkbenchData'
import { useSourceSettings } from '@/views/capability/source/composables/useSourceSettings'
import { useSourceSemanticDocs } from '@/views/capability/source/composables/useSourceSemanticDocs'
import { useSourceSummary, type ApiGovernanceAction } from '@/views/capability/source/composables/useSourceSummary'
import { useSourceObservationDetails } from '@/views/capability/source/composables/useSourceObservationDetails'
import { parameterRows } from '@/views/capability/source/sourceParameterRows'
import { sourceOwnerRoutes } from '@/views/capability/source/sourceOwnerRoutes'
import { hasPlatformResourcePermission, PLATFORM_PERMISSION_READ, PLATFORM_PERMISSION_WRITE } from '@/auth/platformAccess'
import { platformSessionId, platformSessionUser } from '@/auth/platformSession'
import { useSourceOperations } from '@/views/capability/source/composables/useSourceOperations'
import { useSourceSubjectContext } from './composables/useSourceSubjectContext'
import { BUSINESS_CAPABILITY_ROOT, businessCapabilityWorkspaceLocation, type BusinessCapabilityWorkspace } from '../businessCapabilityRoutes'

const props = defineProps<{ projectId: number; workspace: BusinessCapabilityWorkspace }>()
const route = useRoute()
const sourceChangesRef = ref<InstanceType<typeof ProjectSourceChanges> | null>(null)
let disposed = false
onBeforeUnmount(() => { disposed = true })

const refreshSideEffects: ScanProjectRefreshSideEffects = {
  loadSemanticAssets: async () => {},
  applyProjectLoaded: () => {},
  onToolsLoaded: () => {},
  clearSemanticAssets: () => {},
}
const router = useRouter()
const addInterfaceDialogVisible = ref(false)
const addInterfaceSettingsVisible = ref(false)
const modelGenerateDrawerVisible = ref(false)
const sdkScanLoading = ref(false)
const sdkScanResult = ref<SdkCapabilityScanResult | null>(null)
const sdkScanError = ref<string | null>(null)

const interfaceCollapseActive = ref<string[]>([])
const sourceQuery = useCatalogQuery('source', ['keyword', 'moduleId'] as const)
const sourcePageQuery = computed<SourcePageQuery>(() => ({
  current: sourceQuery.page.value, size: sourceQuery.size.value, keyword: sourceQuery.filters.value.keyword,
  moduleId: /^\d+$/.test(sourceQuery.filters.value.moduleId) ? Number(sourceQuery.filters.value.moduleId) : undefined,
  ungrouped: sourceQuery.filters.value.moduleId === 'unclassified' || undefined,
  observationIds: sourceContextActive.value && sourceContextIds.value.length ? sourceContextIds.value : undefined,
  focusObservationId: sourceContextActive.value && sourceContextIds.value.length === 1 ? sourceContextIds.value[0] : undefined,
  defer: sourceContextActive.value && (sourceContextLoading.value || !!sourceContextError.value),
}))
let refreshingSourceContext = false

const {
  projectId,
  project,
  tools,
  loading,
  semanticLoading,
  error: sourceError,
  partialError: sourcePartialError,
  refreshAll: refreshSourceData,
  refreshSources, sourceStats, sourceModules, sourceTotal, sourceCurrent, sourceSize,
} = useSourceWorkbenchData({
  projectId: computed(() => props.projectId),
  workspace: computed(() => props.workspace),
  sourceQuery: sourcePageQuery,
  sideEffects: () => refreshSideEffects,
})
async function refreshAll() {
  const existingChanges = props.workspace === 'sources' ? sourceChangesRef.value : null
  if (project.value && sourceContextActive.value) {
    refreshingSourceContext = true
    try { await refreshSourceContext() } finally { refreshingSourceContext = false }
  }
  await Promise.all([refreshSourceData(), existingChanges?.refresh()])
}

const ownerRoutes = computed(() => sourceOwnerRoutes(project.value))
const canReadOwnerCatalogs = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_READ, 'PROJECT', null, project.value?.projectCode,
))
const canWriteSemanticDocs = computed(() => hasPlatformResourcePermission(
  platformSessionUser.value?.permissionGrants, PLATFORM_PERMISSION_WRITE, 'PROJECT', null, project.value?.projectCode,
))
const { active: sourceContextActive, observationIds: sourceContextIds, title: sourceContextTitle,
  loading: sourceContextLoading, error: sourceContextError, refresh: refreshSourceContext } = useSourceSubjectContext({
  projectId, workspace: () => props.workspace, query: () => route.query,
  canRead: () => !!project.value && canReadOwnerCatalogs.value, contextKey: () => platformSessionId.value || '',
})
const sourceEvidenceTools = computed(() => sourceContextActive.value ? tools.value.filter(row => sourceContextIds.value.includes(row.scanToolId)) : tools.value)
watch(() => [props.workspace, JSON.stringify(sourcePageQuery.value)] as const, ([workspace], [previousWorkspace]) => {
  if (!refreshingSourceContext && workspace === 'sources' && previousWorkspace === 'sources') void refreshSources()
}, { flush: 'post' })
function showAllSources() {
  void router.replace({ path: route.path, hash: route.hash, query: { ...route.query, subjectType: undefined, subjectId: undefined, subjectKey: undefined } })
}

const {
  diffDialogVisible,
  diffDialogRow,
  toolDetailLoading,
  toolDetailError,
  resetToolDetailCache,
  scanToolRowClassName,
  ensureToolDetail,
  handleToolExpandChange,
  toolLinkLabel,
  toolLinkTagType,
  openDiffDialog,
} = useSourceObservationDetails({
  projectId,
  tools,
})

function onToolExpandChange(row: ProjectToolInfo, expanded: boolean) {
  void handleToolExpandChange(row, expanded ? [row] : [])
}

const {
  scanSettingsForm,
  scanSettingsSaving,
  descriptionSourceLabels,
  paramSourceLabels,
  allHttpMethods,
  isOpenApiMode,
  syncScanSettingsFormFromProject,
  setDescriptionSourceEnabled,
  setParamDescriptionSourceEnabled,
  moveDescriptionOrder,
  moveParamOrder,
  handleSaveScanSettings,
} = useSourceSettings({ projectId, project })

const {
  projectDoc,
  semanticDocs,
  semanticGeneratingKeys,
  regenerateSemanticDoc,
  projectDocGenerating,
  regenerateProject,
  toolDocMap,
  batchStarting,
  taskStatusError,
  sensitiveStatusError,
  sensitiveScanStarting,
  sensitiveTaskPolling,
  semanticModelInstances,
  semanticModelInstancesLoading,
  semanticModelInstancesLoadError,
  semanticModelInstanceId,
  aiGenerationMode,
  docEditVisible,
  docEditContent,
  docEditSaving,
  taskPercent,
  taskRunning,
  taskFailed,
  taskFailedTitle,
  clearSemanticAssets,
  loadSemanticAssetsForRefresh,
  renderMd,
  loadSemanticModelInstances,
  restoreAiGenerationSettings,
  saveAiGenerationSettings,
  resumeBatchTaskIfAny,
  stopPollingTask,
  startBatchGenerate,
  resumeSensitiveTaskIfAny,
  stopPollingSensitiveTask,
  startSensitiveDataScanFlow,
  regenerateTool,
  openEditDoc,
  submitDocEdit,
} = useSourceSemanticDocs({
  projectId,
  project,
  tools,
  modelGenerateDrawerVisible,
  refreshAll,
  canWrite: () => canWriteSemanticDocs.value,
})


const {
  rescanLoading,
  reconcileLoading,
  rescanSourceLoading,
  exportScanToolsExcelLoading,
  handleReconcile,
  handleRescan,
  handleRescanToolFromSource,
  handleExportScanToolsExcel,
} = useSourceOperations({
  projectId,
  project,
  tools,
  sourceCount: computed(() => sourceStats.value.total),
  refreshAll,
})

const {
  stageAdvice,
  toolModuleGroups,
  toolParameterCount,
} = useSourceSummary({
  project,
  tools,
  statistics: sourceStats,
})

let initializedGroups = false
const knownGroups = new Set<string>()
watch(toolModuleGroups, groups => {
  if (sourceContextActive.value) { interfaceCollapseActive.value = groups.map(group => group.key); return }
  if (!initializedGroups && groups.length) {
    interfaceCollapseActive.value = [groups[0].key]
    initializedGroups = true
  } else if (initializedGroups) {
    interfaceCollapseActive.value = [...new Set([...interfaceCollapseActive.value, ...groups.filter(group => !knownGroups.has(group.key)).map(group => group.key)])]
  }
  for (const group of groups) knownGroups.add(group.key)
})


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
  syncScanSettingsFormFromProject()
}
refreshSideEffects.onToolsLoaded = () => {
  resetToolDetailCache()
}
refreshSideEffects.clearSemanticAssets = clearSemanticAssets

function openModelGeneratePanel() { modelGenerateDrawerVisible.value = true }

function openSdkAccessGuide() {
  const projectCode = project.value?.projectCode?.trim()
  if (!projectCode) {
    void router.push('/registry/projects')
    return
  }
  void router.push({ name: 'SdkAccessWizard', params: { projectCode } })
}

function openToolsCatalog() {
  const category = route.params.category === 'http-apis' ? 'http-apis' : 'java-methods'
  void router.push(businessCapabilityWorkspaceLocation(category, 'sources',
    { ...route.query, projectId: String(props.projectId), projectCode: project.value?.projectCode || undefined }))
}

function openImportDialog() {
  addInterfaceSettingsVisible.value = false
  addInterfaceDialogVisible.value = true
}

function openScanRulesPanel() {
  addInterfaceSettingsVisible.value = true
  addInterfaceDialogVisible.value = true
}

function returnToCatalog() {
  const category = route.params.category === 'http-apis' ? 'http-apis' : 'java-methods'
  void router.push({ path: `${BUSINESS_CAPABILITY_ROOT}/${category}`, query: { ...route.query, projectId: String(props.projectId),
    subjectType: undefined, subjectId: undefined, subjectKey: undefined } })
}

async function scanSdkCapabilities() {
  if (disposed || sdkScanLoading.value || !canWriteSemanticDocs.value) return
  sdkScanLoading.value = true
  sdkScanError.value = null
  try {
    const { data } = await triggerSdkCapabilityScan(projectId.value)
    if (disposed) return
    if (data.projectId !== projectId.value || (project.value?.projectCode && data.projectCode !== project.value.projectCode)
      || !Number.isSafeInteger(data.capabilityCount) || data.capabilityCount < 0) throw new Error('SDK 同步返回的项目身份未确认，请重新核对')
    sdkScanResult.value = data
    await refreshAll()
    if (disposed) return
    ElMessage.success(`已扫描并同步 ${data.capabilityCount} 个 SDK 接口`)
  } catch (error) {
    if (disposed) return
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
    if (canWriteSemanticDocs.value) void handleRescan()
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
  } else {
    openToolsCatalog()
  }
}

watch(modelGenerateDrawerVisible, visible => {
  if (visible) void loadSemanticModelInstances()
})
watch(() => props.workspace, workspace => {
  stopPollingTask()
  stopPollingSensitiveTask()
  restoreAiGenerationSettings()
  void refreshAll()
  if (workspace === 'sources' || workspace === 'semantics') {
    void resumeBatchTaskIfAny()
    void resumeSensitiveTaskIfAny()
  }
}, { immediate: true })
</script>

<style scoped lang="scss">
@use './styles/SourceWorkbenchPanel.scss';
</style>
