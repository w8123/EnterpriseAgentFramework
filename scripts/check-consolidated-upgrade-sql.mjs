#!/usr/bin/env node

import { createHash } from 'node:crypto'
import { readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const targetName = 'upgrade-20261019-consolidated.sql'
const directory = join(root, 'sql')
const read = name => readFileSync(join(directory, name), 'utf8').replace(/^\uFEFF/, '').replace(/\r\n/g, '\n')
const init = read('initV2.sql')
const upgrade = read(targetName)
const sources = [
  ['upgrade-20261016-http-api-project-connection.sql', '3d3a9114cbf0a938fd463a671eca94ade510dcc62080ff7bc12f817cba147669'],
  ['upgrade-20261016-model-catalog-analyzer-setting.sql', 'bce7f84e7943db9501e3c62d14069c28543d32719463221b6542edecdebd098a'],
  ['upgrade-20261016-model-provider-account.sql', 'e3aa7865a207eaea9b88698b2ce2302c784ab10d350285467d622d393cb94812'],
  ['upgrade-20261016-platform-admin-tool-acl.sql', '29d7a881c5891c551b516db83cfcea4b74ca7a04d27a67c6a6fede270604fd45'],
  ['upgrade-20261017-agent-memory-policy.sql', '19c5371e21d82a631c759f924d8a29a9b3ba52e9522094a1263b57cdb29caaf2'],
  ['upgrade-20261017-business-index-reference-naming.sql', 'e2d1c663696d8a6eecb0926ef96ba0ed31705e116a798a27906ca74b05cf127c'],
  ['upgrade-20261017-personal-memory-tables.sql', '9e4419f72c17fcf852a615f33f1e67262f908bf4d2aaffca004439d2eea61faa'],
  ['upgrade-20261017-platform-roles-redesign.sql', '40380932f6e077f31ff3b4c6f8b99412437f893a12fd851daaf9a2eb2f69dd15'],
  ['upgrade-20261017-run-center-cleanup.sql', '4102341992d1fcac09f1af982693f80b9f98b31d21abcb5bb905b823ccc7800b'],
  ['upgrade-20261018-knowledge-cleanup.sql', '8a817877694bd9706c2d97d3bc36912210fc9c7972fbf66470ddb9c868db33b8'],
]
const issues = []
function fail(message) {
  issues.push(message)
  console.error(`[consolidated-upgrade-sql] FAIL: ${message}`)
}

// Future dated increments remain valid; the retired migration chain must stay out of sql/.
for (const name of readdirSync(directory).filter(name => /^upgrade-.*\.sql$/i.test(name))) {
  if (name === targetName) continue
  const date = name.match(/^upgrade-(\d{8})-[a-z0-9-]+\.sql$/)?.[1]
  if (!date || date < '20261019') fail(`retired or duplicate upgrade remains: ${name}`)
}

if ([...upgrade.matchAll(/^-- Section /gm)].length !== sources.length) fail('expected exactly 10 source sections')
let previous = -1
for (let index = 0; index < sources.length; index++) {
  const [source, checksum] = sources[index]
  const number = String(index + 1).padStart(2, '0')
  const start = upgrade.indexOf(`-- Section ${number}/10:`)
  if (start < 0 || start <= previous) fail(`source section ${number} is missing or out of order`)
  previous = start
  const end = upgrade.indexOf(`-- End source ${number}`, start)
  const section = upgrade.slice(start, end)
  if (!section.includes(`-- Consolidated from: sql/${source}\n`)) fail(`missing source manifest for ${source}`)
  if (!section.includes(`-- Source SHA-256 (UTF-8 LF): ${checksum}\n`)) fail(`missing source digest for ${source}`)
  const beginMarker = `-- Begin source ${number}\n`
  const begin = section.indexOf(beginMarker)
  const content = begin < 0 ? '' : section.slice(begin + beginMarker.length)
  const actual = createHash('sha256').update(content).digest('hex')
  if (end < 0 || actual !== checksum) fail(`source SQL changed or was truncated: ${source}`)
}

function tables(sql) {
  return new Map([...sql.matchAll(/CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+`(\w+)`\s*\([\s\S]*?\)\s*ENGINE=InnoDB[^;]*;/gi)]
    .map(match => [match[1], match[0].replace(/\s+/g, ' ').trim()]))
}
const baselineTables = tables(init)
const upgradeTables = tables(upgrade)
if (upgradeTables.size !== 11) fail(`expected 11 created tables, found ${upgradeTables.size}`)
for (const [table, definition] of upgradeTables) {
  if (!baselineTables.has(table)) fail(`${table} is missing from initV2.sql`)
  else if (definition !== baselineTables.get(table)) fail(`${table} differs from initV2.sql`)
}

const columns = {
  runtime_http_api_connection: ['override_active'],
  model_catalog_setting: ['analyzer_model_instance_id'],
  model_instance: ['provider_account_id'],
  runtime_agent_config_version: ['session_memory_enabled', 'session_memory_window', 'personal_memory_read', 'personal_memory_write'],
  knowledge_business_index: ['agent_reference_enabled'],
  control_embed_session: ['external_user_name'],
  knowledge_base: ['access_mode'],
  knowledge_hit_log: ['source'],
}
for (const [table, names] of Object.entries(columns)) {
  for (const column of names) {
    const inline = baselineTables.get(table)?.includes('`' + column + '`')
    const added = init.includes(`add_col_if_absent('${table}', '${column}',`)
    if (!inline && !added) fail(`baseline is missing ${table}.${column}`)
    if (!upgrade.includes('`' + column + '`')) fail(`upgrade is missing ${table}.${column}`)
  }
}
for (const [table, index] of [
  ['model_instance', 'idx_model_instance_account'],
  ['runtime_run', 'idx_runtime_run_session_time'],
  ['knowledge_hit_log', 'idx_kb_source_time'],
]) {
  if (!baselineTables.get(table)?.includes('`' + index + '`')
      && !init.includes(`add_idx_if_absent('${table}', '${index}',`)) fail(`baseline is missing ${table}.${index}`)
}
for (const table of [
  'control_context_namespace', 'control_context_item', 'control_context_binding', 'control_context_evidence',
  'control_context_audit_event', 'control_context_memory_candidate', 'control_context_memory_outbox',
  'control_context_runtime_user_mapping', 'runtime_agent_eval_dataset', 'runtime_agent_eval_case',
  'runtime_agent_eval_run', 'runtime_agent_eval_case_result', 'knowledge_tag', 'knowledge_question',
]) {
  if (baselineTables.has(table) || upgradeTables.has(table)) fail(`retired table must not be recreated: ${table}`)
}
if (!upgrade.includes('2026-10-15') || !upgrade.includes('新库只执行 initV2.sql')) fail('missing migration baseline boundary')
if (!issues.length) console.log('[consolidated-upgrade-sql] PASS: 10 intact source sections, 11 baseline-matched tables, current columns and indexes')
else process.exitCode = 1
