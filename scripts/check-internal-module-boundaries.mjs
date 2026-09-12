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

// Ignore Java comments and literals so examples/SQL strings cannot create dependencies.
function javaCode(text) {
  return text.replace(/"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\/\/[^\n]*|\/\*[\s\S]*?\*\//g,
    value => value.replace(/[^\n]/g, ' '))
}

function sourceTypes(files) {
  const sources = files.map(file => {
    const code = javaCode(fs.readFileSync(file, 'utf8'))
    const packageName = parsePackage(code)
    return { file, code, packageName, imports: parseImports(code),
      qualifiedName: `${packageName}.${path.basename(file, '.java')}` }
  })
  const persistence = new Set(sources.filter(source =>
    /\.(?:persistence)\./.test(source.qualifiedName)
      || /@(?:[\w.]+\.)?(?:TableName|Entity|MappedSuperclass|Mapper|Select|Insert|Update|Delete)\b/.test(source.code)
      || /\b(?:extends|implements)\s+(?:[\w.]+\.)?(?:BaseMapper|JpaRepository|CrudRepository|PagingAndSortingRepository|ServiceImpl)\b/.test(source.code)
  ).map(source => source.qualifiedName))
  let changed
  do {
    changed = false
    for (const source of sources) {
      if (persistence.has(source.qualifiedName)) continue
      const parent = source.code.match(/\bextends\s+([\w.]+)/)?.[1]
      if (!parent) continue
      const resolved = parent.includes('.') ? parent
        : source.imports.find(value => value.endsWith(`.${parent}`)) || `${source.packageName}.${parent}`
      if (persistence.has(resolved)) { persistence.add(source.qualifiedName); changed = true }
    }
  } while (changed)
  return { sources, persistence }
}

// Tarjan SCC: cycles of every length, including new edges inside an existing cycle.
function cyclicEdges(edges) {
  const graph = new Map()
  for (const dependency of edges) {
    const [from, to] = dependency.split('->')
    if (!graph.has(from)) graph.set(from, [])
    if (!graph.has(to)) graph.set(to, [])
    graph.get(from).push(to)
  }
  let sequence = 0
  const index = new Map(), low = new Map(), component = new Map()
  const stack = [], onStack = new Set()
  const groups = []
  function visit(node) {
    index.set(node, sequence); low.set(node, sequence++)
    stack.push(node); onStack.add(node)
    for (const next of graph.get(node)) {
      if (!index.has(next)) { visit(next); low.set(node, Math.min(low.get(node), low.get(next))) }
      else if (onStack.has(next)) low.set(node, Math.min(low.get(node), index.get(next)))
    }
    if (low.get(node) !== index.get(node)) return
    const group = []
    let item
    do {
      item = stack.pop(); onStack.delete(item); group.push(item); component.set(item, groups.length)
    } while (item !== node)
    groups.push(group.sort())
  }
  for (const node of graph.keys()) if (!index.has(node)) visit(node)
  return { groups: groups.filter(group => group.length > 1), edges: new Set([...edges].filter(dependency => {
    const [from, to] = dependency.split('->')
    return from !== to && component.get(from) === component.get(to)
  })) }
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
  const { sources, persistence } = sourceTypes(walkJavaFiles(service.root || ''))
  const knownTypes = new Set(sources.map(source => source.qualifiedName))

  function resolveReference(name) {
    if (name.endsWith('.*')) {
      const prefix = name.slice(0, -1)
      const expanded = [...knownTypes].filter(type => type.startsWith(prefix) && !type.slice(prefix.length).includes('.'))
      return expanded.length ? expanded : [name]
    }
    let type = name
    while (type.includes('.')) {
      if (knownTypes.has(type)) return [type]
      type = type.slice(0, type.lastIndexOf('.'))
    }
    return [name]
  }

  for (const { file, packageName, code, imports } of sources) {
    const relative = normalizePath(path.relative(root, file))
    const sourceModule = serviceModuleOf(packageName, service)
    if (!sourceModule || !declaredModules.has(sourceModule)) {
      fail(`${relative}: package ${packageName || '(missing)'} belongs to undeclared ${serviceName} module ${sourceModule || '(outside prefix)'}`)
      continue
    }

    const references = new Set(imports.flatMap(resolveReference))
    for (const token of code.matchAll(/\b[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*){2,}/g)) {
      if (token[0] !== packageName && !token[0].startsWith(`${packageName}.`)) {
        resolveReference(token[0]).forEach(type => references.add(type))
      }
    }
    for (const importName of references) {
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
      if (importName.includes('.persistence.') || persistence.has(importName)) {
        const allowance = `${relative}|${importName}`
        observedPersistence.add(allowance)
        if (!allowedPersistence.has(allowance)) {
          fail(`${relative}: cross-module persistence import is forbidden: ${importName}`)
        }
      }
    }
  }

  const cycles = cyclicEdges(observedEdges)
  const allowedCycleEdges = new Set(service.allowedCycleEdges || [...allowedPairs].flatMap(pair => {
    const [from, to] = pair.split('|')
    return [edge(from, to), edge(to, from)]
  }))
  for (const dependency of cycles.edges) {
    if (!allowedCycleEdges.has(dependency)) fail(`[new cyclic module dependency] ${serviceName}: ${dependency}`)
  }
  for (const dependency of allowedCycleEdges) {
    if (!cycles.edges.has(dependency)) fail(`[stale cycle allowance] ${serviceName}: ${dependency}; remove it from the policy`)
  }

  const observedPairs = new Set()
  for (const dependency of observedEdges) {
    const [from, to] = dependency.split('->')
    if (!observedEdges.has(edge(to, from))) continue
    const pair = reciprocalPair(from, to)
    if (observedPairs.has(pair)) continue
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
    persistenceDebtCount: observedPersistence.size,
    cycles: cycles.groups,
    cyclicEdges: [...cycles.edges].sort(),
    persistenceImports: [...observedPersistence].sort()
  }
}

const policy = readPolicy()
const summaries = policy.services.map(validateService)
if (process.env.REACHAI_INTERNAL_MODULE_BOUNDARY_REPORT) {
  fs.writeFileSync(process.env.REACHAI_INTERNAL_MODULE_BOUNDARY_REPORT,
    `${JSON.stringify({ failures, services: summaries }, null, 2)}\n`, 'utf8')
}

if (failures > 0) {
  console.error(`internal module boundary check failed: ${failures} issue(s)`)
  process.exit(1)
}

const summary = summaries
  .map(item => `${item.serviceName}=${item.moduleCount} modules/${item.cycles.length} cyclic components/${item.cyclicEdges.length} cyclic edges/${item.persistenceDebtCount} persistence exceptions`)
  .join(', ')
console.log(`internal module boundary check passed (${summary})`)
