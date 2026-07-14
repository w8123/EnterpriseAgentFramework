import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const root = process.cwd()

const failures = []

const sidebar = readFileSync(resolve(root, 'src/components/common/sidebarMenu.ts'), 'utf8')
if (sidebar.includes("label: '注册中心'")) {
  failures.push('sidebar menu group still labels the project area as 注册中心')
}
if (sidebar.includes("label: '项目中心'")) {
  failures.push('sidebar main menu should rename 项目中心 to 项目管理')
}
if (sidebar.includes("index: '/registry-group'")) {
  failures.push('sidebar 项目管理 should be a direct menu item instead of a submenu group')
}

const workbenchGroupIndex = sidebar.indexOf("{ kind: 'group', label: '工作台' }")
const projectManagementIndex = sidebar.indexOf("{ kind: 'item', index: '/registry/projects', label: '项目管理', icon: Connection }")
const assetGroupIndex = sidebar.indexOf("{ kind: 'group', label: '资产与编排' }")
const platformGovernanceGroupIndex = sidebar.indexOf("{ kind: 'group', label: '平台治理' }")
const unfinishedGroupIndex = sidebar.indexOf("{ kind: 'group', label: '未完成' }")
const capabilityKernelIndex = sidebar.indexOf("label: '能力内核'")
const capabilityReviewIndex = sidebar.indexOf("{ index: '/capability/review', label: '能力变更评审' }")
const capabilitySnapshotIndex = sidebar.indexOf("{ index: '/capability/sync-snapshot', label: '同步能力快照' }")

if (
  workbenchGroupIndex === -1
  || projectManagementIndex === -1
  || assetGroupIndex === -1
  || !(workbenchGroupIndex < projectManagementIndex && projectManagementIndex < assetGroupIndex)
) {
  failures.push('sidebar 项目管理 should be a direct 工作台 item before 资产与编排')
}

if (
  assetGroupIndex === -1
  || platformGovernanceGroupIndex === -1
  || unfinishedGroupIndex === -1
  || capabilityKernelIndex === -1
  || !(
    assetGroupIndex < platformGovernanceGroupIndex
    && platformGovernanceGroupIndex < unfinishedGroupIndex
    && unfinishedGroupIndex < capabilityKernelIndex
  )
) {
  failures.push('sidebar should place 未完成 after 平台治理 and move 能力内核 into 未完成')
}

if (sidebar.includes('/registry/runtimes') || sidebar.includes('Runtime 纳管')) {
  failures.push('sidebar should not expose the retired Runtime registry')
}

if (
  capabilityKernelIndex === -1
  || capabilityReviewIndex === -1
  || capabilitySnapshotIndex === -1
  || !(capabilityKernelIndex < capabilityReviewIndex && capabilityReviewIndex < capabilitySnapshotIndex)
) {
  failures.push('sidebar should expose 能力变更评审 and 同步能力快照 under 能力内核')
}

const router = readFileSync(resolve(root, 'src/router/index.ts'), 'utf8')
if (router.includes("title: '注册中心") || router.includes("{ title: '注册中心' }")) {
  failures.push('registry route titles or breadcrumbs still expose 注册中心')
}

if (router.includes('/registry/runtimes') || router.includes('RuntimeRegistry') || router.includes('Runtime 纳管')) {
  failures.push('router should not expose the retired Runtime registry')
}

if (
  !router.includes("path: 'capability/review'")
  || !router.includes("path: 'capability/sync-snapshot'")
  || !router.includes("activeMenu: '/capability/review'")
  || !router.includes("activeMenu: '/capability/sync-snapshot'")
) {
  failures.push('capability review and snapshot routes should live under 能力内核 menu entries')
}

const projectList = readFileSync(resolve(root, 'src/views/registry/RegistryProjectList.vue'), 'utf8')
if (projectList.includes('tag-brand">注册中心')) {
  failures.push('project list hero tag still exposes 注册中心')
}

const projectWorkbench = readFileSync(resolve(root, 'src/views/registry/composables/useRegistryProjectWorkbench.ts'), 'utf8')
if (projectWorkbench.includes("title: '能力变更评审'") || projectWorkbench.includes("title: '同步能力快照'")) {
  failures.push('project detail workbench should not show 能力变更评审 or 同步能力快照 cards')
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
