import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const workflowListPath = path.join(root, 'src/views/workflow/WorkflowList.vue')
const metricIconPath = path.join(root, 'src/components/common/MetricIconBg.vue')
const source = fs.readFileSync(workflowListPath, 'utf8')
const metricIconSource = fs.readFileSync(metricIconPath, 'utf8')

const failures = []

if (!source.includes("import MetricIconBg from '@/components/common/MetricIconBg.vue'")) {
  failures.push('Workflow list should reuse MetricIconBg for metric icons.')
}

if (!source.includes('class="metric-strip"')) {
  failures.push('Workflow list should use the project-management metric-strip layout.')
}

if (!source.includes('class="metric-segment"')) {
  failures.push('Workflow list should render metrics as metric-segment entries.')
}

if (!source.includes('class="workflow-table-shell"')) {
  failures.push('Workflow table should be wrapped in a table shell matching project management.')
}

if (source.includes('class="metric-grid"') || source.includes('class="metric-card"')) {
  failures.push('Workflow list should not keep the old card-grid metric layout.')
}

if (!source.includes('class="hero-copy"') || !source.includes('class="hero-tags"')) {
  failures.push('Workflow hero should mirror the project-management hero copy and tag structure.')
}

const contextFilterIndex = source.indexOf('class="context-filter"')
const toolbarIndex = source.indexOf('class="toolbar"')
if (contextFilterIndex === -1 || toolbarIndex === -1 || contextFilterIndex > toolbarIndex) {
  failures.push('Project-scoped Workflow notice should appear above the search toolbar.')
}

for (const iconKey of ['workflow-total', 'workflow-published', 'workflow-sources', 'workflow-manual']) {
  if (!source.includes(`iconKey: '${iconKey}'`)) {
    failures.push(`Workflow metrics should use the ${iconKey} scene icon.`)
  }

  if (!metricIconSource.includes(`iconKey === '${iconKey}'`)) {
    failures.push(`MetricIconBg should implement the ${iconKey} scene icon.`)
  }
}

if (!/\.hero-copy\s*\{[^}]*align-items:\s*flex-start;/s.test(source)) {
  failures.push('Workflow hero accent should align to the title line instead of centering on the whole copy block.')
}

if (!/\.hero-accent\s*\{[^}]*margin-top:\s*4px;/s.test(source)) {
  failures.push('Workflow hero accent should have a small top offset to sit beside the title.')
}

const workflowMetricIconBlock = source.match(/\.metric-segment-icon\s*\{[^}]*\}/s)?.[0] ?? ''
for (const declaration of [
  '--metric-icon-bg-size: 54px;',
  '--metric-icon-glyph-size: 30px;',
  '--metric-icon-stroke-width: 1.75;',
]) {
  if (!workflowMetricIconBlock.includes(declaration)) {
    failures.push(`Workflow metric icons should be larger and include ${declaration}`)
  }
}

const workflowListPageBlock = source.match(/\.workflow-list-page\s*\{[^}]*\}/s)?.[0] ?? ''
if (!workflowListPageBlock.includes('height: 100%;')) {
  failures.push('Workflow list page should inherit the main content height for sidebar-bottom alignment.')
}

if (workflowListPageBlock.includes('height: calc(100vh - 56px);')) {
  failures.push('Workflow list page should not use the old hard-coded viewport height.')
}

if (!workflowListPageBlock.includes('padding: 24px 28px 14px;')) {
  failures.push('Workflow list page bottom padding should match the sidebar bottom inset.')
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Workflow list follows the project-management page style structure.')
