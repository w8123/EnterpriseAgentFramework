import fs from 'node:fs'
import path from 'node:path'

const root = process.cwd()
const ownershipDoc = 'docs/architecture/service-table-ownership.md'
const serviceRoots = [
  'reachai-control-service',
  'reachai-runtime-service',
  'reachai-capability-service',
  'reachai-knowledge-service',
  'reachai-model-service'
]
const servicePackagePrefixes = {
  'reachai-control-service': ['com.enterprise.ai.control'],
  'reachai-runtime-service': ['com.enterprise.ai.runtime'],
  'reachai-capability-service': [
    'com.enterprise.ai.capability',
    'com.enterprise.ai.agent.capability',
    'com.enterprise.ai.agent.registry'
  ],
  'reachai-knowledge-service': [
    'com.enterprise.ai.bizindex',
    'com.enterprise.ai.client',
    'com.enterprise.ai.config',
    'com.enterprise.ai.controller',
    'com.enterprise.ai.domain',
    'com.enterprise.ai.embedding',
    'com.enterprise.ai.pipeline',
    'com.enterprise.ai.rag',
    'com.enterprise.ai.repository',
    'com.enterprise.ai.security',
    'com.enterprise.ai.service',
    'com.enterprise.ai.text',
    'com.enterprise.ai.vector'
  ],
  'reachai-model-service': ['com.enterprise.ai.model']
}
const sharedPackagePrefixes = [
  'com.enterprise.ai.common',
  'com.enterprise.ai.runtime.contract'
]

let failures = 0

function fail(message) {
  console.error(message)
  failures += 1
}

function walk(dir, matches = []) {
  if (!fs.existsSync(dir)) {
    return matches
  }
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const target = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      walk(target, matches)
    } else if (entry.name.endsWith('.java')) {
      matches.push(target)
    }
  }
  return matches
}

function normalizeCell(cell) {
  return cell.trim().replace(/^`|`$/g, '').trim()
}

function tableAccesses() {
  const byTable = new Map()
  for (const service of serviceRoots) {
    const javaRoot = path.join(root, service, 'src/main/java')
    for (const file of walk(javaRoot)) {
      const text = fs.readFileSync(file, 'utf8')
      for (const match of text.matchAll(/@TableName\(\s*"([^"]+)"\s*\)/g)) {
        const table = match[1]
        if (!byTable.has(table)) {
          byTable.set(table, [])
        }
        byTable.get(table).push({
          service,
          file: path.relative(root, file).replace(/\\/g, '/')
        })
      }
    }
  }
  return byTable
}

function parseOwnershipDoc() {
  const docPath = path.join(root, ownershipDoc)
  if (!fs.existsSync(docPath)) {
    fail(`[missing service table ownership doc] ${ownershipDoc}`)
    return new Map()
  }

  const rows = new Map()
  const text = fs.readFileSync(docPath, 'utf8')
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    if (!line.startsWith('|') || !line.endsWith('|')) {
      continue
    }
    if (/^\|\s*-+/.test(line) || /^\|\s*Table\s*\|/i.test(line)) {
      continue
    }
    const cells = line.slice(1, -1).split('|')
    if (cells.length < 4) {
      continue
    }
    const table = normalizeCell(cells[0])
    const owner = normalizeCell(cells[1])
    const additionalAccess = cells[2].trim()
    if (!table) {
      continue
    }
    if (rows.has(table)) {
      fail(`[duplicate ownership row] ${table}`)
    }
    rows.set(table, { owner, additionalAccess, rawLine })
  }
  return rows
}

function startsWithPackage(value, prefix) {
  return value === prefix || value.startsWith(`${prefix}.`)
}

function tableOrMapperClass(importedClass) {
  const simpleName = importedClass.split('.').at(-1)
  return simpleName.endsWith('Mapper') || simpleName.endsWith('Entity')
}

function checkCrossServiceMapperImports() {
  const ownedPrefixes = Object.entries(servicePackagePrefixes)
    .flatMap(([service, prefixes]) => prefixes.map(prefix => ({ service, prefix })))

  for (const service of serviceRoots) {
    const ownPrefixes = servicePackagePrefixes[service] || []
    const javaRoot = path.join(root, service, 'src/main/java')
    for (const file of walk(javaRoot)) {
      const text = fs.readFileSync(file, 'utf8')
      for (const match of text.matchAll(/^import\s+(com\.enterprise\.ai\.[^;]+);/gm)) {
        const imported = match[1]
        if (sharedPackagePrefixes.some(prefix => startsWithPackage(imported, prefix))) {
          continue
        }
        if (ownPrefixes.some(prefix => startsWithPackage(imported, prefix))) {
          continue
        }
        const owner = ownedPrefixes.find(({ prefix }) => startsWithPackage(imported, prefix))
        if (!owner || owner.service === service || !tableOrMapperClass(imported)) {
          continue
        }
        const relativeFile = path.relative(root, file).replace(/\\/g, '/')
        fail(`[cross-service Mapper/Entity import] ${service} -> ${owner.service}: ${relativeFile} imports ${imported}`)
      }
    }
  }
}

const accesses = tableAccesses()
const ownership = parseOwnershipDoc()
checkCrossServiceMapperImports()

for (const [table, refs] of [...accesses.entries()].sort(([a], [b]) => a.localeCompare(b))) {
  const row = ownership.get(table)
  if (!row) {
    fail(`[missing ownership row] ${table}`)
    continue
  }
  if (!serviceRoots.includes(row.owner)) {
    fail(`[invalid owner service] ${table}: ${row.owner}`)
    continue
  }

  const services = [...new Set(refs.map(ref => ref.service))]
  const additionalAccess = row.additionalAccess.trim()
  for (const service of services) {
    if (service === row.owner) {
      continue
    }
    if (!additionalAccess.includes(service)) {
      fail(`[undocumented non-owner table access] ${service} -> ${table}`)
    }
  }
  if (services.length === 1 && additionalAccess !== '-') {
    fail(`[stale additional direct access] ${table}: ${additionalAccess}`)
  }
}

for (const table of [...ownership.keys()].sort()) {
  if (!accesses.has(table)) {
    fail(`[ownership row has no current @TableName access] ${table}`)
  }
}

if (failures > 0) {
  console.error(`service table ownership check failed: ${failures} issue(s)`)
  process.exit(1)
}

console.log('service table ownership check passed')
