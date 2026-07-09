import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const stepProgressSource = readFileSync(
  join(process.cwd(), 'src/views/registry/components/page-assistant/PageAssistantStepProgress.vue'),
  'utf8',
)
const headerSource = readFileSync(
  join(process.cwd(), 'src/views/registry/components/page-assistant/PageAssistantHeader.vue'),
  'utf8',
)
const connectPanelSource = readFileSync(
  join(process.cwd(), 'src/views/registry/components/page-assistant/PageAssistantConnectPanel.vue'),
  'utf8',
)
const pageAssistantStyleSource = readFileSync(
  join(process.cwd(), 'src/views/registry/styles/PageAssistantWizard.scss'),
  'utf8',
)

assert.match(stepProgressSource, /step-progress access-progress--refined page-assistant-progress/)
assert.match(stepProgressSource, /completedStepCount/)
assert.match(stepProgressSource, /completedPercent/)
assert.match(stepProgressSource, /access-progress-track/)
assert.match(stepProgressSource, /class="step-copy"/)
assert.match(stepProgressSource, /class="step-title-line"/)
assert.match(stepProgressSource, /class="step-number"/)
assert.match(stepProgressSource, /\.page-assistant-progress\.access-progress--refined/)
assert.match(stepProgressSource, /grid-template-columns:\s*minmax\(0,\s*1fr\)\s*18px/)

assert.match(headerSource, /page-header title-card page-assistant-title-card/)
assert.match(headerSource, /title-card-layers/)
assert.match(headerSource, /title-card-hero-image/)
assert.match(headerSource, /title-card-body/)
assert.match(headerSource, /hero-copy/)
assert.match(headerSource, /hero-accent/)
assert.match(headerSource, /class="project-meta"/)
assert.match(headerSource, /\.page-assistant-title-card/)
assert.doesNotMatch(headerSource, /ArrowLeft|defineEmits|<el-button/)

assert.match(connectPanelSource, /step-screen page-assistant-connect-workbench/)
assert.match(connectPanelSource, /connect-hero/)
assert.match(connectPanelSource, /connect-stat-strip/)
assert.match(connectPanelSource, /connect-stat-card/)
assert.match(connectPanelSource, /access-task-panel/)
assert.match(connectPanelSource, /access-empty-state/)
assert.doesNotMatch(connectPanelSource, /health-grid|health-card/)

assert.match(pageAssistantStyleSource, /\.page-assistant-connect-workbench/)
assert.match(pageAssistantStyleSource, /\.connect-hero/)
assert.match(pageAssistantStyleSource, /\.connect-stat-strip/)
assert.match(pageAssistantStyleSource, /\.access-task-panel/)
assert.match(pageAssistantStyleSource, /\.access-empty-state/)

console.log('page assistant progress ui assertions passed')
