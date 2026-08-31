#!/usr/bin/env node

import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const AGENT_SKILL_MIGRATION = 'sql/upgrade-20260830-platform-consolidated.sql'
export const AGENT_SKILL_MARKET_MIGRATION = AGENT_SKILL_MIGRATION

export const REQUIRED_AGENT_SKILL_TABLES = Object.freeze([
  { name: 'control_internal_auth_nonce', owner: 'reachai-control-service' },
  { name: 'control_agent_skill', owner: 'reachai-control-service' },
  { name: 'control_agent_skill_version', owner: 'reachai-control-service' },
  { name: 'control_agent_skill_review', owner: 'reachai-control-service' },
  { name: 'runtime_agent_skill_binding', owner: 'reachai-runtime-service' },
])

export const REQUIRED_AGENT_SKILL_MARKET_TABLES = Object.freeze([
  { name: 'control_agent_skill_market_source', owner: 'reachai-control-service' },
  { name: 'control_agent_skill_market_import', owner: 'reachai-control-service' },
])

export const REQUIRED_AGENT_SKILL_MARKET_SOURCES = Object.freeze([
  'SKILLS_SH',
  'GITHUB',
  'ANTHROPIC_SKILLS',
  'VERCEL_AGENT_SKILLS',
  'JETBRAINS_SKILLS',
  'OPENAI_PLUGINS',
])

export const REQUIRED_AGENT_SKILL_PERMISSIONS = Object.freeze([
  'skill:read',
  'skill:import',
  'skill:review',
  'skill:publish',
  'skill:bind',
  'skill:script:approve',
])

const REQUIRED_REPOSITORY_FILES = Object.freeze([
  'docs/architecture/agent-skill-center.md',
  'docs/architecture/agent-skill-market.md',
  'docs/architecture/service-table-ownership.md',
  'sql/initV2.sql',
  AGENT_SKILL_MIGRATION,
  AGENT_SKILL_MARKET_MIGRATION,
  'reachai-control-service/src/main/java/com/enterprise/ai/control/agentskill/AgentSkillPackageInspector.java',
  'reachai-control-service/src/main/java/com/enterprise/ai/control/agentskill/AgentSkillController.java',
  'reachai-control-service/src/main/java/com/enterprise/ai/control/agentskill/AgentSkillAccessPolicy.java',
  'reachai-control-service/src/main/java/com/enterprise/ai/control/agentskill/AgentSkillCatalogService.java',
  'reachai-control-service/src/test/java/com/enterprise/ai/control/agentskill/AgentSkillControllerTest.java',
  'reachai-control-service/src/test/java/com/enterprise/ai/control/agentskill/AgentSkillExternalBundleProbeTest.java',
  'reachai-control-service/src/test/java/com/enterprise/ai/control/agentskill/AgentSkillExternalPackageProbeTest.java',
  'reachai-control-service/src/main/java/com/enterprise/ai/control/skillmarket/SkillMarketController.java',
  'reachai-control-service/src/main/java/com/enterprise/ai/control/skillmarket/SkillMarketService.java',
  'reachai-control-service/src/main/java/com/enterprise/ai/control/skillmarket/SkillMarketHttpTransport.java',
  'reachai-control-service/src/main/java/com/enterprise/ai/control/skillmarket/SkillSourceProvider.java',
  'reachai-control-service/src/main/java/com/enterprise/ai/control/skillmarket/GitHubSkillRepositoryClient.java',
  'reachai-control-service/src/test/java/com/enterprise/ai/control/skillmarket/SkillMarketControllerTest.java',
  'reachai-control-service/src/test/java/com/enterprise/ai/control/skillmarket/SkillMarketHttpTransportTest.java',
  'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentSkillRepositoryFactory.java',
  'reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentSkillCacheProductionGuard.java',
  'reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/agent/RuntimeAgentSkillCacheProductionGuardTest.java',
  'ai-admin-front/src/views/skill/SkillCenter.vue',
  'ai-admin-front/src/views/skill-market/SkillMarket.vue',
  'ai-admin-front/src/views/skill-market/components/SkillMarketCard.vue',
  'ai-admin-front/src/views/skill-market/skillMarketPresentation.test.ts',
  'ai-admin-front/src/views/skill/composables/useAgentSkillBundleImport.ts',
  'ai-admin-front/src/views/skill/composables/useAgentSkillBundleImport.test.ts',
  'ai-admin-front/vitest.skill.config.ts',
  'ai-admin-front/src/views/agent/AgentEdit.vue',
])

const BOOLEAN_REQUIREMENTS = Object.freeze({
  configuration: {
    true: [
      'servicesHealthy',
      'artifactsFresh',
      'serviceSecretConfigured',
      'signedResolvePassed',
      'replayRejected',
      'noncePersistenceVerified',
      'controlArtifactStorePersistent',
      'runtimeCacheWritable',
      'runtimeCacheNonTemporary',
      'quarantineWritable',
    ],
    false: ['scriptExecutionEnabled'],
  },
  externalPackage: {
    true: [
      'productionInspectorUsed',
      'unknownFilesPreserved',
      'scriptRiskFlagged',
      'codexMetadataVisible',
      'toolDependenciesVisible',
      'bundleDiscoveryPassed',
      'bundleSelectionIsolated',
      'bundleAuditTraceable',
    ],
    false: ['scriptsExecuted'],
  },
  browserWorkflow: {
    true: [
      'authorImportPassed',
      'reviewerApprovePassed',
      'reviewerPublishPassed',
      'designerBindPassed',
      'agentPublishPassed',
      'unauthorizedDenied',
      'privateIsolationPassed',
      'projectIsolationPassed',
    ],
    false: ['apiMocksUsed'],
  },
  runtimeE2e: {
    true: [
      'realModelInvoked',
      'modelSelectedLoaded',
      'alwaysInjected',
      'explicitOrdinaryConversationSkipped',
      'exactVersionTraceVisible',
      'runOpsVisible',
      'controlStatusRevalidated',
      'requiredFailureBlocked',
      'optionalFailureSkipped',
      'skillContentAbsentFromTraceMetadata',
    ],
    false: [],
  },
  tamperAndRevocation: {
    true: [
      'cacheTamperQuarantined',
      'redownloadDigestVerified',
      'digestMismatchRejected',
      'revokedCacheRejected',
    ],
    false: ['uncheckedCacheFallbackObserved'],
  },
})

function nonEmpty(value) {
  return typeof value === 'string' && value.trim() !== ''
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function sha256Hex(value) {
  return /^[a-f0-9]{64}$/i.test(String(value || ''))
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

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function normalizeDdl(value) {
  return value
    .replaceAll('\r\n', '\n')
    .split('\n')
    .map(line => line.trimEnd())
    .join('\n')
    .trim()
}

function extractCreateTable(sql, tableName) {
  if (!nonEmpty(sql)) return null
  const expression = new RegExp(
    `CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+\\\`${escapeRegExp(tableName)}\\\`\\s*\\([\\s\\S]*?\\n\\)\\s*ENGINE\\s*=\\s*[^;]+;`,
    'i',
  )
  return sql.match(expression)?.[0] || null
}

function inspectTableContracts({ tables, baseline, migration, migrationPath, ownership, collector, pathPrefix }) {
  const results = []
  for (const table of tables) {
    const baselineDdl = extractCreateTable(baseline, table.name)
    const migrationDdl = extractCreateTable(migration, table.name)
    const baselinePresent = baselineDdl != null
    const migrationPresent = migrationDdl != null
    const ddlExact = baselinePresent
      && migrationPresent
      && normalizeDdl(baselineDdl) === normalizeDdl(migrationDdl)
    const ownershipPattern = new RegExp(
      `\\|\\s*\\\`${escapeRegExp(table.name)}\\\`\\s*\\|\\s*\\\`${escapeRegExp(table.owner)}\\\`\\s*\\|`,
    )
    const ownershipDeclared = nonEmpty(ownership) && ownershipPattern.test(ownership)
    const tablePath = `${pathPrefix}.${table.name}`
    if (!baselinePresent) {
      collector.add('BASELINE_TABLE_MISSING', tablePath, 'table is missing from sql/initV2.sql')
    }
    if (!migrationPresent) {
      collector.add('MIGRATION_TABLE_MISSING', tablePath, `table is missing from ${migrationPath}`)
    }
    if (baselinePresent && migrationPresent && !ddlExact) {
      collector.add('TABLE_DDL_DRIFT', tablePath, 'baseline and upgrade DDL are not identical')
    }
    if (!ownershipDeclared) {
      collector.add('TABLE_OWNER_MISSING', `${tablePath}.owner`, `expected owner ${table.owner}`)
    }
    results.push({
      name: table.name,
      owner: table.owner,
      baselinePresent,
      migrationPresent,
      ddlExact,
      ownershipDeclared,
    })
  }
  return results
}

async function readRepositoryFile(repoRoot, relativePath, collector) {
  try {
    return await readFile(path.resolve(repoRoot, relativePath), 'utf8')
  } catch (error) {
    collector.add('REPOSITORY_FILE_MISSING', relativePath, `required repository file cannot be read: ${error.code || error.message}`)
    return null
  }
}

export async function inspectAgentSkillRepository(repoRoot = process.cwd()) {
  const collector = issueCollector()
  const entries = await Promise.all(REQUIRED_REPOSITORY_FILES.map(async relativePath => [
    relativePath,
    await readRepositoryFile(repoRoot, relativePath, collector),
  ]))
  const files = Object.fromEntries(entries)
  const baseline = files['sql/initV2.sql']
  const migration = files[AGENT_SKILL_MIGRATION]
  const marketMigration = files[AGENT_SKILL_MARKET_MIGRATION]
  const ownership = files['docs/architecture/service-table-ownership.md']
  const controlConfig = await readRepositoryFile(
    repoRoot,
    'reachai-control-service/src/main/resources/application.yml',
    collector,
  )
  const runtimeConfig = await readRepositoryFile(
    repoRoot,
    'reachai-runtime-service/src/main/resources/application.yml',
    collector,
  )

  const tableResults = inspectTableContracts({
    tables: REQUIRED_AGENT_SKILL_TABLES,
    baseline,
    migration,
    migrationPath: AGENT_SKILL_MIGRATION,
    ownership,
    collector,
    pathPrefix: 'tables',
  })
  const marketTableResults = inspectTableContracts({
    tables: REQUIRED_AGENT_SKILL_MARKET_TABLES,
    baseline,
    migration: marketMigration,
    migrationPath: AGENT_SKILL_MARKET_MIGRATION,
    ownership,
    collector,
    pathPrefix: 'market.tables',
  })

  const marketSourceResults = REQUIRED_AGENT_SKILL_MARKET_SOURCES.map(sourceKey => {
    const baselinePresent = nonEmpty(baseline) && baseline.includes(`'${sourceKey}'`)
    const migrationPresent = nonEmpty(marketMigration) && marketMigration.includes(`'${sourceKey}'`)
    if (!baselinePresent) {
      collector.add('BASELINE_MARKET_SOURCE_MISSING', `market.sources.${sourceKey}`,
        'market source is missing from sql/initV2.sql')
    }
    if (!migrationPresent) {
      collector.add('MIGRATION_MARKET_SOURCE_MISSING', `market.sources.${sourceKey}`,
        `market source is missing from ${AGENT_SKILL_MARKET_MIGRATION}`)
    }
    return { sourceKey, baselinePresent, migrationPresent }
  })

  const permissionResults = REQUIRED_AGENT_SKILL_PERMISSIONS.map(permission => {
    const baselinePresent = nonEmpty(baseline) && baseline.includes(`'${permission}'`)
    const migrationPresent = nonEmpty(migration) && migration.includes(`'${permission}'`)
    if (!baselinePresent) {
      collector.add('BASELINE_PERMISSION_MISSING', `permissions.${permission}`, 'permission is missing from sql/initV2.sql')
    }
    if (!migrationPresent) {
      collector.add('MIGRATION_PERMISSION_MISSING', `permissions.${permission}`, `permission is missing from ${AGENT_SKILL_MIGRATION}`)
    }
    return { permission, baselinePresent, migrationPresent }
  })

  const requiredConfigFragments = [
    {
      path: 'reachai-control-service/src/main/resources/application.yml',
      content: controlConfig,
      fragments: [
        'artifact-root: ${REACHAI_SKILL_ARTIFACT_ROOT:',
        'service-secret: ${REACHAI_INTERNAL_SERVICE_SECRET:',
        'max-package-bytes: ${REACHAI_SKILL_MAX_PACKAGE_BYTES:',
        'skill-market:',
        'allow-synthetic-proxy-dns: ${REACHAI_SKILL_MARKET_ALLOW_SYNTHETIC_PROXY_DNS:false}',
      ],
    },
    {
      path: 'reachai-runtime-service/src/main/resources/application.yml',
      content: runtimeConfig,
      fragments: [
        'cache-directory: ${RUNTIME_SKILL_CACHE_DIR:',
        'service-secret: ${REACHAI_INTERNAL_SERVICE_SECRET:',
        'max-package-bytes: ${RUNTIME_SKILL_MAX_PACKAGE_BYTES:',
      ],
    },
  ]
  for (const config of requiredConfigFragments) {
    for (const fragment of config.fragments) {
      if (!nonEmpty(config.content) || !config.content.includes(fragment)) {
        collector.add('CONFIG_CONTRACT_MISSING', config.path, `missing configuration contract: ${fragment}`)
      }
    }
  }

  return {
    schema: 'reachai-agent-skill-repository-readiness-v1',
    inspectedAt: new Date().toISOString(),
    repositoryReady: collector.issues.length === 0,
    migration: {
      script: AGENT_SKILL_MIGRATION,
      checksumSha256: migration == null ? null : sha256(Buffer.from(migration, 'utf8')),
    },
    tables: tableResults,
    permissions: permissionResults,
    market: {
      migration: {
        script: AGENT_SKILL_MARKET_MIGRATION,
        checksumSha256: marketMigration == null ? null : sha256(Buffer.from(marketMigration, 'utf8')),
      },
      tables: marketTableResults,
      sources: marketSourceResults,
    },
    issueCount: collector.issues.length,
    issues: collector.issues,
  }
}

function requireGate(gate, gateName, collector, nowMs, maxAgeDays) {
  if (gate?.status !== 'PASSED') {
    collector.add('GATE_NOT_PASSED', `gates.${gateName}.status`, 'gate status must be PASSED')
  }
  if (!nonEmpty(gate?.evidenceRef)) {
    collector.add('EVIDENCE_REF_REQUIRED', `gates.${gateName}.evidenceRef`, 'immutable evidence reference is required')
  }
  if (!recent(gate?.observedAt, nowMs, maxAgeDays)) {
    collector.add(
      'EVIDENCE_STALE_OR_INVALID',
      `gates.${gateName}.observedAt`,
      `evidence must be valid and no older than ${maxAgeDays} days`,
    )
  }
}

function requireBoolean(gate, gateName, field, expected, collector) {
  if (gate?.[field] !== expected) {
    collector.add(
      expected ? 'REQUIRED_PROOF_MISSING' : 'UNSAFE_BEHAVIOR_OBSERVED',
      `gates.${gateName}.${field}`,
      `${field} must be ${expected}`,
    )
  }
}

function validateBooleanRequirements(gates, collector) {
  for (const [gateName, requirements] of Object.entries(BOOLEAN_REQUIREMENTS)) {
    const gate = gates?.[gateName]
    for (const field of requirements.true) requireBoolean(gate, gateName, field, true, collector)
    for (const field of requirements.false) requireBoolean(gate, gateName, field, false, collector)
  }
}

export function evaluateAgentSkillDeploymentEvidence(evidence, {
  expectedMigrationChecksum,
  now = new Date(),
  maxEvidenceAgeDays = 30,
} = {}) {
  const collector = issueCollector()
  const nowMs = now instanceof Date ? now.getTime() : new Date(now).getTime()
  const maxAge = Number(maxEvidenceAgeDays)
  if (!Number.isFinite(nowMs)) throw new Error('now must be a valid date')
  if (!Number.isInteger(maxAge) || maxAge < 1 || maxAge > 365) {
    throw new Error('maxEvidenceAgeDays must be an integer between 1 and 365')
  }

  if (evidence?.schema !== 'reachai-agent-skill-production-evidence-v2') {
    collector.add('SCHEMA_MISMATCH', 'schema', 'unsupported Agent Skill deployment evidence schema')
  }
  const environment = evidence?.environment || {}
  if (!nonEmpty(environment.name)) {
    collector.add('ENVIRONMENT_NAME_REQUIRED', 'environment.name', 'environment name is required')
  }
  if (!['development', 'staging', 'production'].includes(environment.classification)) {
    collector.add(
      'ENVIRONMENT_CLASSIFICATION_INVALID',
      'environment.classification',
      'classification must be development, staging, or production',
    )
  }
  if (!nonEmpty(environment.sourceRevision)) {
    collector.add('SOURCE_REVISION_REQUIRED', 'environment.sourceRevision', 'source revision is required')
  }
  if (!nonEmpty(environment.artifactRef)) {
    collector.add('ARTIFACT_REF_REQUIRED', 'environment.artifactRef', 'deployed artifact evidence is required')
  }
  if (['staging', 'production'].includes(environment.classification) && !nonEmpty(environment.changeId)) {
    collector.add('CHANGE_ID_REQUIRED', 'environment.changeId', 'staging and production evidence requires an approved change identifier')
  }

  const migration = evidence?.migration || {}
  if (migration.script !== AGENT_SKILL_MIGRATION) {
    collector.add('MIGRATION_SCRIPT_MISMATCH', 'migration.script', `expected ${AGENT_SKILL_MIGRATION}`)
  }
  if (migration.status !== 'PASSED') {
    collector.add('MIGRATION_NOT_PASSED', 'migration.status', 'migration status must be PASSED')
  }
  if (!sha256Hex(migration.checksumSha256)) {
    collector.add('MIGRATION_CHECKSUM_INVALID', 'migration.checksumSha256', 'migration checksum must be SHA-256')
  } else if (sha256Hex(expectedMigrationChecksum)
      && migration.checksumSha256.toLowerCase() !== expectedMigrationChecksum.toLowerCase()) {
    collector.add('MIGRATION_CHECKSUM_MISMATCH', 'migration.checksumSha256', 'executed migration differs from the repository script')
  }
  if (timestamp(migration.executedAt) == null) {
    collector.add('MIGRATION_TIMESTAMP_INVALID', 'migration.executedAt', 'migration execution timestamp is required')
  }
  if (!recent(migration.readbackObservedAt, nowMs, maxAge)) {
    collector.add('MIGRATION_READBACK_STALE', 'migration.readbackObservedAt', `schema readback must be no older than ${maxAge} days`)
  }
  if (!nonEmpty(migration.evidenceRef)) {
    collector.add('EVIDENCE_REF_REQUIRED', 'migration.evidenceRef', 'migration readback evidence is required')
  }

  const tableEvidence = Array.isArray(migration.tables) ? migration.tables : []
  for (const expected of REQUIRED_AGENT_SKILL_TABLES) {
    const matches = tableEvidence.filter(item => item?.name === expected.name)
    const pathName = `migration.tables[${expected.name}]`
    if (matches.length === 0) {
      collector.add('TABLE_READBACK_MISSING', pathName, 'required table readback is missing')
      continue
    }
    if (matches.length > 1) {
      collector.add('TABLE_READBACK_DUPLICATE', pathName, 'table readback must contain one authoritative row')
      continue
    }
    const table = matches[0]
    if (table.exists !== true) collector.add('TABLE_NOT_PRESENT', `${pathName}.exists`, 'table must exist')
    if (table.owner !== expected.owner) {
      collector.add('TABLE_OWNER_MISMATCH', `${pathName}.owner`, `expected ${expected.owner}`)
    }
    if (table.columnContractVerified !== true) {
      collector.add('TABLE_COLUMN_CONTRACT_UNVERIFIED', `${pathName}.columnContractVerified`, 'column and index contract must be verified')
    }
  }

  const permissionEvidence = Array.isArray(migration.permissions) ? migration.permissions : []
  for (const permission of REQUIRED_AGENT_SKILL_PERMISSIONS) {
    const matches = permissionEvidence.filter(item => item?.permission === permission)
    const pathName = `migration.permissions[${permission}]`
    if (matches.length !== 1 || matches[0]?.exists !== true) {
      collector.add('PERMISSION_READBACK_MISSING', pathName, 'permission must exist exactly once')
    }
  }
  if (migration.roleGrantsReadbackPassed !== true) {
    collector.add('ROLE_GRANTS_UNVERIFIED', 'migration.roleGrantsReadbackPassed', 'role-to-permission grants must be read back')
  }

  const gates = evidence?.gates || {}
  for (const gateName of Object.keys(BOOLEAN_REQUIREMENTS)) {
    requireGate(gates[gateName], gateName, collector, nowMs, maxAge)
  }
  validateBooleanRequirements(gates, collector)

  const externalPackage = gates.externalPackage || {}
  if (!nonEmpty(externalPackage.packageIdentity)) {
    collector.add('PACKAGE_IDENTITY_REQUIRED', 'gates.externalPackage.packageIdentity', 'external package identity is required')
  }
  for (const field of ['sourceSha256', 'contentTreeSha256']) {
    if (!sha256Hex(externalPackage[field])) {
      collector.add('PACKAGE_DIGEST_INVALID', `gates.externalPackage.${field}`, `${field} must be SHA-256`)
    }
  }
  if (!sha256Hex(externalPackage.bundleSourceSha256)) {
    collector.add('BUNDLE_DIGEST_INVALID', 'gates.externalPackage.bundleSourceSha256',
      'bundleSourceSha256 must be SHA-256')
  }
  if (!nonEmpty(externalPackage.bundleSkillRoot)) {
    collector.add('BUNDLE_SKILL_ROOT_REQUIRED', 'gates.externalPackage.bundleSkillRoot',
      'selected repository or plugin Skill root is required')
  }

  const browser = gates.browserWorkflow || {}
  if (browser.mode !== 'REAL_SERVICE') {
    collector.add('BROWSER_NOT_REAL_SERVICE', 'gates.browserWorkflow.mode', 'browser workflow must use deployed services')
  }
  if (!Number.isInteger(browser.independentPrincipalCount) || browser.independentPrincipalCount < 4) {
    collector.add('BROWSER_PRINCIPALS_INSUFFICIENT', 'gates.browserWorkflow.independentPrincipalCount', 'at least four independent role principals are required')
  }

  const runtime = gates.runtimeE2e || {}
  if (runtime.host !== 'AGENTSCOPE') {
    collector.add('RUNTIME_HOST_MISMATCH', 'gates.runtimeE2e.host', 'current live evidence must use AGENTSCOPE')
  }
  if (runtime.hostE2e !== 'PASSED') {
    collector.add('HOST_E2E_NOT_PASSED', 'gates.runtimeE2e.hostE2e', 'real Host E2E must be PASSED')
  }
  if (!Array.isArray(runtime.traceIds) || runtime.traceIds.length < 2
      || runtime.traceIds.some(value => !nonEmpty(value))) {
    collector.add('TRACE_EVIDENCE_INSUFFICIENT', 'gates.runtimeE2e.traceIds', 'MODEL_SELECTED and ALWAYS require trace identifiers')
  }

  const deploymentReady = collector.issues.length === 0
  return {
    schema: 'reachai-agent-skill-production-readiness-v1',
    environment: {
      name: environment.name || null,
      classification: environment.classification || null,
      changeId: environment.changeId || null,
      sourceRevision: environment.sourceRevision || null,
      artifactRef: environment.artifactRef || null,
    },
    evaluatedAt: new Date(nowMs).toISOString(),
    deploymentReady,
    productionPromotionEligible: deploymentReady && ['staging', 'production'].includes(environment.classification),
    issueCount: collector.issues.length,
    issues: collector.issues,
  }
}

function argument(name) {
  const prefix = `--${name}=`
  const value = process.argv.slice(2).find(item => item.startsWith(prefix))
  return value ? value.slice(prefix.length) : null
}

async function main() {
  const repoRoot = path.resolve(argument('repo') || process.cwd())
  const repository = await inspectAgentSkillRepository(repoRoot)
  const evidencePath = argument('evidence') || process.env.REACHAI_AGENT_SKILL_PRODUCTION_EVIDENCE
  if (!evidencePath) {
    process.stdout.write(`${JSON.stringify(repository, null, 2)}\n`)
    if (!repository.repositoryReady) process.exitCode = 1
    return
  }

  const evidence = JSON.parse(await readFile(path.resolve(evidencePath), 'utf8'))
  const deployment = evaluateAgentSkillDeploymentEvidence(evidence, {
    expectedMigrationChecksum: repository.migration.checksumSha256,
    maxEvidenceAgeDays: Number(
      argument('max-age-days') || process.env.REACHAI_AGENT_SKILL_EVIDENCE_MAX_AGE_DAYS || 30,
    ),
  })
  const report = {
    schema: 'reachai-agent-skill-readiness-report-v1',
    ready: repository.repositoryReady && deployment.deploymentReady,
    productionPromotionEligible: repository.repositoryReady && deployment.productionPromotionEligible,
    repository,
    deployment,
  }
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`)
  if (!report.ready) process.exitCode = 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  })
}
