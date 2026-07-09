import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const workflowListPath = path.join(root, 'src/views/workflow/WorkflowList.vue')
const source = fs.readFileSync(workflowListPath, 'utf8')

const failures = []

if (!source.includes('<template #empty>')) {
  failures.push('Workflow list should render its custom empty state through the el-table empty slot.')
}

if (!source.includes('class="workflow-table-empty-state"')) {
  failures.push('Workflow empty state should use an explicit table-scoped wrapper.')
}

if (source.includes('v-if="!loading && filteredWorkflows.length === 0" class="empty-state"')) {
  failures.push('Workflow empty state should not be a standalone block below the table.')
}

if (!source.includes('.workflow-table-empty-state')) {
  failures.push('Workflow table empty state should have scoped centering styles.')
}

if (!source.includes('justify-content: center;') || !source.includes('align-items: center;')) {
  failures.push('Workflow empty state should center its content inside the table area.')
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Workflow empty state is integrated into the table.')
