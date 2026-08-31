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
const dashboardIndex = sidebar.indexOf("{ kind: 'item', index: '/dashboard', label: '工作台', icon: DataAnalysis }")
const projectManagementIndex = sidebar.indexOf("{ kind: 'item', index: '/registry/projects', label: '项目管理', icon: Connection }")
const assetGroupIndex = sidebar.indexOf("{ kind: 'group', label: 'AI 资产' }")
const skillIndex = sidebar.indexOf("{ kind: 'item', index: '/skills', label: 'Skill', icon: Collection }")
const capabilityGroupIndex = sidebar.indexOf("index: '/capability-api-group'")
const capabilityDirectoryIndex = sidebar.indexOf("{ index: '/capability', label: '能力目录' }")
const capabilityReviewIndex = sidebar.indexOf("{ index: '/capability/review', label: '变更评审' }")
const apiMarketIndex = sidebar.indexOf("{ index: '/api-market', label: 'API 市场' }")
const runtimeGovernanceGroupIndex = sidebar.indexOf("{ kind: 'group', label: '运行与治理' }")
const runOpsIndex = sidebar.indexOf("{ kind: 'item', index: '/runops', label: '运行中心', icon: DataAnalysis }")
const platformResourceGroupIndex = sidebar.indexOf("{ kind: 'group', label: '平台资源' }")
const advancedLabGroupIndex = sidebar.indexOf("{ kind: 'group', label: '实验室' }")
const diagnosticsGroupIndex = sidebar.indexOf("index: '/diagnostics-group'")
const capabilitySnapshotIndex = sidebar.indexOf("{ index: '/capability/sync-snapshot', label: '同步能力快照' }")

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
  failures.push('sidebar should lead with 工作台 and 项目管理 under 智能化改造')
}

if (
  skillIndex === -1
  || sidebar.includes("{ index: '/skill-market', label: 'Skill 市场' }")
  || !(assetGroupIndex < skillIndex && skillIndex < capabilityGroupIndex)
) {
  failures.push('sidebar should expose one Skill asset entry and keep the market inside the Skill workspace')
}

if (
  capabilityGroupIndex === -1
  || capabilityDirectoryIndex === -1
  || capabilityReviewIndex === -1
  || apiMarketIndex === -1
  || sidebar.includes("{ index: '/tool', label: '工具目录' }")
  || !(
    assetGroupIndex < capabilityGroupIndex
    && capabilityGroupIndex < capabilityDirectoryIndex
    && capabilityDirectoryIndex < capabilityReviewIndex
    && capabilityReviewIndex < apiMarketIndex
  )
) {
  failures.push('sidebar should expose Capability and API Market under 能力与 API without a generic Tool catalog')
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
  || capabilitySnapshotIndex === -1
  || !(advancedLabGroupIndex < diagnosticsGroupIndex && diagnosticsGroupIndex < capabilitySnapshotIndex)
) {
  failures.push('sidebar should move sync and diagnostic-only entries into 实验室')
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
  || !/path:\s*'tool',\s*redirect:\s*'\/capability'/.test(router)
  || existsSync(resolve(root, 'src/views/tool/ToolList.vue'))
) {
  failures.push('generic Tool catalog must stay retired and the legacy /tool path must redirect to /capability')
}

if (
  !router.includes("path: 'capability/review'")
  || !router.includes("path: 'capability/sync-snapshot'")
  || !router.includes("activeMenu: '/capability/review'")
  || !router.includes("activeMenu: '/capability/sync-snapshot'")
) {
  failures.push('capability review and snapshot routes should remain available after navigation regrouping')
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
const capabilitySyncDebug = readFileSync(resolve(root, 'src/views/registry/CapabilitySyncDebug.vue'), 'utf8')
const capabilityReviewPanel = readFileSync(resolve(root, 'src/views/registry/components/CapabilityReviewPanel.vue'), 'utf8')
if (projectDetail.includes('CapabilityReviewPanel')) {
  failures.push('project detail should not embed the capability snapshot and review panel')
}
if (
  !capabilitySyncDebug.includes('<CapabilityReviewPanel')
  || !capabilitySyncDebug.includes('title="能力变更评审 / 同步调试台"')
) {
  failures.push('capability review and sync debug page should host the capability snapshot and review panel')
}
if (
  !capabilityReviewPanel.includes('<span>能力同步与变更</span>')
  || !capabilityReviewPanel.includes('刷新列表')
  || capabilityReviewPanel.includes('刷新快照')
) {
  failures.push('capability review panel should use the aligned snapshot and change-management copy')
}

const scanVisibleCopyFiles = [
  'src/views/scan/components/scan-project/ScanProjectAddInterfaceDialog.vue',
  'src/views/scan/components/scan-project/ScanProjectScanRulesDrawer.vue',
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
