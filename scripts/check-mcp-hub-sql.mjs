import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const initPath = join(root, 'sql', 'initV2.sql')
const upgradePath = join(root, 'sql', 'upgrade-20260830-platform-consolidated.sql')
const init = readFileSync(initPath, 'utf8')
const upgrade = readFileSync(upgradePath, 'utf8')

const tables = [
  'control_mcp_publication',
  'control_mcp_publication_item',
  'control_mcp_publication_revision',
  'control_mcp_client',
  'control_mcp_call_log',
]

function fail(message) {
  console.error(`[mcp-hub-sql] FAIL: ${message}`)
  process.exitCode = 1
}

function createTable(sql, table, source) {
  const pattern = new RegExp(
    'CREATE TABLE IF NOT EXISTS `' + table
      + '` \\([\\s\\S]*?\\) ENGINE=InnoDB[^;]+;',
  )
  const match = sql.match(pattern)
  if (!match) {
    fail(`${source} is missing CREATE TABLE for ${table}`)
    return ''
  }
  return match[0].replace(/\s+/g, ' ').trim()
}

for (const table of tables) {
  const baseline = createTable(init, table, 'sql/initV2.sql')
  const migration = createTable(upgrade, table, 'consolidated upgrade Section 14')
  if (baseline && migration && baseline !== migration) {
    fail(`${table} differs between baseline and upgrade SQL`)
  }
}

for (const required of [
  '`project_id`',
  '`project_code`',
  '`environment`',
  '`tenant_id`',
  '`idx_mcp_client_project`',
  '`idx_mcp_log_project_trace`',
  "'mcp-hub:publication:irreversible'",
]) {
  if (!upgrade.includes(required)) {
    fail(`upgrade SQL is missing retained project-scope contract ${required}`)
  }
}

for (const required of [
  /`project_code`\s+VARCHAR\(96\)\s+NOT NULL/,
  /`environment`\s+VARCHAR\(32\)\s+NOT NULL/,
  /`tenant_id`\s+VARCHAR\(96\)\s+NOT NULL/,
  /`request_body`\s+MEDIUMTEXT/,
  /`response_body`\s+MEDIUMTEXT/,
]) {
  if (!required.test(createTable(upgrade, required.source.includes('request_body') || required.source.includes('response_body')
    ? 'control_mcp_call_log' : 'control_mcp_client', 'consolidated upgrade Section 14'))) {
    fail(`upgrade SQL is missing enforced MCP contract ${required}`)
  }
}

if (/CREATE TABLE IF NOT EXISTS `control_mcp_visibility`/i.test(init)
    || /CREATE TABLE IF NOT EXISTS `control_mcp_visibility`/i.test(upgrade)) {
  fail('retired control_mcp_visibility must not be recreated')
}

if (!process.exitCode) {
  console.log('[mcp-hub-sql] PASS: baseline and upgrade MCP Hub schemas match')
}
