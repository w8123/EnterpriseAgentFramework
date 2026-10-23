#!/usr/bin/env node
/**
 * Static checks for the current Model Center SQL baseline. Recent migration
 * definitions are compared with the baseline by check-consolidated-upgrade-sql.mjs.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const initPath = join(root, 'sql', 'initV2.sql')
const init = readFileSync(initPath, 'utf8')

let failed = 0

function fail(message) {
  console.error(`FAIL: ${message}`)
  failed += 1
}

function pass(message) {
  console.log(`ok: ${message}`)
}

function createTable(sql, table, label) {
  const pattern = new RegExp(
    'CREATE TABLE IF NOT EXISTS `' + table
      + '` \\([\\s\\S]*?\\) ENGINE=InnoDB[^;]+;',
    'i',
  )
  const match = sql.match(pattern)
  if (!match) {
    fail(`${label}: missing CREATE TABLE for ${table}`)
    return ''
  }
  return match[0].replace(/\s+/g, ' ').trim()
}

function expect(sql, pattern, message) {
  if (!pattern.test(sql)) fail(message)
  else pass(message)
}

const modelTables = [
  'model_catalog_source',
  'model_catalog_setting',
  'model_catalog_sync_run',
  'model_catalog_snapshot',
  'model_catalog_change',
  'model_provider_account',
]

for (const table of modelTables) {
  if (createTable(init, table, 'initV2.sql')) pass(`baseline contains ${table}`)
}

const templateIds = [...init.matchAll(/\('(tpl-[^']+)'/g)].map(match => match[1])
const uniqueTemplateIds = new Set(templateIds)
if (uniqueTemplateIds.size !== 31) {
  fail(`initV2.sql has ${uniqueTemplateIds.size} fixed tpl-* templates, expected 31`)
} else {
  pass('initV2.sql has 31 fixed templates')
}

expect(
  init,
  /`project_scope_key`\s+VARCHAR\(64\)[\s\S]{0,80}?AS\s*\(IFNULL\(`project_code`,\s*''\)\)\s*STORED/i,
  'model_instance uses a STORED generated project_scope_key',
)
expect(
  init,
  /UNIQUE KEY\s+`uk_model_instance_name_scope`\s*\(`name`,\s*`project_scope_key`\)/i,
  'model_instance keeps its scoped unique identity',
)

for (const column of [
  'lifecycle_status',
  'source_url',
  'last_verified_at',
  'recommendation_status',
  'recommendation_tier',
  'recommendation_reason',
]) {
  expect(init, new RegExp('`' + column + '`', 'i'), `initV2.sql contains model_template.${column}`)
}

for (const [label, sql] of [
  ['initV2.sql', init],
]) {
  expect(
    sql,
    /`auto_sync_enabled`\s+TINYINT\(1\)\s+NOT NULL DEFAULT 0/i,
    `${label} defaults automatic catalog synchronization off`,
  )
  expect(
    sql,
    /`trigger_type`[^\n]*STARTUP\s*\/\s*DAILY\s*\/\s*MANUAL/i,
    `${label} records MANUAL synchronization as a first-class trigger`,
  )
  const settingSeed = sql.match(
    /INSERT INTO\s+`model_catalog_setting`[\s\S]*?ON DUPLICATE KEY UPDATE[\s\S]*?;/i,
  )?.[0] ?? ''
  if (!settingSeed || /ON DUPLICATE KEY UPDATE[\s\S]*?auto_sync_enabled/i.test(settingSeed)) {
    fail(`${label} must preserve an existing automatic-sync setting on rerun`)
  } else {
    pass(`${label} preserves an existing automatic-sync setting on rerun`)
  }
}

const executableCatalog = init
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/--.*$/gm, '')
if (/\bIS_GENERATED\b/i.test(executableCatalog)) {
  fail('baseline Model Catalog SQL contains MySQL-incompatible IS_GENERATED executable SQL')
} else {
  pass('baseline Model Catalog SQL avoids IS_GENERATED executable SQL')
}

expect(createTable(init, 'model_instance', 'initV2.sql'), /`provider_account_id`\s+VARCHAR\(64\)\s+DEFAULT NULL/i,
  'model instances retain the optional provider account reference')
expect(createTable(init, 'model_catalog_setting', 'initV2.sql'), /`analyzer_model_instance_id`\s+VARCHAR\(64\)\s+DEFAULT NULL/i,
  'catalog settings retain the optional analyzer model selection')

if (failed > 0) {
  console.error(`\nmodel center SQL check failed: ${failed} issue(s)`)
  process.exit(1)
}

console.log('\nmodel center SQL check passed')
