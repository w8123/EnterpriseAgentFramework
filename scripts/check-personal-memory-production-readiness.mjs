#!/usr/bin/env node

import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const REQUIRED_MIGRATIONS = [
  'sql/upgrade-20260830-platform-consolidated.sql',
]

export const REQUIRED_ERASURE_DOMAINS = [
  'CONTROL_PERSONAL_MEMORY',
  'CONTROL_CANDIDATE_OUTBOX',
  'KNOWLEDGE_PERSONAL_PROJECTION',
  'RUNTIME_SESSION_STATE',
  'RUNTIME_CONVERSATION_LEDGER',
  'RUNOPS_TRACE_INTERACTION',
  'BUSINESS_INDEX',
  'BUSINESS_SOURCE_SYSTEM',
  'BACKUP_EXPIRY_AND_RESTORE',
]

const SIMPLE_GATES = [
  'sessionRetention',
  'businessIngress',
  'legalHold',
  'concurrentErase',
  'auditRedaction',
  'alerting',
  'sseRegression',
  'dependencyFailureDrill',
]

function finite(value) {
  return typeof value === 'number' && Number.isFinite(value)
}

function nonEmpty(value) {
  return typeof value === 'string' && value.trim() !== ''
}

function timestamp(value) {
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

function recent(value, nowMs, maxAgeDays) {
  const parsed = timestamp(value)
  if (parsed == null || parsed > nowMs + 5 * 60_000) return false
  return nowMs - parsed <= maxAgeDays * 86_400_000
}

function issueCollector() {
  const issues = []
  return {
    issues,
    add(code, pathName, message) {
      issues.push({ code, path: pathName, message })
    },
  }
}

function requireEvidence(gate, pathName, collector, nowMs, maxAgeDays) {
  if (gate?.status !== 'PASSED') {
    collector.add('GATE_NOT_PASSED', `${pathName}.status`, 'gate status must be PASSED')
  }
  if (!nonEmpty(gate?.evidenceRef)) {
    collector.add('EVIDENCE_REF_REQUIRED', `${pathName}.evidenceRef`, 'immutable evidence reference is required')
  }
  if (!recent(gate?.observedAt, nowMs, maxAgeDays)) {
    collector.add('EVIDENCE_STALE_OR_INVALID', `${pathName}.observedAt`,
      `evidence must be valid and no older than ${maxAgeDays} days`)
  }
}

export function evaluateProductionEvidence(evidence, {
  expectedMigrationChecksums = {},
  now = new Date(),
  maxEvidenceAgeDays = 90,
} = {}) {
  const collector = issueCollector()
  const nowMs = now instanceof Date ? now.getTime() : new Date(now).getTime()
  const maxAge = Number(maxEvidenceAgeDays)
  if (!Number.isFinite(nowMs)) throw new Error('now must be a valid date')
  if (!Number.isInteger(maxAge) || maxAge < 1 || maxAge > 365) {
    throw new Error('maxEvidenceAgeDays must be an integer between 1 and 365')
  }
  if (evidence?.schema !== 'reachai-personal-memory-production-evidence-v1') {
    collector.add('SCHEMA_MISMATCH', 'schema', 'unsupported production evidence schema')
  }
  if (!nonEmpty(evidence?.environment?.name)) {
    collector.add('ENVIRONMENT_NAME_REQUIRED', 'environment.name', 'environment name is required')
  }
  if (!['staging', 'production'].includes(evidence?.environment?.classification)) {
    collector.add('ENVIRONMENT_NOT_PROMOTABLE', 'environment.classification',
      'only staging or production evidence can authorize production promotion')
  }
  if (!nonEmpty(evidence?.environment?.changeId)) {
    collector.add('CHANGE_ID_REQUIRED', 'environment.changeId', 'approved change identifier is required')
  }

  const migrations = Array.isArray(evidence?.migrations) ? evidence.migrations : []
  for (const script of REQUIRED_MIGRATIONS) {
    const migration = migrations.find(item => item?.script === script)
    const migrationPath = `migrations[${script}]`
    if (!migration) {
      collector.add('MIGRATION_EVIDENCE_MISSING', migrationPath, 'required migration evidence is missing')
      continue
    }
    if (migration.status !== 'PASSED') {
      collector.add('MIGRATION_NOT_PASSED', `${migrationPath}.status`, 'migration status must be PASSED')
    }
    if (!/^[a-f0-9]{64}$/i.test(String(migration.checksumSha256 || ''))) {
      collector.add('MIGRATION_CHECKSUM_INVALID', `${migrationPath}.checksumSha256`,
        'migration checksum must be a SHA-256 hex digest')
    } else if (expectedMigrationChecksums[script]
        && migration.checksumSha256.toLowerCase() !== expectedMigrationChecksums[script].toLowerCase()) {
      collector.add('MIGRATION_CHECKSUM_MISMATCH', `${migrationPath}.checksumSha256`,
        'executed migration does not match the current repository file')
    }
    if (timestamp(migration.executedAt) == null) {
      collector.add('MIGRATION_TIMESTAMP_INVALID', `${migrationPath}.executedAt`,
        'migration execution timestamp is required')
    }
    if (!nonEmpty(migration.evidenceRef)) {
      collector.add('EVIDENCE_REF_REQUIRED', `${migrationPath}.evidenceRef`,
        'migration evidence reference is required')
    }
  }

  const gates = evidence?.gates || {}
  for (const name of SIMPLE_GATES) {
    requireEvidence(gates[name], `gates.${name}`, collector, nowMs, maxAge)
  }

  const semantic = gates.semanticProjection || {}
  requireEvidence(semantic, 'gates.semanticProjection', collector, nowMs, maxAge)
  const semanticThresholds = {
    minReadyRatio: 0.99,
    maxDeadCount: 0,
    maxOldestPendingSeconds: 300,
  }

  const outbox = gates.outboxProjection || {}
  requireEvidence(outbox, 'gates.outboxProjection', collector, nowMs, maxAge)
  if (outbox.snapshotFresh !== true) {
    collector.add('OUTBOX_SNAPSHOT_STALE', 'gates.outboxProjection.snapshotFresh',
      'personal-memory outbox aggregate snapshot must be fresh')
  }
  if (!Number.isInteger(outbox.backlogCount) || outbox.backlogCount < 0) {
    collector.add('OUTBOX_BACKLOG_COUNT_INVALID', 'gates.outboxProjection.backlogCount',
      'outbox backlog count must be a non-negative integer')
  }
  if (!Number.isInteger(outbox.deadCount) || outbox.deadCount !== 0) {
    collector.add('OUTBOX_DEAD_LETTERS_PRESENT', 'gates.outboxProjection.deadCount',
      'personal-memory outbox must contain no dead letters')
  }
  if (!finite(outbox.oldestUnpublishedSeconds) || outbox.oldestUnpublishedSeconds < 0
      || outbox.oldestUnpublishedSeconds > 300) {
    collector.add('OUTBOX_BACKLOG_AGE_EXCEEDED',
      'gates.outboxProjection.oldestUnpublishedSeconds',
      'oldest unpublished personal-memory event must not exceed 300 seconds')
  }
  if (!['HYBRID', 'VECTOR'].includes(semantic.mode)) {
    collector.add('SEMANTIC_MODE_NOT_ENABLED', 'gates.semanticProjection.mode',
      'production promotion requires HYBRID or VECTOR Knowledge ranking')
  }
  if (!nonEmpty(semantic.modelInstanceEvidenceRef)) {
    collector.add('MODEL_INSTANCE_EVIDENCE_REQUIRED',
      'gates.semanticProjection.modelInstanceEvidenceRef',
      'versioned embedding model instance evidence is required')
  }
  if (semantic.snapshotFresh !== true) {
    collector.add('SEMANTIC_SNAPSHOT_STALE', 'gates.semanticProjection.snapshotFresh',
      'semantic projection metrics snapshot must be fresh')
  }
  if (!Number.isInteger(semantic.activeCount) || semantic.activeCount < 1) {
    collector.add('SEMANTIC_ACTIVE_CANARY_REQUIRED', 'gates.semanticProjection.activeCount',
      'at least one active canary projection is required')
  }
  if (!Number.isInteger(semantic.readyCount) || semantic.readyCount < 0
      || (Number.isInteger(semantic.activeCount) && semantic.readyCount > semantic.activeCount)) {
    collector.add('SEMANTIC_READY_COUNT_INVALID', 'gates.semanticProjection.readyCount',
      'ready count must be a non-negative integer not greater than active count')
  }
  if (!finite(semantic.readyRatio) || semantic.readyRatio < semanticThresholds.minReadyRatio
      || semantic.readyRatio > 1) {
    collector.add('SEMANTIC_READY_RATIO_BELOW_THRESHOLD',
      'gates.semanticProjection.readyRatio',
      `ready ratio must be between ${semanticThresholds.minReadyRatio} and 1`)
  }
  if (Number.isInteger(semantic.activeCount) && semantic.activeCount > 0
      && Number.isInteger(semantic.readyCount) && semantic.readyCount >= 0
      && finite(semantic.readyRatio)
      && Math.abs(semantic.readyCount / semantic.activeCount - semantic.readyRatio) > 1e-9) {
    collector.add('SEMANTIC_READY_RATIO_INCONSISTENT', 'gates.semanticProjection.readyRatio',
      'ready ratio must equal readyCount divided by activeCount')
  }
  if (!Number.isInteger(semantic.deadCount) || semantic.deadCount < 0
      || semantic.deadCount > semanticThresholds.maxDeadCount) {
    collector.add('SEMANTIC_DEAD_COUNT_EXCEEDED', 'gates.semanticProjection.deadCount',
      `dead count must not exceed ${semanticThresholds.maxDeadCount}`)
  }
  if (!finite(semantic.oldestPendingSeconds) || semantic.oldestPendingSeconds < 0
      || semantic.oldestPendingSeconds > semanticThresholds.maxOldestPendingSeconds) {
    collector.add('SEMANTIC_PENDING_AGE_EXCEEDED',
      'gates.semanticProjection.oldestPendingSeconds',
      `oldest pending age must not exceed ${semanticThresholds.maxOldestPendingSeconds} seconds`)
  }
  if (!Number.isInteger(semantic.unsafeDeletedVectorCount)
      || semantic.unsafeDeletedVectorCount !== 0) {
    collector.add('SEMANTIC_DELETED_VECTOR_RESIDUE',
      'gates.semanticProjection.unsafeDeletedVectorCount',
      'deleted or inactive projections must retain no embedding task or vector state')
  }
  for (const field of [
    'modelSwitchDrillStatus',
    'providerFailureFallbackStatus',
    'deletionVectorScrubStatus',
  ]) {
    if (semantic[field] !== 'PASSED') {
      collector.add('SEMANTIC_DRILL_NOT_PASSED', `gates.semanticProjection.${field}`,
        `${field} must be PASSED`)
    }
  }

  const shadow = gates.shadow || {}
  requireEvidence(shadow, 'gates.shadow', collector, nowMs, maxAge)
  const shadowThresholds = {
    minSamples: evidence?.thresholds?.shadow?.minSamples ?? 100,
    minCanonicalCoverage: evidence?.thresholds?.shadow?.minCanonicalCoverage ?? 0.8,
    minProjectedPrecision: evidence?.thresholds?.shadow?.minProjectedPrecision ?? 0.8,
    maxInvalidIdRatio: evidence?.thresholds?.shadow?.maxInvalidIdRatio ?? 0.01,
    maxErrorRatio: evidence?.thresholds?.shadow?.maxErrorRatio ?? 0.01,
  }
  if (!finite(shadow.sampleCount) || shadow.sampleCount < shadowThresholds.minSamples) {
    collector.add('SHADOW_SAMPLES_INSUFFICIENT', 'gates.shadow.sampleCount', 'shadow sample count is below threshold')
  }
  if (!finite(shadow.canonicalCoverage) || shadow.canonicalCoverage < shadowThresholds.minCanonicalCoverage) {
    collector.add('SHADOW_COVERAGE_LOW', 'gates.shadow.canonicalCoverage', 'canonical coverage is below threshold')
  }
  if (!finite(shadow.projectedPrecision) || shadow.projectedPrecision < shadowThresholds.minProjectedPrecision) {
    collector.add('SHADOW_PRECISION_LOW', 'gates.shadow.projectedPrecision', 'projected precision is below threshold')
  }
  if (!finite(shadow.invalidIdRatio) || shadow.invalidIdRatio > shadowThresholds.maxInvalidIdRatio) {
    collector.add('SHADOW_INVALID_ID_RATIO_HIGH', 'gates.shadow.invalidIdRatio', 'invalid id ratio exceeds threshold')
  }
  if (!finite(shadow.errorRatio) || shadow.errorRatio > shadowThresholds.maxErrorRatio) {
    collector.add('SHADOW_ERROR_RATIO_HIGH', 'gates.shadow.errorRatio', 'shadow query error ratio exceeds threshold')
  }

  const load = gates.load || {}
  requireEvidence(load, 'gates.load', collector, nowMs, maxAge)
  const loadThresholds = {
    minRequestCount: evidence?.thresholds?.load?.minRequestCount ?? 200,
    maxErrorRatio: evidence?.thresholds?.load?.maxErrorRatio ?? 0.01,
    maxQueryP95Ms: evidence?.thresholds?.load?.maxQueryP95Ms ?? 750,
  }
  if (!finite(load.actorCount) || load.actorCount < 2) {
    collector.add('LOAD_ACTOR_COUNT_LOW', 'gates.load.actorCount', 'at least two independent actors are required')
  }
  if (!finite(load.requestCount) || load.requestCount < loadThresholds.minRequestCount) {
    collector.add('LOAD_REQUEST_COUNT_LOW', 'gates.load.requestCount', 'load request count is below threshold')
  }
  if (!finite(load.errorRatio) || load.errorRatio > loadThresholds.maxErrorRatio) {
    collector.add('LOAD_ERROR_RATIO_HIGH', 'gates.load.errorRatio', 'load error ratio exceeds threshold')
  }
  if (!finite(load.queryP95Ms) || load.queryP95Ms > loadThresholds.maxQueryP95Ms) {
    collector.add('LOAD_QUERY_P95_HIGH', 'gates.load.queryP95Ms', 'query p95 exceeds threshold')
  }
  for (const zeroField of ['crossOwnerLeaks', 'deletedItemLeaks', 'cleanupFailures']) {
    if (load[zeroField] !== 0) {
      collector.add('LOAD_SAFETY_VIOLATION', `gates.load.${zeroField}`, `${zeroField} must be zero`)
    }
  }

  const transport = gates.transport || {}
  requireEvidence(transport, 'gates.transport', collector, nowMs, maxAge)
  if (!['DIRECT_TLS', 'MTLS_MESH'].includes(transport.mode)) {
    collector.add('TRANSPORT_MODE_UNSAFE', 'gates.transport.mode', 'transport mode must be DIRECT_TLS or MTLS_MESH')
  }
  if (transport.plaintextProbePassed !== true) {
    collector.add('PLAINTEXT_PROBE_REQUIRED', 'gates.transport.plaintextProbePassed',
      'a probe proving plaintext rejection is required')
  }

  const databaseTransport = gates.databaseTransport || {}
  requireEvidence(databaseTransport, 'gates.databaseTransport', collector, nowMs, maxAge)
  if (databaseTransport.mode !== 'VERIFY_IDENTITY') {
    collector.add('DATABASE_TLS_MODE_UNSAFE', 'gates.databaseTransport.mode',
      'MySQL transport mode must verify both the certificate chain and target identity')
  }
  if (databaseTransport.preAuthenticationProbePassed !== true) {
    collector.add('DATABASE_TLS_IDENTITY_PROBE_REQUIRED',
      'gates.databaseTransport.preAuthenticationProbePassed',
      'a credential-free MySQL TLS identity probe must pass')
  }
  if (databaseTransport.credentialsUsed !== false) {
    collector.add('DATABASE_TLS_PROBE_USED_CREDENTIALS',
      'gates.databaseTransport.credentialsUsed',
      'the transport probe must not transmit database credentials')
  }

  const encryption = gates.encryption || {}
  requireEvidence(encryption, 'gates.encryption', collector, nowMs, maxAge)
  for (const field of ['mysqlAtRest', 'redisAtRest', 'backupAtRest']) {
    if (encryption[field] !== true) {
      collector.add('AT_REST_ENCRYPTION_REQUIRED', `gates.encryption.${field}`, `${field} must be true`)
    }
  }
  if (encryption.keyRotationStatus !== 'PASSED') {
    collector.add('KEY_ROTATION_NOT_PASSED', 'gates.encryption.keyRotationStatus',
      'key rotation drill must be PASSED')
  }

  const backup = gates.backupRestore || {}
  requireEvidence(backup, 'gates.backupRestore', collector, nowMs, maxAge)
  if (backup.policyApproved !== true) {
    collector.add('BACKUP_POLICY_NOT_APPROVED', 'gates.backupRestore.policyApproved',
      'backup retention policy must be approved')
  }
  if (!Number.isInteger(backup.maxRetentionDays) || backup.maxRetentionDays < 1
      || backup.maxRetentionDays > 3650) {
    collector.add('BACKUP_RETENTION_INVALID', 'gates.backupRestore.maxRetentionDays',
      'backup retention must be between 1 and 3650 days')
  }
  if (backup.restoreDrillStatus !== 'PASSED') {
    collector.add('RESTORE_DRILL_NOT_PASSED', 'gates.backupRestore.restoreDrillStatus',
      'restore drill must be PASSED')
  }
  if (backup.deletionReconciliationStatus !== 'PASSED') {
    collector.add('RESTORE_DELETION_RECONCILIATION_NOT_PASSED',
      'gates.backupRestore.deletionReconciliationStatus',
      'restored data must be caught up past the erasure cutoff and revalidated before serving traffic')
  }
  for (const field of ['rpoMinutes', 'rtoMinutes']) {
    if (!finite(backup[field]) || backup[field] < 0) {
      collector.add('RECOVERY_OBJECTIVE_INVALID', `gates.backupRestore.${field}`, `${field} must be non-negative`)
    }
  }
  if (!nonEmpty(backup.expiryEvidenceRef)) {
    collector.add('BACKUP_EXPIRY_EVIDENCE_REQUIRED', 'gates.backupRestore.expiryEvidenceRef',
      'backup expiry evidence reference is required')
  }

  const erasureOrchestration = gates.erasureOrchestration || {}
  requireEvidence(erasureOrchestration, 'gates.erasureOrchestration', collector, nowMs, maxAge)
  const erasureResult = erasureOrchestration.result || {}
  if (erasureResult.schema !== 'reachai-memory-erasure-e2e-result-v1') {
    collector.add('ERASURE_E2E_SCHEMA_MISMATCH', 'gates.erasureOrchestration.result.schema',
      'cross-domain erasure evidence must be produced by the versioned E2E verifier')
  }
  if (erasureResult.executed !== true || erasureResult.passed !== true) {
    collector.add('ERASURE_E2E_NOT_PASSED', 'gates.erasureOrchestration.result.passed',
      'cross-domain erasure E2E must execute and pass')
  }
  if (erasureResult.targetClass !== 'REMOTE') {
    collector.add('ERASURE_E2E_NOT_REMOTE', 'gates.erasureOrchestration.result.targetClass',
      'production evidence must come from an approved remote environment')
  }
  if (!['COMPLETED', 'COMPLETED_WITH_RETENTION'].includes(erasureResult.finalStatus)
      || erasureResult.finalStatus !== erasureResult.expectedStatus) {
    collector.add('ERASURE_E2E_FINAL_STATUS_INVALID',
      'gates.erasureOrchestration.result.finalStatus',
      'erasure E2E must finish in its expected completed status')
  }
  if (erasureResult.dataPlaneExercised !== true
      || !Number.isSafeInteger(erasureResult.dataPlaneAffectedCount)
      || erasureResult.dataPlaneAffectedCount < 1) {
    collector.add('ERASURE_E2E_DATA_PLANE_NOT_EXERCISED',
      'gates.erasureOrchestration.result.dataPlaneExercised',
      'an empty-target control-plane smoke cannot authorize production promotion')
  }
  for (const field of [
    'domainInventoryVerified',
    'identityLeakCheckPassed',
    'createReplayVerified',
    'evidenceReplayVerified',
  ]) {
    if (erasureResult[field] !== true) {
      collector.add('ERASURE_E2E_INVARIANT_NOT_VERIFIED',
        `gates.erasureOrchestration.result.${field}`, `${field} must be true`)
    }
  }
  if (!/^[a-f0-9]{64}$/i.test(String(erasureResult.runtimeUserHash || ''))) {
    collector.add('ERASURE_E2E_OWNER_HASH_INVALID',
      'gates.erasureOrchestration.result.runtimeUserHash',
      'erasure E2E must expose only a valid pseudonymous owner hash')
  }
  if (!nonEmpty(erasureResult.requestId) || !nonEmpty(erasureResult.runId)) {
    collector.add('ERASURE_E2E_CORRELATION_REQUIRED',
      'gates.erasureOrchestration.result.requestId',
      'erasure E2E run and request correlation identifiers are required')
  }
  if (!recent(erasureResult.completedAt, nowMs, maxAge)) {
    collector.add('ERASURE_E2E_RESULT_STALE',
      'gates.erasureOrchestration.result.completedAt',
      `erasure E2E result must be no older than ${maxAge} days`)
  }
  if (!Array.isArray(erasureResult.issues) || erasureResult.issues.length !== 0) {
    collector.add('ERASURE_E2E_ISSUES_PRESENT', 'gates.erasureOrchestration.result.issues',
      'successful erasure E2E evidence must contain no issues')
  }
  const erasureResultDomains = Array.isArray(erasureResult.domains) ? erasureResult.domains : []
  const resultDomainNames = new Set()
  for (const domain of erasureResultDomains) {
    if (!REQUIRED_ERASURE_DOMAINS.includes(domain?.domainCode)
        || resultDomainNames.has(domain?.domainCode)) {
      collector.add('ERASURE_E2E_DOMAIN_INVENTORY_INVALID',
        'gates.erasureOrchestration.result.domains',
        'erasure E2E contains an unknown or duplicate domain')
      continue
    }
    resultDomainNames.add(domain.domainCode)
    if (domain.status !== 'COMPLETED') {
      collector.add('ERASURE_E2E_DOMAIN_NOT_COMPLETED',
        `gates.erasureOrchestration.result.domains[${domain.domainCode}].status`,
        'every erasure E2E domain must be completed')
    }
  }
  for (const domainName of REQUIRED_ERASURE_DOMAINS) {
    if (!resultDomainNames.has(domainName)) {
      collector.add('ERASURE_E2E_DOMAIN_MISSING',
        `gates.erasureOrchestration.result.domains[${domainName}]`,
        'required erasure E2E domain is missing')
    }
  }

  const domains = Array.isArray(gates.erasureDomains) ? gates.erasureDomains : []
  for (const domainName of REQUIRED_ERASURE_DOMAINS) {
    const domain = domains.find(item => item?.domain === domainName)
    const domainPath = `gates.erasureDomains[${domainName}]`
    if (!domain) {
      collector.add('ERASURE_DOMAIN_MISSING', domainPath, 'required erasure domain evidence is missing')
      continue
    }
    const allowed = domainName === 'BUSINESS_SOURCE_SYSTEM' ? ['PASSED', 'OWNER_ATTESTED'] : ['PASSED']
    if (!allowed.includes(domain.status)) {
      collector.add('ERASURE_DOMAIN_NOT_PASSED', `${domainPath}.status`,
        `status must be ${allowed.join(' or ')}`)
    }
    if (!nonEmpty(domain.evidenceRef)) {
      collector.add('EVIDENCE_REF_REQUIRED', `${domainPath}.evidenceRef`, 'domain evidence reference is required')
    }
    if (!recent(domain.observedAt, nowMs, maxAge)) {
      collector.add('EVIDENCE_STALE_OR_INVALID', `${domainPath}.observedAt`,
        `domain evidence must be no older than ${maxAge} days`)
    }
  }

  return {
    schema: 'reachai-personal-memory-production-readiness-v1',
    environment: {
      name: evidence?.environment?.name || null,
      classification: evidence?.environment?.classification || null,
      changeId: evidence?.environment?.changeId || null,
    },
    evaluatedAt: new Date(nowMs).toISOString(),
    productionPromotionEligible: collector.issues.length === 0,
    issueCount: collector.issues.length,
    issues: collector.issues,
  }
}

export async function repositoryMigrationChecksums(repoRoot = process.cwd()) {
  const entries = await Promise.all(REQUIRED_MIGRATIONS.map(async script => {
    const bytes = await readFile(path.resolve(repoRoot, script))
    return [script, createHash('sha256').update(bytes).digest('hex')]
  }))
  return Object.fromEntries(entries)
}

function argument(name) {
  const prefix = `--${name}=`
  const value = process.argv.slice(2).find(item => item.startsWith(prefix))
  return value ? value.slice(prefix.length) : null
}

async function main() {
  const evidencePath = argument('evidence') || process.env.REACHAI_MEMORY_PRODUCTION_EVIDENCE
  if (!evidencePath) {
    throw new Error('provide --evidence=<json-file> or REACHAI_MEMORY_PRODUCTION_EVIDENCE')
  }
  const evidence = JSON.parse(await readFile(path.resolve(evidencePath), 'utf8'))
  const result = evaluateProductionEvidence(evidence, {
    expectedMigrationChecksums: await repositoryMigrationChecksums(),
    maxEvidenceAgeDays: Number(argument('max-age-days') || process.env.REACHAI_MEMORY_EVIDENCE_MAX_AGE_DAYS || 90),
  })
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
  if (!result.productionPromotionEligible) process.exitCode = 1
}

if (process.argv[1]
    && fileURLToPath(import.meta.url) === fileURLToPath(new URL(`file:///${process.argv[1].replaceAll('\\', '/')}`))) {
  main().catch(error => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  })
}
