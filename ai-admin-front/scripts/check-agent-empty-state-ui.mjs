import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const agentListPath = path.join(root, 'src/views/agent/AgentList.vue')
const source = fs.readFileSync(agentListPath, 'utf8')

const failures = []

if (!source.includes('<DataTableShell')) {
  failures.push('Agent empty state should be owned by the shared DataTableShell.')
}

if (!source.includes('empty-description="暂无符合条件的智能体"')) {
  failures.push('Agent DataTableShell should expose the canonical empty-state copy.')
}

if (!source.includes('<template #empty>')) {
  failures.push('Agent list should provide an explicit DataTableShell empty slot.')
}

if (!source.includes('<el-empty description="暂无符合条件的智能体">')) {
  failures.push('Agent empty slot should render the canonical empty illustration and copy.')
}

if (!source.includes('@click="handleCreate">新建智能体</el-button>')) {
  failures.push('Agent empty state should offer the primary create action.')
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Agent empty state is aligned with the shared DataTableShell.')
