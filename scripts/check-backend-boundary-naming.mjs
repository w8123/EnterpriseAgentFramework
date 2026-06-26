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

function assertFile(rel) {
  if (!fs.existsSync(path.join(root, rel))) {
    console.error(`[missing] ${rel}`)
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
assertIncludes('ai-agent-service/src/main/resources/application.yml', 'port: 18603')
assertIncludes('ai-skills-service/src/main/resources/application.yml', 'port: 18602')
assertIncludes('ai-model-service/src/main/resources/application.yml', 'port: 18601')
assertMatches('ai-admin-front/vite.config.ts', /['"]\/ai['"]:\s*\{[\s\S]*?target:\s*['"]http:\/\/localhost:18602['"]/, '/ai proxy -> 18602')
assertMatches('ai-admin-front/vite.config.ts', /['"]\/api['"]:\s*\{[\s\S]*?target:\s*['"]http:\/\/localhost:18603['"]/, '/api proxy -> 18603')
assertMatches('ai-admin-front/vite.config.ts', /\^\/model\/\(providers\|instances\|chat\)[\s\S]*?target:\s*['"]http:\/\/localhost:18601['"]/, '/model proxy -> 18601')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/controller', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/service', '.java')
assertNoFilesUnder('ai-agent-service/src/test/java/com/enterprise/ai/agent/controller', '.java')
assertNoPackageDeclarationUnder('ai-agent-service/src/test/java', 'com.enterprise.ai.agent.controller')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/scan', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/domain', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/semantic', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/tool/retrieval', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/tools/definition', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/auth', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/identity', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/mcp', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/a2a', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/acl', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/governance', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/market', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/context', '.java')
assertNoFilesUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/tool/governance', '.java')
assertNoFilesUnder('ai-agent-service/src/test/java/com/enterprise/ai/agent/scan', '.java')
assertNoFilesUnder('ai-agent-service/src/test/java/com/enterprise/ai/agent/tool/retrieval', '.java')
assertNoFilesUnder('ai-agent-service/src/test/java/com/enterprise/ai/agent/platform/auth', '.java')
assertNoFilesUnder('ai-agent-service/src/test/java/com/enterprise/ai/agent/identity', '.java')
assertNoFilesUnder('ai-agent-service/src/test/java/com/enterprise/ai/agent/mcp', '.java')
assertNoFilesUnder('ai-agent-service/src/test/java/com/enterprise/ai/agent/acl', '.java')
assertNoFilesUnder('ai-agent-service/src/test/java/com/enterprise/ai/agent/context', '.java')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host', 'com.enterprise.ai.agent.runtime.host')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/scan', 'com.enterprise.ai.agent.capability.catalog.scan')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/domain', 'com.enterprise.ai.agent.capability.catalog.domain')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/semantic', 'com.enterprise.ai.agent.capability.catalog.semantic')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/tool/retrieval', 'com.enterprise.ai.agent.capability.catalog.tool.retrieval')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/tool/definition', 'com.enterprise.ai.agent.capability.catalog.tool.definition')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/graph', 'com.enterprise.ai.agent.capability.catalog.graph')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/auth', 'com.enterprise.ai.agent.platform.control.auth')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/identity', 'com.enterprise.ai.agent.platform.control.identity')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/mcp', 'com.enterprise.ai.agent.platform.control.mcp')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/a2a', 'com.enterprise.ai.agent.platform.control.a2a')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/acl', 'com.enterprise.ai.agent.platform.control.acl')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/governance', 'com.enterprise.ai.agent.platform.control.governance')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/market', 'com.enterprise.ai.agent.platform.control.market')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/context', 'com.enterprise.ai.agent.platform.control.context')
assertPackagePrefixUnder('ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/tool/governance', 'com.enterprise.ai.agent.platform.control.tool.governance')

const keyApiPaths = [
  ['ai-model-service/src/main/java/com/enterprise/ai/model/controller/ModelController.java', '@RequestMapping("/model")'],
  ['ai-model-service/src/main/java/com/enterprise/ai/model/controller/OpenAIProxyController.java', '@RequestMapping("/model/openai-proxy")'],
  ['ai-model-service/src/main/java/com/enterprise/ai/model/instance/ModelInstanceController.java', '@RequestMapping("/model/instances")'],
  ['ai-skills-service/src/main/java/com/enterprise/ai/controller/KnowledgeController.java', '@RequestMapping("/knowledge")'],
  ['ai-skills-service/src/main/java/com/enterprise/ai/controller/RagController.java', '@RequestMapping("/rag")'],
  ['ai-skills-service/src/main/java/com/enterprise/ai/controller/RetrievalController.java', '@RequestMapping("/retrieval")'],
  ['ai-skills-service/src/main/java/com/enterprise/ai/controller/ScannerController.java', '@RequestMapping("/scanner")'],
  ['ai-skills-service/src/main/java/com/enterprise/ai/bizindex/controller/BizIndexController.java', '@RequestMapping("/biz-index")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/AiRegistryController.java', '@RequestMapping("/api/registry")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/ApiAssetController.java', '@RequestMapping("/api/api-assets")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/ApiGraphController.java', '@RequestMapping("/api/api-graph/projects/{projectId}")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/CapabilityKernelController.java', '@RequestMapping("/api/capabilities")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/CompositionController.java', '@RequestMapping({"/api/compositions", "/api/skills"})'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/DomainController.java', '@RequestMapping("/api/domains")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/ScanProjectController.java', '@RequestMapping("/api/scan-projects")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/SemanticDocController.java', '@RequestMapping("/api")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/SkillMiningController.java', '@RequestMapping({"/api/skill-mining", "/api/capability-mining"})'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/ToolController.java', '@RequestMapping("/api/tools")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/ToolRetrievalController.java', '@RequestMapping("/api/tool-retrieval")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/AgentEntryController.java', '@RequestMapping("/api/agents")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/AgentController.java', '@RequestMapping("/api/agent")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/AgentInteractionController.java', '@RequestMapping("/api/agent/interactions")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/AgentWorkflowBindingController.java', '@RequestMapping("/api/agents/{agentId}/workflow-bindings")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/CapabilityRuntimeController.java', '@RequestMapping("/api/runtime")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/ChatController.java', '@RequestMapping("/api/chat")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/RuntimeRegistryController.java', '@RequestMapping("/api/runtimes")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/ExecutableDebugSessionController.java', '@RequestMapping("/api/runtime/debug-sessions")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/TraceController.java', '@RequestMapping("/api/traces")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/RunOpsController.java', '@RequestMapping("/api/runops")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/WorkflowCredentialController.java', '@RequestMapping("/api/agent/workflow-credentials")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/WorkflowAiCodingCatalogController.java', '@RequestMapping("/api/workflows/ai-coding")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/WorkflowAiCodingController.java', '@RequestMapping("/api/workflows/{workflowId}/ai-coding")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/WorkflowDefinitionController.java', '@RequestMapping("/api/workflows")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/WorkflowStudioDraftController.java', '@RequestMapping("/api/workflows/studio")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/WorkflowStudioDebugController.java', '@RequestMapping("/api/workflows/studio")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/WorkflowVersionController.java', '@RequestMapping("/api/workflows/{workflowId}/versions")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/AgentGatewayController.java', '@RequestMapping("/api/v1/agents")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/A2aAdminController.java', '@RequestMapping("/api/admin/a2a")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/AiAssistController.java', '@RequestMapping("/api/ai-assist")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/AiCodingContextCandidateController.java', '@RequestMapping("/api/ai-coding/projects/{projectId}/context-candidates")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/AiCodingGatewayController.java', '@RequestMapping("/api/ai-coding/projects/{projectId}")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/AiCodingProjectAssistController.java', '@RequestMapping("/api/ai-coding/projects/{projectId}")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/ContextController.java', '@RequestMapping("/api/context")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/ContextMemoryCandidateController.java', '@RequestMapping("/api/context/memory/candidates")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/ContextRuntimeUserMappingController.java', '@RequestMapping("/api/context/runtime-user-mappings")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/EmbedChatController.java', '@RequestMapping("/api/embed")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/EmbedMemoryCandidateController.java', '@RequestMapping("/api/embed/sessions/{sessionId}/memory/candidates")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/McpAdminController.java', '@RequestMapping("/api/mcp")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/AiGatewayController.java', '@RequestMapping("/gateway")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/PlatformEmbedOpsController.java', '@RequestMapping("/api/platform/embed")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/PlatformAuthController.java', '@RequestMapping("/api/platform")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/SlotDictController.java', '@RequestMapping("/api/slot-dict")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/SlotExtractLogController.java', '@RequestMapping("/api")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/SlotExtractorController.java', '@RequestMapping("/api/slot-extractors")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/ToolAclController.java', '@RequestMapping("/api/tool-acl")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/mcp/McpServerEndpoint.java', '@RequestMapping("/mcp")'],
  ['ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/a2a/A2aServerEndpoint.java', '@RequestMapping("/a2a")']
]

for (const [rel, apiPath] of keyApiPaths) {
  assertIncludes(rel, apiPath)
}

const platformControlControllers = [
  'A2aAdminController.java',
  'AgentGatewayController.java',
  'AiAssistController.java',
  'AiCodingContextCandidateController.java',
  'AiCodingGatewayController.java',
  'AiCodingProjectAssistController.java',
  'AiGatewayController.java',
  'ContextController.java',
  'ContextMemoryCandidateController.java',
  'ContextRuntimeUserMappingController.java',
  'EmbedChatController.java',
  'EmbedMemoryCandidateController.java',
  'MarketController.java',
  'McpAdminController.java',
  'PlatformAuthController.java',
  'PlatformAuthProviderController.java',
  'PlatformBusinessUserController.java',
  'PlatformEmbedOpsController.java',
  'SlotDictController.java',
  'SlotExtractLogController.java',
  'SlotExtractorController.java',
  'ToolAclController.java',
  'TraceCenterController.java'
]

for (const fileName of platformControlControllers) {
  const rel = `ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/controller/${fileName}`
  assertFile(rel)
  assertIncludes(rel, 'package com.enterprise.ai.agent.platform.control.controller;')
}

const platformControlConfigs = [
  'ToolRateLimitProperties.java'
]

for (const fileName of platformControlConfigs) {
  const rel = `ai-agent-service/src/main/java/com/enterprise/ai/agent/platform/control/config/${fileName}`
  assertFile(rel)
  assertIncludes(rel, 'package com.enterprise.ai.agent.platform.control.config;')
}

const capabilityCatalogControllers = [
  'AiRegistryController.java',
  'ApiAssetController.java',
  'ApiGraphController.java',
  'CapabilityKernelController.java',
  'CompositionController.java',
  'DomainController.java',
  'ScanProjectController.java',
  'SemanticDocController.java',
  'SkillMiningController.java',
  'ToolController.java',
  'ToolRetrievalController.java'
]

for (const fileName of capabilityCatalogControllers) {
  const rel = `ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/controller/${fileName}`
  assertFile(rel)
  assertIncludes(rel, 'package com.enterprise.ai.agent.capability.catalog.controller;')
}

const capabilityCatalogClients = [
  'ScannerServiceClient.java'
]

for (const fileName of capabilityCatalogClients) {
  const rel = `ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/client/${fileName}`
  assertFile(rel)
  assertIncludes(rel, 'package com.enterprise.ai.agent.capability.catalog.client;')
}

const capabilityCatalogConfigs = [
  'DomainProperties.java',
  'ToolRetrievalProperties.java'
]

for (const fileName of capabilityCatalogConfigs) {
  const rel = `ai-agent-service/src/main/java/com/enterprise/ai/agent/capability/catalog/config/${fileName}`
  assertFile(rel)
  assertIncludes(rel, 'package com.enterprise.ai.agent.capability.catalog.config;')
}

const runtimeHostControllers = [
  'AgentController.java',
  'AgentEntryController.java',
  'AgentInteractionController.java',
  'AgentWorkflowBindingController.java',
  'CapabilityRuntimeController.java',
  'ChatController.java',
  'ExecutableDebugSessionController.java',
  'RunOpsController.java',
  'RuntimeRegistryController.java',
  'TraceController.java',
  'WorkflowCredentialController.java',
  'WorkflowAiCodingCatalogController.java',
  'WorkflowAiCodingController.java',
  'WorkflowAiCodingControllerAdvice.java',
  'WorkflowDefinitionController.java',
  'WorkflowStudioDebugController.java',
  'WorkflowStudioDraftController.java',
  'WorkflowVersionController.java'
]

for (const fileName of runtimeHostControllers) {
  const rel = `ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/controller/${fileName}`
  assertFile(rel)
  assertIncludes(rel, 'package com.enterprise.ai.agent.runtime.host.controller;')
}

const runtimeHostServices = [
  'AgentService.java',
  'ChatService.java',
  'IntentService.java',
  'LightweightToolCaller.java',
  'RouteEvaluationService.java'
]

for (const fileName of runtimeHostServices) {
  const rel = `ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/host/service/${fileName}`
  assertFile(rel)
  assertIncludes(rel, 'package com.enterprise.ai.agent.runtime.host.service;')
}

const allowedDiffScanFiles = new Set(['scripts/check-backend-boundary-naming.mjs'])
assertNoAddedText(/\bAI Skills Service\b/, 'do not add new mainline "AI Skills Service" wording', allowedDiffScanFiles)
assertNoAddedText(/\bAgent Studio\b/, 'do not add new wording that reverts Workflow Studio back to Agent Studio', allowedDiffScanFiles)

if (failures > 0) {
  console.error(`backend boundary naming check failed: ${failures} issue(s)`)
  process.exit(1)
}

console.log('backend boundary naming check passed')
