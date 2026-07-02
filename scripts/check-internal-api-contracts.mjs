import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const docPath = 'docs/architecture/internal-api-contracts.md'
const internalPathPattern = /\/internal\/(?:runtime|capability|control)(?:\/[^"`'\s),}]*)?/g
const internalPathExactPattern = /\/internal\/(?:runtime|capability|control)(?:\/.*)?/
const mappingAnnotationPattern = /@(Get|Post|Put|Patch|Delete|Request)Mapping\s*\(([\s\S]*?)\)/g

let failures = 0

const serviceRoots = [
  'reachai-control-service/src/main/java',
  'reachai-runtime-service/src/main/java',
  'reachai-capability-service/src/main/java',
  'reachai-knowledge-service/src/main/java',
  'reachai-model-service/src/main/java'
]

const frontendRoots = [
  'ai-admin-front/src',
  'ai-admin-front/.env.development',
  'ai-admin-front/env.d.ts',
  'ai-admin-front/README.md'
]

function exists(rel) {
  return fs.existsSync(path.join(root, rel))
}

function read(rel) {
  return fs.readFileSync(path.join(root, rel), 'utf8')
}

function walkFiles(rel, predicate = () => true) {
  const base = path.join(root, rel)
  if (!fs.existsSync(base)) {
    return []
  }
  if (fs.statSync(base).isFile()) {
    return predicate(base) ? [base] : []
  }
  const files = []
  const stack = [base]
  while (stack.length > 0) {
    const current = stack.pop()
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const target = path.join(current, entry.name)
      if (entry.isDirectory()) {
        stack.push(target)
      } else if (predicate(target)) {
        files.push(target)
      }
    }
  }
  return files
}

function relPath(abs) {
  return path.relative(root, abs).replace(/\\/g, '/')
}

function serviceNameForFile(rel) {
  const match = rel.match(/^(reachai-[^/]+-service)\//)
  return match ? match[1] : 'unknown-service'
}

function ownerForInternalPath(internalPath) {
  if (internalPath.startsWith('/internal/runtime/')) {
    return 'reachai-runtime-service'
  }
  if (internalPath.startsWith('/internal/capability/')) {
    return 'reachai-capability-service'
  }
  if (internalPath.startsWith('/internal/control/')) {
    return 'reachai-control-service'
  }
  return 'unknown-service'
}

function httpMethod(annotationName, args) {
  if (annotationName !== 'Request') {
    return annotationName.toUpperCase()
  }
  const requestMethodMatch = args.match(/RequestMethod\.(GET|POST|PUT|PATCH|DELETE)/)
  return requestMethodMatch ? requestMethodMatch[1] : 'REQUEST'
}

function stringLiterals(text) {
  return Array.from(text.matchAll(/"([^"]+)"/g), (match) => match[1])
}

function normalizePath(value) {
  return value.replace(/\s+/g, '')
}

function joinPaths(base, child) {
  if (!base) {
    return normalizePath(child)
  }
  if (!child || child === '/') {
    return normalizePath(base)
  }
  if (child.startsWith('/')) {
    return normalizePath(child)
  }
  return normalizePath(`${base.replace(/\/$/, '')}/${child}`)
}

function classLevelInternalBase(text) {
  const classIndex = text.search(/\b(class|interface)\s+\w+/)
  if (classIndex < 0) {
    return ''
  }
  const prefix = text.slice(0, classIndex)
  const requestMappings = Array.from(prefix.matchAll(/@RequestMapping\s*\(([\s\S]*?)\)/g))
  for (const mapping of requestMappings.reverse()) {
    const paths = stringLiterals(mapping[1]).filter((value) => internalPathExactPattern.test(value))
    if (paths.length > 0) {
      return normalizePath(paths[0])
    }
  }
  return ''
}

function discoverInternalContracts() {
  const contracts = new Map()
  for (const sourceRoot of serviceRoots) {
    for (const abs of walkFiles(sourceRoot, (target) => target.endsWith('.java'))) {
      const rel = relPath(abs)
      const text = fs.readFileSync(abs, 'utf8')
      const base = classLevelInternalBase(text)
      const classIndex = text.search(/\b(class|interface)\s+\w+/)
      const methodText = classIndex >= 0 ? text.slice(classIndex) : text
      mappingAnnotationPattern.lastIndex = 0
      for (const mapping of methodText.matchAll(mappingAnnotationPattern)) {
        const method = httpMethod(mapping[1], mapping[2])
        const literals = stringLiterals(mapping[2])
        const paths = literals.length > 0 ? literals : ['']
        for (const rawPath of paths) {
          const fullPath = rawPath.startsWith('/internal/') ? normalizePath(rawPath) : joinPaths(base, rawPath)
          if (!fullPath.startsWith('/internal/')) {
            continue
          }
          const contract = `${method} ${fullPath}`
          if (!contracts.has(contract)) {
            contracts.set(contract, {
              contract,
              owner: ownerForInternalPath(fullPath),
              files: new Set()
            })
          }
          contracts.get(contract).files.add(rel)
        }
      }
    }
  }
  return Array.from(contracts.values()).sort((left, right) => left.contract.localeCompare(right.contract))
}

function checkDocumentation(contracts) {
  if (!exists(docPath)) {
    console.error(`[missing internal API contract doc] ${docPath}`)
    failures += 1
    return
  }
  const doc = read(docPath)
  for (const contract of contracts) {
    if (doc.includes(`\`${contract.contract}\``)) {
      continue
    }
    if (contract.contract.startsWith('REQUEST ')) {
      const pathOnly = contract.contract.slice('REQUEST '.length)
      if (doc.includes(pathOnly)) {
        continue
      }
    }
    console.error(`[undocumented internal API] ${contract.contract}`)
    console.error(`  owner: ${contract.owner}`)
    for (const file of contract.files) {
      console.error(`  source: ${file}`)
    }
    failures += 1
  }
}

function checkFrontendDoesNotCallInternalApi() {
  const matches = []
  for (const frontendRoot of frontendRoots) {
    for (const abs of walkFiles(frontendRoot, (target) => {
      return /\.(ts|tsx|js|jsx|vue|md|env)$/.test(target) || path.basename(target).startsWith('.env')
    })) {
      const text = fs.readFileSync(abs, 'utf8')
      const found = text.match(internalPathPattern)
      internalPathPattern.lastIndex = 0
      if (found && found.length > 0) {
        matches.push(`${relPath(abs)}: ${Array.from(new Set(found)).join(', ')}`)
      }
    }
  }
  if (matches.length > 0) {
    console.error('[frontend must not call internal service API]')
    for (const match of matches) {
      console.error(`  ${match}`)
    }
    failures += matches.length
  }
}

const contracts = discoverInternalContracts()
checkDocumentation(contracts)
checkFrontendDoesNotCallInternalApi()

if (failures > 0) {
  process.exit(1)
}

console.log('internal API contract check passed')
