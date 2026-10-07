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
const advancedLabGroupIndex = sidebar.indexOf("{ kind: 'group', label: '实验室' }")
const diagnosticsGroupIndex = sidebar.indexOf("index: '/diagnostics-group'")

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
  || advancedLabGroupIndex === -1
  || !(
    assetGroupIndex < runtimeGovernanceGroupIndex
    && runtimeGovernanceGroupIndex < runOpsIndex
    && runOpsIndex < platformResourceGroupIndex
    && platformResourceGroupIndex < advancedLabGroupIndex
  )
) {
  failures.push('sidebar should surface 运行中心 and keep platform resources before the advanced lab')
}

if (sidebar.includes('/registry/runtimes') || sidebar.includes('Runtime 纳管')) {
  failures.push('sidebar should not expose the retired Runtime registry')
}

if (
  advancedLabGroupIndex === -1
  || diagnosticsGroupIndex === -1
  || !(advancedLabGroupIndex < diagnosticsGroupIndex)
) {
  failures.push('sidebar should keep platform diagnostics in 实验室')
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
  || !router.includes("path: 'registry/projects/:projectCode/sync-diagnostics'")
  || !router.includes("name: 'RegistrySyncDiagnostics'")
  || router.includes('CapabilityReview')
  || router.includes('CapabilitySyncSnapshot')
  || router.includes('CapabilityKernel')
) {
  failures.push('typed catalogs must belong to the workbench routes and sync diagnostics must require a project route')
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
const sourceChanges = readFileSync(resolve(root, 'src/views/registry/components/ProjectSourceChanges.vue'), 'utf8')
const syncDiagnostics = readFileSync(resolve(root, 'src/views/registry/RegistrySyncDiagnostics.vue'), 'utf8')
const capabilityReviewPanel = readFileSync(resolve(root, 'src/views/registry/components/CapabilityReviewPanel.vue'), 'utf8')
if (!projectDetail.includes('<ProjectSourceChanges') || !projectDetail.includes("projectSection === 'source-changes'")
  || !projectDetail.includes('project?.projectCode === projectCode')) {
  failures.push('project detail must show source changes only for the current project section')
}
if (
  !sourceChanges.includes('<CapabilityReviewPanel v-if="canRead && platformSessionId" :key="contextKey"')
  || !sourceChanges.includes("PLATFORM_PERMISSION_READ, 'PROJECT', null, props.project.projectCode")
  || !sourceChanges.includes("PLATFORM_PERMISSION_WRITE, 'PROJECT', null, props.project.projectCode")
  || !sourceChanges.includes("{ flush: 'sync' }")
  || !sourceChanges.includes("name: 'RegistrySyncDiagnostics'")
) {
  failures.push('source changes must enforce project read/write permissions and discard stale session/project panels')
}
if (!syncDiagnostics.includes('route.params.projectCode')
  || !syncDiagnostics.includes("PLATFORM_PERMISSION_WRITE, 'PROJECT', null, project.projectCode")
  || !syncDiagnostics.includes('payload = { ...parsed, apply: false }')
  || syncDiagnostics.includes('<CapabilityReviewPanel')
  || /<el-select\b/.test(syncDiagnostics)) {
  failures.push('sync diagnostics must remain project-bound, diagnostic-only, and separate from source decisions')
}
const methodCatalog = readFileSync(resolve(root, 'src/views/capability/BusinessMethodCatalog.vue'), 'utf8')
const methodDetail = readFileSync(resolve(root, 'src/views/capability/components/BusinessMethodDetailDialog.vue'), 'utf8')
if (!methodCatalog.includes("useCatalogQuery('method'") || !methodCatalog.includes('getBusinessMethodSummary')
  || /\bsize:\s*1\s*[,}]/.test(methodCatalog) || methodCatalog.includes('<PageHeader')) {
  failures.push('method panel must use namespaced query state and aggregate counts without its own page header or count-only pages')
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
  'src/views/scan/components/scan-project/ScanProjectAddInterfaceDialog.vue',
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
