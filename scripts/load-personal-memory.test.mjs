import assert from 'node:assert/strict'
import test from 'node:test'

import {
  EXECUTE_ACK,
  percentile,
  runPersonalMemoryLoad,
  validateTarget,
} from './load-personal-memory.mjs'

function jsonResponse(status, value) {
  return {
    status,
    ok: status >= 200 && status < 300,
    async text() {
      return value == null ? '' : JSON.stringify(value)
    },
  }
}

function safeMemoryApi() {
  let nextId = 1
  const records = new Map()
  return async (url, options = {}) => {
    const actor = options.headers.Authorization
    const method = options.method
    const path = new URL(url).pathname
    const body = options.body ? JSON.parse(options.body) : null
    if (method === 'POST' && path === '/api/context/personal-memories') {
      const id = nextId++
      records.set(id, { id, actor, content: body.content, active: true })
      return jsonResponse(201, { memory: { id } })
    }
    if (method === 'POST' && path === '/api/context/personal-memories/query') {
      const hits = [...records.values()]
        .filter(item => item.active && item.actor === actor && item.content === body.query)
        .map(item => ({ id: item.id }))
      return jsonResponse(200, { hits })
    }
    const deleteMatch = path.match(/^\/api\/context\/personal-memories\/(\d+)$/)
    if (method === 'DELETE' && deleteMatch) {
      const record = records.get(Number(deleteMatch[1]))
      if (record && record.actor === actor) record.active = false
      return jsonResponse(200, { id: Number(deleteMatch[1]), status: 'DELETED' })
    }
    return jsonResponse(404, null)
  }
}

test('remote targets require both explicit opt-in and HTTPS', () => {
  assert.throws(() => validateTarget('https://memory.example.com', false), /ALLOW_REMOTE/)
  assert.throws(() => validateTarget('http://memory.example.com', true), /HTTPS/)
  assert.equal(validateTarget('https://memory.example.com', true).loopback, false)
  assert.equal(validateTarget('http://127.0.0.1:18603', false).loopback, true)
})

test('dry run performs no requests and reports the bounded plan', async () => {
  let requests = 0
  const result = await runPersonalMemoryLoad({
    fetchImpl: async () => {
      requests++
      throw new Error('must not be called')
    },
    samplesPerActor: 3,
  })
  assert.equal(requests, 0)
  assert.equal(result.executed, false)
  assert.equal(result.expectedRequests, 36)
  assert.deepEqual(result.issues, ['EXECUTION_NOT_CONFIRMED'])
})

test('two-owner load verifies recall, isolation, deletion, and cleanup', async () => {
  const result = await runPersonalMemoryLoad({
    executeAck: EXECUTE_ACK,
    actors: [
      { token: 'token-a', tenantId: 'default' },
      { token: 'token-b', tenantId: 'default' },
    ],
    samplesPerActor: 3,
    concurrency: 2,
    fetchImpl: safeMemoryApi(),
    runId: 'unit-run',
  })
  assert.equal(result.executed, true)
  assert.equal(result.passed, true)
  assert.equal(result.actorCount, 2)
  assert.equal(result.requestCount, 36)
  assert.equal(result.errorRatio, 0)
  assert.equal(result.createdCount, 6)
  assert.equal(result.ownRecallMisses, 0)
  assert.equal(result.crossOwnerLeaks, 0)
  assert.equal(result.deletedItemLeaks, 0)
  assert.equal(result.cleanupFailures, 0)
})

test('percentile uses nearest-rank semantics', () => {
  assert.equal(percentile([1, 4, 2, 3], 0.5), 2)
  assert.equal(percentile([1, 4, 2, 3], 0.95), 4)
  assert.equal(percentile([], 0.95), null)
})
