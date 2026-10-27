import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse as parseSfc } from '@vue/compiler-sfc'
import { parse as parseTemplate } from '@vue/compiler-dom'

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const srcRoot = resolve(projectRoot, 'src')

const rawOverlayAllowlist = new Set([
  'src/components/common/AppDialog.vue',
  'src/components/common/AppDrawer.vue',
])

// SDK 在外部业务系统内使用独立主题；调用确认区是表单内的警示，而非浮层。
const isolatedDialogAllowlist = new Map([
  ['src/sdk/EmbedMemoryPanel.vue', 'eaf-memory'],
  ['src/sdk/EmbedAiToolPanel.vue', 'eaf-aitool'],
  ['src/views/capability/components/BusinessMethodInvocationPanel.vue', 'invocation-confirmation'],
  // 侧栏外观/个人设置弹层自带 glass-surface-overlay + backdrop-filter 磨砂配方，套用全局配方会改掉模糊半径
  ['src/components/common/AppSidebar.vue', 'sidebar-footer-popover'],
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
let customDialogCount = 0

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

  // Match a complete static class token: market-page-header is a page-specific
  // hook on the shared PageHeader, not the retired page-header component.
  const hasLegacyHeader = [...code.matchAll(/(?:^|\s)class\s*=\s*["']([^"']*)["']/g)]
    .some(([, classes]) => classes.split(/\s+/).includes('page-header'))
  if (hasLegacyHeader) {
    errors.push(`${path}: 旧 page-header 骨架必须迁移到共享 PageHeader`)
  }

  const { descriptor } = parseSfc(code, { filename: file })
  if (descriptor.template) {
    const inspect = (node) => {
      if (node.type === 1) {
        const attr = name => node.props.find(prop => prop.type === 6 && prop.name === name)?.value?.content || ''
        if (['dialog', 'alertdialog'].includes(attr('role'))) {
          customDialogCount++
          const classes = `${attr('class')} ${attr('popper-class')}`.split(/\s+/)
          const isolatedClass = isolatedDialogAllowlist.get(path)
          if (!classes.includes('app-frosted-overlay') && !(isolatedClass && classes.includes(isolatedClass))) {
            errors.push(`${path}: 自定义弹层必须复用 app-frosted-overlay 磨砂材质，独立嵌入场景需显式登记`)
          }
        }
      }
      for (const child of node.children || []) inspect(child)
    }
    inspect(parseTemplate(descriptor.template.content))
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
  '.el-message-box',
  '.app-frosted-overlay.el-popper.is-light',
  '.el-overlay',
]) {
  if (!overlays.includes(selector)) {
    errors.push(`src/styles/_overlays.scss: 缺少共享覆盖层契约 ${selector}`)
  }
}

const components = readFileSync(resolve(srcRoot, 'styles/_components.scss'), 'utf8')
if (/\.el-message-box\s*\{/.test(components)) {
  errors.push('src/styles/_components.scss: 确认框材质必须由 _overlays.scss 统一管理')
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

console.log(`Overlay migration contract passed (${rawOverlayAllowlist.size} native adapters, ${customDialogCount} custom dialog declarations checked).`)
