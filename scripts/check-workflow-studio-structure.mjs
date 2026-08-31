import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const studioPath = join(projectRoot, 'ai-admin-front', 'src', 'views', 'workflow', 'WorkflowStudio.vue')
const studioSource = readFileSync(studioPath, 'utf8')
const studioLineCount = studioSource.split(/\r?\n/).length

const requiredOverlays = [
  'WorkflowStudioApiQueryTemplateDialog',
  'WorkflowStudioDebugDrawer',
  'WorkflowStudioEvalDrawer',
  'WorkflowStudioPublishDialog',
  'WorkflowStudioSourceDrawer',
]

const forbiddenInlineMarkers = [
  'class="studio-debug-drawer"',
  'title="Workflow 评测"',
  'title="Workflow 源码"',
  'class="api-query-template-dialog"',
  'title="发布 Workflow 版本"',
  'function resolveWorkflowThinkingPresentation(',
  ':global(.studio-debug-drawer-overlay)',
]

function fail(message) {
  console.error(`workflow studio structure check failed: ${message}`)
  process.exitCode = 1
}

if (studioLineCount > 7000) {
  fail(`WorkflowStudio.vue has ${studioLineCount} lines; the shell ceiling is 7000`)
}

for (const overlay of requiredOverlays) {
  const importPath = `@/views/workflow/studio-overlays/${overlay}.vue`
  if (!studioSource.includes(`import ${overlay} from '${importPath}'`)) {
    fail(`missing ${overlay} import from studio-overlays`)
  }
  if (!studioSource.includes(`<${overlay}`)) {
    fail(`missing ${overlay} composition in WorkflowStudio.vue`)
  }
  const overlayPath = join(
    projectRoot,
    'ai-admin-front',
    'src',
    'views',
    'workflow',
    'studio-overlays',
    `${overlay}.vue`,
  )
  try {
    readFileSync(overlayPath, 'utf8')
  } catch {
    fail(`missing overlay source ${overlayPath}`)
  }
}

for (const marker of forbiddenInlineMarkers) {
  if (studioSource.includes(marker)) {
    fail(`overlay-owned implementation leaked back into WorkflowStudio.vue: ${marker}`)
  }
}

if (process.exitCode) process.exit(process.exitCode)

console.log(`workflow studio structure checks passed (${studioLineCount} shell lines, ${requiredOverlays.length} overlays)`)
