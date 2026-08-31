import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const policyPath = path.resolve(
  root,
  process.env.REACHAI_INTERNAL_MODULE_BOUNDARY_POLICY
    || 'scripts/internal-module-boundaries.json'
)
let failures = 0

function fail(message) {
  console.error(message)
  failures += 1
}

function normalizePath(value) {
  return value.replace(/\\/g, '/')
}

function readPolicy() {
  if (!fs.existsSync(policyPath)) {
    fail(`[missing internal module boundary policy] ${normalizePath(policyPath)}`)
    return { services: [] }
  }
  let policy
  try {
    policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'))
  } catch (error) {
    fail(`[invalid internal module boundary policy] ${error.message}`)
    return { services: [] }
  }
  if (policy.schema !== 'reachai.internal-module-boundaries.v1'
      || !Array.isArray(policy.services)) {
    fail('[invalid internal module boundary policy] unsupported schema or services')
    return { services: [] }
  }
  return policy
}

function walkJavaFiles(relativeRoot) {
  const base = path.resolve(root, relativeRoot)
  if (!fs.existsSync(base)) {
    fail(`[missing service source root] ${relativeRoot}`)
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
  return Array.from(
    text.matchAll(/^import\s+(?:static\s+)?([^;]+);/gm),
    match => match[1]
  )
}

function moduleOf(qualifiedName, prefix) {
  if (qualifiedName === prefix) return '(root)'
  if (!qualifiedName.startsWith(`${prefix}.`)) return null
  return qualifiedName.slice(prefix.length + 1).split('.')[0] || '(root)'
}

function serviceModuleOf(qualifiedName, service) {
  const primary = moduleOf(qualifiedName, service.packagePrefix || '')
  if (primary) return primary
  for (const alias of service.packageAliases || []) {
    if (qualifiedName === alias.prefix
        || qualifiedName.startsWith(`${alias.prefix}.`)) {
      return alias.module
    }
  }
  return null
}

function ignoredImport(importName, service) {
  return (service.ignoredImportPrefixes || []).some(prefix =>
    importName === prefix || importName.startsWith(`${prefix}.`))
}

function edge(from, to) {
  return `${from}->${to}`
}

function reciprocalPair(first, second) {
  return [first, second].sort().join('|')
}

function validateService(service) {
  const serviceName = service.name || '(unnamed)'
  const packagePrefix = service.packagePrefix || ''
  const declaredModules = new Set(service.modules || [])
  const allowedPairs = new Set(service.allowedReciprocalPairs || [])
  const forbiddenEdges = new Set(service.forbiddenEdges || [])
  const allowedPersistence = new Set(
    service.allowedCrossModulePersistenceImports || []
  )
  const observedEdges = new Set()
  const observedPersistence = new Set()

  for (const file of walkJavaFiles(service.root || '')) {
    const relative = normalizePath(path.relative(root, file))
    const text = fs.readFileSync(file, 'utf8')
    const packageName = parsePackage(text)
    const sourceModule = serviceModuleOf(packageName, service)
    if (!sourceModule || !declaredModules.has(sourceModule)) {
      fail(`${relative}: package ${packageName || '(missing)'} belongs to undeclared ${serviceName} module ${sourceModule || '(outside prefix)'}`)
      continue
    }

    for (const importName of parseImports(text)) {
      if (ignoredImport(importName, service)) continue
      const targetModule = serviceModuleOf(importName, service)
      if (!targetModule || targetModule === sourceModule) continue
      if (!declaredModules.has(targetModule)) {
        fail(`${relative}: imports undeclared ${serviceName} module ${targetModule}: ${importName}`)
        continue
      }
      const dependency = edge(sourceModule, targetModule)
      observedEdges.add(dependency)
      if (forbiddenEdges.has(dependency)) {
        fail(`${relative}: forbidden module dependency ${dependency}; depend on the owning module port instead`)
      }
      if (importName.includes('.persistence.')) {
        const allowance = `${relative}|${importName}`
        observedPersistence.add(allowance)
        if (!allowedPersistence.has(allowance)) {
          fail(`${relative}: cross-module persistence import is forbidden: ${importName}`)
        }
      }
    }
  }

  const observedPairs = new Set()
  for (const dependency of observedEdges) {
    const [from, to] = dependency.split('->')
    if (!observedEdges.has(edge(to, from))) continue
    const pair = reciprocalPair(from, to)
    observedPairs.add(pair)
    if (!allowedPairs.has(pair)) {
      fail(`[new reciprocal module dependency] ${serviceName}: ${pair}`)
    }
  }

  for (const pair of allowedPairs) {
    if (!observedPairs.has(pair)) {
      fail(`[stale reciprocal allowance] ${serviceName}: ${pair}; remove it from the policy so the cycle cannot return`)
    }
  }
  for (const allowance of allowedPersistence) {
    if (!observedPersistence.has(allowance)) {
      fail(`[stale persistence allowance] ${serviceName}: ${allowance}; remove it from the policy`)
    }
  }

  return {
    serviceName,
    moduleCount: declaredModules.size,
    reciprocalDebtCount: observedPairs.size,
    persistenceDebtCount: observedPersistence.size
  }
}

const policy = readPolicy()
const summaries = policy.services.map(validateService)

if (failures > 0) {
  console.error(`internal module boundary check failed: ${failures} issue(s)`)
  process.exit(1)
}

const summary = summaries
  .map(item => `${item.serviceName}=${item.moduleCount} modules/${item.reciprocalDebtCount} cycles/${item.persistenceDebtCount} persistence exceptions`)
  .join(', ')
console.log(`internal module boundary check passed (${summary})`)
