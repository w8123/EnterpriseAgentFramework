import assert from 'node:assert/strict'
import test from 'node:test'

import {
  ALLOW_EMPTY_ACK,
  ATTEST_ACK,
  AUTOMATED_DOMAINS,
  EXECUTE_ACK,
  MANUAL_DOMAINS,
  runMemoryErasureE2e,
  validateTarget,
} from './verify-memory-erasure-e2e.mjs'

const REQUEST_ID = 'memory-erasure-request-1'
const CLIENT_REQUEST_ID = 'memory-erasure-client-1'
const OWNER = 'e2e-memory-erasure-owner-0001'
const OWNER_HASH = 'a'.repeat(64)
const TENANT = 'tenant-a'

function jsonResponse(status, value) {
  return {
    status,
    ok: status >= 200 && status < 300,
    async text() {
      return value == null ? '' : JSON.stringify(value)
    },
  }
}

function manualEvidence() {
  return MANUAL_DOMAINS.map((domainCode, index) => ({
    domainCode,
    resultCode: index === 0 ? 'ERASED' : 'NOT_APPLICABLE',
    evidenceReference: `change:approved:${index + 1}`,
  }))
}

function snapshot(status, {
  created = false,
  affected = true,
  evidence = new Map(),
  extra = {},
} = {}) {
  const domains = [...AUTOMATED_DOMAINS, ...MANUAL_DOMAINS].map((domainCode, index) => {
    const manual = MANUAL_DOMAINS.includes(domainCode)
    const attestation = evidence.get(domainCode)
    let domainStatus
    if (manual) domainStatus = attestation ? 'COMPLETED' : 'WAITING_EVIDENCE'
    else if (status === 'BLOCKED_LEGAL_HOLD' && domainCode.startsWith('RUNTIME_')) {
      domainStatus = 'BLOCKED_LEGAL_HOLD'
    } else domainStatus = 'COMPLETED'
    return {
      domainCode,
      ownerService: manual ? 'evidence-owner' : 'reachai-service',
      executionMode: manual ? 'MANUAL_EVIDENCE' : 'AUTOMATED',
      status: domainStatus,
      resultCode: attestation?.resultCode
        || (domainStatus === 'BLOCKED_LEGAL_HOLD' ? 'LEGAL_HOLD' : (manual ? null : 'ERASED')),
      affectedCount: manual ? null : (affected ? index + 1 : 0),
      evidenceReference: attestation?.evidenceReference || null,
      attemptCount: manual ? 0 : 1,
      lastFailureCode: domainStatus === 'BLOCKED_LEGAL_HOLD'
        ? 'RUNTIME_SESSION_LEGAL_HOLD'
        : null,
    }
  })
  return {
    requestId: REQUEST_ID,
    clientRequestId: CLIENT_REQUEST_ID,
    tenantId: TENANT,
    runtimeUserHash: OWNER_HASH,
    status,
    reasonCode: 'SYNTHETIC_E2E_ERASURE',
    referenceId: 'change:approved',
    attemptCount: 1,
    created,
    domains,
    ...extra,
  }
}

function completedApi({ affected = true } = {}) {
  let createCount = 0
  const evidence = new Map()
  const requests = []
  return {
    requests,
    async fetch(url, options = {}) {
      const requestUrl = new URL(url)
      const body = options.body ? JSON.parse(options.body) : null
      requests.push({ method: options.method, path: requestUrl.pathname, body })
      assert.equal(options.headers.Authorization, 'Bearer platform-token')
      if (options.method === 'POST'
          && requestUrl.pathname === '/api/context/memory-erasure-requests') {
        createCount++
        assert.equal(body.runtimeUserId, OWNER)
        return jsonResponse(createCount === 1 ? 202 : 200,
          snapshot('ACTION_REQUIRED', { created: createCount === 1, affected, evidence }))
      }
      const match = requestUrl.pathname.match(
        /^\/api\/context\/memory-erasure-requests\/[^/]+\/domains\/([^/]+)\/evidence$/)
      if (options.method === 'POST' && match) {
        const domainCode = decodeURIComponent(match[1])
        const existing = evidence.get(domainCode)
        if (existing && (existing.resultCode !== body.resultCode
            || existing.evidenceReference !== body.evidenceReference)) {
          return jsonResponse(409, { code: 'CONFLICT' })
        }
        evidence.set(domainCode, body)
        const status = evidence.size === MANUAL_DOMAINS.length ? 'COMPLETED' : 'ACTION_REQUIRED'
        return jsonResponse(200, snapshot(status, { created: false, affected, evidence }))
      }
      return jsonResponse(404, { code: 'NOT_FOUND' })
    },
  }
}

function executionOptions(overrides = {}) {
  return {
    executeAck: EXECUTE_ACK,
    token: 'platform-token',
    tenantId: TENANT,
    runtimeUserId: OWNER,
    clientRequestId: CLIENT_REQUEST_ID,
    referenceId: 'change:approved',
    timeoutMs: 1000,
    requestTimeoutMs: 1000,
    runId: 'unit-run',
    ...overrides,
  }
}

test('remote targets require explicit opt-in and HTTPS without URL credentials', () => {
  assert.throws(() => validateTarget('https://memory.example.com', false), /ALLOW_REMOTE/)
  assert.throws(() => validateTarget('http://memory.example.com', true), /HTTPS/)
  assert.throws(() => validateTarget('https://user:secret@memory.example.com', true), /credentials/)
  assert.throws(() => validateTarget('https://memory.example.com/control', true), /application path/)
  assert.equal(validateTarget('https://memory.example.com', true).loopback, false)
  assert.equal(validateTarget('http://127.0.0.1:18603', false).loopback, true)
})

test('dry run performs no requests and exposes no owner or token', async () => {
  let requests = 0
  const result = await runMemoryErasureE2e({
    token: 'must-not-appear',
    runtimeUserId: OWNER,
    fetchImpl: async () => {
      requests++
      throw new Error('must not be called')
    },
    runId: 'dry-run',
  })
  assert.equal(requests, 0)
  assert.equal(result.executed, false)
  assert.deepEqual(result.issues, ['EXECUTION_NOT_CONFIRMED'])
  assert.equal(JSON.stringify(result).includes(OWNER), false)
  assert.equal(JSON.stringify(result).includes('must-not-appear'), false)
})

test('execution rejects a non-synthetic owner before the first request', async () => {
  let requests = 0
  await assert.rejects(() => runMemoryErasureE2e(executionOptions({
    runtimeUserId: 'real-user-123',
    fetchImpl: async () => {
      requests++
      throw new Error('must not be called')
    },
  })), /approved synthetic owner/)
  assert.equal(requests, 0)
})

test('execution rejects placeholder evidence before the first request', async () => {
  let requests = 0
  const evidence = manualEvidence()
  evidence[0].evidenceReference = 'REPLACE_WITH_APPROVED_EVIDENCE'
  await assert.rejects(() => runMemoryErasureE2e(executionOptions({
    attestAck: ATTEST_ACK,
    manualEvidence: evidence,
    fetchImpl: async () => {
      requests++
      throw new Error('must not be called')
    },
  })), /not placeholders/)
  assert.equal(requests, 0)
})

test('manual evidence objects require the versioned schema', async () => {
  let requests = 0
  await assert.rejects(() => runMemoryErasureE2e(executionOptions({
    attestAck: ATTEST_ACK,
    manualEvidence: { domains: manualEvidence() },
    fetchImpl: async () => {
      requests++
      throw new Error('must not be called')
    },
  })), /schema is invalid/)
  assert.equal(requests, 0)
})

test('full run verifies create replay, nine domains, evidence, and evidence replay', async () => {
  const api = completedApi()
  const result = await runMemoryErasureE2e(executionOptions({
    attestAck: ATTEST_ACK,
    manualEvidence: manualEvidence(),
    fetchImpl: api.fetch,
  }))
  assert.equal(result.executed, true)
  assert.equal(result.passed, true)
  assert.equal(result.finalStatus, 'COMPLETED')
  assert.equal(result.createReplayVerified, true)
  assert.equal(result.evidenceReplayVerified, true)
  assert.equal(result.dataPlaneExercised, true)
  assert.equal(result.domains.length, 9)
  assert.equal(result.requestCount, 7)
  assert.equal(JSON.stringify(result).includes(OWNER), false)
  assert.equal(JSON.stringify(result).includes('platform-token'), false)
})

test('zero affected data fails closed before manual attestations', async () => {
  const api = completedApi({ affected: false })
  const result = await runMemoryErasureE2e(executionOptions({
    attestAck: ATTEST_ACK,
    manualEvidence: manualEvidence(),
    fetchImpl: api.fetch,
  }))
  assert.equal(result.passed, false)
  assert.deepEqual(result.issues, ['NO_AUTOMATED_DATA_ERASED'])
  assert.equal(result.dataPlaneExercised, false)
  assert.equal(api.requests.length, 2)
})

test('explicit empty-target acknowledgement is labelled as control-plane-only', async () => {
  const api = completedApi({ affected: false })
  const result = await runMemoryErasureE2e(executionOptions({
    attestAck: ATTEST_ACK,
    allowEmptyAck: ALLOW_EMPTY_ACK,
    manualEvidence: manualEvidence(),
    fetchImpl: api.fetch,
  }))
  assert.equal(result.passed, true)
  assert.equal(result.dataPlaneExercised, false)
  assert.equal(result.dataPlaneAffectedCount, 0)
})

test('Legal Hold negative scenario passes only when a Runtime domain remains blocked', async () => {
  let createCount = 0
  const result = await runMemoryErasureE2e(executionOptions({
    expectedStatus: 'BLOCKED_LEGAL_HOLD',
    fetchImpl: async () => {
      createCount++
      return jsonResponse(createCount === 1 ? 202 : 200,
        snapshot('BLOCKED_LEGAL_HOLD', { created: createCount === 1 }))
    },
  }))
  assert.equal(result.passed, true)
  assert.equal(result.finalStatus, 'BLOCKED_LEGAL_HOLD')
  assert.equal(result.issues.length, 0)
})

test('response identity leak aborts without returning unsafe evidence', async () => {
  await assert.rejects(() => runMemoryErasureE2e(executionOptions({
    fetchImpl: async () => jsonResponse(202,
      snapshot('ACTION_REQUIRED', { created: true, extra: { debugOwner: OWNER } })),
  })), /leaked the raw runtime user id/)
})
