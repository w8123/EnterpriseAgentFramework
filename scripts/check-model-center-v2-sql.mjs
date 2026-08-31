#!/usr/bin/env node
/**
 * Static checks for the current Model Center SQL baseline and the Model Catalog
 * section of the single unpublished-release upgrade.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const initPath = join(root, 'sql', 'initV2.sql')
const upgradePath = join(root, 'sql', 'upgrade-20260830-platform-consolidated.sql')
const init = readFileSync(initPath, 'utf8')
const upgrade = readFileSync(upgradePath, 'utf8')

let failed = 0

function fail(message) {
  console.error(`FAIL: ${message}`)
  failed += 1
}

function pass(message) {
  console.log(`ok: ${message}`)
}

function section(sql, number) {
  const marker = `-- Section ${String(number).padStart(2, '0')}/14:`
  const start = sql.indexOf(marker)
  if (start < 0) {
    fail(`consolidated upgrade is missing ${marker}`)
    return ''
  }
  const next = sql.indexOf('-- Section ', start + marker.length)
  return sql.slice(start, next < 0 ? sql.length : next)
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

const catalog = section(upgrade, 10)
const modelTables = [
  'model_catalog_source',
  'model_catalog_setting',
  'model_catalog_sync_run',
  'model_catalog_snapshot',
  'model_catalog_change',
]

for (const table of modelTables) {
  const baseline = createTable(init, table, 'initV2.sql')
  const migration = createTable(catalog, table, 'consolidated upgrade Section 10')
  if (baseline && migration && baseline !== migration) {
    fail(`${table} differs between initV2.sql and consolidated upgrade Section 10`)
  } else if (baseline && migration) {
    pass(`${table} matches between baseline and current upgrade`)
  }
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
  expect(
    catalog,
    new RegExp("add_col_if_absent\\('model_template',\\s*'" + column + "'", 'i'),
    `consolidated upgrade adds model_template.${column} idempotently`,
  )
}

for (const [label, sql] of [
  ['initV2.sql', init],
  ['consolidated upgrade Section 10', catalog],
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

const executableCatalog = catalog
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/--.*$/gm, '')
if (/\bIS_GENERATED\b/i.test(executableCatalog)) {
  fail('consolidated Model Catalog SQL contains MySQL-incompatible IS_GENERATED executable SQL')
} else {
  pass('consolidated Model Catalog SQL avoids IS_GENERATED executable SQL')
}

const section09 = upgrade.indexOf('-- Section 09/14:')
const section10 = upgrade.indexOf('-- Section 10/14:')
const section11 = upgrade.indexOf('-- Section 11/14:')
if (section09 < 0 || section10 < section09 || section11 < section10) {
  fail('Model Catalog section must remain between Runtime checkpoint and authorization P1')
} else {
  pass('Model Catalog section order is stable')
}

if (failed > 0) {
  console.error(`\nmodel center SQL check failed: ${failed} issue(s)`)
  process.exit(1)
}

console.log('\nmodel center SQL check passed')
