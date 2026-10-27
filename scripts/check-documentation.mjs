import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '..')
const docsRoot = path.join(repoRoot, 'docs')
const failures = []
const linksByFile = new Map()

function relative(file) {
  return path.relative(repoRoot, file).replaceAll(path.sep, '/')
}

function fail(file, message, line) {
  failures.push(`${relative(file)}${line ? `:${line}` : ''} ${message}`)
}

function readText(file) {
  return fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')
}

function walkFiles(directory, predicate) {
  const result = []
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const resolved = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      result.push(...walkFiles(resolved, predicate))
    } else if (predicate(resolved)) {
      result.push(resolved)
    }
  }
  return result
}

function decodeLinkTarget(value) {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function isWithin(file, directory) {
  const resolved = path.relative(directory, file)
  return resolved === '' || (!resolved.startsWith(`..${path.sep}`) && resolved !== '..' && !path.isAbsolute(resolved))
}

function validateMarkdownFile(file, requireHeading = true) {
  const text = readText(file)
  const firstContent = text.split(/\r?\n/).find((line) => line.trim().length > 0)
  if (requireHeading && !firstContent?.startsWith('# ')) {
    fail(file, '首个非空行必须是一级标题')
  }

  const lines = text.split(/\r?\n/)
  const links = new Set()
  linksByFile.set(file, links)
  for (let index = 0; index < lines.length; index += 1) {
    const trailingWhitespace = lines[index].match(/[ \t]+$/)?.[0]
    if (trailingWhitespace && trailingWhitespace !== '  ') {
      fail(file, '行尾存在多余空白', index + 1)
    }
    const linkPattern = /\[[^\]]*\]\(([^)]+)\)/g
    for (const match of lines[index].matchAll(linkPattern)) {
      let target = match[1].trim()
      if (target.startsWith('<') && target.endsWith('>')) {
        target = target.slice(1, -1)
      }
      if (!target || /^(?:[a-z][a-z0-9+.-]*:|#)/i.test(target)) continue

      const pathPart = target.split('#', 1)[0].split('?', 1)[0]
      if (!pathPart) continue
      const decoded = decodeLinkTarget(pathPart)
      const resolved = path.resolve(path.dirname(file), decoded)
      links.add(resolved)
      if (isWithin(resolved, path.join(repoRoot, 'output'))) {
        if (isWithin(file, docsRoot)) fail(file, '核心文档不得链接本机历史执行产物', index + 1)
        continue
      }
      if (!fs.existsSync(resolved)) {
        fail(file, `本地链接不存在: ${target}`, index + 1)
      }
    }
  }
}

const markdownFiles = walkFiles(docsRoot, (file) => file.endsWith('.md'))
for (const file of markdownFiles) validateMarkdownFile(file)

// 一个入口形成可遍历的阅读图，不要求每层目录都复制 README 索引。
const entryFile = path.join(docsRoot, 'README.md')
const reachable = new Set()
const pending = [entryFile]
while (pending.length > 0) {
  const current = pending.pop()
  if (reachable.has(current)) continue
  reachable.add(current)
  for (const target of linksByFile.get(current) || []) {
    if (linksByFile.has(target)) pending.push(target)
  }
}
for (const file of markdownFiles) {
  if (!reachable.has(file)) fail(entryFile, `未从文档入口可达: ${relative(file)}`)
}

const rootPom = readText(path.join(repoRoot, 'pom.xml'))
const serviceModules = [...rootPom.matchAll(/<module>([^<]+-service)<\/module>/g)].map((match) => match[1])
for (const moduleName of serviceModules) {
  const readme = path.join(repoRoot, moduleName, 'README.md')
  if (!fs.existsSync(readme)) {
    fail(readme, `Maven 服务模块 ${moduleName} 缺少 README.md`)
  }
}

const moduleReadmes = [
  ...serviceModules.map((name) => path.join(repoRoot, name, 'README.md')),
  path.join(repoRoot, 'ai-admin-front', 'README.md'),
  path.join(repoRoot, 'reachai-capability-sdk', 'README.md'),
  path.join(repoRoot, 'reachai-spring-boot2-starter', 'README.md'),
]
for (const readme of moduleReadmes) {
  if (fs.existsSync(readme)) validateMarkdownFile(readme)
}
validateMarkdownFile(path.join(repoRoot, 'README.md'), false)

// 模块之外的规则、设计、部署和随产品分发的 Skill 同样需要可用的引用。
const additionalPaths = [
  'AGENTS.md', 'CONTRIBUTING.md', 'sql/README.md',
  'deploy/quickstart/README.md', 'managed-executor-worker/README.md',
  'ai-admin-front/DESIGN.md', 'ai-admin-front/THIRD_PARTY_NOTICES.md',
  '.cursor/rules/reachai-project.mdc',
]
const additionalRoots = [
  'reachai-control-service/src/main/resources/ai-assist/skills',
  'ai-admin-front/src/assets/ai-coding-providers',
]
const additionalMarkdownFiles = [
  ...additionalPaths.map((name) => path.join(repoRoot, name)).filter((file) => fs.existsSync(file)),
  ...additionalRoots.flatMap((name) => {
    const directory = path.join(repoRoot, name)
    return fs.existsSync(directory) ? walkFiles(directory, (file) => file.endsWith('.md')) : []
  }),
]
for (const file of additionalMarkdownFiles) validateMarkdownFile(file, false)

const stableOverviewFiles = [
  ...markdownFiles,
  ...serviceModules.map((name) => path.join(repoRoot, name, 'README.md')),
  path.join(repoRoot, 'ai-admin-front', 'README.md'),
]

const portableDocumentationFiles = [
  ...markdownFiles,
  ...moduleReadmes,
  ...additionalMarkdownFiles,
  path.join(repoRoot, 'README.md'),
]

for (const file of portableDocumentationFiles) {
  if (!fs.existsSync(file)) continue
  const text = readText(file)
  if (/[A-Z]:[\\/]Users[\\/]/i.test(text)) {
    fail(file, '维护文档不得包含个人机器用户目录')
  }
  if (/[A-Z]:[\\/]work[\\/]/i.test(text)) {
    fail(file, '维护文档不得绑定个人机器工作目录；请使用仓库相对路径')
  }
  if (/app-secret:\s*\$\{[^}\n]*:change-me\}/i.test(text)) {
    fail(file, '密钥配置不得提供 change-me 弱默认值')
  }
}

const retiredTableNames = [
  'scan_project', 'scan_project_tool', 'ai_project_instance', 'tool_acl',
  'mcp_client', 'mcp_visibility', 'mcp_call_log', 'market_item',
  'domain_def', 'domain_assignment', 'file_info', 'chunk',
  'business_index', 'business_index_record', 'user_file_permission',
  'tool_definition', 'tool_retrieval_setting', 'semantic_doc', 'scan_module',
  'api_graph_node', 'api_graph_edge', 'api_graph_layout', 'guard_decision_log',
]

for (const file of stableOverviewFiles) {
  if (!fs.existsSync(file)) continue
  const text = readText(file)
  for (const tableName of retiredTableNames) {
    if (text.includes(`\`${tableName}\``)) {
      fail(file, `稳定入口仍引用退役/无前缀表名: ${tableName}`)
    }
  }
}

const retiredSourceSymbols = [
  'ApiGraphController', 'ScanProjectController', 'ToolAclController', 'ToolAclService',
  'TraceCenterController', 'McpAdminController', 'WorkflowReleaseValidationService',
  'LangGraph4jRuntimeAdapter',
]
for (const file of stableOverviewFiles) {
  if (!fs.existsSync(file)) continue
  const text = readText(file)
  if (text.includes('http://localhost:8080/api/workflows')) {
    fail(file, 'Workflow 公共示例必须使用 Control 默认端口 18603')
  }
  for (const symbol of retiredSourceSymbols) {
    if (text.includes(`\`${symbol}\``)) {
      fail(file, `稳定文档仍引用已重命名或不存在的源码符号: ${symbol}`)
    }
  }
}

if (failures.length > 0) {
  console.error(`documentation check failed (${failures.length})`)
  for (const failure of failures) console.error(`- ${failure}`)
  process.exit(1)
}

console.log(`documentation check passed (${markdownFiles.length} docs, ${additionalMarkdownFiles.length} additional Markdown files, ${serviceModules.length} service READMEs)`)
