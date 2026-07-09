import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const agentListPath = path.join(root, 'src/views/agent/AgentList.vue')
const source = fs.readFileSync(agentListPath, 'utf8')

const failures = []

if (!source.includes('class="agent-empty-state"')) {
  failures.push('Agent card empty state should use an explicit full-grid wrapper.')
}

if (!source.includes('<el-empty description="暂无符合条件的智能体" />')) {
  failures.push('Agent empty-state copy should remain unchanged.')
}

if (!source.includes('grid-column: 1 / -1;')) {
  failures.push('Agent empty-state wrapper should span all card-grid columns.')
}

if (!source.includes('place-items: center;')) {
  failures.push('Agent empty-state wrapper should center the empty illustration and text.')
}

if (!source.includes('min-height: 320px;')) {
  failures.push('Agent empty-state wrapper should reserve enough vertical space for centered content.')
}

if (failures.length) {
  console.error(failures.join('\n'))
  process.exit(1)
}

console.log('Agent empty state is centered in card view.')
