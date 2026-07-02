import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
let failures = 0

const serviceRoots = {
  runtime: 'reachai-runtime-service/src/main/java',
  capability: 'reachai-capability-service/src/main/java'
}

const controlClientRoots = {
  runtime: 'reachai-control-service/src/main/java/com/enterprise/ai/control/client/runtime',
  capability: 'reachai-control-service/src/main/java/com/enterprise/ai/control/client/capability'
}

const controlServiceRoot = 'reachai-control-service/src/main/java'
const retiredControlGenericLegacyFile = 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/LegacyAgentCompatibilityProxyController.java'
const retiredPlatformControlProxyFile = 'reachai-control-service/src/main/java/com/enterprise/ai/control/compat/PlatformControlCompatibilityProxyController.java'
const retiredCapabilityProxyFile = 'reachai-capability-service/src/main/java/com/enterprise/ai/capability/compat/CapabilityLegacyCompatibilityProxyController.java'
const retiredLegacyAgentDeploymentFile = 'deploy/k8s/ai-agent-service.yml'
const retiredKnowledgeModelDeploymentFiles = [
  'deploy/k8s/ai-skills-service.yml',
  'deploy/k8s/ai-model-service.yml',
  'deploy/Dockerfile.skills-service',
  'deploy/Dockerfile.model-service'
]
const runtimeCompatRoot = 'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/compat'
const physicalServiceConfigFiles = [
  'reachai-control-service/src/main/resources/application.yml',
  'reachai-runtime-service/src/main/resources/application.yml',
  'reachai-capability-service/src/main/resources/application.yml'
]
const physicalServiceDeployFiles = [
  'deploy/k8s/reachai-control-service.yml',
  'deploy/k8s/reachai-runtime-service.yml',
  'deploy/k8s/reachai-capability-service.yml'
]
const frontendPublicApiClientRoots = [
  'ai-admin-front/src',
  'ai-admin-front/README.md',
  'ai-admin-front/.env.development',
  'ai-admin-front/env.d.ts'
]
const frontendLegacyControlClientPatterns = [
  {
    pattern: 'agentRequest',
    targetRoot: 'frontend /api client must be named controlRequest'
  },
  {
    pattern: 'VITE_AI_AGENT_SERVICE_URL',
    targetRoot: 'frontend Control base URL env must be VITE_REACHAI_CONTROL_SERVICE_URL'
  },
  {
    pattern: 'no longer proxied',
    targetRoot: 'retired-route toast must not be shipped'
  },
  {
    pattern: 'ai-agent-service',
    targetRoot: 'frontend must not describe /api as legacy ai-agent-service'
  }
]
const backendSourceRoots = [
  'reachai-control-service/src/main/java',
  'reachai-runtime-service/src/main/java',
  'reachai-capability-service/src/main/java'
]
const backendLegacyProxyNarrativePatterns = [
  {
    pattern: 'legacy execution paths proxy only during migration',
    targetRoot: 'runtime-owned implementation or explicit Control delegation'
  },
  {
    pattern: 'no longer proxied',
    targetRoot: 'real route implementation, not retired-route responses'
  },
  {
    pattern: 'migrate it into',
    targetRoot: 'real route implementation, not migration-time error text'
  },
  {
    pattern: 'LEGACY_AGENT_SERVICE_DISABLED',
    targetRoot: 'physical services must not expose legacy-agent disabled responses'
  }
]
const mainlineDocRoots = [
  'README.md',
  'AGENTS.md',
  'docs/README.md',
  'docs/architecture/backend-boundaries-and-naming.md',
  'docs/architecture/physical-services-and-startup.md',
  'docs/architecture/legacy-retirement.md',
  'docs/architecture',
  'docs/ai-memory/PROJECT-MEMORY.md',
  'docs/ai-memory/DECISIONS.md',
  'docs/ai-memory/WORKING-RULES.md'
]
const mainlineDocLegacyDisabledPatterns = [
  {
    pattern: 'must return `LEGACY_AGENT_SERVICE_DISABLED`',
    targetRoot: 'legacy disabled sentinel has been retired'
  },
  {
    pattern: 'should return `LEGACY_AGENT_SERVICE_DISABLED`',
    targetRoot: 'legacy disabled sentinel has been retired'
  },
  {
    pattern: 'return `LEGACY_AGENT_SERVICE_DISABLED` for unmigrated routes',
    targetRoot: 'legacy disabled sentinel has been retired'
  },
  {
    pattern: '直接返回 `LEGACY_AGENT_SERVICE_DISABLED`',
    targetRoot: 'legacy disabled sentinel has been retired'
  },
  {
    pattern: '仍返回 `LEGACY_AGENT_SERVICE_DISABLED`',
    targetRoot: 'legacy disabled sentinel has been retired'
  },
  {
    pattern: '必须返回明确 `LEGACY_AGENT_SERVICE_DISABLED`',
    targetRoot: 'legacy disabled sentinel has been retired'
  },
  {
    pattern: '鐩存帴杩斿洖 `LEGACY_AGENT_SERVICE_DISABLED`',
    targetRoot: 'legacy disabled sentinel has been retired'
  },
  {
    pattern: '粛杩斿洖 `LEGACY_AGENT_SERVICE_DISABLED`',
    targetRoot: 'legacy disabled sentinel has been retired'
  },
  {
    pattern: '蹇呴』杩斿洖鏄庣‘ `LEGACY_AGENT_SERVICE_DISABLED`',
    targetRoot: 'legacy disabled sentinel has been retired'
  }
]
const mainlineDocRetiredBridgeBacklogPatterns = [
  {
    pattern: 'generic legacy fallback',
    targetRoot: 'retired bridge backlog has been cleared'
  },
  {
    pattern: 'generic legacy 浠ｇ悊鍏滃簳',
    targetRoot: 'retired bridge backlog has been cleared'
  },
  {
    pattern: 'pending bridge',
    targetRoot: 'retired bridge backlog has been cleared'
  },
  {
    pattern: 'disabled public routes',
    targetRoot: 'legacy disabled route backlog has been retired'
  },
  {
    pattern: 'disabled 鐨勫叕寮€璺緞',
    targetRoot: 'legacy disabled route backlog has been retired'
  }
]
const mainlineDocDisabledRouteVocabularyPatterns = [
  {
    pattern: 'disabled route',
    targetRoot: 'retired public route vocabulary must use retired-route or explicit ownership'
  },
  {
    pattern: 'disabled response fallback',
    targetRoot: 'retired public route vocabulary must use retired-route or explicit ownership'
  },
  {
    pattern: 'disabled-route backlog',
    targetRoot: 'retired public route vocabulary must use retired-route or explicit ownership'
  },
  {
    pattern: '禁用响应兜底',
    targetRoot: 'retired public route vocabulary must use retired-route or explicit ownership'
  },
  {
    pattern: '逐路由把禁用响应替换',
    targetRoot: 'retired public route vocabulary must use retired-route or explicit ownership'
  }
]
const mainlineDocUnmigratedDisabledResponsePatterns = [
  {
    pattern: 'should return disabled responses',
    targetRoot: 'unmigrated public routes must be owned by a service or deleted'
  },
  {
    pattern: 'must return disabled responses',
    targetRoot: 'unmigrated public routes must be owned by a service or deleted'
  },
  {
    pattern: '应返回禁用响应',
    targetRoot: 'unmigrated public routes must be owned by a service or deleted'
  },
  {
    pattern: '必须返回禁用响应',
    targetRoot: 'unmigrated public routes must be owned by a service or deleted'
  },
  {
    pattern: '绂佺敤鍝嶅簲',
    targetRoot: 'unmigrated public routes must be owned by a service or deleted'
  }
]
const allowedRuntimeLegacyBridgeFiles = new Set()
const allowedRuntimeLegacyBridgeMethods = new Map()
const migratedRuntimeMethods = new Map([
  ['RuntimeAgentInteractionCompatibilityController.java', ['humanApprovals', 'submitHumanApproval', 'cancelHumanApproval']],
  ['RuntimeChatCompatibilityController.java', ['chat', 'chatStream', 'clearSession']],
  ['RuntimeCapabilityExecutionCompatibilityController.java', ['executeTool', 'executeComposition', 'resumeInteraction']],
  ['RuntimeDebugSessionCompatibilityController.java', ['create', 'get', 'submit', 'cancel']],
  ['RuntimePublicCompatibilityController.java', ['executeAgent', 'executeAgentDetailed', 'runOpsReplay']],
  ['RuntimeRegistryCompatibilityController.java', ['dispatchEmbedded']],
  ['RuntimeWorkflowAiCodingCompatibilityController.java', [
    'create',
    'context',
    'validate',
    'patch',
    'run',
    'versions',
    'publish',
    'runs',
    'runDetail',
    'pageAssistantCatalog',
    'validatePageAssistant',
    'smokeTestPageAssistant'
  ]],
  ['RuntimeWorkflowCompatibilityController.java', [
    'debugWorkflowNode',
    'debugWorkflowRun',
    'generateWorkflowStudioDraft',
    'editWorkflowStudioDraft'
  ]]
])
const migratedCapabilityRoutes = [
  { method: 'GET', path: '/api/capabilities' },
  { method: 'POST', path: '/api/capabilities' },
  { method: 'GET', path: '/api/capabilities/{code}/tools' },
  { method: 'POST', path: '/api/capabilities/{code}/tools' },
  { method: 'GET', path: '/api/capabilities/{code}/compositions' },
  { method: 'POST', path: '/api/capabilities/{code}/compositions' },
  { method: 'GET', path: '/api/capabilities/{code}/interactions' },
  { method: 'POST', path: '/api/capabilities/{code}/interactions' },
  { method: 'GET', path: '/api/domains' },
  { method: 'POST', path: '/api/domains' },
  { method: 'PUT', path: '/api/domains/{id}' },
  { method: 'DELETE', path: '/api/domains/{id}' },
  { method: 'GET', path: '/api/domains/{code}/assignments' },
  { method: 'POST', path: '/api/domains/{code}/assignments' },
  { method: 'DELETE', path: '/api/domains/assignments/{id}' },
  { method: 'POST', path: '/api/domains/classify' },
  { method: 'GET', path: '/api/domains/coverage' },
  { method: 'GET', path: '/api/api-assets' },
  { method: 'POST', path: '/api/registry/projects/register' },
  { method: 'GET', path: '/api/registry/projects/{projectCode}/capability-description-settings' },
  { method: 'GET', path: '/api/registry/projects/{projectCode}/instances' },
  { method: 'POST', path: '/api/registry/projects/{projectCode}/instances/heartbeat' },
  { method: 'POST', path: '/api/registry/projects/{projectCode}/instances/offline' },
  { method: 'POST', path: '/api/registry/projects/{projectCode}/instances/purge-offline' },
  { method: 'POST', path: '/api/registry/projects/{projectCode}/instances/status' },
  { method: 'POST', path: '/api/registry/projects/{projectCode}/instances/governance-policy' },
  { method: 'POST', path: '/api/registry/projects/{projectCode}/capabilities/sync' },
  { method: 'POST', path: '/api/registry/projects/{projectCode}/capabilities/diff' },
  { method: 'POST', path: '/api/registry/projects/{projectCode}/capabilities/apply' },
  { method: 'GET', path: '/api/registry/projects/{projectCode}/capability-snapshots' },
  { method: 'GET', path: '/api/registry/capability-snapshots/{snapshotId}/diff-items' },
  { method: 'POST', path: '/api/registry/capability-diff-items/{diffItemId}/review' },
  { method: 'GET', path: '/api/tools' },
  { method: 'POST', path: '/api/tools' },
  { method: 'GET', path: '/api/tools/{name}' },
  { method: 'PUT', path: '/api/tools/{name}' },
  { method: 'DELETE', path: '/api/tools/{name}' },
  { method: 'PUT', path: '/api/tools/{name}/toggle' },
  { method: 'POST', path: '/api/tools/{name}/test' },
  { method: 'POST', path: '/api/tool-retrieval/search' },
  { method: 'POST', path: '/api/tool-retrieval/rebuild' },
  { method: 'GET', path: '/api/tool-retrieval/rebuild/status' },
  { method: 'GET', path: '/api/tool-retrieval/health' },
  { method: 'GET', path: '/api/skill-mining/precheck' },
  { method: 'POST', path: '/api/skill-mining/drafts/generate' },
  { method: 'GET', path: '/api/skill-mining/drafts' },
  { method: 'POST', path: '/api/skill-mining/drafts/{id}/status' },
  { method: 'POST', path: '/api/skill-mining/drafts/{id}/publish' },
  { method: 'POST', path: '/api/skill-mining/drafts/from-trace' },
  { method: 'POST', path: '/api/skill-mining/drafts/from-canvas' },
  { method: 'POST', path: '/api/skill-mining/demo-traces/generate' },
  { method: 'POST', path: '/api/skill-mining/demo-traces/clear' },
  { method: 'GET', path: '/api/capability-mining/precheck' },
  { method: 'POST', path: '/api/capability-mining/drafts/generate' },
  { method: 'GET', path: '/api/capability-mining/drafts' },
  { method: 'POST', path: '/api/capability-mining/drafts/{id}/status' },
  { method: 'POST', path: '/api/capability-mining/drafts/{id}/publish' },
  { method: 'POST', path: '/api/capability-mining/drafts/from-trace' },
  { method: 'POST', path: '/api/capability-mining/drafts/from-canvas' },
  { method: 'POST', path: '/api/capability-mining/demo-traces/generate' },
  { method: 'POST', path: '/api/capability-mining/demo-traces/clear' },
  { method: 'GET', path: '/api/compositions' },
  { method: 'POST', path: '/api/compositions' },
  { method: 'GET', path: '/api/compositions/{name}' },
  { method: 'PUT', path: '/api/compositions/{name}' },
  { method: 'DELETE', path: '/api/compositions/{name}' },
  { method: 'PUT', path: '/api/compositions/{name}/toggle' },
  { method: 'POST', path: '/api/compositions/{name}/test' },
  { method: 'POST', path: '/api/compositions/{name}/test/resume' },
  { method: 'GET', path: '/api/compositions/{name}/metrics' },
  { method: 'GET', path: '/api/compositions/pending-interactions/admin-test' },
  { method: 'DELETE', path: '/api/compositions/pending-interactions/admin-test/{interactionId}' },
  { method: 'POST', path: '/api/compositions/pending-interactions/admin-test/cancel-all' },
  { method: 'GET', path: '/api/scan-projects' },
  { method: 'POST', path: '/api/scan-projects' },
  { method: 'GET', path: '/api/scan-projects/{id}' },
  { method: 'PUT', path: '/api/scan-projects/{id}' },
  { method: 'PATCH', path: '/api/scan-projects/{id}/auth-settings' },
  { method: 'PATCH', path: '/api/scan-projects/{id}/registry-credential' },
  { method: 'POST', path: '/api/scan-projects/{id}/sdk-access-check' },
  { method: 'PATCH', path: '/api/scan-projects/{id}/scan-settings' },
  { method: 'DELETE', path: '/api/scan-projects/{id}' },
  { method: 'POST', path: '/api/scan-projects/{id}/scan' },
  { method: 'POST', path: '/api/scan-projects/{id}/rescan' },
  { method: 'POST', path: '/api/scan-projects/{id}/sensitive-data/scan' },
  { method: 'GET', path: '/api/scan-projects/{id}/sensitive-data/status' },
  { method: 'GET', path: '/api/scan-projects/{id}/tools' },
  { method: 'POST', path: '/api/scan-projects/{projectId}/tools/reconcile' },
  { method: 'GET', path: '/api/scan-projects/{projectId}/scan-tools/{scanToolId}' },
  { method: 'POST', path: '/api/scan-projects/{projectId}/scan-tools/{scanToolId}/rescan-from-source' },
  { method: 'PUT', path: '/api/scan-projects/{projectId}/scan-tools/{scanToolId}' },
  { method: 'PUT', path: '/api/scan-projects/{projectId}/scan-tools/{scanToolId}/toggle' },
  { method: 'POST', path: '/api/scan-projects/{projectId}/scan-tools/{scanToolId}/test' },
  { method: 'POST', path: '/api/scan-projects/{projectId}/scan-tools/{scanToolId}/promote-to-tool' },
  { method: 'POST', path: '/api/scan-projects/{projectId}/scan-tools/{scanToolId}/unpromote-from-global' },
  { method: 'POST', path: '/api/scan-projects/{projectId}/scan-tools/{scanToolId}/push-to-global-tool' },
  { method: 'POST', path: '/api/scan-projects/{projectId}/scan-tools/promote-by-module' },
  { method: 'GET', path: '/api/scan-projects/{id}/diff-summary' },
  { method: 'GET', path: '/api/scan-projects/{id}/operation-blockers' },
  { method: 'GET', path: '/api/semantic-docs' },
  { method: 'PUT', path: '/api/semantic-docs/{id}' },
  { method: 'GET', path: '/api/scan-projects/{id}/semantic-docs' },
  { method: 'POST', path: '/api/scan-projects/{id}/semantic/generate' },
  { method: 'GET', path: '/api/scan-projects/{id}/semantic/status' },
  { method: 'POST', path: '/api/scan-projects/{id}/semantic/generate-project' },
  { method: 'GET', path: '/api/scan-projects/{id}/modules' },
  { method: 'PUT', path: '/api/scan-modules/{id}' },
  { method: 'POST', path: '/api/scan-modules/{id}/semantic/generate' },
  { method: 'POST', path: '/api/scan-modules/merge' },
  { method: 'POST', path: '/api/tools/{name}/semantic/generate' },
  { method: 'POST', path: '/api/scan-projects/{projectId}/scan-tools/{scanToolId}/semantic/generate' },
  { method: 'GET', path: '/api/api-graph/projects/{projectId}/snapshot' },
  { method: 'GET', path: '/api/api-graph/projects/{projectId}/candidates' },
  { method: 'POST', path: '/api/api-graph/projects/{projectId}/candidates/{edgeId}/confirm' },
  { method: 'POST', path: '/api/api-graph/projects/{projectId}/candidates/{edgeId}/reject' },
  { method: 'GET', path: '/api/api-graph/projects/{projectId}/tools/{toolName}/param-hints' },
  { method: 'POST', path: '/api/api-graph/projects/{projectId}/infer' },
  { method: 'POST', path: '/api/api-graph/projects/{projectId}/infer/request-response' },
  { method: 'POST', path: '/api/api-graph/projects/{projectId}/rebuild' },
  { method: 'POST', path: '/api/api-graph/projects/{projectId}/regenerate' },
  { method: 'POST', path: '/api/api-graph/projects/{projectId}/edges' },
  { method: 'DELETE', path: '/api/api-graph/projects/{projectId}/edges/{edgeId}' },
  { method: 'PUT', path: '/api/api-graph/projects/{projectId}/layout' }
]

function exists(rel) {
  return fs.existsSync(path.join(root, rel))
}

function read(rel) {
  return fs.readFileSync(path.join(root, rel), 'utf8')
}

function walk(rel) {
  const abs = path.join(root, rel)
  if (!fs.existsSync(abs)) {
    return []
  }
  return fs.readdirSync(abs, { withFileTypes: true }).flatMap((entry) => {
    const child = path.join(rel, entry.name).replaceAll('\\', '/')
    if (entry.isDirectory()) {
      return walk(child)
    }
    return entry.isFile() && entry.name.endsWith('.java') ? [child] : []
  })
}

function walkFiles(rel) {
  const abs = path.join(root, rel)
  if (!fs.existsSync(abs)) {
    return []
  }
  const stat = fs.statSync(abs)
  if (stat.isFile()) {
    return [rel.replaceAll('\\', '/')]
  }
  if (!stat.isDirectory()) {
    return []
  }
  return fs.readdirSync(abs, { withFileTypes: true }).flatMap((entry) => {
    if (entry.name === 'node_modules' || entry.name === 'dist') {
      return []
    }
    const child = path.join(rel, entry.name).replaceAll('\\', '/')
    if (entry.isDirectory()) {
      return walkFiles(child)
    }
    return entry.isFile() ? [child] : []
  })
}

function normalizePath(value) {
  if (!value || value === '""') {
    return ''
  }
  const trimmed = value.trim().replace(/^["']|["']$/g, '')
  if (!trimmed) {
    return ''
  }
  return trimmed.startsWith('/') ? trimmed : `/${trimmed}`
}

function joinPaths(left, right) {
  const a = normalizePath(left)
  const b = normalizePath(right)
  if (!a) {
    return b || '/'
  }
  if (!b || b === '/') {
    return a
  }
  return `${a.replace(/\/+$/, '')}/${b.replace(/^\/+/, '')}`
}

function annotationText(lines, index) {
  let text = lines[index].trim()
  while (text.includes('(') && !text.includes(')') && index + 1 < lines.length) {
    index++
    text += ` ${lines[index].trim()}`
  }
  return text
}

function firstStringArgument(text) {
  return pathArguments(text)[0] ?? ''
}

function stringArguments(text) {
  return [...text.matchAll(/"([^"]*)"/g)].map((match) => match[1])
}

function pathArguments(text) {
  const positionalArray = text.match(/\(\s*\{\s*((?:"[^"]*"\s*,?\s*)+)\}/)
  if (positionalArray) {
    return stringArguments(positionalArray[1])
  }
  const positionalSingle = text.match(/\(\s*"([^"]*)"/)
  if (positionalSingle) {
    return [positionalSingle[1]]
  }
  const namedArray = text.match(/(?:value|path)\s*=\s*\{\s*((?:"[^"]*"\s*,?\s*)+)\}/)
  if (namedArray) {
    return stringArguments(namedArray[1])
  }
  const namedSingle = text.match(/(?:value|path)\s*=\s*"([^"]*)"/)
  if (namedSingle) {
    return [namedSingle[1]]
  }
  return ['']
}

function requestMethods(text, defaultMethod) {
  if (defaultMethod) {
    return [defaultMethod]
  }
  const matches = [...text.matchAll(/RequestMethod\.([A-Z]+)/g)].map((match) => match[1])
  return matches.length === 0 ? ['ANY'] : [...new Set(matches)]
}

function routeFromAnnotation(text) {
  if (text.startsWith('@GetMapping')) {
    return { methods: ['GET'], paths: pathArguments(text) }
  }
  if (text.startsWith('@PostMapping')) {
    return { methods: ['POST'], paths: pathArguments(text) }
  }
  if (text.startsWith('@PutMapping')) {
    return { methods: ['PUT'], paths: pathArguments(text) }
  }
  if (text.startsWith('@DeleteMapping')) {
    return { methods: ['DELETE'], paths: pathArguments(text) }
  }
  if (text.startsWith('@PatchMapping')) {
    return { methods: ['PATCH'], paths: pathArguments(text) }
  }
  if (text.startsWith('@RequestMapping')) {
    return { methods: requestMethods(text), paths: pathArguments(text) }
  }
  return null
}

function collectRoutes(files) {
  const routes = []
  for (const file of files) {
    const lines = read(file).split(/\r?\n/)
    let classPrefixes = ['']
    for (let i = 0; i < lines.length; i++) {
      const text = annotationText(lines, i)
      if (text.startsWith('@RequestMapping')) {
        const nextSignificant = lines.slice(i + 1).find((line) => line.trim() && !line.trim().startsWith('@'))
        if (nextSignificant && /\b(class|interface|record)\b/.test(nextSignificant)) {
          classPrefixes = pathArguments(text)
          continue
        }
      }
      if (!/^@(Get|Post|Put|Delete|Patch|Request)Mapping/.test(text)) {
        continue
      }
      const route = routeFromAnnotation(text)
      if (!route) {
        continue
      }
      for (const path of route.paths) {
        for (const classPrefix of classPrefixes) {
          const fullPath = joinPaths(classPrefix, path)
          for (const method of route.methods) {
            routes.push({ method, path: fullPath, file })
          }
        }
      }
    }
  }
  return routes
}

function routeKey(route) {
  return `${route.method} ${route.path}`
}

function isPublicRoute(route) {
  return route.path.startsWith('/api/') ||
      route.path === '/api' ||
      route.path.startsWith('/embed/') ||
      route.path === '/embed' ||
      route.path.startsWith('/gateway/') ||
      route.path === '/gateway' ||
      route.path.startsWith('/mcp/') ||
      route.path === '/mcp' ||
      route.path.startsWith('/a2a/') ||
      route.path === '/a2a'
}

function isControlClientFile(file) {
  return file.includes('/com/enterprise/ai/control/client/')
}

function methodMatches(controlRoute, candidateRoute) {
  return controlRoute.method === 'ANY' || controlRoute.method === candidateRoute.method
}

function wildcardBase(pathPattern) {
  const marker = '/{*path}'
  if (!pathPattern.endsWith(marker)) {
    return null
  }
  return pathPattern.slice(0, -marker.length)
}

function pathMatches(controlRoute, candidateRoute) {
  if (controlRoute.path === candidateRoute.path) {
    return true
  }
  const base = wildcardBase(controlRoute.path)
  if (base === null) {
    return false
  }
  return candidateRoute.path === base || candidateRoute.path.startsWith(`${base}/`)
}

function routeCoveredByControl(controlRoutes, route) {
  return controlRoutes.some((controlRoute) => methodMatches(controlRoute, route) && pathMatches(controlRoute, route))
}

function isCapabilityOwnedPublicRoute(route) {
  const path = route.path
  const capabilityRegistryRoute = path.startsWith('/api/registry/') &&
      !/^\/api\/registry\/projects\/[^/]+\/agent-graphs(\/|$)/.test(path) &&
      !/^\/api\/registry\/projects\/[^/]+\/pages(\/|$)/.test(path)
  return capabilityRegistryRoute ||
      path === '/api/capabilities' ||
      path.startsWith('/api/capabilities/') ||
      path === '/api/tools' ||
      path.startsWith('/api/tools/') ||
      path === '/api/compositions' ||
      path.startsWith('/api/compositions/') ||
      path === '/api/api-assets' ||
      path.startsWith('/api/api-assets/') ||
      path === '/api/api-graph' ||
      path.startsWith('/api/api-graph/') ||
      path === '/api/tool-retrieval' ||
      path.startsWith('/api/tool-retrieval/') ||
      path === '/api/skill-mining' ||
      path.startsWith('/api/skill-mining/') ||
      path === '/api/capability-mining' ||
      path.startsWith('/api/capability-mining/') ||
      path === '/api/scan-projects' ||
      path.startsWith('/api/scan-projects/') ||
      path === '/api/scan-modules' ||
      path.startsWith('/api/scan-modules/') ||
      path === '/api/semantic-docs' ||
      path.startsWith('/api/semantic-docs/') ||
      path === '/api/domains' ||
      path.startsWith('/api/domains/')
}

function isPlatformControlOwnedPublicRoute(route) {
  const path = route.path
  return path === '/api/platform' ||
      path.startsWith('/api/platform/') ||
      path === '/api/embed' ||
      path.startsWith('/api/embed/') ||
      path === '/embed' ||
      path.startsWith('/embed/') ||
      path === '/api/mcp' ||
      path.startsWith('/api/mcp/') ||
      path === '/mcp' ||
      path.startsWith('/mcp/') ||
      path === '/api/admin/a2a' ||
      path.startsWith('/api/admin/a2a/') ||
      path === '/a2a' ||
      path.startsWith('/a2a/') ||
      path === '/gateway' ||
      path.startsWith('/gateway/') ||
      path === '/api/ai-assist' ||
      path.startsWith('/api/ai-assist/') ||
      path === '/api/ai-coding' ||
      path.startsWith('/api/ai-coding/') ||
      path === '/api/context' ||
      path.startsWith('/api/context/') ||
      path === '/api/market' ||
      path.startsWith('/api/market/') ||
      path === '/api/tool-acl' ||
      path.startsWith('/api/tool-acl/') ||
      path === '/api/trace-center' ||
      path.startsWith('/api/trace-center/') ||
      path === '/api/slot-dict' ||
      path.startsWith('/api/slot-dict/') ||
      path === '/api/slot-extractors' ||
      path.startsWith('/api/slot-extractors/') ||
      path === '/api/slot-extract-logs' ||
      path.startsWith('/api/slot-extract-logs/') ||
      path === '/api/slot-bindings' ||
      path.startsWith('/api/slot-bindings/') ||
      path === '/api/agent/evals' ||
      path.startsWith('/api/agent/evals/') ||
      path === '/api/v1/agents' ||
      path.startsWith('/api/v1/agents/') ||
      /^\/api\/registry\/projects\/[^/]+\/pages(\/|$)/.test(path)
}

function isCapabilityLegacyProxyFile(file) {
  return file.endsWith('/com/enterprise/ai/capability/compat/CapabilityLegacyCompatibilityProxyController.java')
}

function hasRuntimeLegacyBridgeMarker(file) {
  const text = read(file)
  return text.includes('legacyProxyGateway.proxy(') ||
      text.includes('X-ReachAI-Runtime-Legacy-Proxy') ||
      text.includes('services.legacy-agent-service.url') ||
      text.includes('LEGACY_AGENT_SERVICE_URL')
}

function usesRuntimeLegacyProxyGateway(file) {
  const text = read(file)
  return text.includes('RuntimeLegacyProxyGateway') ||
      text.includes('legacyProxyGateway')
}

function fileName(file) {
  return file.split('/').pop()
}

function methodBody(text, methodName) {
  const signature = new RegExp(`\\b${methodName}\\s*\\(`)
  const match = signature.exec(text)
  if (!match) {
    return ''
  }
  const open = text.indexOf('{', match.index)
  if (open < 0) {
    return ''
  }
  let depth = 0
  for (let i = open; i < text.length; i++) {
    const ch = text[i]
    if (ch === '{') {
      depth++
    } else if (ch === '}') {
      depth--
      if (depth === 0) {
        return text.slice(open, i + 1)
      }
    }
  }
  return text.slice(open)
}

function methodUsesRuntimeLegacyBridge(file, methodName) {
  const body = methodBody(read(file), methodName)
  return body.includes('legacyProxyGateway.proxy(') ||
      body.includes('X-ReachAI-Runtime-Legacy-Proxy') ||
      body.includes('services.legacy-agent-service.url') ||
      body.includes('LEGACY_AGENT_SERVICE_URL')
}

function methodBodies(text) {
  const methods = []
  const declaration = /(?:^|\n)\s*(?:public|private|protected)?\s*(?:static\s+)?[\w<>\[\],.?]+\s+(\w+)\s*\([^;{}]*\)\s*\{/g
  let match
  while ((match = declaration.exec(text)) !== null) {
    const name = match[1]
    const open = text.indexOf('{', match.index)
    if (open < 0) {
      continue
    }
    let depth = 0
    for (let i = open; i < text.length; i++) {
      const ch = text[i]
      if (ch === '{') {
        depth++
      } else if (ch === '}') {
        depth--
        if (depth === 0) {
          methods.push({ name, body: text.slice(open, i + 1) })
          declaration.lastIndex = i + 1
          break
        }
      }
    }
  }
  return methods
}

function runtimeLegacyBridgeMethods(file) {
  return methodBodies(read(file))
    .filter((method) => method.body.includes('legacyProxyGateway.proxy(') ||
        method.body.includes('X-ReachAI-Runtime-Legacy-Proxy') ||
        method.body.includes('services.legacy-agent-service.url') ||
        method.body.includes('LEGACY_AGENT_SERVICE_URL'))
}

function report(title, issues) {
  if (issues.length === 0) {
    return
  }
  failures += issues.length
  console.error(`\n${title}`)
  for (const issue of issues) {
    console.error(`  - ${issue.key}`)
    if (issue.clientFile) {
      console.error(`    client: ${issue.clientFile}`)
    }
    if (issue.sourceFile) {
      console.error(`    source: ${issue.sourceFile}`)
    }
    console.error(`    target: ${issue.targetRoot}`)
  }
}

for (const [service, clientRoot] of Object.entries(controlClientRoots)) {
  if (!exists(clientRoot)) {
    continue
  }
  const targetRoot = serviceRoots[service]
  const clientRoutes = collectRoutes(walk(clientRoot))
  const targetRoutes = collectRoutes(walk(targetRoot))
  const issues = clientRoutes
    .filter((route) => !routeCoveredByControl(targetRoutes, route))
    .map((route) => ({
      key: routeKey(route),
      clientFile: route.file,
      targetRoot
    }))
  report(`control ${service === 'runtime' ? 'RuntimeProxyClient' : 'CapabilityProxyClient'} route must exist in ${service === 'runtime' ? 'reachai-runtime-service' : 'reachai-capability-service'}`, issues)
}

const controlPublicRoutes = exists(controlServiceRoot)
  ? collectRoutes(walk(controlServiceRoot).filter((file) => !isControlClientFile(file)))
  : []

if (exists(controlServiceRoot)) {
  if (exists(retiredControlGenericLegacyFile)) {
    report('control generic legacy catch-all must stay retired', [{
      key: retiredControlGenericLegacyFile,
      sourceFile: retiredControlGenericLegacyFile,
      targetRoot: 'explicit Control route ownership'
    }])
  }

  if (exists(retiredPlatformControlProxyFile)) {
    report('platform-control retired-route proxy must stay deleted', [{
      key: retiredPlatformControlProxyFile,
      sourceFile: retiredPlatformControlProxyFile,
      targetRoot: 'explicit Control route ownership'
    }])
  }

  for (const [service, clientRoot] of Object.entries(controlClientRoots)) {
    if (!exists(clientRoot)) {
      continue
    }
    const clientRoutes = collectRoutes(walk(clientRoot)).filter(isPublicRoute)
    const issues = clientRoutes
      .filter((route) => !routeCoveredByControl(controlPublicRoutes, route))
      .map((route) => ({
        key: routeKey(route),
        clientFile: route.file,
        targetRoot: controlServiceRoot
      }))
    report(`control ${service === 'runtime' ? 'RuntimeProxyClient' : 'CapabilityProxyClient'} public route must be exposed by reachai-control-service`, issues)
  }
}

if (exists(retiredLegacyAgentDeploymentFile)) {
  report('default deployment must not include legacy ai-agent-service', [{
    key: retiredLegacyAgentDeploymentFile,
    sourceFile: retiredLegacyAgentDeploymentFile,
    targetRoot: 'five-service physical topology'
  }])
}

const retiredKnowledgeModelDeploymentIssues = retiredKnowledgeModelDeploymentFiles
  .filter(exists)
  .map((file) => ({
    key: file,
    sourceFile: file,
    targetRoot: 'reachai-knowledge-service and reachai-model-service deployment artifacts'
  }))
report('default deployment must not include legacy knowledge/model service artifacts', retiredKnowledgeModelDeploymentIssues)

const legacyDefaultIssues = physicalServiceConfigFiles
  .filter(exists)
  .filter((file) => read(file).includes('LEGACY_AGENT_SERVICE_URL:http://localhost:18606') ||
      read(file).includes('LEGACY_AGENT_SERVICE_URL:http://ai-agent-service:18606'))
  .map((file) => ({
    key: file,
    sourceFile: file,
    targetRoot: 'legacy agent URL must be opt-in, not a physical-service default'
  }))
report('physical service config must not default to ai-agent-service:18606', legacyDefaultIssues)

const legacyDeployIssues = physicalServiceDeployFiles
  .filter(exists)
  .filter((file) => read(file).includes('LEGACY_AGENT_SERVICE_URL'))
  .map((file) => ({
    key: file,
    sourceFile: file,
    targetRoot: 'default deployment without legacy agent dependency'
  }))
report('physical service deployment must not inject LEGACY_AGENT_SERVICE_URL by default', legacyDeployIssues)

const frontendLegacyControlClientIssues = frontendPublicApiClientRoots
  .flatMap(walkFiles)
  .flatMap((file) => {
    const text = read(file)
    return frontendLegacyControlClientPatterns
      .filter(({ pattern }) => text.includes(pattern))
      .map(({ pattern, targetRoot }) => ({
        key: `${file}: ${pattern}`,
        sourceFile: file,
        targetRoot
      }))
  })
report('frontend public API client must use Control service naming', frontendLegacyControlClientIssues)

const backendLegacyProxyNarrativeIssues = backendSourceRoots
  .flatMap(walkFiles)
  .flatMap((file) => {
    const text = read(file)
    return backendLegacyProxyNarrativePatterns
      .filter(({ pattern }) => text.includes(pattern))
      .map(({ pattern, targetRoot }) => ({
        key: `${file}: ${pattern}`,
        sourceFile: file,
        targetRoot
      }))
  })
report('backend source must not describe physical services as legacy agent proxy', backendLegacyProxyNarrativeIssues)

const mainlineDocLegacyDisabledIssues = mainlineDocRoots
  .flatMap(walkFiles)
  .flatMap((file) => {
    const text = read(file)
    return mainlineDocLegacyDisabledPatterns
      .filter(({ pattern }) => text.includes(pattern))
      .map(({ pattern, targetRoot }) => ({
        key: `${file}: ${pattern}`,
        sourceFile: file,
        targetRoot
      }))
  })
report('mainline docs must not present legacy disabled responses as current route behavior', mainlineDocLegacyDisabledIssues)

const mainlineDocRetiredBridgeBacklogIssues = mainlineDocRoots
  .flatMap(walkFiles)
  .flatMap((file) => {
    const text = read(file)
    return mainlineDocRetiredBridgeBacklogPatterns
      .filter(({ pattern }) => text.includes(pattern))
      .map(({ pattern, targetRoot }) => ({
        key: `${file}: ${pattern}`,
        sourceFile: file,
        targetRoot
      }))
  })
report('mainline docs must not describe retired bridge backlogs as current work', mainlineDocRetiredBridgeBacklogIssues)

const mainlineDocDisabledRouteVocabularyIssues = mainlineDocRoots
  .flatMap(walkFiles)
  .flatMap((file) => {
    const text = read(file)
    return mainlineDocDisabledRouteVocabularyPatterns
      .filter(({ pattern }) => text.includes(pattern))
      .map(({ pattern, targetRoot }) => ({
        key: `${file}: ${pattern}`,
        sourceFile: file,
        targetRoot
      }))
  })
report('mainline docs must not use disabled route vocabulary for retired public routes', mainlineDocDisabledRouteVocabularyIssues)

const mainlineDocUnmigratedDisabledResponseIssues = mainlineDocRoots
  .flatMap(walkFiles)
  .flatMap((file) => {
    const text = read(file)
    return mainlineDocUnmigratedDisabledResponsePatterns
      .filter(({ pattern }) => text.includes(pattern))
      .map(({ pattern, targetRoot }) => ({
        key: `${file}: ${pattern}`,
        sourceFile: file,
        targetRoot
      }))
  })
report('mainline docs must not present unmigrated public routes as disabled responses', mainlineDocUnmigratedDisabledResponseIssues)

const capabilityRoutes = exists(serviceRoots.capability)
  ? collectRoutes(walk(serviceRoots.capability))
  : []
if (exists(retiredCapabilityProxyFile)) {
  report('capability retired-route proxy must stay deleted', [{
    key: retiredCapabilityProxyFile,
    sourceFile: retiredCapabilityProxyFile,
    targetRoot: 'migrated Capability route implementation'
  }])
}
const concreteCapabilityRoutes = capabilityRoutes.filter((route) => !isCapabilityLegacyProxyFile(route.file))
const migratedCapabilityIssues = migratedCapabilityRoutes
  .filter((route) => !routeCoveredByControl(concreteCapabilityRoutes, route))
  .map((route) => ({
    key: routeKey(route),
    targetRoot: 'migrated Capability route implementation'
  }))
report('migrated Capability route must have a real implementation', migratedCapabilityIssues)

const aiAgentRoot = 'ai-agent-service/src/main/java'
if (exists(aiAgentRoot)) {
  const aiAgentPublicRoutes = collectRoutes(walk(aiAgentRoot)).filter(isPublicRoute)
  const issues = aiAgentPublicRoutes
    .filter((route) => !routeCoveredByControl(controlPublicRoutes, route))
    .map((route) => ({
      key: routeKey(route),
      sourceFile: route.file,
      targetRoot: controlServiceRoot
    }))
  report('ai-agent-service public route must be owned by reachai-control-service', issues)

  const capabilityIssues = aiAgentPublicRoutes
    .filter(isCapabilityOwnedPublicRoute)
    .filter((route) => !routeCoveredByControl(capabilityRoutes, route))
    .map((route) => ({
      key: routeKey(route),
      sourceFile: route.file,
      targetRoot: serviceRoots.capability
    }))
  report('capability public route must be owned by reachai-capability-service', capabilityIssues)

  const platformIssues = aiAgentPublicRoutes
    .filter(isPlatformControlOwnedPublicRoute)
    .filter((route) => !routeCoveredByControl(controlPublicRoutes, route))
    .map((route) => ({
      key: routeKey(route),
      sourceFile: route.file,
      targetRoot: controlServiceRoot
    }))
  report('platform-control public route must be explicitly owned by reachai-control-service', platformIssues)
}

if (exists(runtimeCompatRoot)) {
  const runtimeLegacyProxyGatewayIssues = walk(runtimeCompatRoot)
    .filter(usesRuntimeLegacyProxyGateway)
    .map((file) => ({
      key: file,
      sourceFile: file,
      targetRoot: 'retired Runtime legacy proxy gateway'
    }))
  report('runtime legacy proxy gateway must not be used by physical service', runtimeLegacyProxyGatewayIssues)

  const issues = walk(runtimeCompatRoot)
    .filter(hasRuntimeLegacyBridgeMarker)
    .filter((file) => !allowedRuntimeLegacyBridgeFiles.has(fileName(file)))
    .map((file) => ({
      key: file,
      sourceFile: file,
      targetRoot: 'runtime legacy bridge allowlist'
    }))
  report('runtime legacy bridge must stay on explicit migration allowlist', issues)

  const migratedMethodIssues = walk(runtimeCompatRoot).flatMap((file) => {
    const methods = migratedRuntimeMethods.get(fileName(file))
    if (!methods) {
      return []
    }
    return methods
      .filter((methodName) => methodUsesRuntimeLegacyBridge(file, methodName))
      .map((methodName) => ({
        key: `${file}#${methodName}`,
        sourceFile: file,
        targetRoot: 'migrated runtime route implementation'
      }))
  })
  report('migrated Runtime route must not use legacy bridge', migratedMethodIssues)

  const undocumentedBridgeMethodIssues = walk(runtimeCompatRoot)
    .filter(hasRuntimeLegacyBridgeMarker)
    .filter((file) => fileName(file) !== 'RuntimeLegacyProxyGateway.java')
    .flatMap((file) => {
      const allowedMethods = new Set(allowedRuntimeLegacyBridgeMethods.get(fileName(file)) ?? [])
      return runtimeLegacyBridgeMethods(file)
        .filter((method) => !allowedMethods.has(method.name))
        .map((method) => ({
          key: `${file}#${method.name}`,
          sourceFile: file,
          targetRoot: 'runtime legacy bridge migration backlog'
        }))
    })
  report('runtime legacy bridge method must be on explicit migration backlog', undocumentedBridgeMethodIssues)
}

if (failures > 0) {
  console.error(`\nphysical service route contract check failed: ${failures} issue(s)`)
  process.exit(1)
}

console.log('physical service route contract check passed')
