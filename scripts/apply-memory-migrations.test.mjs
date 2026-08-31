import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  EXECUTE_CONFIRM,
  finalizeEvidence,
  repositoryMigrationPlan,
  reserveEvidenceTarget,
  runCli,
  validateExecutionGuards,
} from './apply-memory-migrations.mjs'
import { inspectMysqlJdbcUrl } from './check-mysql-transport-readiness.mjs'
import { REQUIRED_MIGRATIONS } from './check-personal-memory-production-readiness.mjs'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

function passingEnvironment() {
  return {
    AI_MYSQL_URL: 'jdbc:mysql://db.example.test/reach_ai',
    AI_MYSQL_USER: 'migration-user',
    AI_MYSQL_PASSWORD: 'secret-value-that-must-not-be-rendered',
  }
}

test('repository migration plan uses the single current release upgrade and SHA-256 checksum', async () => {
  const plan = await repositoryMigrationPlan(ROOT)
  assert.deepEqual(plan.map(item => item.script), REQUIRED_MIGRATIONS)
  assert.ok(plan.every(item => /^[a-f0-9]{64}$/.test(item.checksumSha256)))
  assert.ok(plan.every(item => item.sizeBytes > 0))
})

test('execution guards accept an explicitly confirmed remote development target', () => {
  const environment = passingEnvironment()
  const jdbcConfig = inspectMysqlJdbcUrl(environment.AI_MYSQL_URL)
  const result = validateExecutionGuards(environment, {
    evidenceFile: path.resolve('approved-change', 'memory-migration-evidence.json'),
    jdbcConfig,
    confirm: EXECUTE_CONFIRM,
    allowRemote: true,
  })
  assert.equal(result.passed, true)
  assert.deepEqual(result.issues, [])
})

test('execution guards require an explicit confirmation and remote opt-in', () => {
  const environment = passingEnvironment()
  const jdbcConfig = inspectMysqlJdbcUrl(environment.AI_MYSQL_URL)
  const result = validateExecutionGuards(environment, {
    evidenceFile: 'relative-evidence.json',
    jdbcConfig,
    confirm: 'yes',
  })
  assert.equal(result.passed, false)
  const codes = new Set(result.issues.map(item => item.code))
  for (const code of [
    'EXECUTION_CONFIRMATION_REQUIRED',
    'REMOTE_EXECUTION_NOT_ALLOWED',
    'ABSOLUTE_EVIDENCE_FILE_REQUIRED',
  ]) {
    assert.ok(codes.has(code), code)
  }
})

test('loopback development execution does not require remote opt-in or TLS configuration', () => {
  const environment = passingEnvironment()
  environment.AI_MYSQL_URL = 'jdbc:mysql://localhost/reach_ai?sslMode=DISABLED'
  const jdbcConfig = inspectMysqlJdbcUrl(environment.AI_MYSQL_URL)
  const result = validateExecutionGuards(environment, {
    jdbcConfig,
    confirm: EXECUTE_CONFIRM,
  })
  assert.equal(result.passed, true)
  assert.deepEqual(result.issues, [])
})

test('default CLI mode is a credential-free dry-run and never renders secrets', async () => {
  const environment = passingEnvironment()
  const result = await runCli({ argv: [], environment, rootDirectory: ROOT })
  assert.equal(result.mode, 'DRY_RUN')
  assert.equal(result.executed, false)
  assert.equal(result.credentialsUsed, false)
  assert.equal(result.migrations.length, REQUIRED_MIGRATIONS.length)
  const rendered = JSON.stringify(result)
  assert.equal(rendered.includes(environment.AI_MYSQL_USER), false)
  assert.equal(rendered.includes(environment.AI_MYSQL_PASSWORD), false)
  assert.equal(rendered.includes('db.example.test'), false)
})

test('evidence file is reserved exclusively and finalized from byte zero', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'reachai-memory-evidence-'))
  const evidenceFile = path.join(directory, 'migration.json')
  try {
    const reservation = await reserveEvidenceTarget(evidenceFile)
    const reserved = JSON.parse(await readFile(evidenceFile, 'utf8'))
    assert.equal(reserved.status, 'RESERVED')

    const finalEvidence = { schema: 'test-evidence-v1', passed: true }
    await finalizeEvidence(reservation, finalEvidence)
    assert.deepEqual(JSON.parse(await readFile(evidenceFile, 'utf8')), finalEvidence)

    await assert.rejects(
      reserveEvidenceTarget(evidenceFile),
      error => error?.safe?.code === 'EVIDENCE_FILE_ALREADY_EXISTS',
    )
  } finally {
    await rm(directory, { recursive: true, force: true })
  }
})
