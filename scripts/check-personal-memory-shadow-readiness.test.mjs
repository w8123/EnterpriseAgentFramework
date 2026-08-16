import assert from 'node:assert/strict'
import test from 'node:test'

import { evaluateShadowReadiness } from './check-personal-memory-shadow-readiness.mjs'

function response(payload, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    async json() { return payload },
  }
}

function summary(count, total) {
  return { measurements: [
    { statistic: 'COUNT', value: count },
    { statistic: 'TOTAL', value: total },
    { statistic: 'MAX', value: count ? total / count : 0 },
  ] }
}

function gauge(value) {
  return { measurements: [{ statistic: 'VALUE', value }] }
}

test('passes only when shadow quality and safety thresholds are met', async () => {
  const fetchImpl = async url => {
    const name = decodeURIComponent(url.pathname.split('/').at(-1))
    const outcome = url.searchParams.getAll('tag').find(tag => tag.startsWith('outcome:'))
    if (name.endsWith('.requests')) return response(summary(outcome ? 99 : 100, outcome ? 99 : 100))
    if (name.endsWith('.canonical_coverage')) return response(summary(100, 92))
    if (name.endsWith('.projected_precision')) return response(summary(100, 90))
    if (name.endsWith('.invalid_id_ratio')) return response(summary(100, 0.5))
    if (name.endsWith('.outbox.snapshot_fresh')) return response(gauge(1))
    if (name.endsWith('.outbox.backlog')) return response(gauge(2))
    if (name.endsWith('.outbox.dead')) return response(gauge(0))
    if (name.endsWith('.outbox.oldest_unpublished_seconds')) return response(gauge(12))
    return response(null, 404)
  }

  const result = await evaluateShadowReadiness({ fetchImpl })

  assert.equal(result.ready, true)
  assert.equal(result.values.sampleCount, 100)
  assert.equal(result.values.errorRatio, 0.01)
  assert.equal(result.values.canonicalCoverage, 0.92)
  assert.equal(result.values.projectedPrecision, 0.9)
  assert.equal(result.values.invalidIdRatio, 0.005)
  assert.equal(result.values.outboxSnapshotFresh, 1)
  assert.equal(result.values.outboxBacklogCount, 2)
})

test('fails closed when metrics are missing', async () => {
  const result = await evaluateShadowReadiness({
    fetchImpl: async () => response(null, 404),
  })

  assert.equal(result.ready, false)
  assert.deepEqual(result.issues, [
    'INSUFFICIENT_SAMPLES',
    'QUERY_ERROR_RATIO',
    'CANONICAL_COVERAGE',
    'PROJECTED_PRECISION',
    'INVALID_ID_RATIO',
    'OUTBOX_SNAPSHOT_STALE',
    'OUTBOX_BACKLOG_COUNT',
    'OUTBOX_DEAD_LETTERS',
    'OUTBOX_BACKLOG_AGE',
  ])
})

test('fails when canonical projection delivery is stale or dead', async () => {
  const fetchImpl = async url => {
    const name = decodeURIComponent(url.pathname.split('/').at(-1))
    const outcome = url.searchParams.getAll('tag').find(tag => tag.startsWith('outcome:'))
    if (name.endsWith('.requests')) return response(summary(outcome ? 100 : 100, 100))
    if (name.endsWith('.canonical_coverage')) return response(summary(100, 100))
    if (name.endsWith('.projected_precision')) return response(summary(100, 100))
    if (name.endsWith('.invalid_id_ratio')) return response(summary(100, 0))
    if (name.endsWith('.outbox.snapshot_fresh')) return response(gauge(0))
    if (name.endsWith('.outbox.backlog')) return response(gauge(1))
    if (name.endsWith('.outbox.dead')) return response(gauge(1))
    if (name.endsWith('.outbox.oldest_unpublished_seconds')) return response(gauge(301))
    return response(null, 404)
  }
  const result = await evaluateShadowReadiness({ fetchImpl })
  assert.equal(result.ready, false)
  assert.ok(result.issues.includes('OUTBOX_SNAPSHOT_STALE'))
  assert.ok(result.issues.includes('OUTBOX_DEAD_LETTERS'))
  assert.ok(result.issues.includes('OUTBOX_BACKLOG_AGE'))
})
