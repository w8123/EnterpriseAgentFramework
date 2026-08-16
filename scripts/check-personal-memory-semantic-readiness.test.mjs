import assert from 'node:assert/strict'
import test from 'node:test'

import { evaluateSemanticProjectionReadiness } from './check-personal-memory-semantic-readiness.mjs'

const PREFIX = 'reachai.personal_memory.embedding.projection.'
const NOW = 2_000_000_000

function fetchFor(values) {
  return async url => {
    assert.equal(url.pathname.startsWith('/ai/actuator/metrics/'), true)
    const name = decodeURIComponent(url.pathname.split('/').at(-1)).replace(PREFIX, '')
    if (!(name in values)) return { status: 404, ok: false }
    return {
      status: 200,
      ok: true,
      async json() {
        return { measurements: [{ statistic: 'VALUE', value: values[name] }] }
      },
    }
  }
}

function passingValues() {
  return {
    enabled: 1,
    snapshot_fresh: 1,
    active: 100,
    ready: 100,
    ready_ratio: 1,
    dead: 0,
    oldest_pending_seconds: 0,
    unsafe_deleted_vectors: 0,
    last_success_epoch_seconds: NOW - 10,
  }
}

test('accepts a fresh complete aggregate-only semantic projection', async () => {
  const result = await evaluateSemanticProjectionReadiness({
    baseUrl: 'https://knowledge.example.test/ai/',
    nowEpochSeconds: NOW,
    fetchImpl: fetchFor(passingValues()),
  })
  assert.equal(result.ready, true)
  assert.deepEqual(result.issues, [])
  assert.equal(result.values.snapshotAgeSeconds, 10)
})

test('fails closed for stale, inconsistent, dead, or deleted projection state', async () => {
  const values = passingValues()
  Object.assign(values, {
    snapshot_fresh: 0,
    ready: 80,
    ready_ratio: 0.95,
    dead: 1,
    oldest_pending_seconds: 301,
    unsafe_deleted_vectors: 1,
    last_success_epoch_seconds: NOW - 91,
  })
  const result = await evaluateSemanticProjectionReadiness({
    baseUrl: 'https://knowledge.example.test/ai/',
    nowEpochSeconds: NOW,
    fetchImpl: fetchFor(values),
  })
  assert.equal(result.ready, false)
  for (const issue of [
    'SNAPSHOT_STALE',
    'READY_RATIO_BELOW_THRESHOLD',
    'READY_RATIO_INCONSISTENT',
    'DEAD_COUNT_EXCEEDED',
    'PENDING_AGE_EXCEEDED',
    'DELETED_VECTOR_RESIDUE',
    'LAST_SUCCESS_STALE',
  ]) assert.ok(result.issues.includes(issue), issue)
})

test('missing actuator metrics never pass as zero', async () => {
  const result = await evaluateSemanticProjectionReadiness({
    baseUrl: 'https://knowledge.example.test/ai/',
    nowEpochSeconds: NOW,
    fetchImpl: fetchFor({}),
  })
  assert.equal(result.ready, false)
  assert.ok(result.issues.includes('METRICS_MISSING'))
  assert.equal(result.missingMetrics.length, 9)
})
