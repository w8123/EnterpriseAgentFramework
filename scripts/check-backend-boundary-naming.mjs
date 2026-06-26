import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
let failures = 0

function read(rel) {
  const target = path.join(root, rel)
  if (!fs.existsSync(target)) {
    console.error(`[missing] ${rel}`)
    failures += 1
    return ''
  }
  return fs.readFileSync(target, 'utf8')
}

function assertFile(rel) {
  if (!fs.existsSync(path.join(root, rel))) {
    console.error(`[missing] ${rel}`)
    failures += 1
  }
}

function assertIncludes(rel, needle) {
  const text = read(rel)
  if (!text.includes(needle)) {
    console.error(`[missing text] ${rel}: ${needle}`)
    failures += 1
  }
}

function assertNotIncludes(rel, needle) {
  const text = read(rel)
  if (text.includes(needle)) {
    console.error(`[stale text] ${rel}: ${needle}`)
    failures += 1
  }
}

assertFile('docs/16-后端逻辑边界与命名重塑.md')
assertIncludes('README.md', '当前部署单元与目标逻辑域')
assertIncludes('README.md', 'Knowledge / Retrieval')
assertIncludes('README.md', 'Capability Catalog')
assertIncludes('README.md', 'Runtime Host')
assertIncludes('README.md', 'Platform Control')
assertIncludes('docs/01-平台定位与架构总览.md', '五个长期逻辑域')
assertIncludes('docs/16-后端逻辑边界与命名重塑.md', '不先拆服务，先拆职责')
assertIncludes('ai-agent-service/pom.xml', '<name>ReachAI Platform Core Service</name>')
assertNotIncludes('ai-agent-service/pom.xml', '<name>AI Agent Service</name>')
assertIncludes('ai-model-service/pom.xml', '<name>ReachAI Model Gateway Service</name>')
assertNotIncludes('ai-model-service/pom.xml', '<name>AI Model Service</name>')
assertIncludes('ai-skills-service/pom.xml', '<name>ReachAI Knowledge Retrieval Service</name>')
assertNotIncludes('ai-skills-service/pom.xml', '<name>AI Skills Service</name>')
assertNotIncludes('ai-skills-service/README.md', '# AI Skills Service')
assertIncludes('ai-skills-service/README.md', '# ReachAI Knowledge / Retrieval Service')
assertIncludes('ai-admin-front/src/api/request.ts', 'Knowledge / Retrieval deployment unit')
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', 'Knowledge / Retrieval')
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "name: 'ai-skills-service'")
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "healthPath: '/ai/actuator/health'")
assertIncludes('ai-admin-front/vite.config.ts', "target: 'http://localhost:18602'")
assertIncludes('ai-admin-front/vite.config.ts', "target: 'http://localhost:18603'")
assertIncludes('ai-admin-front/vite.config.ts', "target: 'http://localhost:18601'")

if (failures > 0) {
  console.error(`backend boundary naming check failed: ${failures} issue(s)`)
  process.exit(1)
}

console.log('backend boundary naming check passed')
