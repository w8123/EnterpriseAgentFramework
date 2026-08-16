#!/usr/bin/env node

import { fileURLToPath } from 'node:url'

const METRIC_PREFIX = 'reachai.personal_memory.retrieval'
const OUTBOX_METRIC_PREFIX = 'reachai.personal_memory.outbox'

function measurement(payload, statistic) {
  const match = payload?.measurements?.find(item => item.statistic === statistic)
  return Number(match?.value || 0)
}

function average(payload) {
  const count = measurement(payload, 'COUNT')
  return count > 0 ? measurement(payload, 'TOTAL') / count : null
}

function gauge(payload) {
  if (!payload) return null
  const value = payload.measurements?.find(item => item.statistic === 'VALUE')?.value
    ?? payload.measurements?.[0]?.value
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

async function metric(baseUrl, name, tags, fetchImpl) {
  const url = new URL(`/actuator/metrics/${name}`, baseUrl)
  for (const [key, value] of Object.entries(tags)) {
    url.searchParams.append('tag', `${key}:${value}`)
  }
  const response = await fetchImpl(url)
  if (response.status === 404) return null
  if (!response.ok) {
    throw new Error(`metric ${name} returned HTTP ${response.status}`)
  }
  return response.json()
}

export async function evaluateShadowReadiness({
  baseUrl = 'http://127.0.0.1:18603',
  minSamples = 100,
  minCanonicalCoverage = 0.8,
  minProjectedPrecision = 0.8,
  maxInvalidIdRatio = 0.01,
  maxErrorRatio = 0.01,
  maxOutboxDeadCount = 0,
  maxOutboxAgeSeconds = 300,
  fetchImpl = globalThis.fetch,
} = {}) {
  const commonTags = { provider: 'knowledge', mode: 'shadow' }
  const [requests, successfulRequests, coverage, precision, invalidIds,
    outboxFresh, outboxBacklog, outboxDead, outboxOldest] = await Promise.all([
    metric(baseUrl, `${METRIC_PREFIX}.requests`, commonTags, fetchImpl),
    metric(baseUrl, `${METRIC_PREFIX}.requests`, { ...commonTags, outcome: 'success' }, fetchImpl),
    metric(baseUrl, `${METRIC_PREFIX}.canonical_coverage`, commonTags, fetchImpl),
    metric(baseUrl, `${METRIC_PREFIX}.projected_precision`, commonTags, fetchImpl),
    metric(baseUrl, `${METRIC_PREFIX}.invalid_id_ratio`, commonTags, fetchImpl),
    metric(baseUrl, `${OUTBOX_METRIC_PREFIX}.snapshot_fresh`, {}, fetchImpl),
    metric(baseUrl, `${OUTBOX_METRIC_PREFIX}.backlog`, {}, fetchImpl),
    metric(baseUrl, `${OUTBOX_METRIC_PREFIX}.dead`, {}, fetchImpl),
    metric(baseUrl, `${OUTBOX_METRIC_PREFIX}.oldest_unpublished_seconds`, {}, fetchImpl),
  ])

  const sampleCount = requests ? measurement(requests, 'COUNT') : 0
  const successCount = successfulRequests ? measurement(successfulRequests, 'COUNT') : 0
  const errorRatio = sampleCount > 0
    ? Math.max(0, sampleCount - successCount) / sampleCount
    : null
  const values = {
    sampleCount,
    errorRatio,
    canonicalCoverage: average(coverage),
    projectedPrecision: average(precision),
    invalidIdRatio: average(invalidIds),
    outboxSnapshotFresh: gauge(outboxFresh),
    outboxBacklogCount: gauge(outboxBacklog),
    outboxDeadCount: gauge(outboxDead),
    outboxOldestUnpublishedSeconds: gauge(outboxOldest),
  }
  const thresholds = {
    minSamples,
    minCanonicalCoverage,
    minProjectedPrecision,
    maxInvalidIdRatio,
    maxErrorRatio,
    maxOutboxDeadCount,
    maxOutboxAgeSeconds,
  }
  const issues = []
  if (sampleCount < minSamples) issues.push('INSUFFICIENT_SAMPLES')
  if (values.errorRatio == null || values.errorRatio > maxErrorRatio) issues.push('QUERY_ERROR_RATIO')
  if (values.canonicalCoverage == null || values.canonicalCoverage < minCanonicalCoverage) {
    issues.push('CANONICAL_COVERAGE')
  }
  if (values.projectedPrecision == null || values.projectedPrecision < minProjectedPrecision) {
    issues.push('PROJECTED_PRECISION')
  }
  if (values.invalidIdRatio == null || values.invalidIdRatio > maxInvalidIdRatio) {
    issues.push('INVALID_ID_RATIO')
  }
  if (values.outboxSnapshotFresh !== 1) issues.push('OUTBOX_SNAPSHOT_STALE')
  if (!Number.isInteger(values.outboxBacklogCount) || values.outboxBacklogCount < 0) {
    issues.push('OUTBOX_BACKLOG_COUNT')
  }
  if (!Number.isInteger(values.outboxDeadCount)
      || values.outboxDeadCount < 0 || values.outboxDeadCount > maxOutboxDeadCount) {
    issues.push('OUTBOX_DEAD_LETTERS')
  }
  if (values.outboxOldestUnpublishedSeconds == null
      || values.outboxOldestUnpublishedSeconds < 0
      || values.outboxOldestUnpublishedSeconds > maxOutboxAgeSeconds) {
    issues.push('OUTBOX_BACKLOG_AGE')
  }
  return {
    schema: 'reachai-personal-memory-shadow-readiness-v1',
    ready: issues.length === 0,
    provider: 'knowledge',
    mode: 'shadow',
    values,
    thresholds,
    issues,
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
  const result = await evaluateShadowReadiness({
    baseUrl: process.env.REACHAI_CONTROL_URL || 'http://127.0.0.1:18603',
    minSamples: numberEnv('REACHAI_MEMORY_SHADOW_MIN_SAMPLES', 100),
    minCanonicalCoverage: numberEnv('REACHAI_MEMORY_SHADOW_MIN_COVERAGE', 0.8),
    minProjectedPrecision: numberEnv('REACHAI_MEMORY_SHADOW_MIN_PRECISION', 0.8),
    maxInvalidIdRatio: numberEnv('REACHAI_MEMORY_SHADOW_MAX_INVALID_RATIO', 0.01),
    maxErrorRatio: numberEnv('REACHAI_MEMORY_SHADOW_MAX_ERROR_RATIO', 0.01),
    maxOutboxDeadCount: numberEnv('REACHAI_MEMORY_OUTBOX_MAX_DEAD_COUNT', 0),
    maxOutboxAgeSeconds: numberEnv('REACHAI_MEMORY_OUTBOX_MAX_AGE_SECONDS', 300),
  })
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
  if (!result.ready) process.exitCode = 1
}

if (process.argv[1] && fileURLToPath(import.meta.url) === fileURLToPath(new URL(`file:///${process.argv[1].replaceAll('\\', '/')}`))) {
  main().catch(error => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  })
}
