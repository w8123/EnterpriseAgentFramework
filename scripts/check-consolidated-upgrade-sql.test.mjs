import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const script = 'scripts/check-consolidated-upgrade-sql.mjs'
const migration = 'sql/upgrade-20261019-consolidated.sql'

function fixture(t) {
  const temporary = mkdtempSync(path.join(os.tmpdir(), 'reachai-sql-check-'))
  assert.equal(path.dirname(temporary), path.resolve(os.tmpdir()))
  t.after(() => rmSync(temporary, { recursive: true, force: true }))
  for (const file of [script, migration, 'sql/initV2.sql']) {
    cpSync(path.join(root, file), path.join(temporary, file), { recursive: true })
  }
  return temporary
}
function run(directory) {
  return spawnSync(process.execPath, [script], { cwd: directory, encoding: 'utf8', windowsHide: true })
}
function edit(directory, file, mutate) {
  const target = path.join(directory, file)
  writeFileSync(target, mutate(readFileSync(target, 'utf8')), 'utf8')
}

test('intact merge accepts CRLF checkouts and subsequent increments', t => {
  const directory = fixture(t)
  edit(directory, migration, sql => sql.replace(/\r?\n/g, '\r\n'))
  writeFileSync(path.join(directory, 'sql/upgrade-20261019-next-change.sql'), 'SELECT 1;\n')
  writeFileSync(path.join(directory, 'sql/upgrade-20261020-next-change.sql'), 'SELECT 1;\n')
  const result = run(directory)
  assert.equal(result.status, 0, result.stderr)
})

test('editing source SQL without changing its manifest fails integrity verification', t => {
  const directory = fixture(t)
  edit(directory, migration, sql => sql.replace('DEFAULT 40', 'DEFAULT 41'))
  const result = run(directory)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /source SQL changed or was truncated: upgrade-20261017-agent-memory-policy/)
})

test('missing and reordered source sections are rejected', t => {
  const directory = fixture(t)
  edit(directory, migration, sql => sql.replace('-- Section 01/10:', '-- Section 02/10:'))
  const result = run(directory)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /source section 01 is missing or out of order/)
})

test('fresh database missing a migrated column is rejected', t => {
  const directory = fixture(t)
  edit(directory, 'sql/initV2.sql', sql => sql.replace(/`override_active`/g, '`removed_override`'))
  const result = run(directory)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /baseline is missing runtime_http_api_connection.override_active/)
})

test('baseline table drift and restored historical migrations are rejected', t => {
  const directory = fixture(t)
  edit(directory, 'sql/initV2.sql', sql => sql.replace("DEFAULT 'ACTIVE' COMMENT 'ACTIVE'", "DEFAULT 'INACTIVE' COMMENT 'ACTIVE'"))
  writeFileSync(path.join(directory, 'sql/upgrade-20261016-old-change.sql'), 'SELECT 1;\n')
  const result = run(directory)
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /retired or duplicate upgrade remains/)
  assert.match(result.stderr, /model_provider_account differs from initV2.sql/)
})
