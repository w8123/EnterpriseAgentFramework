import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = process.cwd()

const failures = []

const sidebar = readFileSync(resolve(root, 'src/components/common/sidebarMenu.ts'), 'utf8')
if (sidebar.includes("label: '注册中心'")) {
  failures.push('sidebar menu group still labels the project area as 注册中心')
}
if (sidebar.includes("label: '项目中心'")) {
  failures.push('sidebar main menu should not expose the implementation-oriented 项目中心 label')
}
if (sidebar.includes("index: '/registry-group'")) {
  failures.push('sidebar 改造项目 should be a direct menu item instead of a submenu group')
}
if (
  sidebar.includes("{ kind: 'group', label: '资产与编排' }")
  || sidebar.includes("{ kind: 'group', label: '平台治理' }")
  || sidebar.includes("{ kind: 'group', label: '未完成' }")
) {
  failures.push('sidebar should use task-oriented groups instead of the retired technical inventory groups')
}

const transformationGroupIndex = sidebar.indexOf("{ kind: 'group', label: '智能化改造' }")
const dashboardIndex = sidebar.indexOf("{ kind: 'item', index: '/dashboard', label: '概览', icon: DataAnalysis }")
const projectManagementIndex = sidebar.indexOf("{ kind: 'item', index: '/registry/projects', label: '项目管理', icon: Connection }")
const assetGroupIndex = sidebar.indexOf("{ kind: 'group', label: 'AI 资产' }")
const skillIndex = sidebar.indexOf("{ kind: 'item', index: '/skills', label: 'Skill', icon: Collection }")
const businessCapabilityIndex = sidebar.search(/index:\s*'\/business-capabilities',\s*label:\s*'业务能力'/)
const workflowIndex = sidebar.indexOf("index: '/workflows'")
const agentIndex = sidebar.indexOf("index: '/agent'")
const integrationIndex = sidebar.indexOf("index: '/integration-group'")
const apiMarketIndex = sidebar.indexOf("{ index: '/api-market', label: 'API 市场' }")
const runtimeGovernanceGroupIndex = sidebar.indexOf("{ kind: 'group', label: '运行与治理' }")
const runOpsIndex = sidebar.indexOf("index: '/runops'")
const platformResourceGroupIndex = sidebar.indexOf("{ kind: 'group', label: '平台资源' }")

if (
  transformationGroupIndex === -1
  || dashboardIndex === -1
  || projectManagementIndex === -1
  || assetGroupIndex === -1
  || !(
    transformationGroupIndex < dashboardIndex
    && dashboardIndex < projectManagementIndex
    && projectManagementIndex < assetGroupIndex
  )
) {
  failures.push('sidebar should lead with 概览 and 项目管理 under 智能化改造')
}

if (
  skillIndex === -1
  || sidebar.includes("{ index: '/skill-market', label: 'Skill 市场' }")
  || !(assetGroupIndex < skillIndex && skillIndex < integrationIndex)
) {
  failures.push('sidebar should expose one Skill asset entry and keep the market inside the Skill workspace')
}

if (
  businessCapabilityIndex === -1
  || sidebar.includes("index: '/business-methods'")
  || sidebar.includes("index: '/apis'")
  || workflowIndex === -1
  || agentIndex === -1
  || integrationIndex === -1
  || apiMarketIndex === -1
  || sidebar.includes("{ index: '/tool', label: '工具目录' }")
  || !(
    assetGroupIndex < businessCapabilityIndex
    && businessCapabilityIndex < workflowIndex
    && workflowIndex < agentIndex
    && agentIndex < skillIndex
    && skillIndex < integrationIndex
    && integrationIndex < apiMarketIndex
  )
) {
  failures.push('sidebar should expose one business capability workbench before Workflow/Agent, with the market under integration')
}

if (
  runtimeGovernanceGroupIndex === -1
  || runOpsIndex === -1
  || platformResourceGroupIndex === -1
  || !(
    assetGroupIndex < runtimeGovernanceGroupIndex
    && runtimeGovernanceGroupIndex < runOpsIndex
    && runOpsIndex < platformResourceGroupIndex
  )
) {
  failures.push('sidebar should surface 运行中心 before platform resources')
}

if (sidebar.includes('/registry/runtimes') || sidebar.includes('Runtime 纳管')) {
  failures.push('sidebar should not expose the retired Runtime registry')
}

if (sidebar.includes("index: '/diagnostics-group'") || sidebar.includes('/tool/retrieval') || sidebar.includes("label: '实验室'")) {
  failures.push('sidebar must not restore the retired 实验室 group or Tool retrieval page')
}

if (/index:\s*['"]\/(?:capability(?:\/[^'"]*)?|tools?|tools\/(?:compositions|interactions))['"]/.test(sidebar)
  || sidebar.includes('CapabilitySyncSnapshot') || sidebar.includes('RegistrySyncDiagnostics')) {
  failures.push('sidebar must not restore a third asset catalog or project sync diagnostics as a global entry')
}

const router = readFileSync(resolve(root, 'src/router/index.ts'), 'utf8')
if (router.includes("title: '注册中心") || router.includes("{ title: '注册中心' }")) {
  failures.push('registry route titles or breadcrumbs still expose 注册中心')
}

if (router.includes('/registry/runtimes') || router.includes('RuntimeRegistry') || router.includes('Runtime 纳管')) {
  failures.push('router should not expose the retired Runtime registry')
}

if (
  router.includes("import('@/views/tool/ToolList.vue')")
  || /path:\s*['"](?:capability(?:\/[^'"]*)?|tools?|tools\/(?:compositions|interactions))['"]/.test(router)
  || existsSync(resolve(root, 'src/views/tool/ToolList.vue'))
) {
  failures.push('generic Tool and Capability catalog routes and redirects must remain retired')
}

if (
  !router.includes("path: 'business-capabilities'")
  || !router.includes("import('@/views/capability/BusinessCapabilityWorkbench.vue')")
  || !router.includes("path: 'java-methods'")
  || !router.includes("path: 'http-apis'")
  || !router.includes("path: 'business-methods'")
  || !router.includes("path: 'apis'")
  || router.includes('CapabilityReview')
  || router.includes('CapabilitySyncSnapshot')
  || router.includes('CapabilityKernel')
) {
  failures.push('typed catalogs must belong to the workbench routes')
}

const retiredDiagnosticsRedirect = /\{\s*path:\s*'registry\/projects\/:projectCode\/sync-diagnostics',\s*meta:\s*\{\s*layoutMode:\s*'project-workbench'\s*\},\s*redirect:\s*to\s*=>\s*\(\{\s*name:\s*'RegistryProjectDetail',\s*params:\s*\{\s*projectCode:\s*to\.params\.projectCode\s*\},\s*query:\s*\{\s*section:\s*'source-changes'\s*\},?\s*\}\),?\s*\}/g
if (
  [...router.matchAll(retiredDiagnosticsRedirect)].length !== 1
  || router.replace(retiredDiagnosticsRedirect, '').includes('sync-diagnostics')
  || router.includes('RegistrySyncDiagnostics')
  || existsSync(resolve(root, 'src/views/registry/RegistrySyncDiagnostics.vue'))
) {
  failures.push('the retired diagnostics URL must only redirect to source changes in the same project, without a diagnostics name or page')
}

const registryApi = readFileSync(resolve(root, 'src/api/registry.ts'), 'utf8')
if (/\b(?:diffRegistryCapabilities|syncRegistryCapabilities|CapabilitySyncRequest|CapabilitySyncResponse)\b|\/capabilities\/(?:diff|sync)/.test(registryApi)) {
  failures.push('the frontend must not restore dedicated project sync diagnostics API wrappers')
}

const capabilityWorkbench = readFileSync(resolve(root, 'src/views/capability/BusinessCapabilityWorkbench.vue'), 'utf8')
const httpApiCatalog = readFileSync(resolve(root, 'src/views/api/HttpApiCatalog.vue'), 'utf8')
if (!capabilityWorkbench.includes('<el-tabs') || !capabilityWorkbench.includes('Java 业务方法')
  || !capabilityWorkbench.includes('HTTP API') || !capabilityWorkbench.includes('<router-view')
  || !capabilityWorkbench.includes('query: route.query') || !capabilityWorkbench.includes('activeTab === tab.value')
  || !httpApiCatalog.includes("useCatalogQuery('api'") || httpApiCatalog.includes('<PageHeader')) {
  failures.push('the workbench must own typed tabs, one header, preserved query state and active-panel mounting')
}

const projectList = readFileSync(resolve(root, 'src/views/registry/RegistryProjectList.vue'), 'utf8')
if (projectList.includes('tag-brand">注册中心')) {
  failures.push('project list hero tag still exposes 注册中心')
}

const projectWorkbench = readFileSync(resolve(root, 'src/views/registry/composables/useRegistryProjectWorkbench.ts'), 'utf8')
if (projectWorkbench.includes("title: '能力变更评审'") || projectWorkbench.includes("title: '同步能力快照'")) {
  failures.push('project detail workbench should not show 能力变更评审 or 同步能力快照 cards')
}

const projectDetail = readFileSync(resolve(root, 'src/views/registry/RegistryProjectDetail.vue'), 'utf8')
const projectNavigation = readFileSync(resolve(root, 'src/views/registry/composables/useRegistryProjectDetailNavigation.ts'), 'utf8')
const sourceChanges = readFileSync(resolve(root, 'src/views/registry/components/ProjectSourceChanges.vue'), 'utf8')
const sourceActions = readFileSync(resolve(root, 'src/views/capability/source/components/SourceActions.vue'), 'utf8')
const sourceWorkbench = readFileSync(resolve(root, 'src/views/capability/source/SourceWorkbenchPanel.vue'), 'utf8')
const capabilityReviewPanel = readFileSync(resolve(root, 'src/views/registry/components/CapabilityReviewPanel.vue'), 'utf8')
if (projectDetail.includes('<ProjectSourceChanges')
  || !projectNavigation.includes('projectCapabilityWorkspaceLocation(project)')
  || !projectNavigation.includes('project.projectCode !== projectCode.value')
  || !sourceWorkbench.includes("project && workspace === 'sources'")
  || !sourceWorkbench.includes('<ProjectSourceChanges v-if="project.projectCode"')) {
  failures.push('project detail must hand source changes to the current project capability workspace without a duplicate panel')
}
if (
  !sourceChanges.includes('<CapabilityReviewPanel v-if="canRead && platformSessionId" :key="contextKey"')
  || !sourceChanges.includes("PLATFORM_PERMISSION_READ, 'PROJECT', null, props.project.projectCode")
  || !sourceChanges.includes("{ flush: 'sync' }")
) {
  failures.push('source changes must enforce project read permissions and discard stale session/project panels')
}
for (const [file, content] of [
  ['sidebarMenu', sidebar],
  ['useRegistryProjectWorkbench', projectWorkbench],
  ['RegistryProjectDetail', projectDetail],
  ['useRegistryProjectDetailNavigation', projectNavigation],
  ['ProjectSourceChanges', sourceChanges],
  ['SourceActions', sourceActions],
  ['SourceWorkbenchPanel', sourceWorkbench],
]) {
  if (/RegistrySyncDiagnostics|sync-diagnostics|openDiagnostics|canDiagnose|同步诊断|同步与诊断/.test(content)) {
    failures.push(`${file} must not expose the retired project sync diagnostics action`)
  }
}
const methodCatalog = readFileSync(resolve(root, 'src/views/capability/BusinessMethodCatalog.vue'), 'utf8')
const methodDetail = readFileSync(resolve(root, 'src/views/capability/components/BusinessMethodDetailDialog.vue'), 'utf8')
if (!methodCatalog.includes("useCatalogQuery('method'") || !methodCatalog.includes('getBusinessMethods')
  || !methodCatalog.includes('total.value = data.total')
  || /\bsize:\s*1\s*[,}]/.test(methodCatalog) || methodCatalog.includes('<PageHeader')) {
  failures.push('method panel must use namespaced query state and owner pagination totals without its own page header or count-only pages')
}
for (const [file, content] of [['BusinessMethodCatalog', methodCatalog], ['BusinessMethodDetailDialog', methodDetail]]) {
  if (!content.includes('@/api/businessMethod') || !content.includes('BusinessMethodInfo')
    || content.includes("from '@/api/tool'") || content.includes('ToolInfo') || content.includes('route.name')) {
    failures.push(`${file} must consume the business-method owner without a generic catalog mode`)
  }
}
if (
  !capabilityReviewPanel.includes('待处理')
  || !capabilityReviewPanel.includes('处理记录')
  || !capabilityReviewPanel.includes('部分引用证据暂未确认，不能据此判断没有影响')
  || !capabilityReviewPanel.includes("impact.value.runtimeEvidence === 'COMPLETE' && impact.value.publicationEvidence === 'COMPLETE'")
) {
  failures.push('source changes must expose pending/processed decisions and require complete runtime/publication evidence')
}

const scanVisibleCopyFiles = [
  'src/views/capability/source/components/SourceImportDialog.vue',
]

for (const file of scanVisibleCopyFiles) {
  const content = readFileSync(resolve(root, file), 'utf8')
  if (content.includes('注册中心项目')) {
    failures.push(`${file} still exposes 注册中心项目 in scan settings copy`)
  }
}

if (failures.length > 0) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Project center labels are aligned.')
