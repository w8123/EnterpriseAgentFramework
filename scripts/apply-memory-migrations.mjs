#!/usr/bin/env node

import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { access, open, readdir, readFile, stat } from 'node:fs/promises'
import { constants as fsConstants } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { inspectMysqlJdbcUrl } from './check-mysql-transport-readiness.mjs'
import { REQUIRED_MIGRATIONS } from './check-personal-memory-production-readiness.mjs'

export const EXECUTE_CONFIRM = 'reach_ai'

const RESULT_SCHEMA = 'reachai-memory-migration-evidence-v1'
const PLAN_SCHEMA = 'reachai-memory-migration-plan-v1'
const JAVA_RUNNER = 'scripts/java/ReachAiMysqlMigrationRunner.java'
const MAX_CHILD_OUTPUT_BYTES = 2 * 1024 * 1024

function argument(name, argv = process.argv.slice(2)) {
  const exact = `--${name}`
  const prefix = `${exact}=`
  const value = argv.find(item => item === exact || item.startsWith(prefix))
  if (!value) return null
  return value === exact ? true : value.slice(prefix.length)
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function nonEmpty(value) {
  return typeof value === 'string' && value.trim() !== ''
}

function baseJavaEnvironment(environment) {
  const allowed = [
    'PATH', 'Path', 'PATHEXT', 'SystemRoot', 'SYSTEMROOT', 'WINDIR',
    'TEMP', 'TMP', 'HOME', 'USERPROFILE', 'LANG', 'LC_ALL',
    'JAVA_HOME', 'REACHAI_JAVA_HOME',
  ]
  return Object.fromEntries(allowed
    .filter(name => environment[name] != null)
    .map(name => [name, environment[name]]))
}

function migrationJavaEnvironment(environment) {
  const result = baseJavaEnvironment(environment)
  for (const name of [
    'AI_MYSQL_URL',
    'AI_MYSQL_USER',
    'AI_MYSQL_PASSWORD',
    'REACHAI_MYSQL_JAVA_TRUSTSTORE',
    'REACHAI_MYSQL_JAVA_TRUSTSTORE_PASSWORD',
    'REACHAI_MYSQL_JAVA_TRUSTSTORE_TYPE',
  ]) {
    if (environment[name] != null) result[name] = environment[name]
  }
  return result
}

function safeError(code, phase, extra = {}) {
  const error = new Error(code)
  error.safe = {
    code,
    phase,
    ...extra,
  }
  return error
}

export function validateExecutionGuards(environment, {
  evidenceFile,
  jdbcConfig,
  confirm,
  allowRemote = false,
} = {}) {
  const issues = []
  const add = (code, field) => issues.push({ code, field })
  if (confirm !== EXECUTE_CONFIRM) {
    add('EXECUTION_CONFIRMATION_REQUIRED', '--confirm=reach_ai')
  }
  if (jdbcConfig?.targetClass === 'NON_LOOPBACK' && allowRemote !== true) {
    add('REMOTE_EXECUTION_NOT_ALLOWED', '--allow-remote')
  }
  if (evidenceFile && !path.isAbsolute(String(evidenceFile))) {
    add('ABSOLUTE_EVIDENCE_FILE_REQUIRED', '--evidence')
  }
  if (!jdbcConfig?.databasePresent) add('JDBC_DATABASE_MISSING', 'AI_MYSQL_URL')
  if (jdbcConfig?.embeddedCredentials) {
    add('JDBC_URL_EMBEDDED_CREDENTIALS', 'AI_MYSQL_URL')
  }
  for (const name of ['AI_MYSQL_USER', 'AI_MYSQL_PASSWORD']) {
    if (!nonEmpty(environment[name])) add('DATABASE_CREDENTIAL_MISSING', name)
  }
  return {
    passed: issues.length === 0,
    issues,
  }
}

export async function repositoryMigrationPlan(rootDirectory) {
  const migrations = []
  for (const script of REQUIRED_MIGRATIONS) {
    const absolute = path.resolve(rootDirectory, script)
    const content = await readFile(absolute)
    migrations.push({
      script,
      absolute,
      checksumSha256: sha256(content),
      sizeBytes: content.length,
    })
  }
  return migrations
}

async function isRegularFile(file) {
  try {
    await access(file, fsConstants.R_OK)
    return true
  } catch {
    return false
  }
}

function versionPartsFromConnectorJar(file) {
  const match = path.basename(file).match(/^mysql-connector-j-(\d+)\.(\d+)\.(\d+)\.jar$/)
  return match ? match.slice(1).map(Number) : null
}

export async function resolveConnectorJar(environment = process.env) {
  if (nonEmpty(environment.REACHAI_MYSQL_CONNECTOR_JAR)) {
    const configured = path.resolve(environment.REACHAI_MYSQL_CONNECTOR_JAR)
    if (!await isRegularFile(configured)) {
      throw safeError('MYSQL_CONNECTOR_JAR_NOT_FOUND', 'TOOLCHAIN')
    }
    return configured
  }
  const repository = path.join(
    environment.USERPROFILE || os.homedir(),
    '.m2', 'repository', 'com', 'mysql', 'mysql-connector-j',
  )
  let entries
  try {
    entries = await readdir(repository, { recursive: true })
  } catch {
    throw safeError('MYSQL_CONNECTOR_JAR_NOT_FOUND', 'TOOLCHAIN')
  }
  const candidates = entries
    .map(entry => path.join(repository, entry))
    .map(file => ({ file, version: versionPartsFromConnectorJar(file) }))
    .filter(item => item.version)
    .sort((left, right) => {
      for (let index = 0; index < 3; index += 1) {
        if (left.version[index] !== right.version[index]) {
          return right.version[index] - left.version[index]
        }
      }
      return 0
    })
  if (candidates.length === 0 || !await isRegularFile(candidates[0].file)) {
    throw safeError('MYSQL_CONNECTOR_JAR_NOT_FOUND', 'TOOLCHAIN')
  }
  return candidates[0].file
}

async function capture(executable, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      cwd: options.cwd,
      env: options.env,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    let stdout = Buffer.alloc(0)
    let stderr = Buffer.alloc(0)
    const append = (current, chunk) => {
      const next = Buffer.concat([current, chunk])
      if (next.length > MAX_CHILD_OUTPUT_BYTES) {
        child.kill()
        reject(safeError('CHILD_OUTPUT_LIMIT_EXCEEDED', 'TOOLCHAIN'))
      }
      return next
    }
    child.stdout.on('data', chunk => { stdout = append(stdout, chunk) })
    child.stderr.on('data', chunk => { stderr = append(stderr, chunk) })
    child.once('error', () => reject(safeError('CHILD_PROCESS_START_FAILED', 'TOOLCHAIN')))
    child.once('close', code => resolve({
      code,
      stdout: stdout.toString('utf8'),
      stderr: stderr.toString('utf8'),
    }))
  })
}

export async function resolveJava(environment = process.env) {
  const executableName = process.platform === 'win32' ? 'java.exe' : 'java'
  const candidates = [
    environment.REACHAI_JAVA_HOME,
    environment.JAVA_HOME,
    process.platform === 'win32' ? 'C:\\Program Files\\Java\\jdk-17' : null,
  ].filter(nonEmpty).map(home => path.join(home, 'bin', executableName))
  candidates.push(executableName)
  const probeEnvironment = baseJavaEnvironment(environment)
  for (const candidate of candidates) {
    const result = await capture(candidate, ['-version'], { env: probeEnvironment }).catch(() => null)
    if (!result || result.code !== 0) continue
    const versionText = `${result.stdout}\n${result.stderr}`
    const match = versionText.match(/version\s+"(?:1\.)?(\d+)/i)
    if (match && Number(match[1]) >= 17) return candidate
  }
  throw safeError('JAVA_17_OR_NEWER_REQUIRED', 'TOOLCHAIN')
}

export async function reserveEvidenceTarget(file) {
  const absolute = path.resolve(file)
  if (!path.isAbsolute(file)) throw safeError('ABSOLUTE_EVIDENCE_FILE_REQUIRED', 'EVIDENCE')
  const parent = path.dirname(absolute)
  try {
    const parentInfo = await stat(parent)
    if (!parentInfo.isDirectory()) throw safeError('EVIDENCE_PARENT_NOT_FOUND', 'EVIDENCE')
    await access(parent, fsConstants.W_OK)
  } catch (error) {
    if (error?.safe) throw error
    throw safeError('EVIDENCE_PARENT_NOT_WRITABLE', 'EVIDENCE')
  }
  try {
    await access(absolute)
    throw safeError('EVIDENCE_FILE_ALREADY_EXISTS', 'EVIDENCE')
  } catch (error) {
    if (error?.safe) throw error
    if (error?.code !== 'ENOENT') throw safeError('EVIDENCE_FILE_NOT_WRITABLE', 'EVIDENCE')
  }
  let handle
  try {
    handle = await open(absolute, 'wx', 0o600)
    const reservation = {
      schema: RESULT_SCHEMA,
      mode: 'EXECUTE',
      executed: false,
      passed: false,
      status: 'RESERVED',
      reservedAt: new Date().toISOString(),
    }
    await handle.writeFile(`${JSON.stringify(reservation, null, 2)}\n`, 'utf8')
    await handle.sync()
    return { absolute, handle }
  } catch (error) {
    await handle?.close().catch(() => {})
    if (error?.code === 'EEXIST') throw safeError('EVIDENCE_FILE_ALREADY_EXISTS', 'EVIDENCE')
    throw safeError('EVIDENCE_FILE_NOT_WRITABLE', 'EVIDENCE')
  }
}

export async function finalizeEvidence(reservation, evidence) {
  const { handle } = reservation
  try {
    const content = Buffer.from(`${JSON.stringify(evidence, null, 2)}\n`, 'utf8')
    await handle.truncate(0)
    await handle.write(content, 0, content.length, 0)
    await handle.sync()
  } finally {
    await handle.close()
  }
}

function parseRunnerResult(output) {
  const lines = output.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  if (lines.length !== 1) throw safeError('JAVA_RUNNER_OUTPUT_INVALID', 'EXECUTE')
  let parsed
  try {
    parsed = JSON.parse(lines[0])
  } catch {
    throw safeError('JAVA_RUNNER_OUTPUT_INVALID', 'EXECUTE')
  }
  if (parsed?.schema !== 'reachai-memory-migration-execution-v1'
      || typeof parsed?.passed !== 'boolean') {
    throw safeError('JAVA_RUNNER_OUTPUT_INVALID', 'EXECUTE')
  }
  return parsed
}

async function validateMigrationParsing({ rootDirectory, migrations, environment, toolchain }) {
  const parseEnvironment = baseJavaEnvironment(environment)
  const result = await capture(toolchain.java, [
    '--class-path', toolchain.connectorJar,
    toolchain.javaRunner,
    '--parse-only',
    ...migrations.map(item => `--script=${item.absolute}`),
  ], { cwd: rootDirectory, env: parseEnvironment })
  const parsed = parseRunnerResult(result.stdout)
  if (result.code !== 0 || parsed.passed !== true || parsed.parseOnly !== true
      || parsed.credentialsUsed !== false
      || parsed.parsedScripts?.length !== migrations.length) {
    throw safeError('MIGRATION_SQL_PARSE_VALIDATION_FAILED', 'TOOLCHAIN')
  }
  for (let index = 0; index < migrations.length; index += 1) {
    if (parsed.parsedScripts[index]?.script !== path.basename(migrations[index].script)
        || !Number.isInteger(parsed.parsedScripts[index]?.statementCount)
        || parsed.parsedScripts[index].statementCount < 1) {
      throw safeError('MIGRATION_SQL_PARSE_VALIDATION_FAILED', 'TOOLCHAIN')
    }
  }
}

async function resolveExecutionToolchain({ rootDirectory, migrations, environment }) {
  const [java, connectorJar] = await Promise.all([
    resolveJava(environment),
    resolveConnectorJar(environment),
  ])
  const javaRunner = path.resolve(rootDirectory, JAVA_RUNNER)
  if (!await isRegularFile(javaRunner)) throw safeError('JAVA_RUNNER_NOT_FOUND', 'TOOLCHAIN')
  const toolchain = { java, connectorJar, javaRunner }
  await validateMigrationParsing({ rootDirectory, migrations, environment, toolchain })
  return toolchain
}

async function executeMigrations({ rootDirectory, migrations, environment, toolchain }) {
  const { java, connectorJar, javaRunner } = toolchain
  const args = [
    '--class-path', connectorJar,
    javaRunner,
    ...migrations.map(item => `--script=${item.absolute}`),
  ]
  const result = await capture(java, args, {
    cwd: rootDirectory,
    env: migrationJavaEnvironment(environment),
  })
  const runner = parseRunnerResult(result.stdout)
  if (result.code !== 0 && runner.passed) {
    throw safeError('JAVA_RUNNER_EXIT_STATUS_INCONSISTENT', 'EXECUTE')
  }
  return runner
}

async function preflightDatabase({ rootDirectory, migrations, environment, toolchain }) {
  const { java, connectorJar, javaRunner } = toolchain
  const result = await capture(java, [
    '--class-path', connectorJar,
    javaRunner,
    '--preflight-only',
    ...migrations.map(item => `--script=${item.absolute}`),
  ], {
    cwd: rootDirectory,
    env: migrationJavaEnvironment(environment),
  })
  const runner = parseRunnerResult(result.stdout)
  if (result.code !== 0 && runner.passed) {
    throw safeError('JAVA_RUNNER_EXIT_STATUS_INCONSISTENT', 'PREFLIGHT')
  }
  return runner
}

function publicPlan(mode, jdbcConfig, migrations) {
  return {
    schema: PLAN_SCHEMA,
    mode,
    executed: false,
    credentialsUsed: false,
    targetClass: jdbcConfig.targetClass,
    jdbcSslMode: jdbcConfig.sslMode,
    databasePresent: jdbcConfig.databasePresent,
    migrations: migrations.map(({ script, checksumSha256, sizeBytes }, index) => ({
      order: index + 1,
      script,
      checksumSha256,
      sizeBytes,
    })),
  }
}

function publicFailure(error, mode) {
  return {
    schema: RESULT_SCHEMA,
    mode,
    executed: false,
    passed: false,
    credentialsUsed: false,
    failure: error?.safe || { code: 'MIGRATION_RUNNER_FAILED', phase: 'RUNNER' },
  }
}

export async function runCli({
  argv = process.argv.slice(2),
  environment = process.env,
  rootDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'),
} = {}) {
  const execute = argument('execute', argv) === true
  const preflight = argument('preflight', argv) === true
  if (execute && preflight) throw safeError('MODE_CONFLICT', 'ARGUMENT')
  const mode = execute ? 'EXECUTE' : preflight ? 'PREFLIGHT' : 'DRY_RUN'
  const jdbcUrl = environment.AI_MYSQL_URL
  const jdbcConfig = inspectMysqlJdbcUrl(jdbcUrl)
  const migrations = await repositoryMigrationPlan(rootDirectory)
  if (mode === 'DRY_RUN') return publicPlan(mode, jdbcConfig, migrations)

  const toolchain = await resolveExecutionToolchain({ rootDirectory, migrations, environment })
  if (mode === 'PREFLIGHT') {
    const runner = await preflightDatabase({ rootDirectory, migrations, environment, toolchain })
    return {
      ...publicPlan(mode, jdbcConfig, migrations),
      credentialsUsed: runner.credentialsUsed === true,
      passed: runner.passed === true,
      server: runner.server,
      failure: runner.failure,
    }
  }

  const evidenceArgument = argument('evidence', argv)
  const evidenceFile = typeof evidenceArgument === 'string' ? evidenceArgument : null
  const guards = validateExecutionGuards(environment, {
    evidenceFile,
    jdbcConfig,
    confirm: argument('confirm', argv),
    allowRemote: argument('allow-remote', argv) === true,
  })
  if (!guards.passed) {
    throw safeError('EXECUTION_GUARDS_FAILED', 'AUTHORIZATION', { issues: guards.issues })
  }
  const reservation = evidenceFile ? await reserveEvidenceTarget(evidenceFile) : null
  let runner
  try {
    runner = await executeMigrations({ rootDirectory, migrations, environment, toolchain })
  } catch (error) {
    const failureEvidence = {
      schema: RESULT_SCHEMA,
      mode,
      executed: true,
      passed: false,
      credentialsUsed: null,
      executedAt: new Date().toISOString(),
      targetClass: jdbcConfig.targetClass,
      connection: {
        configuredSslMode: jdbcConfig.sslMode,
      },
      migrations: migrations.map(({ script, checksumSha256 }, index) => ({
        order: index + 1,
        script,
        checksumSha256,
        status: 'UNKNOWN_AFTER_RUNNER_FAILURE',
      })),
      failure: error?.safe || { code: 'JAVA_RUNNER_FAILED', phase: 'EXECUTE' },
    }
    if (reservation) await finalizeEvidence(reservation, failureEvidence)
    return { ...failureEvidence, evidenceFile: reservation?.absolute || null }
  }
  const executedAt = new Date().toISOString()
  const migrationByName = new Map(runner.migrations.map(item => [item.script, item]))
  const evidence = {
    schema: RESULT_SCHEMA,
    mode,
    executed: true,
    passed: runner.passed === true,
    credentialsUsed: runner.credentialsUsed === true,
    executedAt,
    targetClass: jdbcConfig.targetClass,
    connection: {
      configuredSslMode: jdbcConfig.sslMode,
    },
    server: runner.server,
    migrations: migrations.map(({ script, checksumSha256 }, index) => {
      const runnerMigration = migrationByName.get(path.basename(script))
      const failedHere = runner.failure?.script === path.basename(script)
      const { script: _runnerScript, ...runnerDetails } = runnerMigration || (failedHere
        ? { status: 'FAILED', failure: runner.failure }
        : { status: 'NOT_RUN' })
      return {
        order: index + 1,
        script,
        checksumSha256,
        ...runnerDetails,
      }
    }),
    durationMs: runner.durationMs,
    failure: runner.failure,
    evidencePayloadSha256: null,
  }
  const canonicalForHash = JSON.stringify({ ...evidence, evidencePayloadSha256: null })
  evidence.evidencePayloadSha256 = sha256(canonicalForHash)
  if (reservation) await finalizeEvidence(reservation, evidence)
  return {
    ...evidence,
    evidenceFile: reservation?.absolute || null,
  }
}

async function main() {
  const mode = argument('execute') === true ? 'EXECUTE'
    : argument('preflight') === true ? 'PREFLIGHT' : 'DRY_RUN'
  try {
    const result = await runCli()
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    if (result.passed === false) process.exitCode = 1
  } catch (error) {
    const result = publicFailure(error, mode)
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    process.exitCode = 1
  }
}

if (process.argv[1]
    && fileURLToPath(import.meta.url) === fileURLToPath(new URL(`file:///${process.argv[1].replaceAll('\\', '/')}`))) {
  main()
}
