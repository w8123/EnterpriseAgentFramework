import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const repositoryRoot = path.resolve(frontendRoot, '..')
const failures = []

function read(relativePath, root = frontendRoot) {
  return fs.readFileSync(path.join(root, relativePath), 'utf8')
}

function expectIncludes(source, fragment, label) {
  if (!source.includes(fragment)) failures.push(`${label} is missing ${fragment}`)
}

function expectExcludes(source, fragment, label) {
  if (source.includes(fragment)) failures.push(`${label} still contains ${fragment}`)
}

function expectMatches(source, pattern, label) {
  if (!pattern.test(source)) failures.push(`${label} does not match ${pattern}`)
}

const wizard = read('src/views/registry/PageAssistantWizard.vue')
const tabs = read('src/views/registry/components/page-workbench/PageWorkbenchTabs.vue')
const map = read('src/views/registry/components/page-workbench/PageWorkbenchMapPanel.vue')
const pageCover = read(
  'src/views/registry/components/page-workbench/PageWorkbenchPageCover.vue',
)
const pageDetail = read(
  'src/views/registry/components/page-workbench/PageWorkbenchPageDetail.vue',
)
const pageResourcePanel = read(
  'src/views/registry/components/page-workbench/PageWorkbenchResourcePanel.vue',
)
const pageDiagnosticsPanel = read(
  'src/views/registry/components/page-workbench/PageWorkbenchDiagnosticsPanel.vue',
)
const pageDebugPanel = read(
  'src/views/registry/components/page-workbench/PageWorkbenchDebugPanel.vue',
)
const pageActionPanel = read(
  'src/views/registry/components/page-workbench/PageWorkbenchActionPanel.vue',
)
const analysisRecommendationDialog = read(
  'src/views/registry/components/page-workbench/PageWorkbenchAnalysisRecommendationDialog.vue',
)
const pageInformationDialog = read(
  'src/views/registry/components/page-workbench/PageWorkbenchPageInfoDialog.vue',
)
const historyDrawer = read(
  'src/views/registry/components/page-workbench/PageWorkbenchHistoryDrawer.vue',
)
const publishConfirmDialog = read(
  'src/views/registry/components/page-workbench/PageWorkbenchPublishConfirmDialog.vue',
)
const acceptanceDialog = read(
  'src/views/registry/components/page-workbench/PageWorkbenchAcceptanceDialog.vue',
)
const tasks = read('src/views/registry/components/page-workbench/PageWorkbenchTasksPanel.vue')
const providerSelector = read('src/components/ai-coding/AiCodingProviderSelector.vue')
const aiCodingProviderIconPaths = [
  'src/assets/ai-coding-providers/codex.svg',
  'src/assets/ai-coding-providers/cursor.svg',
  'src/assets/ai-coding-providers/trae.svg',
  'src/assets/ai-coding-providers/claude-code.svg',
  'src/assets/ai-coding-providers/opencode.svg',
]
const aiCodingProviderIcons = aiCodingProviderIconPaths.map((iconPath) => read(iconPath))
const aiCodingProviderIconLicense = read('src/assets/ai-coding-providers/LICENSE.md')
const published = read('src/views/registry/components/page-workbench/PageWorkbenchPublishedPanel.vue')
const manualPageForm = read(
  'src/views/registry/components/page-workbench/PageWorkbenchManualPageForm.vue',
)
const handoffPanel = read(
  'src/views/registry/components/page-workbench/AiCodingHandoffPanel.vue',
)
const workflowDelivery = read(
  'src/views/registry/components/page-workbench/PageWorkbenchWorkflowDeliveryCard.vue',
)
const styles = read('src/views/registry/styles/PageWorkbench.scss')
const composable = read('src/views/registry/composables/useBusinessPageWorkbench.ts')
const loadErrorState = read(
  'src/views/registry/components/ProjectWorkbenchLoadErrorState.vue',
)
const api = read('src/api/pageWorkbench.ts')
const handoffPromptFactory = read(
  'reachai-control-service/src/main/java/com/enterprise/ai/control/aicoding/application/AiCodingHandoffPromptFactory.java',
  repositoryRoot,
)
const pageMapProvider = read(
  'reachai-control-service/src/main/java/com/enterprise/ai/control/pageworkbench/application/PageMapScanTaskProvider.java',
  repositoryRoot,
)
const accessCenterOverviewService = read(
  'reachai-control-service/src/main/java/com/enterprise/ai/control/pageworkbench/application/PageAccessCenterOverviewApplicationService.java',
  repositoryRoot,
)
const pageWorkbenchController = read(
  'reachai-control-service/src/main/java/com/enterprise/ai/control/pageworkbench/api/PageWorkbenchConsoleController.java',
  repositoryRoot,
)
const taskDetailPanel = read('src/components/ai-coding/AiCodingTaskDetailPanel.vue')
const artifactEvidence = read('src/components/ai-coding/AiCodingArtifactEvidence.vue')
const aiCodingPresentation = read('src/utils/aiCodingPresentation.ts')
const aiCodingGoalSelection = read('src/utils/aiCodingGoalSelection.ts')
const pageWorkbenchPresentation = read('src/utils/pageWorkbenchPresentation.ts')
const mainLayout = read('src/views/layout/MainLayout.vue')
const router = read('src/router/index.ts')

for (const label of ['页面', '活动记录', '在线能力']) {
  expectIncludes(tabs, `label: '${label}'`, 'PageWorkbenchTabs')
}
expectExcludes(tabs, "label: '分析结果'", 'PageWorkbenchTabs')
expectExcludes(tabs, "'analysis' as const", 'PageWorkbenchTabs')
expectExcludes(tabs, 'analysisCount', 'PageWorkbenchTabs')
for (const forbidden of ['1/4', '2/4', '3/4', '4/4', 'completed', 'step-index']) {
  expectExcludes(tabs, forbidden, 'PageWorkbenchTabs')
}

expectIncludes(wizard, 'title="页面接入中心"', 'PageAssistantWizard')
expectIncludes(wizard, '无需理解任务类型', 'PageAssistantWizard')
expectExcludes(wizard, ':analysis-count="findings.length"', 'PageAssistantWizard')
expectExcludes(wizard, "activeTab === 'analysis'", 'PageAssistantWizard')
expectExcludes(wizard, 'PageWorkbenchAnalysisPanel', 'PageAssistantWizard')
expectExcludes(wizard, 'openAnalysisWorkspace', 'PageAssistantWizard')
expectExcludes(wizard, 'advancedWorkspace', 'PageAssistantWizard')
expectExcludes(wizard, 'page-access-advanced-heading', 'PageAssistantWizard')
expectExcludes(wizard, '高级功能', 'PageAssistantWizard')
expectExcludes(wizard, 'openAdvancedWorkspace', 'PageAssistantWizard')
expectExcludes(wizard, 'AI 任务记录', 'PageAssistantWizard')
expectExcludes(wizard, 'toggleTaskRecords', 'PageAssistantWizard')
expectExcludes(wizard, 'AI 编程任务管理', 'PageAssistantWizard')
expectExcludes(wizard, '<el-dropdown-item command="tasks">', 'PageAssistantWizard')
expectExcludes(wizard, '保留的专业入口', 'PageAssistantWizard')
expectExcludes(wizard, 'el-dropdown', 'PageAssistantWizard')
expectIncludes(mainLayout, ':show-back="breadcrumbShowBack"', 'MainLayout')
expectIncludes(router, "path: 'registry/projects/:projectCode/page-assistant'", 'router')
expectIncludes(router, 'breadcrumbShowBack: false', 'page workbench route')
expectIncludes(wizard, 'v-model="activeTab"', 'PageAssistantWizard')
expectIncludes(wizard, 'ProjectWorkbenchLoadErrorState', 'PageAssistantWizard')
expectIncludes(wizard, 'v-if="loadError"', 'PageAssistantWizard')
expectIncludes(wizard, '@retry="loadAll"', 'PageAssistantWizard')
expectIncludes(composable, "const loadError = ref('')", 'useBusinessPageWorkbench')
expectIncludes(composable, "const tasksLoadError = ref('')", 'useBusinessPageWorkbench')
expectIncludes(composable, 'if (tasksLoadError.value)', 'useBusinessPageWorkbench')
expectIncludes(composable, 'clearWorkbenchData()', 'useBusinessPageWorkbench')
expectIncludes(loadErrorState, '重新加载', 'ProjectWorkbenchLoadErrorState')
expectIncludes(wizard, ':unavailable="tasksLoadError"', 'PageAssistantWizard')
expectIncludes(tasks, '任务状态恢复前不会创建新任务', 'PageWorkbenchTasksPanel')
expectIncludes(tasks, "activityMode ? '活动记录' : 'AI 任务记录'", 'PageWorkbenchTasksPanel')
for (const iconPath of aiCodingProviderIconPaths) {
  expectIncludes(tasks, iconPath.split('/').at(-1), 'PageWorkbenchTasksPanel provider icons')
}
for (const icon of aiCodingProviderIcons) {
  expectIncludes(icon, '<svg', 'AI Coding provider icon asset')
}
expectIncludes(tasks, 'providerIcon(task.executorProvider)', 'PageWorkbenchTasksPanel provider icon')
expectIncludes(tasks, 'task-row__provider-fallback', 'PageWorkbenchTasksPanel provider fallback')
expectIncludes(aiCodingProviderIconLicense, '1.94.0', 'AI Coding provider icon license')
expectIncludes(wizard, 'v-model="sourceDialogTab"', 'PageAssistantWizard')
expectIncludes(wizard, '@scan="openPageSourceDialog"', 'PageAssistantWizard')
expectIncludes(wizard, 'AI 编程扫描', 'PageAssistantWizard')
expectIncludes(wizard, '手动添加', 'PageAssistantWizard')
expectIncludes(wizard, 'v-model="scanTaskForm.provider"', 'PageAssistantWizard')
expectIncludes(wizard, 'v-model="taskForm.provider"', 'PageAssistantWizard')
expectIncludes(wizard, 'AiCodingProviderSelector', 'PageAssistantWizard provider selector')
expectMatches(
  wizard,
  /<AiCodingProviderSelector[\s\S]*?v-model="scanTaskForm\.provider"/,
  'page-map scan icon provider selector',
)
expectMatches(
  wizard,
  /<AiCodingProviderSelector[\s\S]*?v-model="taskForm\.provider"/,
  'AI Coding task icon provider selector',
)
expectExcludes(wizard, '<el-radio-button', 'PageAssistantWizard provider selector')
expectIncludes(providerSelector, '<el-tooltip', 'AI Coding provider selector tooltip')
expectIncludes(providerSelector, ':content="option.label"', 'AI Coding provider selector software name')
expectIncludes(wizard, '@click="submitPageMapScan"', 'PageAssistantWizard')
expectIncludes(wizard, "type AiCodingDialogStage = 'FORM' | 'HANDOFF'", 'PageAssistantWizard')
expectIncludes(wizard, "sourceDialogStage.value = 'HANDOFF'", 'PageAssistantWizard')
expectIncludes(wizard, "taskDialogStage.value = 'HANDOFF'", 'PageAssistantWizard')
expectIncludes(wizard, 'AiCodingHandoffPanel', 'PageAssistantWizard')
expectIncludes(wizard, '创建并生成交接包', 'PageAssistantWizard')
expectMatches(
  wizard,
  /setTaskHandoff\(result\.handoff\)\s*sourceDialogStage\.value = 'HANDOFF'/,
  'page-map scan same-dialog handoff',
)
expectMatches(
  wizard,
  /setTaskHandoff\(result\.handoff\)\s*taskDialogStage\.value = 'HANDOFF'/,
  'AI Coding task same-dialog handoff',
)
expectIncludes(wizard, 'PageWorkbenchManualPageForm', 'PageAssistantWizard')
expectIncludes(wizard, '@submit="submitManualPage"', 'PageAssistantWizard')
expectIncludes(manualPageForm, 'v-model="form.pageKey"', 'PageWorkbenchManualPageForm')
expectIncludes(manualPageForm, '页面操作', 'PageWorkbenchManualPageForm')
expectIncludes(manualPageForm, 'sourceType === \'MANUAL\'', 'PageWorkbenchManualPageForm')
expectIncludes(handoffPanel, '任务已创建', 'AiCodingHandoffPanel')
expectIncludes(handoffPanel, '一次性交接包', 'AiCodingHandoffPanel')
expectIncludes(handoffPanel, 'activationExpiresAt', 'AiCodingHandoffPanel')
expectIncludes(handoffPanel, 'aiCodingProviderLabel', 'AiCodingHandoffPanel')
expectExcludes(wizard, '<div class="handoff-heading">', 'PageAssistantWizard')
expectExcludes(wizard, 'page-source-options', 'PageAssistantWizard')
for (const redundantSourceDetail of [
  'AI CODING · PAGE MAP',
  'MANUAL PAGE',
  'page-source-pane__scope',
  'manualDialogVisible',
  'openPageMapScan',
  'openManualPage',
]) {
  expectExcludes(wizard, redundantSourceDetail, 'PageAssistantWizard')
}
expectIncludes(styles, '.page-source-tabs', 'PageWorkbench styles')
expectExcludes(styles, '.page-source-options', 'PageWorkbench styles')
expectMatches(
  styles,
  /\.business-page-workbench\s*\{[\s\S]*?height:\s*100%;[\s\S]*?min-height:\s*0;/,
  'PageWorkbench viewport boundary',
)
expectMatches(
  styles,
  /\.page-access-pages\.page-workbench-section:not\(\.is-empty\)\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?overflow:\s*hidden;/,
  'Page Access Center viewport boundary',
)
expectMatches(
  styles,
  /\.page-access-card-list\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?overflow-y:\s*auto;/,
  'Page Access Center list internal scrolling',
)
expectMatches(
  styles,
  /\.page-goal-workspace\s*\{[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\)\s+280px;/,
  'Page goal adaptive workspace',
)
expectMatches(
  styles,
  /\.page-map-section:not\(\.is-empty\)\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?overflow:\s*hidden;/,
  'Page map bounded section',
)
expectMatches(
  styles,
  /\.page-map-section:not\(\.is-empty\)\s*\{[\s\S]*?padding:\s*0;[\s\S]*?border:\s*0;[\s\S]*?box-shadow:\s*none;/,
  'Page map flat outer section',
)
expectMatches(
  styles,
  /\.page-map-grid\s*\{\s*min-height:\s*0;\s*flex:\s*1;/,
  'Page map flexible grid',
)
expectMatches(
  styles,
  /\.page-map-grid\s*\{[\s\S]*?border:\s*0;/,
  'Page map borderless content grid',
)
expectMatches(
  styles,
  /\.page-map-grid\s*\{[\s\S]*?grid-template-columns:\s*236px\s+minmax\(0,\s*1fr\);/,
  'Page map module-sidebar layout',
)
expectMatches(
  styles,
  /\.page-map-modules\s*\{[\s\S]*?flex-direction:\s*column;[\s\S]*?border-right:\s*1px solid var\(--border-divider\);/,
  'Page map desktop module sidebar',
)
expectIncludes(styles, '.page-map-module-list', 'Page map module list')
expectMatches(
  styles,
  /\.page-map-page-grid\s*\{[\s\S]*?grid-template-columns:\s*repeat\(auto-fill,\s*minmax\(280px,\s*1fr\)\);/,
  'Page map responsive page-card grid',
)
expectMatches(
  styles,
  /\.page-map-card-cover\s*\{[\s\S]*?aspect-ratio:\s*16\s*\/\s*9;/,
  'Page map screenshot-cover boundary',
)
expectMatches(
  styles,
  /\.page-detail__actions\s*\{[\s\S]*?position:\s*sticky;/,
  'Page detail reachable quick actions',
)
expectMatches(
  styles,
  /\.task-section\s*\{[\s\S]*?display:\s*flex;[\s\S]*?min-height:\s*0;[\s\S]*?overflow:\s*hidden;/,
  'AI coding task panel height boundary',
)
expectMatches(
  styles,
  /\.task-section\s*>\s*\.task-list\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?flex:\s*1;[\s\S]*?overflow-y:\s*auto;/,
  'AI coding task list internal scrolling',
)
expectIncludes(wizard, '复制交接包', 'PageAssistantWizard')
expectIncludes(wizard, 'answerQuestion', 'PageAssistantWizard')
expectExcludes(wizard, '@create-task="openFindingDetail"', 'PageAssistantWizard')
expectIncludes(wizard, '@accept="startPublishedAcceptance"', 'PageAssistantWizard')
expectIncludes(wizard, '@run="openPublishedRun"', 'PageAssistantWizard')
expectIncludes(map, 'page-access-layout', 'PageWorkbenchMapPanel')
expectIncludes(map, '扫描新页面', 'PageWorkbenchMapPanel')
expectIncludes(map, 'page-access-view-toggle', 'PageWorkbenchMapPanel')
expectIncludes(map, "viewMode === 'card'", 'PageWorkbenchMapPanel')
expectIncludes(map, 'page-map-page-grid', 'PageWorkbenchMapPanel')
expectIncludes(map, 'page-map-card', 'PageWorkbenchMapPanel')
expectIncludes(map, '查看详情', 'PageWorkbenchMapPanel')
expectIncludes(map, 'relatedContentLabel', 'PageWorkbenchMapPanel')
expectIncludes(map, 'completedSteps', 'PageWorkbenchMapPanel')
expectIncludes(
  map,
  '{{ completedSteps(journeyMap.get(page.pageKey)) }} / 4',
  'PageWorkbenchMapPanel',
)
expectExcludes(map, '先找到要处理的页面', 'PageWorkbenchMapPanel')
expectExcludes(map, 'page-map-toolbar', 'PageWorkbenchMapPanel')
expectExcludes(map, '页面地图已扫描', 'PageWorkbenchMapPanel')
expectExcludes(map, 'page-map-sync-strip', 'PageWorkbenchMapPanel')
expectIncludes(map, 'pageWorkbenchPageName(page)', 'PageWorkbenchMapPanel')
expectIncludes(map, 'pageWorkbenchSourceLabel', 'PageWorkbenchMapPanel')
expectIncludes(map, 'PageWorkbenchPageCover', 'PageWorkbenchMapPanel')
expectIncludes(map, '@click="emit(\'detail\', page)"', 'PageWorkbenchMapPanel')
expectExcludes(map, 'selectedPage:', 'PageWorkbenchMapPanel')
expectExcludes(map, 'page-map-detail', 'PageWorkbenchMapPanel')
expectIncludes(pageCover, 'imageUrl?: string', 'PageWorkbenchPageCover')
expectIncludes(pageCover, 'v-if="imageUrl"', 'PageWorkbenchPageCover')
expectIncludes(pageCover, 'page-map-card-cover__placeholder', 'PageWorkbenchPageCover')
expectIncludes(pageDetail, '接入历程', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '接入诊断', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '页面信息', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '确认并开始接入', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '选择目标', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, 'AI 实施', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '确认上线', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '真实验收', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, "journey.status === 'UNAVAILABLE'", 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, 'AiCodingTaskDetailPanel', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, 'variant="inline"', 'PageWorkbenchPageDetail')
expectExcludes(pageDetail, 'inline-task-question', 'PageWorkbenchPageDetail')
expectExcludes(pageDetail, '查看完整活动', 'PageWorkbenchPageDetail')
expectExcludes(pageDetail, '不使用前端计时器模拟', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '让 AI Coding 只读分析', 'PageWorkbenchPageDetail')
expectExcludes(pageDetail, '新建 AI 编程任务', 'PageWorkbenchPageDetail')
expectExcludes(pageDetail, 'createTask:', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, 'page-detail-workbench-header', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, 'page-detail-capability-summary', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '接入目标', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '选择本次接入目标', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '本次接入目标', 'PageWorkbenchPageDetail locked goals')
expectIncludes(pageDetail, '当前活动目标已锁定', 'PageWorkbenchPageDetail locked goals')
expectIncludes(pageDetail, 'target-source-locked', 'PageWorkbenchPageDetail locked goal empty state')
expectIncludes(pageDetail, '<el-icon><Lock /></el-icon>', 'PageWorkbenchPageDetail locked goal icon')
expectIncludes(pageDetail, 'goal-lock-state', 'PageWorkbenchPageDetail lock badge in content')
expectMatches(
  pageDetail,
  /target-source-locked[\s\S]*?goal-lock-state[\s\S]*?当前活动目标已锁定/,
  'PageWorkbenchPageDetail lock badge lives in locked empty state',
)
expectExcludes(pageDetail, 'target-source-column__mask', 'PageWorkbenchPageDetail blur mask removed')
expectIncludes(styles, '.target-source-locked', 'PageWorkbench locked goal empty state styles')
expectExcludes(styles, '.target-source-column__mask', 'PageWorkbench blur mask styles removed')
expectIncludes(pageDetail, '查看 AI 实施进度', 'PageWorkbenchPageDetail locked goals')
expectIncludes(pageDetail, 'goalsLocked', 'PageWorkbenchPageDetail locked goals')
expectIncludes(pageDetail, 'target-selection-workspace', 'PageWorkbenchPageDetail unified goals layout')
expectExcludes(pageDetail, 'journey-target-review-shell', 'PageWorkbenchPageDetail removed snapshot page')
expectExcludes(pageDetail, '目标快照', 'PageWorkbenchPageDetail removed snapshot page')
expectExcludes(pageDetail, 'journey-target-review-aside', 'PageWorkbenchPageDetail removed snapshot aside')
expectExcludes(styles, 'journey-target-review-hero', 'PageWorkbench styles removed snapshot page')
expectIncludes(styles, 'goal-lock-state', 'PageWorkbench styles locked goals')
expectIncludes(pageDetail, ':type="findingRiskTagType(finding.operationRisk)"', 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '<el-icon><Close /></el-icon>', 'PageWorkbenchPageDetail')
expectExcludes(pageDetail, '<h2>当前任务</h2>', 'PageWorkbenchPageDetail')
expectExcludes(pageDetail, '需要更多信息时再展开', 'PageWorkbenchPageDetail')
expectExcludes(pageDetail, 'page-detail-support', 'PageWorkbenchPageDetail')
expectIncludes(wizard, "query: { ...route.query, pageId: String(page.id) }", 'PageAssistantWizard')
expectIncludes(wizard, 'v-else-if="detailMode"', 'PageAssistantWizard')
expectExcludes(wizard, 'pageDrawerVisible', 'PageAssistantWizard')
expectIncludes(wizard, '@detail="openPageDetail"', 'PageAssistantWizard')
expectIncludes(wizard, 'PageWorkbenchPageDetail', 'PageAssistantWizard')
expectIncludes(wizard, ':task-detail="selectedPageTaskDetail"', 'PageAssistantWizard')
expectIncludes(wizard, 'loadImplementationTaskDetail', 'PageAssistantWizard')
expectIncludes(wizard, 'PageWorkbenchAnalysisRecommendationDialog', 'PageAssistantWizard')
expectExcludes(wizard, 'PageWorkbenchResourceActionDialog', 'PageAssistantWizard')
expectExcludes(wizard, 'actionWorkbenchVisible', 'PageAssistantWizard')
expectIncludes(wizard, 'PageWorkbenchHistoryDrawer', 'PageAssistantWizard')
expectIncludes(wizard, 'PageWorkbenchPublishConfirmDialog', 'PageAssistantWizard')
expectIncludes(wizard, 'PageWorkbenchAcceptanceDialog', 'PageAssistantWizard')
expectExcludes(wizard, 'PageWorkbenchRecoveryDialog', 'PageAssistantWizard')
expectExcludes(wizard, '接入说明', 'PageAssistantWizard')
expectExcludes(wizard, 'openJourneyRecovery', 'PageAssistantWizard')
expectIncludes(wizard, '@refresh="refreshWorkbenchStatus"', 'PageAssistantWizard')
expectExcludes(wizard, 'page-access-summary', 'PageAssistantWizard')
expectExcludes(wizard, '页面接入概览', 'PageAssistantWizard')
expectExcludes(styles, '.page-access-summary', 'PageWorkbench styles')
expectExcludes(wizard, 'page-access-guidance', 'PageAssistantWizard')
expectExcludes(styles, '.page-access-guidance', 'PageWorkbench styles')
expectMatches(
  styles,
  /\.page-detail-workbench-header\s*\{[\s\S]*?min-height:\s*72px;[\s\S]*?border-radius:\s*14px;[\s\S]*?background:\s*var\(--surface-solid-overlay\);/,
  'Page detail compact solid header',
)
expectMatches(
  styles,
  /\.page-detail-capability-summary\s*\{[\s\S]*?min-height:\s*58px;[\s\S]*?overflow-x:\s*auto;/,
  'Page detail compact capability navigation',
)
expectMatches(
  styles,
  /\.page-detail-flow-track\s*\{[\s\S]*?display:\s*flex;[\s\S]*?width:\s*max-content;/,
  'Page detail non-blocking flow track',
)
expectMatches(
  styles,
  /\.page-detail-capability-summary \.page-detail-flow-track > button\.is-current \.page-detail-flow-icon[\s\S]*?page-detail-flow-pulse/,
  'Page detail current-node pulse',
)
expectIncludes(styles, 'prefers-reduced-motion: reduce', 'Page detail reduced motion')
expectIncludes(styles, 'page-detail-flow-preview', 'Page detail future-step preview banner styles')
expectIncludes(styles, 'page-detail-flow-connector', 'Page detail flow connectors')
expectIncludes(styles, 'page-detail-flow-title-row', 'Page detail status pill title row')
expectIncludes(styles, 'border-radius: 999px', 'Page detail status pill shape')
expectExcludes(styles, 'grid-template-columns: repeat(5, minmax(0, 1fr));', 'Page detail removed equal tab grid')
expectExcludes(styles, '.page-detail-capability-summary > button.is-key', 'Page detail removed is-key tab state')
expectMatches(
  styles,
  /\.page-detail-stage-heading\s*\{[\s\S]*?min-height:\s*52px;[\s\S]*?padding:\s*0\s+18px;/,
  'Page detail compact stage heading',
)
expectMatches(
  styles,
  /\.page-detail-stage-card \.target-source-column\s*\{[\s\S]*?gap:\s*0;[\s\S]*?padding:\s*14px\s+16px\s+20px;/,
  'Page detail compact target source column',
)
expectMatches(
  styles,
  /\.page-detail-stage-card \.manual-target-section > article\s*\{[\s\S]*?min-height:\s*86px;[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\)\s+104px;/,
  'Page detail compact manual target composer',
)
expectMatches(
  styles,
  /\.page-detail-stage-card \.manual-target-section \.el-textarea__[\s\S]*?height:\s*58px;[\s\S]*?min-height:\s*58px !important;/,
  'Page detail compact goal textarea',
)
expectExcludes(wizard, 'QuestionFilled', 'PageAssistantWizard')
expectIncludes(pageDetail, "emit('refresh')", 'PageWorkbenchPageDetail')
expectIncludes(pageDetail, '刷新状态', 'PageWorkbenchPageDetail')
expectExcludes(pageDetail, '查看恢复动作', 'PageWorkbenchPageDetail')
expectIncludes(map, 'v-for="step in 4"', 'PageWorkbenchMapPanel')
expectIncludes(map, "'is-done': step <= completedSteps", 'PageWorkbenchMapPanel')
expectExcludes(map, 'MoreFilled', 'PageWorkbenchMapPanel')
expectExcludes(map, 'aria-label="更多"', 'PageWorkbenchMapPanel')
expectIncludes(map, 'is-source-${page.sourceType.toLowerCase()', 'PageWorkbenchMapPanel')
expectIncludes(map, 'if (errorCount > 0)', 'PageWorkbenchMapPanel')
expectIncludes(map, 'aria-label="刷新状态"', 'PageWorkbenchMapPanel')
expectExcludes(map, '>刷新状态</el-button>', 'PageWorkbenchMapPanel')
expectExcludes(map, 'completedPercent', 'PageWorkbenchMapPanel')
expectMatches(
  styles,
  /\.page-access-tab\s*\{[\s\S]*?min-width:\s*110px;[\s\S]*?font-size:\s*12px;/,
  'Page Access Center compact tabs',
)
expectMatches(
  styles,
  /\.page-access-layout\s*\{[\s\S]*?grid-template-columns:\s*200px\s+minmax\(0,\s*1fr\);/,
  'Page Access Center compact sidebar',
)
expectMatches(
  styles,
  /\.page-access-card\s*\{[\s\S]*?min-height:\s*126px;[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\)\s+105px;[\s\S]*?border-radius:\s*14px;/,
  'Page Access Center compact card row',
)
expectMatches(
  styles,
  /\.page-access-card__state strong\s*\{[\s\S]*?font-size:\s*11px;/,
  'Page Access Center compact status typography',
)
expectMatches(
  styles,
  /\.page-access-card__identity code\s*\{[\s\S]*?background:\s*transparent;[\s\S]*?font-family:\s*inherit;/,
  'Page Access Center plain route treatment',
)
expectMatches(
  styles,
  /\.page-access-card-list\s*\{[\s\S]*?scrollbar-width:\s*none;/,
  'Page Access Center unobtrusive internal scrolling',
)
expectMatches(
  styles,
  /\.page-access-list-toolbar\s*>\s*\.el-button\s*\{[\s\S]*?width:\s*38px;[\s\S]*?min-width:\s*38px;[\s\S]*?padding:\s*0;/,
  'Page Access Center icon-only refresh action',
)
expectMatches(
  styles,
  /\.page-access-view-toggle\s*\{[\s\S]*?border-radius:\s*10px;/,
  'Page Access Center view toggle control',
)
expectIncludes(map, 'page-access-card__body', 'PageWorkbenchMapPanel')
expectMatches(
  styles,
  /\.page-map-page-grid\s*\{[\s\S]*?grid-template-columns:\s*repeat\(auto-fill,\s*minmax\(280px,\s*1fr\)\);/,
  'Page Access Center original card grid layout',
)
expectMatches(
  styles,
  /\.page-map-card__more\s*\{[\s\S]*?color:\s*var\(--brand-active\);/,
  'Page Access Center original card detail link',
)
expectMatches(
  styles,
  /\.page-access-scope__list\s*\{[\s\S]*?gap:\s*2px;[\s\S]*?\.page-access-scope__list button\s*\{[\s\S]*?min-height:\s*36px;/,
  'Page Access Center compact scope spacing',
)
for (const statusColor of [
  'var(--brand-primary)',
  'var(--status-warning)',
  'var(--status-success)',
  'var(--status-danger)',
]) {
  expectIncludes(styles, `--status-dot-color: ${statusColor}`, 'PageWorkbench status dots')
}
expectMatches(
  styles,
  /\.page-access-progress\s*>\s*span\s*>\s*i\s*\{[\s\S]*?width:\s*25px;[\s\S]*?height:\s*4px;/,
  'Page Access Center segmented progress',
)
expectIncludes(analysisRecommendationDialog, '不会自动加入本次目标', 'analysis recommendation dialog')
expectIncludes(pageInformationDialog, '统一核对页面定位与基本信息', 'page information dialog')
expectExcludes(pageInformationDialog, 'page-information-summary', 'page information dialog')
expectIncludes(pageDetail, 'PageWorkbenchResourcePanel', 'page detail inline resources')
expectIncludes(pageDetail, "label: '页面基础'", 'page detail foundation entry')
expectIncludes(pageDetail, "label: '接入目标'", 'page detail goal workspace entry')
expectIncludes(pageDetail, "label: '页面接入'", 'page detail implementation workspace entry')
expectIncludes(pageDetail, "label: '接入诊断'", 'page detail diagnostics workspace entry')
expectIncludes(pageDetail, "label: '调试验证'", 'page detail debug workspace entry')
expectIncludes(pageDetail, 'flowNavNodes', 'page detail flow navigation model')
expectIncludes(pageDetail, 'flowState', 'page detail flow navigation model')
expectIncludes(pageDetail, "flowState = 'complete'", 'page detail complete flow state')
expectIncludes(pageDetail, "flowState = 'current'", 'page detail current flow state')
expectIncludes(pageDetail, "flowState = 'future'", 'page detail future flow state')
expectIncludes(pageDetail, "flowState = 'attention'", 'page detail attention flow state')
expectIncludes(pageDetail, "flowState = 'unavailable'", 'page detail unavailable flow state')
expectIncludes(pageDetail, "'is-selected': node.selected", 'page detail selected browsing state')
expectIncludes(pageDetail, '`is-${node.flowState}`', 'page detail flow state class dimension')
expectIncludes(pageDetail, 'selectedFlowNode', 'page detail selected independent of current')
expectIncludes(pageDetail, 'recommendedFlowNode', 'page detail recommended independent of selected')
expectIncludes(pageDetail, 'aria-current', 'page detail recommended aria-current')
expectIncludes(pageDetail, ':aria-pressed="node.selected"', 'page detail selected aria-pressed')
expectIncludes(pageDetail, 'flowPreviewHint', 'page detail future-step preview hint')
expectIncludes(pageDetail, 'page-detail-flow-preview', 'page detail future-step preview banner')
expectIncludes(pageDetail, '返回{{ flowPreviewHint.returnNode.label }}', 'page detail return to recommended node')
expectIncludes(styles, 'is-complete', 'page detail complete class styles')
expectIncludes(styles, 'is-current', 'page detail current class styles')
expectIncludes(styles, 'is-future', 'page detail future class styles')
expectIncludes(styles, 'is-attention', 'page detail attention class styles')
expectIncludes(styles, 'is-unavailable', 'page detail unavailable class styles')
expectIncludes(styles, 'is-selected', 'page detail selected class styles')
expectMatches(
  pageDetail,
  /page-detail-flow-track[\s\S]*?@click="activateFlowNode\(node\)"/,
  'page detail flow nodes always clickable',
)
expectMatches(
  pageDetail,
  /<button\s+type="button"\s+:class="\[\s*`is-\$\{node\.flowState\}`/,
  'page detail flow buttons omit disabled',
)
expectExcludes(pageDetail, "'is-key':", 'page detail removed shared is-key state')
expectIncludes(pageDetail, 'implementation-goal-onboarding', 'page detail illustrated implementation goal guide')
expectIncludes(pageDetail, 'page-goal-before-ai.webp', 'page detail implementation goal illustration')
expectIncludes(pageDetail, '点击上方「接入目标」', 'page detail implementation goal pointer')
expectIncludes(pageDetail, '先选好目标，再交给 AI 实施', 'page detail implementation goal guide')
expectIncludes(pageDetail, "'is-guidance-target': implementationNeedsGoalSelection", 'page detail goal tab guidance')
expectIncludes(pageDetail, ':data-flow-node="node.key"', 'page detail flow node target marker')
for (const duplicateCopy of ['第二步 · AI 实施', '等待确认接入目标', '先确认本次接入目标', '查看接入目标', '去选择目标']) {
  expectExcludes(pageDetail, duplicateCopy, 'page detail illustrated implementation goal guide')
}
expectIncludes(styles, '.implementation-goal-onboarding', 'page detail illustrated implementation goal guide styles')
expectIncludes(styles, '.is-guidance-target', 'page detail animated goal tab guidance styles')
expectIncludes(styles, '@keyframes implementation-goal-pointer-dash', 'page detail animated goal pointer')
expectIncludes(pageDetail, 'foundationWorkspaceActive', 'page detail foundation grouping')
expectIncludes(pageDetail, 'page-foundation-workspace', 'page detail foundation workspace')
expectExcludes(pageDetail, 'page-foundation-overview', 'page detail foundation overview removed')
expectIncludes(pageDetail, 'foundationActionSection', 'page detail action section anchor')
expectIncludes(pageDetail, 'scrollFoundationToActions', 'page detail action section navigation')
expectExcludes(pageDetail, '@show-actions', 'page detail resource action tabs')
expectExcludes(pageDetail, '@show-resources', 'page detail resource action tabs')
expectExcludes(pageDetail, 'apiResourceCount', 'page detail removed API basis count')
expectExcludes(pageDetail, 'journey-foundation-context', 'page detail duplicate access basis')
expectExcludes(pageDetail, '<strong>当前接入</strong>', 'page detail navigation')
expectExcludes(pageDetail, '<strong>接入任务</strong>', 'page detail split access task workspace')
expectExcludes(pageDetail, 'journey-step-switcher', 'page detail duplicate journey step switcher')
expectExcludes(pageDetail, '重新生成 AI 实施', 'page detail retry via retarget instead of snapshot page')
expectIncludes(pageDetail, '确认新目标并重新实施', 'page detail journey retry via retarget')
expectIncludes(pageDetail, 'PageWorkbenchActionPanel', 'page detail inline actions')
expectIncludes(pageDetail, 'PageWorkbenchDiagnosticsPanel', 'page detail inline diagnostics')
expectIncludes(pageDetail, 'PageWorkbenchDebugPanel', 'page detail inline debug')
expectIncludes(pageResourcePanel, 'id="page-resource-inline-title">页面资源</h3>', 'page resource panel')
expectIncludes(pageResourcePanel, 'resource-directory-list', 'page resource panel compact groups')
expectIncludes(pageResourcePanel, 'resource-directory-group', 'page resource panel compact groups')
expectIncludes(pageResourcePanel, 'resource-directory-group__toggle', 'page resource panel collapse toggle')
expectIncludes(pageResourcePanel, 'isGroupExpanded', 'page resource panel collapse state')
expectIncludes(pageResourcePanel, 'expandedByType[type] = !isGroupExpanded(type)', 'page resource panel default collapsed')
expectIncludes(styles, '.resource-directory-group.is-collapsed', 'page resource panel collapsed styles')
expectIncludes(pageResourcePanel, '<el-icon><CopyDocument /></el-icon>', 'page resource panel switcher icon')
expectExcludes(pageResourcePanel, 'resourceGroupColumns', 'page resource panel balanced columns')
expectExcludes(pageResourcePanel, 'foundation-content-switcher', 'page resource panel nested tabs')
expectExcludes(pageResourcePanel, "emit('showActions')", 'page resource panel action switch')
expectExcludes(pageResourcePanel, 'resource-action-guidance', 'page resource panel')
expectExcludes(pageResourcePanel, 'resource-access-legend', 'page resource panel')
expectExcludes(pageResourcePanel, '返回当前接入', 'page resource panel')
expectExcludes(pageActionPanel, '返回当前接入', 'page action panel')
expectIncludes(pageDiagnosticsPanel, 'aria-label="接入诊断"', 'page diagnostics panel')
expectIncludes(pageDiagnosticsPanel, 'embedded-readiness-grid', 'page diagnostics panel')
expectIncludes(pageDiagnosticsPanel, "emit('check', page)", 'page diagnostics panel')
expectIncludes(pageDiagnosticsPanel, 'page-diagnostics-inline__hero', 'page diagnostics panel hero')
expectIncludes(pageDiagnosticsPanel, 'embedded-readiness-metrics', 'page diagnostics panel metrics')
expectExcludes(pageDiagnosticsPanel, '页面级状态', 'page diagnostics duplicate title')
expectExcludes(pageDiagnosticsPanel, 'page-diagnostics-inline-title', 'page diagnostics duplicate title')
expectExcludes(pageDiagnosticsPanel, '查看状态与恢复动作', 'page diagnostics duplicate recovery entry')
expectExcludes(pageDiagnosticsPanel, '返回当前接入', 'page diagnostics panel')
expectIncludes(styles, 'page-diagnostics-inline__hero', 'PageWorkbench diagnostics hero styles')
expectIncludes(styles, 'embedded-readiness-mark', 'PageWorkbench diagnostics item mark')
expectIncludes(pageDebugPanel, '调试验证', 'page debug panel')
expectIncludes(pageDebugPanel, '选择页面操作', 'page debug panel')
expectIncludes(pageDebugPanel, 'listPageActionCatalog', 'page debug panel')
expectIncludes(pageDebugPanel, 'debugPageActionCatalog', 'page debug panel')
expectIncludes(pageDebugPanel, 'getPageActionDebugResult', 'page debug panel')
expectIncludes(pageDebugPanel, '还没有可验证的页面操作', 'page debug panel')
expectIncludes(pageDebugPanel, '不使用前端计时器模拟执行成功', 'page debug panel')
expectExcludes(pageDebugPanel, '返回当前接入', 'page debug panel')
expectIncludes(pageActionPanel, 'id="page-action-inline-title">可调用操作</h3>', 'page action panel')
expectIncludes(pageActionPanel, '供 AI 实施、调试验证和后续运行时调用', 'page action panel')
expectExcludes(pageActionPanel, 'foundation-content-switcher', 'page action panel nested tabs')
expectExcludes(pageActionPanel, "emit('showResources')", 'page action panel resource switch')
expectIncludes(pageActionPanel, '在真实页面调试', 'page action panel')
expectIncludes(pageActionPanel, 'operation-catalog-list', 'page action panel')
expectIncludes(pageActionPanel, 'foundation-action-empty', 'page action panel compact empty state')
expectIncludes(pageActionPanel, '新增手动操作', 'page action panel')
expectIncludes(pageActionPanel, "action.sourceType === 'MANUAL'", 'page action panel')
expectExcludes(wizard, 'readinessDialogVisible', 'PageAssistantWizard')
expectExcludes(wizard, 'actionDebugDialogVisible', 'PageAssistantWizard')
expectExcludes(wizard, 'openPageActionDebug', 'PageAssistantWizard')
expectIncludes(wizard, '@debug-action="openInlineDebugWorkspace"', 'PageAssistantWizard')
expectIncludes(wizard, 'initializedDetailWorkspacePageId', 'PageAssistantWizard detail workspace initialization')
expectIncludes(
  wizard,
  "journey?.stage === 'AI_IMPLEMENTATION' ? 'implementation' : 'goals'",
  'PageAssistantWizard evidence-aware detail default',
)
expectExcludes(styles, '.resource-action-dialog', 'PageWorkbench styles')
expectIncludes(styles, '.page-foundation-workspace', 'page foundation workspace styles')
expectExcludes(styles, '.page-foundation-overview', 'page foundation overview styles removed')
expectIncludes(styles, '.foundation-section-card', 'page foundation section card styles')
expectIncludes(styles, '.foundation-action-empty', 'page foundation compact empty action styles')
expectExcludes(styles, '.foundation-content-switcher', 'page foundation nested switcher styles')
expectExcludes(styles, '.journey-foundation-context', 'access task duplicate basis styles')
expectMatches(
  styles,
  /\.page-detail-flow-track\s*\{[\s\S]*?display:\s*flex;[\s\S]*?min-width:\s*100%;/,
  'page detail five-workspace flow track architecture',
)
expectMatches(
  styles,
  /\.page-detail-capability-summary \.page-detail-flow-track > button\s*\{[\s\S]*?min-width:\s*156px;/,
  'page detail flow node minimum width',
)
expectIncludes(pageDetail, 'page-detail-flow-title-row', 'page detail status pill beside title')
expectIncludes(pageDetail, 'page-detail-flow-status', 'page detail status pill element')
expectMatches(
  styles,
  /\.page-action-inline\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?flex:\s*0\s+0\s+auto;[\s\S]*?flex-direction:\s*column;[\s\S]*?overflow:\s*hidden;/,
  'page detail inline action workspace',
)
expectMatches(
  styles,
  /\.page-foundation-workspace\s*\{[\s\S]*?flex:\s*1;[\s\S]*?gap:\s*16px;[\s\S]*?padding:\s*20px\s+22px\s+32px;[\s\S]*?overflow-y:\s*auto;/,
  'page foundation unified scrolling workspace',
)
expectMatches(
  styles,
  /\.resource-action-pane\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?flex:\s*1;[\s\S]*?padding:\s*20px\s+22px\s+24px;[\s\S]*?overflow-y:\s*auto;/,
  'resource/action internal pane scrolling',
)
expectMatches(
  styles,
  /\.resource-action-pane\.is-actions\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?padding:\s*0;[\s\S]*?overflow:\s*hidden;[\s\S]*?grid-template-columns:\s*310px\s+minmax\(0,\s*1fr\);[\s\S]*?gap:\s*0;/,
  'resource/action integrated master detail workspace',
)
expectMatches(
  styles,
  /\.resource-directory-list\s*\{[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\);/,
  'page resource single-column grouped list',
)
expectMatches(
  styles,
  /\.page-diagnostics-inline\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?flex:\s*1;[\s\S]*?overflow-y:\s*auto;/,
  'page detail inline diagnostics scrolling',
)
expectMatches(
  styles,
  /\.page-debug-inline\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?flex:\s*1;[\s\S]*?overflow-y:\s*auto;/,
  'page detail inline debug scrolling',
)
expectIncludes(historyDrawer, '只展示真实活动或运行数据', 'journey history drawer')
expectIncludes(publishConfirmDialog, '确认 AI 生成的页面操作', 'publish confirmation dialog')
expectIncludes(acceptanceDialog, '不是静态截图、模拟数组', 'acceptance dialog')
expectMatches(
  styles,
  /\.page-access-detail-route\s*\{[\s\S]*?min-height:\s*0;[\s\S]*?overflow-y:\s*auto;/,
  'full page workbench viewport boundary',
)
expectMatches(
  styles,
  /\.target-selection-layout\s*\{[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\)\s+300px;/,
  'full page target selection layout',
)
expectExcludes(wizard, '@create="openTaskComposer()"', 'PageAssistantWizard')
expectExcludes(tasks, '新建任务', 'PageWorkbenchTasksPanel')
expectIncludes(wizard, '<template #actions>', 'PageAssistantWizard')
expectExcludes(wizard, 'page-workbench-header__scan-state', 'PageAssistantWizard removed header scan button')
expectExcludes(wizard, 'scanStatusHint', 'PageAssistantWizard removed header scan button')
expectExcludes(wizard, '@click="openTaskDetail(pageMap.taskId)"', 'PageAssistantWizard removed header scan button')
expectIncludes(wizard, '@click="openPageSourceDialog()"', 'PageAssistantWizard')
expectIncludes(wizard, '扫描 / 添加页面', 'PageAssistantWizard')
expectExcludes(styles, '.page-workbench-header__scan-state', 'PageWorkbench styles removed header scan button')
expectExcludes(styles, '.page-map-toolbar', 'PageWorkbench styles')
expectExcludes(styles, '.page-map-sync-strip', 'PageWorkbench styles')
expectIncludes(tasks, '页面地图扫描', 'PageWorkbenchTasksPanel')
expectIncludes(tasks, '浏览器验收', 'PageWorkbenchTasksPanel')
expectIncludes(tasks, '页面工作流建设', 'PageWorkbenchTasksPanel')
expectIncludes(wizard, 'checkPageReadiness', 'PageAssistantWizard')
expectIncludes(wizard, 'deliverWorkflowTask', 'PageAssistantWizard')
expectIncludes(workflowDelivery, '发布并接入页面副驾驶', 'PageWorkbenchWorkflowDeliveryCard')
expectIncludes(published, '运行服务的发布数据当前不可用', 'PageWorkbenchPublishedPanel')
expectIncludes(published, '发起浏览器验收', 'PageWorkbenchPublishedPanel')
expectIncludes(published, '查看最近运行', 'PageWorkbenchPublishedPanel')
expectIncludes(api, '/page-workbench', 'pageWorkbench API')
expectIncludes(api, '/access-center', 'pageWorkbench API')
expectIncludes(pageWorkbenchController, '@GetMapping("/access-center")', 'PageWorkbench controller')
expectIncludes(
  accessCenterOverviewService,
  'reachai.page-access-center.overview.v1',
  'Page Access Center overview service',
)
expectIncludes(
  accessCenterOverviewService,
  'exactWorkflowTarget(task, published)',
  'Page Access Center exact acceptance projection',
)
expectIncludes(
  accessCenterOverviewService,
  'runtimeAvailable = false',
  'Page Access Center honest Runtime degradation',
)
expectIncludes(composable, "targetType: 'WORKFLOW'", 'useBusinessPageWorkbench')
expectIncludes(composable, "targetRole: 'RELATED'", 'useBusinessPageWorkbench')
expectIncludes(composable, 'workflowVersionId:', 'useBusinessPageWorkbench')
expectIncludes(wizard, 'taskForm.workflow.workflowVersion', 'PageAssistantWizard')
expectIncludes(aiCodingPresentation, 'AI 编程工具已反馈，待平台验证', 'AI Coding presentation')
expectIncludes(aiCodingPresentation, '待人工验收', 'AI Coding presentation')

for (const forbidden of ['setInterval(', 'setTimeout(', 'mock', '模拟数据']) {
  expectExcludes(composable, forbidden, 'useBusinessPageWorkbench')
}

for (const required of [
  '不扫描无关模块',
  '`READY` / `RUNNING`：写回 `STARTED`',
  '`WAITING_USER`：用 `Get-ReachAiQuestions` 获取问题',
  '`RESULT_APPLIED`：不要写 `STARTED`',
  '完成任务要求的测试和真实浏览器自检',
  '现在可以回到 ReachAI，在浏览器里验收。',
  '不安装 ReachAI Runner、常驻程序、后台进程、额外 npm 包或系统软件',
  '名称、标题、描述、说明、System Prompt、进度、问题、结果和验收材料',
]) {
  expectIncludes(handoffPromptFactory, required, 'AiCodingHandoffPromptFactory')
}
for (const forbidden of ['npm install', 'Start-Process', '启动常驻']) {
  expectExcludes(handoffPromptFactory, forbidden, 'AiCodingHandoffPromptFactory')
}
expectIncludes(
  pageMapProvider,
  'This task establishes the page map only. Page interaction analysis is a separate task.',
  'PageMapScanTaskProvider',
)
expectIncludes(pageMapProvider, 'Do not infer business value, priority or high-value actions.', 'PageMapScanTaskProvider')
expectIncludes(
  pageMapProvider,
  'All human-readable titles and descriptions in the report must be Simplified Chinese.',
  'PageMapScanTaskProvider',
)
for (const [source, fragment, label] of [
  [wizard, '已发布 Workflow', 'PageAssistantWizard'],
  [wizard, 'Workflow 工程', 'PageAssistantWizard'],
  [map, 'PAGE MAP', 'PageWorkbenchMapPanel'],
  [tasks, '页面 Workflow 工程', 'PageWorkbenchTasksPanel'],
  [published, 'Runtime 发布数据当前不可用', 'PageWorkbenchPublishedPanel'],
  [published, '关联 Agent', 'PageWorkbenchPublishedPanel'],
  [workflowDelivery, '打开 Workflow Studio', 'PageWorkbenchWorkflowDeliveryCard'],
  [manualPageForm, 'MANUAL 来源', 'PageWorkbenchManualPageForm'],
]) {
  expectExcludes(source, fragment, label)
}
for (const fragment of [
  'pageWorkbenchPageName',
  'pageWorkbenchModuleName',
  'pageWorkbenchTaskTitle',
  'pageWorkbenchSourceLabel',
  'pageWorkbenchRiskLabel',
  'pageWorkbenchReadinessStatusLabel',
]) {
  expectIncludes(
    pageWorkbenchPresentation,
    `function ${fragment}`,
    'pageWorkbenchPresentation',
  )
}
expectIncludes(wizard, 'AiCodingTaskDetailPanel', 'PageAssistantWizard')
expectIncludes(pageDetail, '调整接入目标', 'PageWorkbenchPageDetail goal retargeting')
expectIncludes(pageDetail, '确认新目标并重新实施', 'PageWorkbenchPageDetail goal retargeting')
expectIncludes(pageDetail, '取消调整', 'PageWorkbenchPageDetail goal retargeting')
expectIncludes(pageDetail, 'retargetMode', 'PageWorkbenchPageDetail goal retargeting')
expectIncludes(pageDetail, 'beginRetarget', 'PageWorkbenchPageDetail goal retargeting')
expectIncludes(pageDetail, 'cancelRetarget', 'PageWorkbenchPageDetail goal retargeting')
expectIncludes(pageDetail, 'currentGoalSelection', 'PageWorkbenchPageDetail goal snapshot source')
expectIncludes(wizard, '@retarget="handleImplementationRetarget"', 'PageAssistantWizard goal retargeting')
expectIncludes(wizard, '确认调整接入目标', 'PageAssistantWizard goal retargeting')
expectIncludes(wizard, '确认并重新实施', 'PageAssistantWizard goal retargeting')
expectIncludes(wizard, '继续调整', 'PageAssistantWizard goal retargeting')
expectIncludes(wizard, 'syncImplementationGoalStatuses', 'PageAssistantWizard goal retargeting')
expectIncludes(composable, 'goalSelection: data.goalSelection', 'useBusinessPageWorkbench goal snapshot')
expectIncludes(
  aiCodingGoalSelection,
  'reachai.page-implementation-goal-selection.v1',
  'AI Coding goal selection snapshot',
)
expectIncludes(taskDetailPanel, "acceptance: [taskId: string, passed: boolean, message: string]", 'AiCodingTaskDetailPanel')
expectIncludes(taskDetailPanel, "answer: [taskId: string, questionId: string, answer: string]", 'AiCodingTaskDetailPanel')
expectIncludes(taskDetailPanel, 'AiCodingArtifactEvidence', 'AiCodingTaskDetailPanel')
expectIncludes(taskDetailPanel, '复制恢复命令', 'AiCodingTaskDetailPanel')
expectIncludes(taskDetailPanel, "variant?: 'drawer' | 'inline'", 'AiCodingTaskDetailPanel')
expectIncludes(taskDetailPanel, '取消任务', 'AiCodingTaskDetailPanel')
expectIncludes(taskDetailPanel, 'canCancelInline', 'AiCodingTaskDetailPanel inline actions')
expectExcludes(taskDetailPanel, '更多操作', 'AiCodingTaskDetailPanel inline actions')
expectExcludes(taskDetailPanel, 'command="reissue"', 'AiCodingTaskDetailPanel inline actions')
expectExcludes(pageDetail, '<h2>AI 实施进度</h2>', 'PageWorkbenchPageDetail removed implementation heading')
expectExcludes(pageDetail, 'page-detail-stage-heading is-implementation', 'PageWorkbenchPageDetail removed implementation heading')
expectExcludes(pageDetail, 'page-implementation-actions', 'PageWorkbenchPageDetail removed teleported actions')
expectIncludes(taskDetailPanel, 'ai-task-hero', 'AiCodingTaskDetailPanel drawer hero')
expectIncludes(taskDetailPanel, "v-if=\"variant !== 'inline'\"", 'AiCodingTaskDetailPanel hide hero inline')
expectIncludes(taskDetailPanel, 'ai-task-summary-toolbar', 'AiCodingTaskDetailPanel inline summary actions')
expectIncludes(taskDetailPanel, 'ai-task-inline-actions', 'AiCodingTaskDetailPanel inline actions')
expectIncludes(taskDetailPanel, 'ai-task-split', 'AiCodingTaskDetailPanel split layout')
expectIncludes(taskDetailPanel, 'is-inline .ai-task-split', 'AiCodingTaskDetailPanel stretched split')
expectIncludes(styles, 'implementation-task-workspace', 'PageWorkbench implementation workspace')
expectMatches(
  styles,
  /\.page-detail-stage-card\s*>\s*\.journey-stage-workspace\.implementation-task-workspace\s*\{[\s\S]*?overflow:\s*hidden;/,
  'PageWorkbench implementation workspace fill height',
)
expectExcludes(taskDetailPanel, 'inlineActionsTarget', 'AiCodingTaskDetailPanel removed teleport target')
expectExcludes(taskDetailPanel, '<Teleport', 'AiCodingTaskDetailPanel removed teleport')
expectIncludes(pageDetail, 'variant="inline"', 'PageWorkbenchPageDetail inline task panel')
expectIncludes(taskDetailPanel, 'showFooter', 'AiCodingTaskDetailPanel conditional footer')
expectIncludes(wizard, 'ai-task-drawer-header', 'PageAssistantWizard task drawer refresh')
expectIncludes(wizard, 'title="AI 任务详情"', 'PageAssistantWizard task drawer')
expectMatches(
  wizard,
  /ai-task-drawer-header[\s\S]*?刷新[\s\S]*?<\/el-button>/,
  'PageAssistantWizard task drawer header refresh',
)
expectIncludes(taskDetailPanel, "RESULT_APPLIED: '结果已应用'", 'AiCodingTaskDetailPanel')
expectIncludes(taskDetailPanel, '$env:LOCALAPPDATA\\\\ReachAI\\\\ai-coding-sessions', 'AiCodingTaskDetailPanel')
expectIncludes(artifactEvidence, '真实浏览器材料', 'AiCodingArtifactEvidence')
expectIncludes(artifactEvidence, 'AI 报告通过', 'AiCodingArtifactEvidence')
expectIncludes(artifactEvidence, '不能据此判断页面入口或交互已完成', 'AiCodingArtifactEvidence')

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Business page workbench UI contract check passed.')
