import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

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

function existsRel(rel) {
  return fs.existsSync(path.join(root, rel))
}

function defaultMavenModules() {
  const text = read('pom.xml')
  const match = text.match(/<modules>([\s\S]*?)<\/modules>/)
  if (!match) {
    return []
  }
  return Array.from(match[1].matchAll(/<module>([^<]+)<\/module>/g), (moduleMatch) => moduleMatch[1].trim())
}

function assertDefaultMavenModuleAbsent(moduleName) {
  const modules = defaultMavenModules()
  if (modules.includes(moduleName)) {
    console.error(`[stale default maven module] ${moduleName}`)
    failures += 1
  }
}

function assertFile(rel) {
  if (!fs.existsSync(path.join(root, rel))) {
    console.error(`[missing] ${rel}`)
    failures += 1
  }
}

function assertPathAbsent(rel) {
  if (fs.existsSync(path.join(root, rel))) {
    console.error(`[stale path] ${rel}`)
    failures += 1
  }
}

function assertNoFilesUnder(rel, suffix) {
  const base = path.join(root, rel)
  if (!fs.existsSync(base)) {
    return
  }
  const matches = []
  const stack = [base]
  while (stack.length > 0) {
    const current = stack.pop()
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const target = path.join(current, entry.name)
      if (entry.isDirectory()) {
        stack.push(target)
      } else if (!suffix || entry.name.endsWith(suffix)) {
        matches.push(path.relative(root, target).replace(/\\/g, '/'))
      }
    }
  }
  if (matches.length > 0) {
    console.error(`[unexpected files] ${rel}`)
    for (const match of matches) {
      console.error(`  ${match}`)
    }
    failures += matches.length
  }
}

function assertNoPackageDeclarationUnder(rel, packageName) {
  const base = path.join(root, rel)
  if (!fs.existsSync(base)) {
    return
  }
  const needle = `package ${packageName};`
  const matches = []
  const stack = [base]
  while (stack.length > 0) {
    const current = stack.pop()
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const target = path.join(current, entry.name)
      if (entry.isDirectory()) {
        stack.push(target)
        continue
      }
      if (!entry.name.endsWith('.java')) {
        continue
      }
      const text = fs.readFileSync(target, 'utf8')
      if (text.includes(needle)) {
        matches.push(path.relative(root, target).replace(/\\/g, '/'))
      }
    }
  }
  if (matches.length > 0) {
    console.error(`[stale package] ${packageName}`)
    for (const match of matches) {
      console.error(`  ${match}`)
    }
    failures += matches.length
  }
}

function assertPackagePrefixUnder(rel, packagePrefix) {
  const base = path.join(root, rel)
  if (!fs.existsSync(base)) {
    console.error(`[missing] ${rel}`)
    failures += 1
    return
  }
  const mismatches = []
  const stack = [base]
  while (stack.length > 0) {
    const current = stack.pop()
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const target = path.join(current, entry.name)
      if (entry.isDirectory()) {
        stack.push(target)
        continue
      }
      if (!entry.name.endsWith('.java')) {
        continue
      }
      const text = fs.readFileSync(target, 'utf8')
      const match = text.match(/^package\s+([^;]+);/m)
      const actual = match ? match[1] : '(missing package)'
      if (actual !== packagePrefix && !actual.startsWith(`${packagePrefix}.`)) {
        mismatches.push(`${path.relative(root, target).replace(/\\/g, '/')}: ${actual}`)
      }
    }
  }
  if (mismatches.length > 0) {
    console.error(`[package prefix mismatch] ${rel}: expected ${packagePrefix}`)
    for (const mismatch of mismatches) {
      console.error(`  ${mismatch}`)
    }
    failures += mismatches.length
  }
}

function assertIncludes(rel, needle) {
  const text = read(rel)
  if (!text.includes(needle)) {
    console.error(`[missing text] ${rel}: ${needle}`)
    failures += 1
  }
}

function assertMatches(rel, pattern, description) {
  const text = read(rel)
  if (!pattern.test(text)) {
    console.error(`[missing pattern] ${rel}: ${description}`)
    failures += 1
  }
}

function assertNotMatches(rel, pattern, description) {
  const text = read(rel)
  if (pattern.test(text)) {
    console.error(`[stale pattern] ${rel}: ${description}`)
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

function addedDiffLines() {
  let output = ''
  try {
    output = execFileSync('git', ['diff', '--no-ext-diff', '--unified=0', '--', '.'], {
      cwd: root,
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024
    })
  } catch (error) {
    console.error(`[git diff failed] ${error.message}`)
    failures += 1
    return []
  }

  const lines = []
  let currentFile = ''
  for (const line of output.split(/\r?\n/)) {
    if (line.startsWith('+++ ')) {
      currentFile = line.startsWith('+++ b/') ? line.slice(6) : ''
      continue
    }
    if (line.startsWith('+') && !line.startsWith('+++')) {
      lines.push({ file: currentFile, text: line.slice(1) })
    }
  }

  try {
    const untracked = execFileSync('git', ['ls-files', '--others', '--exclude-standard', '--', '.'], {
      cwd: root,
      encoding: 'utf8'
    }).split(/\r?\n/).filter(Boolean)
    for (const file of untracked) {
      const abs = path.join(root, file)
      if (!fs.existsSync(abs) || fs.statSync(abs).isDirectory()) {
        continue
      }
      const text = fs.readFileSync(abs, 'utf8')
      for (const addedLine of text.split(/\r?\n/)) {
        lines.push({ file, text: addedLine })
      }
    }
  } catch (error) {
    console.error(`[git ls-files failed] ${error.message}`)
    failures += 1
  }
  return lines
}

function assertNoAddedText(pattern, description, allowedFiles = new Set()) {
  const matches = addedDiffLines().filter(({ file, text }) => {
    if (!file || allowedFiles.has(file)) {
      return false
    }
    return pattern.test(text)
  })
  if (matches.length > 0) {
    console.error(`[stale added text] ${description}`)
    for (const match of matches.slice(0, 10)) {
      console.error(`  ${match.file}: ${match.text}`)
    }
    failures += matches.length
  }
}

function assertNoStandaloneTextUnder(rel, pattern, description) {
  const base = path.join(root, rel)
  if (!fs.existsSync(base)) {
    console.error(`[missing] ${rel}`)
    failures += 1
    return
  }
  const matches = []
  const stack = [base]
  while (stack.length > 0) {
    const current = stack.pop()
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const target = path.join(current, entry.name)
      if (entry.isDirectory()) {
        stack.push(target)
        continue
      }
      const text = fs.readFileSync(target, 'utf8')
      pattern.lastIndex = 0
      if (pattern.test(text)) {
        matches.push(path.relative(root, target).replace(/\\/g, '/'))
      }
    }
  }
  if (matches.length > 0) {
    console.error(`[stale text under ${rel}] ${description}`)
    for (const match of matches) {
      console.error(`  ${match}`)
    }
    failures += matches.length
  }
}

function findDocByPrefix(prefix) {
  const docsDir = path.join(root, 'docs')
  const match = fs.readdirSync(docsDir).find((name) => name.startsWith(prefix) && name.endsWith('.md'))
  if (!match) {
    console.error(`[missing doc prefix] docs/${prefix}*.md`)
    failures += 1
    return null
  }
  return `docs/${match}`
}

const architectureOverviewDoc = findDocByPrefix('01-')
const knowledgeAssetDoc = findDocByPrefix('05-')
const embedSupportDoc = 'docs/reference/嵌入式对话与页面动作.md'
const jdkRuntimeLayeringDoc = 'docs/reference/JDK8-SDK与JDK17-Runtime分层.md'
const backendNamingDoc = 'docs/architecture/backend-boundaries-and-naming.md'
const retiredStructureDoc = 'docs/architecture/legacy-retirement.md'
const aiMemoryDocs = [
  'docs/ai-memory/README.md',
  'docs/ai-memory/PROJECT-MEMORY.md',
  'docs/ai-memory/WORKING-RULES.md',
  'docs/ai-memory/DECISIONS.md',
  'docs/ai-memory/VERIFICATION.md'
]
const readableEntryDocs = [
  'CONTRIBUTING.md',
  'docs/README.md',
  'sql/README.md'
]
const readableServiceDocs = [
  'ai-admin-front/README.md',
  'reachai-knowledge-service/README.md'
]
const readableAuthorityDocs = [
  architectureOverviewDoc,
  knowledgeAssetDoc,
  embedSupportDoc,
  jdkRuntimeLayeringDoc,
  'docs/reference/Context-Governance-Kernel.md'
].filter(Boolean)
const mojibakeSignatures = [
  '鈥',
  '鍩',
  '涓',
  '鐭',
  '閺',
  '閸',
  '鐎',
  '娑',
  '鏈枃',
  '褰撳墠',
  '鍚庣',
  '鐗╃悊',
  '绂佺敤',
  '瑜版挸',
  '銆乣',
  '圥',
  '圧',
  '圕',
  '圞',
  '圡'
]

for (const rel of aiMemoryDocs) {
  assertFile(rel)
  assertIncludes(rel, 'reachai-control-service')
  assertIncludes(rel, 'reachai-runtime-service')
  assertIncludes(rel, 'reachai-capability-service')
  assertIncludes(rel, 'reachai-knowledge-service')
  assertIncludes(rel, 'reachai-model-service')
  assertNotIncludes(rel, '鏈洰')
  assertNotIncludes(rel, '褰撳墠')
  assertNotIncludes(rel, '鐗╃悊')
  assertNotIncludes(rel, '鍚庣')
  assertNotIncludes(rel, '绂佺敤鍝嶅簲')
}
for (const rel of readableEntryDocs) {
  assertFile(rel)
  assertIncludes(rel, 'reachai-control-service')
  assertIncludes(rel, 'reachai-runtime-service')
  assertIncludes(rel, 'reachai-capability-service')
  assertIncludes(rel, 'reachai-knowledge-service')
  assertIncludes(rel, 'reachai-model-service')
  assertNotIncludes(rel, '鐫挎睜')
  assertNotIncludes(rel, '鏈洰')
  assertNotIncludes(rel, '褰撳墠')
  assertNotIncludes(rel, '鍚庣')
  assertNotIncludes(rel, '鍚姩')
  assertNotIncludes(rel, '绂佺敤鍝嶅簲')
}

for (const rel of readableServiceDocs) {
  assertFile(rel)
  assertIncludes(rel, 'reachai-control-service')
  assertIncludes(rel, 'reachai-runtime-service')
  assertIncludes(rel, 'reachai-capability-service')
  assertIncludes(rel, 'reachai-knowledge-service')
  assertIncludes(rel, 'reachai-model-service')
  assertNotIncludes(rel, 'disabled response')
  assertNotIncludes(rel, 'disabled route')
  assertNotIncludes(rel, 'Platform Control route is no longer proxied to ai-agent-service')
  assertNotIncludes(rel, 'Capability route is no longer proxied to ai-agent-service')
  for (const signature of mojibakeSignatures) {
    assertNotIncludes(rel, signature)
  }
}
for (const rel of readableAuthorityDocs) {
  assertFile(rel)
  assertIncludes(rel, 'reachai-control-service')
  assertIncludes(rel, 'reachai-runtime-service')
  assertIncludes(rel, 'reachai-capability-service')
  assertIncludes(rel, 'reachai-knowledge-service')
  assertIncludes(rel, 'reachai-model-service')
  assertNotIncludes(rel, 'AI Skills Service')
  assertNotIncludes(rel, 'ai-skills-service')
  assertNotIncludes(rel, 'disabled response')
  assertNotIncludes(rel, 'disabled route')
  for (const signature of mojibakeSignatures) {
    assertNotIncludes(rel, signature)
  }
}

if (backendNamingDoc) {
  assertFile(backendNamingDoc)
  assertNotIncludes(backendNamingDoc, '鍚庣')
  assertNotIncludes(backendNamingDoc, '褰撳墠')
  assertNotIncludes(backendNamingDoc, '绂佺敤鍝嶅簲')
  assertNotIncludes(backendNamingDoc, '杩斿洖鏄庣‘ disabled')
}
if (retiredStructureDoc) {
  assertFile(retiredStructureDoc)
  assertNotIncludes(retiredStructureDoc, '鏃х粨')
  assertNotIncludes(retiredStructureDoc, '褰撳墠')
  assertNotIncludes(retiredStructureDoc, '绂佺敤鍝嶅簲')
  assertNotIncludes(retiredStructureDoc, '鍚庣画')
}
assertNoFilesUnder('docs/superpowers', '.md')
for (const rel of [
  'docs/09-AI长任务代码审计提示词.md',
  'docs/10-只读代码审计报告-20260528.md',
  'docs/12-ReachAI嵌入式对话微服务接入问题与方案讨论.md',
  'docs/13-ai-spring-boot-starter与ai-skill-sdk正式退役计划.md',
  'docs/15-创建页面助手轻闭环.md'
]) {
  assertPathAbsent(rel)
}
assertFile('docs/architecture/physical-split-route-ownership.md')
assertFile('docs/architecture/public-route-contracts.md')
assertFile('docs/architecture/service-table-ownership.md')
assertFile('docs/architecture/internal-api-contracts.md')
assertFile('scripts/check-service-table-ownership.mjs')
assertFile('scripts/check-service-table-ownership.test.mjs')
assertFile('scripts/check-internal-api-contracts.mjs')
assertFile('scripts/check-internal-api-contracts.test.mjs')
assertFile('scripts/check-frontend-public-api-routes.mjs')
assertFile('scripts/check-frontend-public-api-routes.test.mjs')
assertIncludes('README.md', 'Knowledge / Retrieval')
assertIncludes('README.md', 'Capability Catalog')
assertIncludes('README.md', 'Runtime Host')
assertIncludes('README.md', 'Platform Control')
assertIncludes('AGENTS.md', 'ReachAI Agent Rules')
assertIncludes('AGENTS.md', 'reachai-control-service')
assertIncludes('AGENTS.md', 'reachai-runtime-service')
assertIncludes('AGENTS.md', 'reachai-capability-service')
assertIncludes('AGENTS.md', 'reachai-knowledge-service')
assertIncludes('AGENTS.md', 'reachai-model-service')
assertNotIncludes('AGENTS.md', '鏈枃')
assertNotIncludes('AGENTS.md', '褰撳墠')
assertNotIncludes('AGENTS.md', '鐗╃悊')
assertNotIncludes('AGENTS.md', '绂佺敤鍝嶅簲')
assertNotIncludes('README.md', '鐫挎睜')
assertNotIncludes('README.md', '绯荤粺鎴')
assertNotIncludes('README.md', '鍚姩')
assertNotIncludes('README.md', '绂佺敤鍝嶅簲')
if (architectureOverviewDoc) {
  assertIncludes(architectureOverviewDoc, 'Knowledge / Retrieval')
  assertIncludes(architectureOverviewDoc, 'Model Gateway')
}
if (backendNamingDoc) {
  assertIncludes(backendNamingDoc, 'reachai-control-service')
  assertIncludes(backendNamingDoc, 'reachai-runtime-service')
  assertIncludes(backendNamingDoc, 'reachai-capability-service')
}
assertIncludes('docs/architecture/physical-split-route-ownership.md', '`reachai-control-service` is the only public API/BFF entry in the first phase.')
assertIncludes('docs/architecture/physical-split-route-ownership.md', 'docs/architecture/public-route-contracts.md')
assertIncludes('docs/architecture/public-route-contracts.md', '## Route Lifecycle Classes')
assertIncludes('docs/architecture/public-route-contracts.md', '| Main public path |')
assertIncludes('docs/architecture/public-route-contracts.md', '| Frozen compatibility alias |')
assertIncludes('docs/architecture/public-route-contracts.md', '| Retired route |')
assertIncludes('docs/architecture/public-route-contracts.md', '| Internal API |')
assertIncludes('docs/architecture/public-route-contracts.md', '`/api/runtime/agents/execute/**`')
assertIncludes('docs/architecture/public-route-contracts.md', '`/api/capability-mining/**`')
assertIncludes('docs/architecture/public-route-contracts.md', '`/api/agent/execute/**`')
assertIncludes('docs/architecture/public-route-contracts.md', '`/api/skill-mining/**`')
assertIncludes('docs/architecture/public-route-contracts.md', '`/internal/runtime/**`')
assertIncludes('docs/README.md', 'architecture/public-route-contracts.md')
assertIncludes('README.md', 'Public Route Contracts')
assertIncludes('docs/architecture/service-table-ownership.md', '`runtime_skill_interaction`')
assertIncludes('docs/architecture/service-table-ownership.md', 'Shared Table Exceptions Retired')
assertIncludes('docs/architecture/internal-api-contracts.md', '`GET /internal/control/page-actions/{projectCode}/{pageKey}/{actionKey}`')
assertIncludes('docs/architecture/internal-api-contracts.md', 'The frontend enters through `reachai-control-service` public `/api/**`')
assertDefaultMavenModuleAbsent('ai-agent-service')
assertDefaultMavenModuleAbsent('ai-model-service')
assertDefaultMavenModuleAbsent('ai-skills-service')
assertNotIncludes('pom.xml', '<id>legacy-agent-service</id>')
assertFile('deploy/Dockerfile.reachai-knowledge-service')
assertFile('deploy/Dockerfile.reachai-model-service')
assertFile('deploy/Dockerfile.reachai-control-service')
assertFile('deploy/Dockerfile.reachai-runtime-service')
assertFile('deploy/Dockerfile.reachai-capability-service')
assertIncludes('deploy/Dockerfile.reachai-control-service', 'COPY reachai-control-service/target/reachai-control-service-*.jar app.jar')
assertIncludes('deploy/Dockerfile.reachai-control-service', 'EXPOSE 18603')
assertIncludes('deploy/Dockerfile.reachai-runtime-service', 'COPY reachai-runtime-service/target/reachai-runtime-service-*.jar app.jar')
assertIncludes('deploy/Dockerfile.reachai-runtime-service', 'EXPOSE 18604')
assertIncludes('deploy/Dockerfile.reachai-capability-service', 'COPY reachai-capability-service/target/reachai-capability-service-*.jar app.jar')
assertIncludes('deploy/Dockerfile.reachai-capability-service', 'EXPOSE 18605')
assertIncludes('deploy/Dockerfile.reachai-knowledge-service', 'COPY reachai-knowledge-service/target/reachai-knowledge-service-*.jar app.jar')
assertIncludes('deploy/Dockerfile.reachai-knowledge-service', 'EXPOSE 18602')
assertIncludes('deploy/Dockerfile.reachai-model-service', 'COPY reachai-model-service/target/reachai-model-service-*.jar app.jar')
assertIncludes('deploy/Dockerfile.reachai-model-service', 'EXPOSE 18601')
assertFile('deploy/k8s/reachai-control-service.yml')
assertFile('deploy/k8s/reachai-runtime-service.yml')
assertFile('deploy/k8s/reachai-capability-service.yml')
assertFile('deploy/k8s/reachai-knowledge-service.yml')
assertFile('deploy/k8s/reachai-model-service.yml')
assertNoStandaloneTextUnder('deploy', /(^|[^a-z-])ai-agent-service([^a-z-]|$)/, 'standalone ai-agent-service')
assertNoStandaloneTextUnder('deploy', /(^|[^a-z-])ai-skills-service([^a-z-]|$)/, 'standalone ai-skills-service')
assertNoStandaloneTextUnder('deploy', /(^|[^a-z-])ai-model-service([^a-z-]|$)/, 'standalone ai-model-service')
assertNoStandaloneTextUnder('deploy', /Dockerfile\.(agent|skills|model)-service/, 'old Dockerfile service names')
assertNoStandaloneTextUnder('deploy', /(^|[^0-9])18606([^0-9]|$)/, 'old ai-agent-service port 18606')
if (existsRel('ai-agent-service')) {
  console.error('[retired module still exists] ai-agent-service')
  failures += 1
}
assertFile('reachai-model-service/pom.xml')
assertFile('reachai-knowledge-service/pom.xml')
assertIncludes('pom.xml', '<artifactId>spring-boot-maven-plugin</artifactId>')
assertIncludes('pom.xml', '<goal>repackage</goal>')
assertNotIncludes('pom.xml', '閻')
assertNotIncludes('pom.xml', '閸')
assertNotIncludes('pom.xml', '閺')
assertNotIncludes('pom.xml', '鐎')
assertNotIncludes('pom.xml', '鏁')
assertNotIncludes('pom.xml', '鏂')
assertNotIncludes('pom.xml', '鍐')
assertNotIncludes('pom.xml', '鎵')
for (const rel of [
  'reachai-model-service/pom.xml',
  'reachai-knowledge-service/pom.xml',
  'reachai-capability-service/pom.xml',
  'reachai-runtime-service/pom.xml',
  'reachai-control-service/pom.xml'
]) {
  assertIncludes(rel, '<artifactId>spring-boot-maven-plugin</artifactId>')
}
assertIncludes('sql/README.md', 'reachai-knowledge-service')
assertIncludes('sql/README.md', 'reachai-model-service')
assertNotIncludes('sql/README.md', 'ai-skills-service')
assertNotMatches('sql/README.md', /(^|[^a-z-])ai-model-service([^a-z-]|$)/, 'standalone ai-model-service')
assertIncludes('sql/initV2.sql', '当前归属 reachai-knowledge-service')
assertIncludes('sql/initV2.sql', '当前归属 reachai-model-service')
assertNotIncludes('sql/initV2.sql', '对应 ai-skills-service')
assertNotIncludes('sql/initV2.sql', '对应 ai-model-service')
assertIncludes('ai-admin-front/README.md', '# ReachAI Admin Frontend')
assertIncludes('ai-admin-front/README.md', 'Vite dev server runs on http://localhost:5200')
assertIncludes('ai-admin-front/README.md', 'Frontend does not call reachai-runtime-service:18604 or reachai-capability-service:18605 directly.')
assertIncludes('reachai-knowledge-service/README.md', '目录名和 Maven artifactId 均已收口到 `reachai-knowledge-service`')
assertIncludes('reachai-knowledge-service/README.md', 'context path `/ai`')
assertNotIncludes('reachai-knowledge-service/README.md', 'artifactId 暂时保留')
assertNotIncludes('reachai-knowledge-service/README.md', 'artifactId 鏆')
assertNotIncludes('docs/architecture/backend-boundaries-and-naming.md', '历史 artifactId')
assertNotIncludes('docs/architecture/backend-boundaries-and-naming.md', '鍘嗗彶 artifactId')
assertNotIncludes('docs/architecture/physical-services-and-startup.md', 'artifactId 暂时保留')
assertNotIncludes('docs/architecture/physical-services-and-startup.md', 'artifactId 鏆')
assertNotIncludes('docs/architecture/legacy-retirement.md', 'artifactId 暂时保留')
assertNotIncludes('docs/architecture/legacy-retirement.md', 'artifactId 鏆')
assertIncludes(
  'reachai-control-service/src/main/java/com/enterprise/ai/control/aiassist/ControlAiAssistSkillController.java',
  '@RequestMapping("/api/ai-assist")')
assertIncludes(
  'ai-admin-front/scripts/check-page-assistant-prompt.mjs',
  '../reachai-control-service/src/main/resources/ai-assist/skills/reachai-page-assistant-onboarding/SKILL.md')
assertIncludes('reachai-model-service/pom.xml', '<name>ReachAI Model Gateway Service</name>')
assertNotIncludes('reachai-model-service/pom.xml', '<name>AI Model Service</name>')
assertIncludes('reachai-knowledge-service/pom.xml', '<name>ReachAI Knowledge Retrieval Service</name>')
assertNotIncludes('reachai-knowledge-service/pom.xml', '<name>AI Skills Service</name>')
assertNotIncludes('reachai-knowledge-service/README.md', '# AI Skills Service')
assertIncludes('reachai-knowledge-service/README.md', '# ReachAI Knowledge / Retrieval Service')
assertIncludes('ai-admin-front/src/api/request.ts', 'Knowledge / Retrieval deployment unit')
assertIncludes('ai-admin-front/src/api/request.ts', 'Platform Control public API/BFF')
assertIncludes('ai-admin-front/src/api/request.ts', '请求失败')
assertNotIncludes('ai-admin-front/src/api/request.ts', '鈥')
assertNotIncludes('ai-admin-front/src/api/request.ts', '锛')
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', 'Knowledge / Retrieval')
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '服务拓扑')
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '正常')
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '异常')
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '系统启动完成')
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '模型实例')
assertNotIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '姝ｅ父')
assertNotIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '寮傚父')
assertNotIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '姒傝')
assertNotIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '鏈嶅姟鎷撴墤')
assertNotIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '鏌ョ湅')
assertNotIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '妯″瀷')
assertNotIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '绯荤粺')
assertNotIncludes('reachai-knowledge-service/src/main/java/com/enterprise/ai/text/tooling/scanner/ScanOptions.java', 'ai-agent-service')
assertIncludes('reachai-knowledge-service/src/main/java/com/enterprise/ai/client/ModelServiceClient.java', 'ReachAI Model Gateway deployment unit')
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "serviceHealth['reachai-control-service']")
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "serviceHealth['reachai-runtime-service']")
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "serviceHealth['reachai-capability-service']")
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "serviceHealth['reachai-knowledge-service']")
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "checkHttpService('reachai-knowledge-service', '/ai/actuator/health')")
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', "checkHttpService('reachai-model-service', '/model/providers')")
assertIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', '/api/internal-services/health')
assertNotIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', 'http://localhost:18604')
assertNotIncludes('ai-admin-front/src/views/dashboard/Dashboard.vue', 'http://localhost:18605')
assertIncludes('reachai-knowledge-service/src/main/resources/application.yml', 'port: 18602')
assertIncludes('reachai-model-service/src/main/resources/application.yml', 'port: 18601')
assertIncludes('reachai-control-service/pom.xml', '<name>ReachAI Control Service</name>')
assertIncludes('reachai-runtime-service/pom.xml', '<name>ReachAI Runtime Service</name>')
assertIncludes('reachai-capability-service/pom.xml', '<name>ReachAI Capability Service</name>')
assertNotIncludes('reachai-control-service/pom.xml', '<name>AI Agent Service</name>')
assertNotIncludes('reachai-runtime-service/pom.xml', '<name>AI Agent Service</name>')
assertNotIncludes('reachai-capability-service/pom.xml', '<name>AI Agent Service</name>')
assertIncludes('reachai-control-service/src/main/resources/application.yml', 'port: ${SERVER_PORT:18603}')
assertIncludes('reachai-runtime-service/src/main/resources/application.yml', 'port: ${SERVER_PORT:18604}')
assertIncludes('reachai-capability-service/src/main/resources/application.yml', 'port: ${SERVER_PORT:18605}')

const ideaRunConfigurations = [
  ['.run/01-reachai-model-service.run.xml', '01 ReachAI Model Service', 'reachai-model-service', 'com.enterprise.ai.model.ReachAiModelServiceApplication'],
  ['.run/02-reachai-knowledge-service.run.xml', '02 ReachAI Knowledge Service', 'reachai-knowledge-service', 'com.enterprise.ai.ReachAiKnowledgeServiceApplication'],
  ['.run/03-reachai-capability-service.run.xml', '03 ReachAI Capability Service', 'reachai-capability-service', 'com.enterprise.ai.capability.ReachAiCapabilityServiceApplication'],
  ['.run/04-reachai-runtime-service.run.xml', '04 ReachAI Runtime Service', 'reachai-runtime-service', 'com.enterprise.ai.runtime.ReachAiRuntimeServiceApplication'],
  ['.run/05-reachai-control-service.run.xml', '05 ReachAI Control Service', 'reachai-control-service', 'com.enterprise.ai.control.ReachAiControlServiceApplication']
]
for (const [rel, displayName, moduleName, mainClass] of ideaRunConfigurations) {
  assertFile(rel)
  assertIncludes(rel, '<component name="ProjectRunConfigurationManager">')
  assertIncludes(rel, `name="${displayName}"`)
  assertIncludes(rel, 'type="SpringBootApplicationConfigurationType"')
  assertIncludes(rel, `module name="${moduleName}"`)
  assertIncludes(rel, `value="${mainClass}"`)
  assertNotMatches(rel, /(^|[^a-z-])ai-agent-service([^a-z-]|$)/, 'standalone ai-agent-service')
  assertNotMatches(rel, /(^|[^a-z-])ai-skills-service([^a-z-]|$)/, 'standalone ai-skills-service')
  assertNotMatches(rel, /(^|[^a-z-])ai-model-service([^a-z-]|$)/, 'standalone ai-model-service')
}
assertFile('.run/00-reachai-five-services.run.xml')
assertIncludes('.run/00-reachai-five-services.run.xml', '<component name="ProjectRunConfigurationManager">')
assertIncludes('.run/00-reachai-five-services.run.xml', 'name="00 ReachAI Five Services"')
assertIncludes('.run/00-reachai-five-services.run.xml', 'type="CompoundRunConfigurationType"')
for (const [, displayName] of ideaRunConfigurations) {
  assertIncludes('.run/00-reachai-five-services.run.xml', `name="${displayName}"`)
  assertIncludes('.run/00-reachai-five-services.run.xml', 'type="SpringBootApplicationConfigurationType"')
}
assertNotMatches('.run/00-reachai-five-services.run.xml', /(^|[^a-z-])ai-agent-service([^a-z-]|$)/, 'standalone ai-agent-service')
assertNotMatches('.run/00-reachai-five-services.run.xml', /(^|[^a-z-])ai-skills-service([^a-z-]|$)/, 'standalone ai-skills-service')
assertNotMatches('.run/00-reachai-five-services.run.xml', /(^|[^a-z-])ai-model-service([^a-z-]|$)/, 'standalone ai-model-service')
assertMatches('ai-admin-front/vite.config.ts', /['"]\/ai['"]:\s*\{[\s\S]*?target:\s*['"]http:\/\/localhost:18602['"]/, '/ai proxy -> 18602')
assertMatches('ai-admin-front/vite.config.ts', /['"]\/api['"]:\s*\{[\s\S]*?target:\s*['"]http:\/\/localhost:18603['"]/, '/api proxy -> 18603')
assertMatches('ai-admin-front/vite.config.ts', /\^\/model\/\(providers\|instances\|chat\)[\s\S]*?target:\s*['"]http:\/\/localhost:18601['"]/, '/model proxy -> 18601')
assertPackagePrefixUnder('reachai-control-service/src/main/java/com/enterprise/ai/control', 'com.enterprise.ai.control')
assertPackagePrefixUnder('reachai-runtime-service/src/main/java/com/enterprise/ai/runtime', 'com.enterprise.ai.runtime')
assertPackagePrefixUnder('reachai-capability-service/src/main/java/com/enterprise/ai/capability', 'com.enterprise.ai.capability')

const keyApiPaths = [
  ['reachai-model-service/src/main/java/com/enterprise/ai/model/controller/ModelController.java', '@RequestMapping("/model")'],
  ['reachai-model-service/src/main/java/com/enterprise/ai/model/controller/OpenAIProxyController.java', '@RequestMapping("/model/openai-proxy")'],
  ['reachai-model-service/src/main/java/com/enterprise/ai/model/instance/ModelInstanceController.java', '@RequestMapping("/model/instances")'],
  ['reachai-knowledge-service/src/main/java/com/enterprise/ai/controller/KnowledgeController.java', '@RequestMapping("/knowledge")'],
  ['reachai-knowledge-service/src/main/java/com/enterprise/ai/controller/FileController.java', '@RequestMapping("/file")'],
  ['reachai-knowledge-service/src/main/java/com/enterprise/ai/controller/PipelineController.java', '@RequestMapping("/pipeline")'],
  ['reachai-knowledge-service/src/main/java/com/enterprise/ai/controller/RagController.java', '@RequestMapping("/rag")'],
  ['reachai-knowledge-service/src/main/java/com/enterprise/ai/controller/RetrievalController.java', '@RequestMapping("/retrieval")'],
  ['reachai-knowledge-service/src/main/java/com/enterprise/ai/controller/ScannerController.java', '@RequestMapping("/scanner")'],
  ['reachai-knowledge-service/src/main/java/com/enterprise/ai/bizindex/controller/BizIndexController.java', '@RequestMapping("/biz-index")'],
  ['reachai-capability-service/src/main/java/com/enterprise/ai/capability/registry/CapabilityRegistryOperationsCompatibilityController.java', '@RequestMapping("/api/registry")'],
  ['reachai-capability-service/src/main/java/com/enterprise/ai/capability/registry/CapabilityRegistryCompatibilityController.java', '@RequestMapping("/api/registry/projects")'],
  ['reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/asset/CapabilityApiAssetController.java', '@RequestMapping("/api/api-assets")'],
  ['reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/graph/CapabilityApiGraphSnapshotController.java', '@RequestMapping("/api/api-graph/projects/{projectId}")'],
  ['reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/mining/CapabilityMiningController.java', '@RequestMapping({"/api/skill-mining", "/api/capability-mining"})'],
  ['reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/scan/CapabilityScanProjectCatalogController.java', '@RequestMapping("/api/scan-projects")'],
  ['reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/composition/CapabilityCompositionCatalogController.java', '@RequestMapping({"/api/compositions", "/api/skills"})'],
  ['reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/tool/CapabilityToolCatalogController.java', '@RequestMapping("/api/tools")'],
  ['reachai-capability-service/src/main/java/com/enterprise/ai/capability/catalog/retrieval/CapabilityToolRetrievalController.java', '@RequestMapping("/api/tool-retrieval")'],
  ['reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimePublicCompatibilityController.java', '@PostMapping({"/api/agent/execute", "/api/runtime/agents/execute"})'],
  ['reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeWorkflowCompatibilityController.java', '@GetMapping("/api/workflows")'],
  ['reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeWorkflowCredentialCompatibilityController.java', '@GetMapping({"/api/agent/workflow-credentials", "/api/workflows/credentials"})'],
  ['reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat/RuntimeRegistryCompatibilityController.java', '@GetMapping("/api/runtimes")'],
  ['reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/internal/RuntimeToolCallLogInternalController.java', '@RequestMapping("/internal/runtime/tool-call-logs")'],
  ['reachai-control-service/src/main/java/com/enterprise/ai/control/platform/PlatformEmbedPublicController.java', '@RequestMapping("/api/embed")'],
  ['reachai-control-service/src/main/java/com/enterprise/ai/control/aiassist/ControlAiAssistSkillController.java', '@RequestMapping("/api/ai-assist")'],
  ['reachai-control-service/src/main/java/com/enterprise/ai/control/aiassist/ControlAiCodingProjectController.java', '@RequestMapping("/api/ai-coding/projects/{projectId}")'],
  ['reachai-control-service/src/main/java/com/enterprise/ai/control/governance/ControlMcpAdminController.java', '@RequestMapping("/api/mcp")'],
  ['reachai-control-service/src/main/java/com/enterprise/ai/control/governance/ControlToolAclController.java', '@RequestMapping("/api/tool-acl")'],
  ['reachai-control-service/src/main/java/com/enterprise/ai/control/internal/InternalPageActionCatalogController.java', '@RequestMapping("/internal/control/page-actions")']
]

for (const [rel, apiPath] of keyApiPaths) {
  assertIncludes(rel, apiPath)
}

const allowedDiffScanFiles = new Set(['scripts/check-backend-boundary-naming.mjs'])
assertNoAddedText(/\bAI Skills Service\b/, 'do not add new mainline "AI Skills Service" wording', allowedDiffScanFiles)
assertNoAddedText(/\bAgent Studio\b/, 'do not add new wording that reverts Workflow Studio back to Agent Studio', allowedDiffScanFiles)

if (failures > 0) {
  console.error(`backend boundary naming check failed: ${failures} issue(s)`)
  process.exit(1)
}

console.log('backend boundary naming check passed')
