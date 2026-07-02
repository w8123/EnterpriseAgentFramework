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

function walk(dir, matches = [], extensions = ['.java']) {
  if (!fs.existsSync(dir)) {
    return matches
  }
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const target = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      walk(target, matches, extensions)
    } else if (extensions.some(extension => entry.name.endsWith(extension))) {
      matches.push(target)
    }
  }
  return matches
}

function normalizeCell(cell) {
  return cell.trim().replace(/^`|`$/g, '').trim()
}

function addTableAccess(byTable, table, service, file) {
  if (!table) {
    return
  }
  if (!byTable.has(table)) {
    byTable.set(table, [])
  }
  byTable.get(table).push({
    service,
    file: path.relative(root, file).replace(/\\/g, '/')
  })
}

function stripSqlComments(sql) {
  return sql
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/--[^\r\n]*/g, ' ')
}

function extractTableName(match) {
  return match[2] || match[1]
}

function extractTablesFromSql(sql) {
  const clean = stripSqlComments(sql)
  const tableRef = '`?([A-Za-z_][A-Za-z0-9_]*)`?(?:\\s*\\.\\s*`?([A-Za-z_][A-Za-z0-9_]*)`?)?'
  const patterns = [
    new RegExp(`\\b(?:from|join|update)\\s+${tableRef}`, 'gi'),
    new RegExp(`\\b(?:insert|replace)\\s+(?:ignore\\s+)?into\\s+${tableRef}`, 'gi'),
    new RegExp(`\\bdelete\\s+from\\s+${tableRef}`, 'gi')
  ]
  const tables = new Set()
  for (const pattern of patterns) {
    for (const match of clean.matchAll(pattern)) {
      const table = extractTableName(match)
      if (!['select', 'where', 'set', 'values'].includes(table.toLowerCase())) {
        tables.add(table)
      }
    }
  }
  return tables
}

function extractJavaStringLiterals(text) {
  return [...text.matchAll(/"((?:\\.|[^"\\])*)"/g)]
    .map(match => match[1].replace(/\\"/g, '"'))
}

function extractAnnotationSqlSnippets(text) {
  const snippets = []
  for (const match of text.matchAll(/@(Select|Update|Insert|Delete)\s*\(([\s\S]*?)\)/g)) {
    snippets.push(extractJavaStringLiterals(match[2]).join(' '))
  }
  return snippets
}

function extractJdbcTemplateSqlSnippets(text) {
  if (!/\b(?:NamedParameterJdbcTemplate|JdbcTemplate)\b/.test(text)) {
    return []
  }
  return extractJavaStringLiterals(text)
    .filter(value => /\b(?:select|update|insert|delete)\b/i.test(value))
}

function tableAccesses() {
  const byTable = new Map()
  for (const service of serviceRoots) {
    const javaRoot = path.join(root, service, 'src/main/java')
    for (const file of walk(javaRoot)) {
      const text = fs.readFileSync(file, 'utf8')
      for (const match of text.matchAll(/@TableName\(\s*"([^"]+)"\s*\)/g)) {
        addTableAccess(byTable, match[1], service, file)
      }
      for (const snippet of [
        ...extractAnnotationSqlSnippets(text),
        ...extractJdbcTemplateSqlSnippets(text)
      ]) {
        for (const table of extractTablesFromSql(snippet)) {
          addTableAccess(byTable, table, service, file)
        }
      }
    }

    const resourcesRoot = path.join(root, service, 'src/main/resources')
    for (const file of walk(resourcesRoot, [], ['.xml'])) {
      const text = fs.readFileSync(file, 'utf8')
      for (const table of extractTablesFromSql(text)) {
        addTableAccess(byTable, table, service, file)
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

function parseInitTables() {
  const initPath = path.join(root, 'sql/initV2.sql')
  if (!fs.existsSync(initPath)) {
    return new Set()
  }

  const text = fs.readFileSync(initPath, 'utf8')
  const tables = new Set()
  for (const match of text.matchAll(/CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`([^`]+)`/gi)) {
    tables.add(match[1])
  }
  return tables
}

function startsWithPackage(value, prefix) {
  return value === prefix || value.startsWith(`${prefix}.`)
}

function tableOrMapperClass(importedClass) {
  const parts = importedClass.split('.')
  const simpleName = parts[parts.length - 1]
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
const initTables = parseInitTables()
checkCrossServiceMapperImports()

for (const table of [...initTables].sort()) {
  if (!ownership.has(table)) {
    fail(`[missing ownership row] ${table}`)
  }
}

for (const [table, row] of [...ownership.entries()].sort(([a], [b]) => a.localeCompare(b))) {
  if (!serviceRoots.includes(row.owner)) {
    fail(`[invalid owner service] ${table}: ${row.owner}`)
  }
}

for (const [table, refs] of [...accesses.entries()].sort(([a], [b]) => a.localeCompare(b))) {
  const row = ownership.get(table)
  if (!row) {
    fail(`[missing ownership row] ${table}`)
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
  if (!accesses.has(table) && !initTables.has(table)) {
    fail(`[ownership row has no current direct code access or SQL baseline table] ${table}`)
  }
}

if (failures > 0) {
  console.error(`service table ownership check failed: ${failures} issue(s)`)
  process.exit(1)
}

console.log('service table ownership check passed')
