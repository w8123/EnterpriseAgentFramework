import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '..')
const docsRoot = path.join(repoRoot, 'docs')
const failures = []

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

function validateMarkdownFile(file, requireHeading = true) {
  const text = readText(file)
  const firstContent = text.split(/\r?\n/).find((line) => line.trim().length > 0)
  if (requireHeading && !firstContent?.startsWith('# ')) {
    fail(file, '首个非空行必须是一级标题')
  }

  const lines = text.split(/\r?\n/)
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
      if (!target || /^(?:https?:|mailto:|data:|#)/i.test(target)) continue

      const pathPart = target.split('#', 1)[0].split('?', 1)[0]
      if (!pathPart) continue
      const decoded = decodeLinkTarget(pathPart)
      const resolved = path.resolve(path.dirname(file), decoded)
      if (!fs.existsSync(resolved)) {
        fail(file, `本地链接不存在: ${target}`, index + 1)
      }
    }
  }
}

const markdownFiles = walkFiles(docsRoot, (file) => file.endsWith('.md'))
for (const file of markdownFiles) validateMarkdownFile(file)

const indexedSections = ['architecture', 'guides', 'operations', 'plans', 'reference', 'ai-memory']
for (const section of indexedSections) {
  const directory = path.join(docsRoot, section)
  const indexFile = path.join(directory, 'README.md')
  if (!fs.existsSync(indexFile)) {
    fail(indexFile, '目录缺少 README.md')
    continue
  }
  const indexText = decodeLinkTarget(readText(indexFile))
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith('.md') || entry.name === 'README.md') continue
    if (!indexText.includes(entry.name)) {
      fail(indexFile, `未索引同目录文档: ${entry.name}`)
    }
  }
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

for (const section of ['architecture', 'reference']) {
  const directory = path.join(docsRoot, section)
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith('.md')) continue
    if (/(?:implementation-plan|实施计划|实施方案|执行提示词)/i.test(entry.name)) {
      fail(path.join(directory, entry.name), '阶段性计划应放入 docs/plans/')
    }
  }
}

const stableOverviewFiles = [
  ...markdownFiles.filter((file) => !file.startsWith(path.join(docsRoot, 'plans') + path.sep)),
  ...serviceModules.map((name) => path.join(repoRoot, name, 'README.md')),
  path.join(repoRoot, 'ai-admin-front', 'README.md'),
]

const portableDocumentationFiles = [
  ...markdownFiles,
  ...moduleReadmes,
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

console.log(`documentation check passed (${markdownFiles.length} docs, ${serviceModules.length} service READMEs)`)
