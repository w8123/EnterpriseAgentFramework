import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const initPath = join(root, 'sql', 'initV2.sql')
const init = readFileSync(initPath, 'utf8')

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
  createTable(init, table, 'sql/initV2.sql')
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
  if (!init.includes(required)) {
    fail(`baseline SQL is missing retained project-scope contract ${required}`)
  }
}

for (const required of [
  /`project_code`\s+VARCHAR\(96\)\s+NOT NULL/,
  /`environment`\s+VARCHAR\(32\)\s+NOT NULL/,
  /`tenant_id`\s+VARCHAR\(96\)\s+NOT NULL/,
  /`request_body`\s+MEDIUMTEXT/,
  /`response_body`\s+MEDIUMTEXT/,
]) {
  if (!required.test(createTable(init, required.source.includes('request_body') || required.source.includes('response_body')
    ? 'control_mcp_call_log' : 'control_mcp_client', 'sql/initV2.sql'))) {
    fail(`baseline SQL is missing enforced MCP contract ${required}`)
  }
}

if (/CREATE TABLE IF NOT EXISTS `control_mcp_visibility`/i.test(init)) {
  fail('retired control_mcp_visibility must not be recreated')
}

if (!process.exitCode) {
  console.log('[mcp-hub-sql] PASS: current baseline retains MCP Hub schemas and project-scope contracts')
}
