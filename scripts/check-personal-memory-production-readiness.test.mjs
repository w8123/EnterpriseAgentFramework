import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  evaluateProductionEvidence,
  repositoryMigrationChecksums,
  REQUIRED_ERASURE_DOMAINS,
  REQUIRED_MIGRATIONS,
} from './check-personal-memory-production-readiness.mjs'

const NOW = '2026-08-15T12:00:00.000Z'
const OBSERVED = '2026-08-14T12:00:00.000Z'

function passingEvidence(checksums) {
  const evidence = (status = 'PASSED') => ({ status, observedAt: OBSERVED, evidenceRef: 'evidence://change-42/item' })
  return {
    schema: 'reachai-personal-memory-production-evidence-v1',
    environment: { name: 'preprod-cn', classification: 'staging', changeId: 'CHG-42' },
    migrations: REQUIRED_MIGRATIONS.map(script => ({
      script,
      status: 'PASSED',
      checksumSha256: checksums[script],
      executedAt: OBSERVED,
      evidenceRef: 'evidence://change-42/migration',
    })),
    gates: {
      sessionRetention: evidence(),
      businessIngress: evidence(),
      legalHold: evidence(),
      concurrentErase: evidence(),
      auditRedaction: evidence(),
      alerting: evidence(),
      sseRegression: evidence(),
      dependencyFailureDrill: evidence(),
      semanticProjection: {
        ...evidence(),
        mode: 'HYBRID',
        modelInstanceEvidenceRef: 'evidence://change-42/model-instance',
        snapshotFresh: true,
        activeCount: 100,
        readyCount: 100,
        readyRatio: 1,
        deadCount: 0,
        oldestPendingSeconds: 0,
        unsafeDeletedVectorCount: 0,
        modelSwitchDrillStatus: 'PASSED',
        providerFailureFallbackStatus: 'PASSED',
        deletionVectorScrubStatus: 'PASSED',
      },
      outboxProjection: {
        ...evidence(),
        snapshotFresh: true,
        backlogCount: 0,
        deadCount: 0,
        oldestUnpublishedSeconds: 0,
      },
      shadow: {
        ...evidence(),
        sampleCount: 500,
        canonicalCoverage: 0.95,
        projectedPrecision: 0.92,
        invalidIdRatio: 0,
        errorRatio: 0.002,
      },
      load: {
        ...evidence(),
        actorCount: 2,
        requestCount: 600,
        errorRatio: 0,
        queryP95Ms: 120,
        crossOwnerLeaks: 0,
        deletedItemLeaks: 0,
        cleanupFailures: 0,
      },
      transport: {
        ...evidence(),
        mode: 'DIRECT_TLS',
        plaintextProbePassed: true,
      },
      databaseTransport: {
        ...evidence(),
        mode: 'VERIFY_IDENTITY',
        preAuthenticationProbePassed: true,
        credentialsUsed: false,
      },
      encryption: {
        ...evidence(),
        mysqlAtRest: true,
        redisAtRest: true,
        backupAtRest: true,
        keyRotationStatus: 'PASSED',
      },
      backupRestore: {
        ...evidence(),
        policyApproved: true,
        maxRetentionDays: 35,
        restoreDrillStatus: 'PASSED',
        deletionReconciliationStatus: 'PASSED',
        rpoMinutes: 5,
        rtoMinutes: 30,
        expiryEvidenceRef: 'evidence://change-42/backup-expiry',
      },
      erasureOrchestration: {
        ...evidence(),
        result: {
          schema: 'reachai-memory-erasure-e2e-result-v1',
          runId: 'erasure-run-42',
          targetClass: 'REMOTE',
          executed: true,
          passed: true,
          expectedStatus: 'COMPLETED',
          finalStatus: 'COMPLETED',
          requestId: 'erasure-request-42',
          runtimeUserHash: 'a'.repeat(64),
          requestCount: 7,
          dataPlaneAffectedCount: 8,
          dataPlaneExercised: true,
          domainInventoryVerified: true,
          identityLeakCheckPassed: true,
          createReplayVerified: true,
          evidenceReplayVerified: true,
          completedAt: OBSERVED,
          issues: [],
          domains: REQUIRED_ERASURE_DOMAINS.map(domainCode => ({
            domainCode,
            status: 'COMPLETED',
          })),
        },
      },
      erasureDomains: REQUIRED_ERASURE_DOMAINS.map(domain => ({
        domain,
        status: domain === 'BUSINESS_SOURCE_SYSTEM' ? 'OWNER_ATTESTED' : 'PASSED',
        observedAt: OBSERVED,
        evidenceRef: `evidence://change-42/erase/${domain}`,
      })),
    },
  }
}

function checksums() {
  return Object.fromEntries(REQUIRED_MIGRATIONS.map((script, index) => [script, String(index + 1).padStart(64, 'a')]))
}

test('complete current evidence is eligible for promotion', () => {
  const expected = checksums()
  const result = evaluateProductionEvidence(passingEvidence(expected), {
    expectedMigrationChecksums: expected,
    now: NOW,
  })
  assert.equal(result.productionPromotionEligible, true)
  assert.deepEqual(result.issues, [])
})

test('owner leak and stale restore evidence fail closed', () => {
  const expected = checksums()
  const value = passingEvidence(expected)
  value.gates.load.crossOwnerLeaks = 1
  value.gates.backupRestore.observedAt = '2025-01-01T00:00:00.000Z'
  const result = evaluateProductionEvidence(value, { expectedMigrationChecksums: expected, now: NOW })
  assert.equal(result.productionPromotionEligible, false)
  assert.ok(result.issues.some(item => item.code === 'LOAD_SAFETY_VIOLATION'))
  assert.ok(result.issues.some(item => item.path === 'gates.backupRestore.observedAt'))
})

test('database transport without identity verification fails closed', () => {
  const expected = checksums()
  const value = passingEvidence(expected)
  value.gates.databaseTransport.mode = 'PREFERRED'
  value.gates.databaseTransport.preAuthenticationProbePassed = false
  const result = evaluateProductionEvidence(value, { expectedMigrationChecksums: expected, now: NOW })
  assert.equal(result.productionPromotionEligible, false)
  assert.ok(result.issues.some(item => item.code === 'DATABASE_TLS_MODE_UNSAFE'))
  assert.ok(result.issues.some(item => item.code === 'DATABASE_TLS_IDENTITY_PROBE_REQUIRED'))
})

test('semantic backlog and failed deletion scrub block promotion', () => {
  const expected = checksums()
  const value = passingEvidence(expected)
  value.gates.semanticProjection.readyRatio = 0.8
  value.gates.semanticProjection.deadCount = 1
  value.gates.semanticProjection.snapshotFresh = false
  value.gates.semanticProjection.unsafeDeletedVectorCount = 1
  value.gates.semanticProjection.deletionVectorScrubStatus = 'FAILED'
  const result = evaluateProductionEvidence(value, { expectedMigrationChecksums: expected, now: NOW })
  assert.equal(result.productionPromotionEligible, false)
  assert.ok(result.issues.some(item => item.code === 'SEMANTIC_READY_RATIO_BELOW_THRESHOLD'))
  assert.ok(result.issues.some(item => item.code === 'SEMANTIC_DEAD_COUNT_EXCEEDED'))
  assert.ok(result.issues.some(item => item.code === 'SEMANTIC_SNAPSHOT_STALE'))
  assert.ok(result.issues.some(item => item.code === 'SEMANTIC_DELETED_VECTOR_RESIDUE'))
  assert.ok(result.issues.some(item => item.path.endsWith('deletionVectorScrubStatus')))
})

test('stale or dead canonical projection outbox blocks promotion', () => {
  const expected = checksums()
  const value = passingEvidence(expected)
  value.gates.outboxProjection.snapshotFresh = false
  value.gates.outboxProjection.deadCount = 1
  value.gates.outboxProjection.oldestUnpublishedSeconds = 301
  const result = evaluateProductionEvidence(value, { expectedMigrationChecksums: expected, now: NOW })
  assert.equal(result.productionPromotionEligible, false)
  assert.ok(result.issues.some(item => item.code === 'OUTBOX_SNAPSHOT_STALE'))
  assert.ok(result.issues.some(item => item.code === 'OUTBOX_DEAD_LETTERS_PRESENT'))
  assert.ok(result.issues.some(item => item.code === 'OUTBOX_BACKLOG_AGE_EXCEEDED'))
})

test('empty or incomplete cross-domain erasure E2E blocks promotion', () => {
  const expected = checksums()
  const value = passingEvidence(expected)
  value.gates.erasureOrchestration.result.dataPlaneExercised = false
  value.gates.erasureOrchestration.result.dataPlaneAffectedCount = 0
  value.gates.erasureOrchestration.result.createReplayVerified = false
  value.gates.erasureOrchestration.result.domains.pop()
  const result = evaluateProductionEvidence(value, { expectedMigrationChecksums: expected, now: NOW })
  assert.equal(result.productionPromotionEligible, false)
  assert.ok(result.issues.some(item => item.code === 'ERASURE_E2E_DATA_PLANE_NOT_EXERCISED'))
  assert.ok(result.issues.some(item => item.code === 'ERASURE_E2E_INVARIANT_NOT_VERIFIED'))
  assert.ok(result.issues.some(item => item.code === 'ERASURE_E2E_DOMAIN_MISSING'))
})

test('missing migration and repository checksum mismatch fail closed', () => {
  const expected = checksums()
  const missing = passingEvidence(expected)
  missing.migrations.shift()
  const missingResult = evaluateProductionEvidence(missing, {
    expectedMigrationChecksums: expected,
    now: NOW,
  })
  assert.equal(missingResult.productionPromotionEligible, false)
  assert.ok(missingResult.issues.some(item => item.code === 'MIGRATION_EVIDENCE_MISSING'))

  const mismatched = passingEvidence(expected)
  mismatched.migrations[0].checksumSha256 = 'f'.repeat(64)
  const mismatchResult = evaluateProductionEvidence(mismatched, {
    expectedMigrationChecksums: expected,
    now: NOW,
  })
  assert.equal(mismatchResult.productionPromotionEligible, false)
  assert.ok(mismatchResult.issues.some(item => item.code === 'MIGRATION_CHECKSUM_MISMATCH'))
})

test('current release uses one consolidated migration and keeps the semantic projection baseline', async () => {
  const script = 'sql/upgrade-20260830-platform-consolidated.sql'
  assert.deepEqual(REQUIRED_MIGRATIONS, [script])
  const [migration, baseline, digests] = await Promise.all([
    readFile(script, 'utf8'),
    readFile('sql/initV2.sql', 'utf8'),
    repositoryMigrationChecksums(),
  ])
  for (const field of [
    'embedding_vector',
    'embedding_format',
    'embedding_dimension',
    'embedding_model_instance_id',
    'embedding_source_version',
    'embedding_status',
    'embedding_claim_token',
    'embedding_claim_until',
  ]) {
    assert.match(baseline, new RegExp(`\x60${field}\x60`))
  }
  assert.match(baseline, /`embedding_status`[\s\S]*DEFAULT 'DISABLED'/)
  assert.match(migration, /This file consolidates every upgrade created after origin\/main at ae9e1ce6/)
  assert.match(migration, /-- Section 01\/14:/)
  assert.match(migration, /-- Section 14\/14:/)
  assert.match(digests[script], /^[a-f0-9]{64}$/)
})

test('current consolidated migration does not replay historical personal-memory outbox rewrites', async () => {
  const script = 'sql/upgrade-20260830-platform-consolidated.sql'
  const [migration, baseline, digests] = await Promise.all([
    readFile(script, 'utf8'),
    readFile('sql/initV2.sql', 'utf8'),
    repositoryMigrationChecksums(),
  ])
  assert.match(baseline, /`last_error`\s+VARCHAR\(1000\)[^\n]*64/)
  assert.match(digests[script], /^[a-f0-9]{64}$/)
  assert.doesNotMatch(migration, /PUBLISH_LEGACY_REDACTED/)
  assert.doesNotMatch(migration, /ALTER\s+TABLE\s+`control_context_memory_outbox`/i)
})

test('cross-domain erasure remains a baseline contract after historical migration cleanup', async () => {
  const script = 'sql/upgrade-20260830-platform-consolidated.sql'
  const [migration, baseline, digests] = await Promise.all([
    readFile(script, 'utf8'),
    readFile('sql/initV2.sql', 'utf8'),
    repositoryMigrationChecksums(),
  ])
  for (const table of [
    'control_memory_erasure_request',
    'control_memory_erasure_domain',
  ]) {
    assert.match(baseline, new RegExp(`CREATE TABLE IF NOT EXISTS [^\\n]*${table}`))
  }
  assert.match(baseline, /correlation_id/)
  assert.match(baseline, /idx_context_memory_outbox_correlation/)
  assert.match(baseline, /context:memory:erasure:manage/)
  assert.match(digests[script], /^[a-f0-9]{64}$/)
  assert.doesNotMatch(migration, /DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`?control_memory_erasure_/i)
})
