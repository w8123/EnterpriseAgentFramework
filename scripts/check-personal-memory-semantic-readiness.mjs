#!/usr/bin/env node

import { fileURLToPath } from 'node:url'

const METRIC_PREFIX = 'reachai.personal_memory.embedding.projection'

function measurement(payload) {
  const entry = payload?.measurements?.find(item => item.statistic === 'VALUE')
    || payload?.measurements?.[0]
  const value = Number(entry?.value)
  return Number.isFinite(value) ? value : null
}

function managementMetricUrl(baseUrl, name) {
  const normalized = String(baseUrl).endsWith('/') ? String(baseUrl) : `${baseUrl}/`
  return new URL(`actuator/metrics/${name}`, normalized)
}

async function metric(baseUrl, name, fetchImpl) {
  const response = await fetchImpl(managementMetricUrl(baseUrl, name))
  if (response.status === 404) return null
  if (!response.ok) throw new Error(`metric ${name} returned HTTP ${response.status}`)
  return measurement(await response.json())
}

export async function evaluateSemanticProjectionReadiness({
  baseUrl = 'http://127.0.0.1:18602/ai/',
  minReadyRatio = 0.99,
  maxDeadCount = 0,
  maxOldestPendingSeconds = 300,
  maxSnapshotAgeSeconds = 90,
  nowEpochSeconds = Date.now() / 1000,
  fetchImpl = globalThis.fetch,
} = {}) {
  const names = [
    'enabled',
    'snapshot_fresh',
    'active',
    'ready',
    'ready_ratio',
    'dead',
    'oldest_pending_seconds',
    'unsafe_deleted_vectors',
    'last_success_epoch_seconds',
  ]
  const values = Object.fromEntries(await Promise.all(names.map(async name => [
    name,
    await metric(baseUrl, `${METRIC_PREFIX}.${name}`, fetchImpl),
  ])))
  const issues = []
  const missing = names.filter(name => values[name] == null)
  if (missing.length > 0) issues.push('METRICS_MISSING')
  if (values.enabled !== 1) issues.push('SEMANTIC_MODE_DISABLED')
  if (values.snapshot_fresh !== 1) issues.push('SNAPSHOT_STALE')
  if (!Number.isInteger(values.active) || values.active < 1) issues.push('ACTIVE_CANARY_REQUIRED')
  if (!Number.isInteger(values.ready) || values.ready < 0
      || (Number.isInteger(values.active) && values.ready > values.active)) {
    issues.push('READY_COUNT_INVALID')
  }
  if (values.ready_ratio == null || values.ready_ratio < minReadyRatio || values.ready_ratio > 1) {
    issues.push('READY_RATIO_BELOW_THRESHOLD')
  }
  if (Number.isInteger(values.active) && values.active > 0 && Number.isInteger(values.ready)
      && values.ready >= 0 && values.ready_ratio != null
      && Math.abs(values.ready / values.active - values.ready_ratio) > 1e-9) {
    issues.push('READY_RATIO_INCONSISTENT')
  }
  if (!Number.isInteger(values.dead) || values.dead < 0 || values.dead > maxDeadCount) {
    issues.push('DEAD_COUNT_EXCEEDED')
  }
  if (values.oldest_pending_seconds == null || values.oldest_pending_seconds < 0
      || values.oldest_pending_seconds > maxOldestPendingSeconds) {
    issues.push('PENDING_AGE_EXCEEDED')
  }
  if (values.unsafe_deleted_vectors !== 0) issues.push('DELETED_VECTOR_RESIDUE')
  const snapshotAgeSeconds = values.last_success_epoch_seconds == null
    ? null
    : nowEpochSeconds - values.last_success_epoch_seconds
  if (snapshotAgeSeconds == null || snapshotAgeSeconds < -300
      || snapshotAgeSeconds > maxSnapshotAgeSeconds) {
    issues.push('LAST_SUCCESS_STALE')
  }
  return {
    schema: 'reachai-personal-memory-semantic-readiness-v1',
    ready: issues.length === 0,
    values: {
      enabled: values.enabled,
      snapshotFresh: values.snapshot_fresh,
      activeCount: values.active,
      readyCount: values.ready,
      readyRatio: values.ready_ratio,
      deadCount: values.dead,
      oldestPendingSeconds: values.oldest_pending_seconds,
      unsafeDeletedVectorCount: values.unsafe_deleted_vectors,
      lastSuccessEpochSeconds: values.last_success_epoch_seconds,
      snapshotAgeSeconds,
    },
    thresholds: {
      minReadyRatio,
      maxDeadCount,
      maxOldestPendingSeconds,
      maxSnapshotAgeSeconds,
    },
    issues: [...new Set(issues)],
    missingMetrics: missing,
  }
}

function numberEnv(name, fallback) {
  const raw = process.env[name]
  if (raw == null || raw.trim() === '') return fallback
  const parsed = Number(raw)
  if (!Number.isFinite(parsed)) throw new Error(`${name} must be numeric`)
  return parsed
}

async function main() {
  const result = await evaluateSemanticProjectionReadiness({
    baseUrl: process.env.REACHAI_KNOWLEDGE_MANAGEMENT_URL || 'http://127.0.0.1:18602/ai/',
    minReadyRatio: numberEnv('REACHAI_MEMORY_SEMANTIC_MIN_READY_RATIO', 0.99),
    maxDeadCount: numberEnv('REACHAI_MEMORY_SEMANTIC_MAX_DEAD_COUNT', 0),
    maxOldestPendingSeconds: numberEnv('REACHAI_MEMORY_SEMANTIC_MAX_PENDING_SECONDS', 300),
    maxSnapshotAgeSeconds: numberEnv('REACHAI_MEMORY_SEMANTIC_MAX_SNAPSHOT_AGE_SECONDS', 90),
  })
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
  if (!result.ready) process.exitCode = 1
}

if (process.argv[1]
    && fileURLToPath(import.meta.url) === fileURLToPath(new URL(`file:///${process.argv[1].replaceAll('\\', '/')}`))) {
  main().catch(error => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  })
}
