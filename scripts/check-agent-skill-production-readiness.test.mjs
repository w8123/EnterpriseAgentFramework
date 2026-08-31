import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  AGENT_SKILL_MIGRATION,
  AGENT_SKILL_MARKET_MIGRATION,
  evaluateAgentSkillDeploymentEvidence,
  inspectAgentSkillRepository,
  REQUIRED_AGENT_SKILL_MARKET_SOURCES,
  REQUIRED_AGENT_SKILL_MARKET_TABLES,
  REQUIRED_AGENT_SKILL_PERMISSIONS,
  REQUIRED_AGENT_SKILL_TABLES,
} from './check-agent-skill-production-readiness.mjs'

const NOW = '2026-08-23T16:00:00.000Z'
const OBSERVED = '2026-08-23T15:00:00.000Z'

function passingEvidence(checksum, classification = 'development') {
  const gate = extra => ({
    status: 'PASSED',
    observedAt: OBSERVED,
    evidenceRef: 'evidence://skill-release/run-42',
    ...extra,
  })
  return {
    schema: 'reachai-agent-skill-production-evidence-v2',
    environment: {
      name: 'skill-e2e-local',
      classification,
      changeId: classification === 'development' ? null : 'CHG-42',
      sourceRevision: 'worktree-20260823-skill',
      artifactRef: 'artifact://reachai/build-42',
    },
    migration: {
      script: AGENT_SKILL_MIGRATION,
      status: 'PASSED',
      checksumSha256: checksum,
      executedAt: OBSERVED,
      readbackObservedAt: OBSERVED,
      evidenceRef: 'evidence://skill-release/mysql-readback',
      tables: REQUIRED_AGENT_SKILL_TABLES.map(table => ({
        ...table,
        exists: true,
        columnContractVerified: true,
      })),
      permissions: REQUIRED_AGENT_SKILL_PERMISSIONS.map(permission => ({ permission, exists: true })),
      roleGrantsReadbackPassed: true,
    },
    gates: {
      configuration: gate({
        servicesHealthy: true,
        artifactsFresh: true,
        serviceSecretConfigured: true,
        signedResolvePassed: true,
        replayRejected: true,
        noncePersistenceVerified: true,
        controlArtifactStorePersistent: true,
        runtimeCacheWritable: true,
        runtimeCacheNonTemporary: true,
        quarantineWritable: true,
        scriptExecutionEnabled: false,
      }),
      externalPackage: gate({
        packageIdentity: 'community/playwright@1.0.0',
        sourceSha256: 'a'.repeat(64),
        contentTreeSha256: 'b'.repeat(64),
        bundleSourceSha256: 'c'.repeat(64),
        bundleSkillRoot: 'skills-main/skills/playwright',
        productionInspectorUsed: true,
        unknownFilesPreserved: true,
        scriptRiskFlagged: true,
        codexMetadataVisible: true,
        toolDependenciesVisible: true,
        bundleDiscoveryPassed: true,
        bundleSelectionIsolated: true,
        bundleAuditTraceable: true,
        scriptsExecuted: false,
      }),
      browserWorkflow: gate({
        mode: 'REAL_SERVICE',
        independentPrincipalCount: 4,
        authorImportPassed: true,
        reviewerApprovePassed: true,
        reviewerPublishPassed: true,
        designerBindPassed: true,
        agentPublishPassed: true,
        unauthorizedDenied: true,
        privateIsolationPassed: true,
        projectIsolationPassed: true,
        apiMocksUsed: false,
      }),
      runtimeE2e: gate({
        host: 'AGENTSCOPE',
        hostE2e: 'PASSED',
        traceIds: ['trace-model-selected', 'trace-always'],
        realModelInvoked: true,
        modelSelectedLoaded: true,
        alwaysInjected: true,
        explicitOrdinaryConversationSkipped: true,
        exactVersionTraceVisible: true,
        runOpsVisible: true,
        controlStatusRevalidated: true,
        requiredFailureBlocked: true,
        optionalFailureSkipped: true,
        skillContentAbsentFromTraceMetadata: true,
      }),
      tamperAndRevocation: gate({
        cacheTamperQuarantined: true,
        redownloadDigestVerified: true,
        digestMismatchRejected: true,
        revokedCacheRejected: true,
        uncheckedCacheFallbackObserved: false,
      }),
    },
  }
}

test('repository contract keeps baseline, migration, owners, permissions, and configuration aligned', async () => {
  const result = await inspectAgentSkillRepository()
  assert.equal(result.repositoryReady, true, JSON.stringify(result.issues, null, 2))
  assert.equal(result.issueCount, 0)
  assert.match(result.migration.checksumSha256, /^[a-f0-9]{64}$/)
  assert.match(result.market.migration.checksumSha256, /^[a-f0-9]{64}$/)
  assert.equal(result.tables.length, 5)
  assert.ok(result.tables.every(table => table.baselinePresent && table.migrationPresent
    && table.ddlExact && table.ownershipDeclared))
  assert.equal(result.permissions.length, 6)
  assert.ok(result.permissions.every(permission => permission.baselinePresent && permission.migrationPresent))
  assert.equal(result.market.tables.length, REQUIRED_AGENT_SKILL_MARKET_TABLES.length)
  assert.ok(result.market.tables.every(table => table.baselinePresent && table.migrationPresent
    && table.ddlExact && table.ownershipDeclared))
  assert.equal(result.market.sources.length, REQUIRED_AGENT_SKILL_MARKET_SOURCES.length)
  assert.ok(result.market.sources.every(source => source.baselinePresent && source.migrationPresent))

  const migration = await readFile(AGENT_SKILL_MIGRATION, 'utf8')
  const marketMigration = await readFile(AGENT_SKILL_MARKET_MIGRATION, 'utf8')
  const skillSection = migration.slice(
    migration.indexOf('-- Section 02/14:'),
    migration.indexOf('-- Section 03/14:'),
  )
  const marketSection = marketMigration.slice(
    marketMigration.indexOf('-- Section 04/14:'),
    marketMigration.indexOf('-- Section 05/14:'),
  )
  assert.match(skillSection, /-- Section 02\/14:/)
  assert.match(marketSection, /-- Section 04\/14:/)
  assert.doesNotMatch(skillSection, /\b(?:DROP|DELETE|TRUNCATE)\b/i)
  assert.doesNotMatch(marketSection, /\b(?:DROP|DELETE|TRUNCATE)\b/i)
})

test('complete development evidence is deployment ready but cannot authorize production promotion', async () => {
  const repository = await inspectAgentSkillRepository()
  const result = evaluateAgentSkillDeploymentEvidence(
    passingEvidence(repository.migration.checksumSha256),
    { expectedMigrationChecksum: repository.migration.checksumSha256, now: NOW },
  )
  assert.equal(result.deploymentReady, true, JSON.stringify(result.issues, null, 2))
  assert.equal(result.productionPromotionEligible, false)
})

test('complete staging evidence can authorize promotion', async () => {
  const repository = await inspectAgentSkillRepository()
  const result = evaluateAgentSkillDeploymentEvidence(
    passingEvidence(repository.migration.checksumSha256, 'staging'),
    { expectedMigrationChecksum: repository.migration.checksumSha256, now: NOW },
  )
  assert.equal(result.deploymentReady, true, JSON.stringify(result.issues, null, 2))
  assert.equal(result.productionPromotionEligible, true)
})

test('schema drift, missing permissions, and migration checksum mismatch fail closed', async () => {
  const repository = await inspectAgentSkillRepository()
  const evidence = passingEvidence('c'.repeat(64))
  evidence.migration.tables = evidence.migration.tables.filter(table => table.name !== 'control_agent_skill_review')
  evidence.migration.permissions[0].exists = false
  evidence.migration.roleGrantsReadbackPassed = false
  const result = evaluateAgentSkillDeploymentEvidence(evidence, {
    expectedMigrationChecksum: repository.migration.checksumSha256,
    now: NOW,
  })
  assert.equal(result.deploymentReady, false)
  assert.ok(result.issues.some(issue => issue.code === 'MIGRATION_CHECKSUM_MISMATCH'))
  assert.ok(result.issues.some(issue => issue.code === 'TABLE_READBACK_MISSING'))
  assert.ok(result.issues.some(issue => issue.code === 'PERMISSION_READBACK_MISSING'))
  assert.ok(result.issues.some(issue => issue.code === 'ROLE_GRANTS_UNVERIFIED'))
})

test('mocked browser evidence and unsafe runtime configuration cannot pass', async () => {
  const repository = await inspectAgentSkillRepository()
  const evidence = passingEvidence(repository.migration.checksumSha256)
  evidence.gates.configuration.signedResolvePassed = false
  evidence.gates.configuration.replayRejected = false
  evidence.gates.configuration.scriptExecutionEnabled = true
  evidence.gates.browserWorkflow.mode = 'API_MOCK'
  evidence.gates.browserWorkflow.apiMocksUsed = true
  evidence.gates.browserWorkflow.independentPrincipalCount = 1
  const result = evaluateAgentSkillDeploymentEvidence(evidence, {
    expectedMigrationChecksum: repository.migration.checksumSha256,
    now: NOW,
  })
  assert.equal(result.deploymentReady, false)
  assert.ok(result.issues.some(issue => issue.path === 'gates.configuration.signedResolvePassed'))
  assert.ok(result.issues.some(issue => issue.path === 'gates.configuration.scriptExecutionEnabled'))
  assert.ok(result.issues.some(issue => issue.code === 'BROWSER_NOT_REAL_SERVICE'))
  assert.ok(result.issues.some(issue => issue.code === 'BROWSER_PRINCIPALS_INSUFFICIENT'))
})

test('repository bundle discovery and selection evidence cannot be omitted', async () => {
  const repository = await inspectAgentSkillRepository()
  const evidence = passingEvidence(repository.migration.checksumSha256)
  evidence.gates.externalPackage.bundleSourceSha256 = 'not-a-digest'
  evidence.gates.externalPackage.bundleSkillRoot = ''
  evidence.gates.externalPackage.bundleDiscoveryPassed = false
  evidence.gates.externalPackage.bundleSelectionIsolated = false
  evidence.gates.externalPackage.bundleAuditTraceable = false
  const result = evaluateAgentSkillDeploymentEvidence(evidence, {
    expectedMigrationChecksum: repository.migration.checksumSha256,
    now: NOW,
  })
  assert.equal(result.deploymentReady, false)
  assert.ok(result.issues.some(issue => issue.code === 'BUNDLE_DIGEST_INVALID'))
  assert.ok(result.issues.some(issue => issue.code === 'BUNDLE_SKILL_ROOT_REQUIRED'))
  assert.ok(result.issues.some(issue => issue.path === 'gates.externalPackage.bundleDiscoveryPassed'))
  assert.ok(result.issues.some(issue => issue.path === 'gates.externalPackage.bundleSelectionIsolated'))
  assert.ok(result.issues.some(issue => issue.path === 'gates.externalPackage.bundleAuditTraceable'))
})

test('format-only compatibility and unchecked cache fallback cannot masquerade as Host E2E', async () => {
  const repository = await inspectAgentSkillRepository()
  const evidence = passingEvidence(repository.migration.checksumSha256)
  evidence.gates.runtimeE2e.host = 'CODEX'
  evidence.gates.runtimeE2e.hostE2e = 'FORMAT_VERIFIED'
  evidence.gates.runtimeE2e.realModelInvoked = false
  evidence.gates.runtimeE2e.traceIds = ['trace-only-one-mode']
  evidence.gates.tamperAndRevocation.revokedCacheRejected = false
  evidence.gates.tamperAndRevocation.uncheckedCacheFallbackObserved = true
  const result = evaluateAgentSkillDeploymentEvidence(evidence, {
    expectedMigrationChecksum: repository.migration.checksumSha256,
    now: NOW,
  })
  assert.equal(result.deploymentReady, false)
  assert.ok(result.issues.some(issue => issue.code === 'RUNTIME_HOST_MISMATCH'))
  assert.ok(result.issues.some(issue => issue.code === 'HOST_E2E_NOT_PASSED'))
  assert.ok(result.issues.some(issue => issue.code === 'TRACE_EVIDENCE_INSUFFICIENT'))
  assert.ok(result.issues.some(issue => issue.path === 'gates.tamperAndRevocation.uncheckedCacheFallbackObserved'))
})

test('stale evidence and placeholder fixture remain ineligible', async () => {
  const repository = await inspectAgentSkillRepository()
  const evidence = passingEvidence(repository.migration.checksumSha256)
  for (const gate of Object.values(evidence.gates)) gate.observedAt = '2025-01-01T00:00:00.000Z'
  evidence.migration.readbackObservedAt = '2025-01-01T00:00:00.000Z'
  const stale = evaluateAgentSkillDeploymentEvidence(evidence, {
    expectedMigrationChecksum: repository.migration.checksumSha256,
    now: NOW,
  })
  assert.equal(stale.deploymentReady, false)
  assert.ok(stale.issues.some(issue => issue.code === 'EVIDENCE_STALE_OR_INVALID'))

  const fixture = JSON.parse(await readFile('scripts/fixtures/agent-skill-production-evidence.example.json', 'utf8'))
  const placeholder = evaluateAgentSkillDeploymentEvidence(fixture, {
    expectedMigrationChecksum: repository.migration.checksumSha256,
    now: NOW,
  })
  assert.equal(placeholder.deploymentReady, false)
  assert.ok(placeholder.issueCount > 0)
})
