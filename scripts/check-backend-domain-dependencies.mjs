import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
let failures = 0

const services = [
  {
    name: 'control',
    root: 'reachai-control-service/src/main/java',
    ownedPrefixes: ['com.enterprise.ai.control']
  },
  {
    name: 'runtime',
    root: 'reachai-runtime-service/src/main/java',
    ownedPrefixes: ['com.enterprise.ai.runtime', 'com.enterprise.ai.agent.graph']
  },
  {
    name: 'capability',
    root: 'reachai-capability-service/src/main/java',
    ownedPrefixes: [
      'com.enterprise.ai.capability',
      'com.enterprise.ai.agent.capability',
      'com.enterprise.ai.agent.registry'
    ]
  },
  {
    name: 'knowledge',
    root: 'reachai-knowledge-service/src/main/java',
    ownedPrefixes: ['com.enterprise.ai']
  },
  {
    name: 'model',
    root: 'reachai-model-service/src/main/java',
    ownedPrefixes: ['com.enterprise.ai.model']
  }
]

const retiredSourceRoots = [
  'ai-agent-service/src/main/java',
  'ai-agent-service/src/test/java',
  'ai-skills-service/src/main/java',
  'ai-skills-service/src/test/java',
  'ai-model-service/src/main/java',
  'ai-model-service/src/test/java'
]

const forbiddenMainlineText = [
  'LEGACY_AGENT_SERVICE_DISABLED',
  'X-ReachAI-Legacy-Proxy',
  'LegacyProxyObservability',
  'no longer proxied to ai-agent-service',
  'migrate it into reachai-'
]

function relPath(abs) {
  return path.relative(root, abs).replace(/\\/g, '/')
}

function existsRel(rel) {
  return fs.existsSync(path.join(root, rel))
}

function walkJavaFiles(rel) {
  const base = path.join(root, rel)
  if (!fs.existsSync(base)) {
    console.error(`[missing source root] ${rel}`)
    failures += 1
    return []
  }
  const files = []
  const stack = [base]
  while (stack.length > 0) {
    const current = stack.pop()
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const target = path.join(current, entry.name)
      if (entry.isDirectory()) {
        stack.push(target)
      } else if (entry.isFile() && entry.name.endsWith('.java')) {
        files.push(target)
      }
    }
  }
  return files
}

function parsePackage(text) {
  return text.match(/^package\s+([^;]+);/m)?.[1] ?? ''
}

function parseImports(text) {
  return Array.from(text.matchAll(/^import\s+(?:static\s+)?([^;]+);/gm), (match) => match[1])
}

function startsWithAny(value, prefixes) {
  return prefixes.some((prefix) => value === prefix || value.startsWith(`${prefix}.`))
}

function report(file, message) {
  console.error(`${file}: ${message}`)
  failures += 1
}

for (const rel of retiredSourceRoots) {
  if (existsRel(rel)) {
    report(rel, 'retired service source root must not exist in the active repository')
  }
}

const serviceByName = new Map(services.map((service) => [service.name, service]))

function owningServiceOfImport(importName) {
  for (const service of services) {
    if (service.name === 'knowledge') {
      continue
    }
    if (startsWithAny(importName, service.ownedPrefixes)) {
      return service.name
    }
  }
  if (importName.startsWith('com.enterprise.ai.agent.')) {
    return 'legacy-agent'
  }
  return null
}

function isAllowedLegacyAgentImport(serviceName, importName) {
  if (serviceName === 'runtime') {
    return importName === 'com.enterprise.ai.agent.graph.GraphSpec' ||
        importName === 'com.enterprise.ai.agent.graph.AgentGraphNodeType' ||
        importName.startsWith('com.enterprise.ai.agent.graph.')
  }
  if (serviceName === 'capability') {
    return importName.startsWith('com.enterprise.ai.agent.capability.') ||
        importName.startsWith('com.enterprise.ai.agent.registry.')
  }
  return false
}

for (const service of services) {
  for (const file of walkJavaFiles(service.root)) {
    const relative = relPath(file)
    const text = fs.readFileSync(file, 'utf8')
    const packageName = parsePackage(text)

    if (!startsWithAny(packageName, service.ownedPrefixes)) {
      report(relative, `package ${packageName || '(missing)'} is outside ${service.name} ownership`)
    }

    for (const forbidden of forbiddenMainlineText) {
      if (text.includes(forbidden)) {
        report(relative, `retired legacy proxy marker is not allowed in active service source: ${forbidden}`)
      }
    }

    for (const importName of parseImports(text)) {
      const owner = owningServiceOfImport(importName)
      if (!owner || owner === service.name) {
        continue
      }
      if (owner === 'legacy-agent' && isAllowedLegacyAgentImport(service.name, importName)) {
        continue
      }
      if (owner === 'legacy-agent') {
        report(relative, `imports retired agent package ${importName}`)
        continue
      }
      const ownerService = serviceByName.get(owner)
      report(relative, `imports ${ownerService.name} implementation package ${importName}; use a local client/contract boundary`)
    }
  }
}

if (failures > 0) {
  console.error(`backend domain dependency check failed: ${failures} issue(s)`)
  process.exit(1)
}

console.log('backend domain dependency check passed')
