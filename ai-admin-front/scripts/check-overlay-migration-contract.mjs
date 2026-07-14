import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const srcRoot = resolve(projectRoot, 'src')

const rawOverlayAllowlist = new Set([
  'src/components/common/AppDialog.vue',
  'src/components/common/AppDrawer.vue',
  'src/views/scan/ApiGraphCanvas.vue',
  'src/views/scan/components/scan-project/ScanProjectAddInterfaceDialog.vue',
  'src/views/scan/components/scan-project/ScanProjectModelGenerateDrawer.vue',
  'src/views/scan/components/scan-project/ScanProjectOpsDrawer.vue',
  'src/views/scan/components/scan-project/ScanProjectScanRulesDrawer.vue',
  'src/views/scan/components/scan-project/ScanProjectSemanticDialogs.vue',
  'src/views/scan/components/scan-project/ScanProjectToolDiffDialog.vue',
  'src/views/scan/components/scan-project/ScanProjectToolEditDialog.vue',
  'src/views/workflow/WorkflowStudio.vue',
  'src/views/workflow/studio-panels/CredentialSelect.vue',
  'src/views/workflow/studio-panels/InteractionConfigPanel.vue',
  'src/views/workflow/studio-panels/ToolConfigPanel.vue',
])

function normalizePath(file) {
  return relative(projectRoot, file).replaceAll('\\', '/')
}

function collectVueFiles(directory) {
  const files = []
  for (const entry of readdirSync(directory)) {
    const path = resolve(directory, entry)
    if (statSync(path).isDirectory()) files.push(...collectVueFiles(path))
    else if (entry.endsWith('.vue')) files.push(path)
  }
  return files
}

const errors = []
const rawOverlayPattern = /<el-(dialog|drawer)\b/

for (const file of collectVueFiles(srcRoot)) {
  const code = readFileSync(file, 'utf8')
  const path = normalizePath(file)

  if (rawOverlayPattern.test(code) && !rawOverlayAllowlist.has(path)) {
    errors.push(`${path}: 普通业务弹层必须使用 AppDialog / AppDrawer`)
  }

  if (/<AppDialog\b/.test(code) && !/import\s+AppDialog\s+from\s+['"](?:@\/components\/common\/AppDialog|\.\/AppDialog)\.vue['"]/.test(code)) {
    errors.push(`${path}: 使用了 AppDialog，但没有从共享组件入口导入`)
  }

  if (/<AppDrawer\b/.test(code) && !/import\s+AppDrawer\s+from\s+['"](?:@\/components\/common\/AppDrawer|\.\/AppDrawer)\.vue['"]/.test(code)) {
    errors.push(`${path}: 使用了 AppDrawer，但没有从共享组件入口导入`)
  }

  if (/class=["'][^"']*\bpage-header\b/.test(code)) {
    errors.push(`${path}: 旧 page-header 骨架必须迁移到共享 PageHeader`)
  }
}

for (const path of rawOverlayAllowlist) {
  const file = resolve(projectRoot, path)
  if (!existsSync(file)) {
    errors.push(`${path}: 裸弹层允许项对应文件不存在`)
    continue
  }
  if (!rawOverlayPattern.test(readFileSync(file, 'utf8'))) {
    errors.push(`${path}: 裸弹层允许项已经不再需要，请从 allowlist 删除`)
  }
}

const theme = readFileSync(resolve(srcRoot, 'styles/theme.scss'), 'utf8')
if (/\.el-(dialog|drawer|overlay)\b/.test(theme)) {
  errors.push('src/styles/theme.scss: 主题文件不得重新接管弹窗、抽屉或遮罩层皮肤')
}

const overlays = readFileSync(resolve(srcRoot, 'styles/_overlays.scss'), 'utf8')
for (const selector of [
  '.app-dialog.el-dialog',
  '.app-dialog__body--delegated-scroll',
  '.app-drawer.el-drawer',
  '.el-overlay',
]) {
  if (!overlays.includes(selector)) {
    errors.push(`src/styles/_overlays.scss: 缺少共享覆盖层契约 ${selector}`)
  }
}

const wizard = readFileSync(resolve(srcRoot, 'components/common/WizardDialog.vue'), 'utf8')
if (!wizard.includes('body-class="app-dialog__body--delegated-scroll"')) {
  errors.push('src/components/common/WizardDialog.vue: 向导弹窗必须委托内部滚动，避免双滚动条')
}

if (errors.length > 0) {
  console.error('Overlay migration contract failed:')
  for (const error of errors) console.error(`- ${error}`)
  process.exit(1)
}

console.log(`Overlay migration contract passed (${rawOverlayAllowlist.size} technical/adapter allowlist entries).`)
