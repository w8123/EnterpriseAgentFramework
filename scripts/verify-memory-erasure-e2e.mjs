#!/usr/bin/env node

import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { REQUIRED_ERASURE_DOMAINS } from './check-personal-memory-production-readiness.mjs'

export const EXECUTE_ACK = 'I_UNDERSTAND_THIS_PERMANENTLY_ERASES_A_SYNTHETIC_AGENT_MEMORY_OWNER'
export const ATTEST_ACK = 'I_HAVE_COLLECTED_AND_APPROVED_ALL_MANUAL_ERASURE_EVIDENCE'
export const ALLOW_EMPTY_ACK = 'I_APPROVE_AN_EMPTY_ERASURE_CONTROL_PLANE_SMOKE'
export const CREATE_CONFIRMATION = 'ERASE_ALL_AGENT_MEMORY_DOMAINS'
export const SYNTHETIC_OWNER_PREFIX = 'e2e-memory-erasure-'

export const AUTOMATED_DOMAINS = Object.freeze([
  'CONTROL_PERSONAL_MEMORY',
  'CONTROL_CANDIDATE_OUTBOX',
  'KNOWLEDGE_PERSONAL_PROJECTION',
  'RUNTIME_SESSION_STATE',
  'RUNTIME_CONVERSATION_LEDGER',
])

export const MANUAL_DOMAINS = Object.freeze([
  'RUNOPS_TRACE_INTERACTION',
  'BUSINESS_INDEX',
  'BUSINESS_SOURCE_SYSTEM',
  'BACKUP_EXPIRY_AND_RESTORE',
])

const REQUEST_STATUSES = new Set([
  'REQUESTED',
  'RUNNING',
  'RETRY',
  'BLOCKED_LEGAL_HOLD',
  'ACTION_REQUIRED',
  'FAILED',
  'COMPLETED',
  'COMPLETED_WITH_RETENTION',
])
const DOMAIN_STATUSES = new Set([
  'PENDING',
  'WAITING_DEPENDENCY',
  'WAITING_EVIDENCE',
  'BLOCKED_LEGAL_HOLD',
  'FAILED',
  'COMPLETED',
])
const MANUAL_RESULT_CODES = new Set(['ERASED', 'NOT_APPLICABLE', 'RETAINED_LEGAL'])
const EXPECTED_FINAL_STATUSES = new Set([
  'COMPLETED',
  'COMPLETED_WITH_RETENTION',
  'BLOCKED_LEGAL_HOLD',
])
const DECISION_STATUSES = new Set([
  'ACTION_REQUIRED',
  'BLOCKED_LEGAL_HOLD',
  'FAILED',
  'COMPLETED',
  'COMPLETED_WITH_RETENTION',
])
const MAX_RESPONSE_CHARS = 1_048_576

function isLoopback(hostname) {
  const value = String(hostname || '').toLowerCase()
  return value === 'localhost' || value === '127.0.0.1' || value === '::1' || value === '[::1]'
}

export function validateTarget(rawBaseUrl, allowRemote = false) {
  const baseUrl = new URL(rawBaseUrl || 'http://127.0.0.1:18603')
  if (baseUrl.username || baseUrl.password || baseUrl.search || baseUrl.hash) {
    throw new Error('memory erasure target URL must not contain credentials, query parameters, or fragments')
  }
  if (baseUrl.protocol !== 'http:' && baseUrl.protocol !== 'https:') {
    throw new Error('memory erasure target must use HTTP or HTTPS')
  }
  if (baseUrl.pathname !== '/' && baseUrl.pathname !== '') {
    throw new Error('memory erasure target URL must not contain an application path')
  }
  const loopback = isLoopback(baseUrl.hostname)
  if (!loopback && !allowRemote) {
    throw new Error('remote memory erasure targets require REACHAI_MEMORY_ERASURE_ALLOW_REMOTE=YES')
  }
  if (!loopback && baseUrl.protocol !== 'https:') {
    throw new Error('remote memory erasure targets must use HTTPS')
  }
  baseUrl.pathname = '/'
  return { baseUrl, loopback }
}

function boundedInteger(value, fallback, min, max, name) {
  const parsed = value == null || String(value).trim() === '' ? fallback : Number(value)
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`)
  }
  return parsed
}

function machineId(value, name, maxLength) {
  const normalized = String(value || '').trim()
  if (!normalized || normalized.length > maxLength
      || !/^[A-Za-z0-9][A-Za-z0-9._:/-]*$/.test(normalized)) {
    throw new Error(`${name} must be a machine-readable value no longer than ${maxLength} characters`)
  }
  return normalized
}

function tenantId(value) {
  const normalized = machineId(value || 'default', 'tenantId', 96).toLowerCase()
  if (!/^[a-z0-9._:-]+$/.test(normalized)) throw new Error('tenantId is invalid')
  return normalized
}

function syntheticOwner(value) {
  const normalized = String(value || '').trim()
  if (!normalized.startsWith(SYNTHETIC_OWNER_PREFIX)
      || normalized.length < SYNTHETIC_OWNER_PREFIX.length + 8
      || normalized.length > 128
      || !/^[A-Za-z0-9._:-]+$/.test(normalized)) {
    throw new Error(`runtimeUserId must be an approved synthetic owner beginning with ${SYNTHETIC_OWNER_PREFIX}`)
  }
  return normalized
}

function bearerToken(value) {
  const normalized = String(value || '').trim()
  if (!normalized || normalized.length > 16_384
      || [...normalized].some(character => character.charCodeAt(0) < 32
        || character.charCodeAt(0) === 127)) {
    throw new Error('a valid platform session bearer token is required')
  }
  return normalized
}

function normalizedEvidence(input) {
  if (!Array.isArray(input)
      && input?.schema !== 'reachai-memory-erasure-manual-evidence-v1') {
    throw new Error('manual evidence schema is invalid')
  }
  const entries = Array.isArray(input) ? input : input?.domains
  if (!Array.isArray(entries)) throw new Error('manual evidence must contain a domains array')
  if (entries.length !== MANUAL_DOMAINS.length) {
    throw new Error(`manual evidence must contain exactly ${MANUAL_DOMAINS.length} domains`)
  }
  const byDomain = new Map()
  for (const entry of entries) {
    const domainCode = machineId(entry?.domainCode, 'manual evidence domainCode', 64).toUpperCase()
    const resultCode = machineId(entry?.resultCode, 'manual evidence resultCode', 64).toUpperCase()
    const evidenceReference = machineId(
      entry?.evidenceReference, 'manual evidence evidenceReference', 128)
    if (/^(replace|example|todo|tbd)(?:[_:/.-]|$)/i.test(evidenceReference)) {
      throw new Error('manual evidence references must be approved immutable references, not placeholders')
    }
    if (!MANUAL_DOMAINS.includes(domainCode) || byDomain.has(domainCode)) {
      throw new Error('manual evidence contains an unknown or duplicate domain')
    }
    if (!MANUAL_RESULT_CODES.has(resultCode)) {
      throw new Error('manual evidence contains an unsupported resultCode')
    }
    byDomain.set(domainCode, { domainCode, resultCode, evidenceReference })
  }
  for (const domainCode of MANUAL_DOMAINS) {
    if (!byDomain.has(domainCode)) throw new Error(`manual evidence is missing ${domainCode}`)
  }
  return MANUAL_DOMAINS.map(domainCode => byDomain.get(domainCode))
}

function validateFinalExpectation(expectedStatus, evidence) {
  const expected = String(expectedStatus || 'COMPLETED').trim().toUpperCase()
  if (!EXPECTED_FINAL_STATUSES.has(expected)) throw new Error('expectedStatus is invalid')
  const retained = evidence?.some(item => item.resultCode === 'RETAINED_LEGAL') || false
  if (expected === 'COMPLETED_WITH_RETENTION' && !retained) {
    throw new Error('COMPLETED_WITH_RETENTION requires at least one RETAINED_LEGAL evidence result')
  }
  if (expected === 'COMPLETED' && retained) {
    throw new Error('COMPLETED cannot be combined with RETAINED_LEGAL evidence')
  }
  if (expected === 'BLOCKED_LEGAL_HOLD' && evidence) {
    throw new Error('manual evidence must not be submitted during the Legal Hold negative scenario')
  }
  return expected
}

function responseJson(text, status) {
  if (!text) throw new Error(`memory erasure API returned an empty HTTP ${status} response`)
  if (text.length > MAX_RESPONSE_CHARS) throw new Error('memory erasure API response exceeded the safety limit')
  try {
    return JSON.parse(text)
  } catch {
    throw new Error(`memory erasure API returned non-JSON HTTP ${status}`)
  }
}

function nonNegativeInteger(value, name, nullable = false) {
  if (nullable && value == null) return null
  if (!Number.isSafeInteger(value) || value < 0) throw new Error(`${name} must be a non-negative integer`)
  return value
}

export function validateSnapshot(snapshot, {
  rawRuntimeUserId,
  expectedTenantId,
  expectedClientRequestId,
  expectedRequestId,
} = {}) {
  if (!snapshot || typeof snapshot !== 'object' || Array.isArray(snapshot)) {
    throw new Error('memory erasure response must be a JSON object')
  }
  if (rawRuntimeUserId && JSON.stringify(snapshot).includes(rawRuntimeUserId)) {
    throw new Error('memory erasure response leaked the raw runtime user id')
  }
  const requestId = machineId(snapshot.requestId, 'response requestId', 64)
  const clientRequestId = machineId(snapshot.clientRequestId, 'response clientRequestId', 64)
  const responseTenant = tenantId(snapshot.tenantId)
  const runtimeUserHash = String(snapshot.runtimeUserHash || '')
  if (!/^[0-9a-f]{64}$/.test(runtimeUserHash)) {
    throw new Error('memory erasure response runtimeUserHash is invalid')
  }
  if (!REQUEST_STATUSES.has(snapshot.status)) throw new Error('memory erasure response status is invalid')
  nonNegativeInteger(snapshot.attemptCount, 'response attemptCount')
  if (expectedRequestId && requestId !== expectedRequestId) throw new Error('memory erasure request id changed')
  if (expectedClientRequestId && clientRequestId !== expectedClientRequestId) {
    throw new Error('memory erasure client request id changed')
  }
  if (expectedTenantId && responseTenant !== expectedTenantId) throw new Error('memory erasure tenant changed')
  if (!Array.isArray(snapshot.domains) || snapshot.domains.length !== REQUIRED_ERASURE_DOMAINS.length) {
    throw new Error('memory erasure response domain inventory is incomplete')
  }
  const domains = new Map()
  for (const domain of snapshot.domains) {
    const code = machineId(domain?.domainCode, 'response domainCode', 64).toUpperCase()
    if (!REQUIRED_ERASURE_DOMAINS.includes(code) || domains.has(code)) {
      throw new Error('memory erasure response has an unknown or duplicate domain')
    }
    const expectedMode = AUTOMATED_DOMAINS.includes(code) ? 'AUTOMATED' : 'MANUAL_EVIDENCE'
    if (domain.executionMode !== expectedMode) throw new Error(`memory erasure domain ${code} has an invalid mode`)
    if (!DOMAIN_STATUSES.has(domain.status)) throw new Error(`memory erasure domain ${code} has an invalid status`)
    nonNegativeInteger(domain.attemptCount, `memory erasure domain ${code} attemptCount`)
    nonNegativeInteger(domain.affectedCount, `memory erasure domain ${code} affectedCount`, true)
    domains.set(code, domain)
  }
  for (const code of REQUIRED_ERASURE_DOMAINS) {
    if (!domains.has(code)) throw new Error(`memory erasure response is missing ${code}`)
  }
  return { snapshot, requestId, clientRequestId, tenantId: responseTenant, runtimeUserHash, domains }
}

function resultDomains(validated) {
  return REQUIRED_ERASURE_DOMAINS.map(domainCode => {
    const domain = validated.domains.get(domainCode)
    return {
      domainCode,
      executionMode: domain.executionMode,
      status: domain.status,
      resultCode: domain.resultCode || null,
      affectedCount: domain.affectedCount ?? null,
      evidenceReference: domain.evidenceReference || null,
      lastFailureCode: domain.lastFailureCode || null,
    }
  })
}

function dataPlaneCount(validated) {
  return AUTOMATED_DOMAINS.reduce((total, domainCode) => {
    const value = validated.domains.get(domainCode)?.affectedCount
    return total + (Number.isSafeInteger(value) && value > 0 ? value : 0)
  }, 0)
}

function resultView({
  target,
  runId,
  startedAt,
  requestCount,
  validated,
  expectedStatus,
  passed,
  issues,
  createReplayVerified,
  evidenceReplayVerified,
}) {
  return {
    schema: 'reachai-memory-erasure-e2e-result-v1',
    runId,
    targetClass: target.loopback ? 'LOOPBACK' : 'REMOTE',
    executed: true,
    passed,
    expectedStatus,
    finalStatus: validated.snapshot.status,
    requestId: validated.requestId,
    runtimeUserHash: validated.runtimeUserHash,
    requestCount,
    dataPlaneAffectedCount: dataPlaneCount(validated),
    dataPlaneExercised: dataPlaneCount(validated) > 0,
    domainInventoryVerified: true,
    identityLeakCheckPassed: true,
    createReplayVerified,
    evidenceReplayVerified,
    startedAt: new Date(startedAt).toISOString(),
    completedAt: new Date().toISOString(),
    domains: resultDomains(validated),
    issues,
  }
}

export async function runMemoryErasureE2e({
  baseUrl = 'http://127.0.0.1:18603',
  allowRemote = false,
  executeAck,
  attestAck,
  allowEmptyAck,
  token,
  tenantId: rawTenantId = 'default',
  runtimeUserId: rawRuntimeUserId,
  clientRequestId: rawClientRequestId,
  reasonCode: rawReasonCode = 'SYNTHETIC_E2E_ERASURE',
  referenceId: rawReferenceId,
  manualEvidence: rawManualEvidence,
  expectedStatus: rawExpectedStatus = 'COMPLETED',
  pollIntervalMs: rawPollIntervalMs = 1000,
  timeoutMs: rawTimeoutMs = 180_000,
  requestTimeoutMs: rawRequestTimeoutMs = 15_000,
  fetchImpl = globalThis.fetch,
  sleepImpl = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds)),
  runId = randomUUID(),
} = {}) {
  const target = validateTarget(baseUrl, allowRemote)
  const plan = {
    schema: 'reachai-memory-erasure-e2e-plan-v1',
    runId,
    targetClass: target.loopback ? 'LOOPBACK' : 'REMOTE',
    executed: false,
    passed: false,
    permanentlyErasesData: true,
    syntheticOwnerRequired: true,
    requiredDomains: REQUIRED_ERASURE_DOMAINS,
    issues: ['EXECUTION_NOT_CONFIRMED'],
  }
  if (executeAck !== EXECUTE_ACK) return plan
  if (typeof fetchImpl !== 'function') throw new Error('a fetch implementation is required')

  const platformToken = bearerToken(token)
  const normalizedTenant = tenantId(rawTenantId)
  const runtimeUserId = syntheticOwner(rawRuntimeUserId)
  const clientRequestId = machineId(
    rawClientRequestId || `memory-erasure-e2e-${runId}`, 'clientRequestId', 64)
  const reasonCode = machineId(rawReasonCode, 'reasonCode', 64).toUpperCase()
  const referenceId = machineId(
    rawReferenceId || `e2e:${runId}`, 'referenceId', 128)
  const manualEvidence = rawManualEvidence == null ? null : normalizedEvidence(rawManualEvidence)
  const expectedStatus = validateFinalExpectation(rawExpectedStatus, manualEvidence)
  if (manualEvidence && attestAck !== ATTEST_ACK) {
    throw new Error('manual evidence submission requires REACHAI_MEMORY_ERASURE_ATTEST_EXECUTE confirmation')
  }
  const pollIntervalMs = boundedInteger(rawPollIntervalMs, 1000, 10, 30_000, 'pollIntervalMs')
  const timeoutMs = boundedInteger(rawTimeoutMs, 180_000, 100, 1_800_000, 'timeoutMs')
  const requestTimeoutMs = boundedInteger(rawRequestTimeoutMs, 15_000, 250, 120_000, 'requestTimeoutMs')
  const startedAt = Date.now()
  const deadline = startedAt + timeoutMs
  let requestCount = 0

  async function request(method, requestPath, body) {
    requestCount++
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), requestTimeoutMs)
    try {
      let response
      try {
        response = await fetchImpl(new URL(requestPath, target.baseUrl), {
          method,
          headers: {
            Authorization: `Bearer ${platformToken}`,
            Accept: 'application/json',
            'X-ReachAI-Tenant-Id': normalizedTenant,
            ...(body == null ? {} : { 'Content-Type': 'application/json' }),
          },
          body: body == null ? undefined : JSON.stringify(body),
          signal: controller.signal,
        })
      } catch {
        throw new Error(`memory erasure API ${method} request failed`)
      }
      const text = await response.text()
      if (!response.ok) throw new Error(`memory erasure API returned HTTP ${response.status}`)
      return responseJson(text, response.status)
    } finally {
      clearTimeout(timeout)
    }
  }

  const createBody = {
    confirmation: CREATE_CONFIRMATION,
    clientRequestId,
    tenantId: normalizedTenant,
    runtimeUserId,
    reasonCode,
    referenceId,
  }
  const first = validateSnapshot(await request(
    'POST', '/api/context/memory-erasure-requests', createBody), {
    rawRuntimeUserId: runtimeUserId,
    expectedTenantId: normalizedTenant,
    expectedClientRequestId: clientRequestId,
  })
  const replay = validateSnapshot(await request(
    'POST', '/api/context/memory-erasure-requests', createBody), {
    rawRuntimeUserId: runtimeUserId,
    expectedTenantId: normalizedTenant,
    expectedClientRequestId: clientRequestId,
    expectedRequestId: first.requestId,
  })
  if (replay.snapshot.created !== false) throw new Error('memory erasure create replay was not idempotent')
  const requestPath = `/api/context/memory-erasure-requests/${encodeURIComponent(first.requestId)}`
  let current = replay
  while (!DECISION_STATUSES.has(current.snapshot.status) && Date.now() < deadline) {
    await sleepImpl(pollIntervalMs)
    current = validateSnapshot(await request('GET', requestPath), {
      rawRuntimeUserId: runtimeUserId,
      expectedTenantId: normalizedTenant,
      expectedClientRequestId: clientRequestId,
      expectedRequestId: first.requestId,
    })
  }
  if (!DECISION_STATUSES.has(current.snapshot.status)) {
    return resultView({
      target, runId, startedAt, requestCount, validated: current, expectedStatus,
      passed: false, issues: ['ORCHESTRATION_TIMEOUT'], createReplayVerified: true,
      evidenceReplayVerified: false,
    })
  }

  if (expectedStatus === 'BLOCKED_LEGAL_HOLD') {
    const runtimeBlocked = ['RUNTIME_SESSION_STATE', 'RUNTIME_CONVERSATION_LEDGER']
      .some(code => current.domains.get(code)?.status === 'BLOCKED_LEGAL_HOLD')
    const passed = current.snapshot.status === expectedStatus && runtimeBlocked
    return resultView({
      target, runId, startedAt, requestCount, validated: current, expectedStatus, passed,
      issues: passed ? [] : ['LEGAL_HOLD_WAS_NOT_PRESERVED'],
      createReplayVerified: true, evidenceReplayVerified: false,
    })
  }
  if (current.snapshot.status === 'BLOCKED_LEGAL_HOLD') {
    return resultView({
      target, runId, startedAt, requestCount, validated: current, expectedStatus,
      passed: false, issues: ['UNEXPECTED_LEGAL_HOLD'], createReplayVerified: true,
      evidenceReplayVerified: false,
    })
  }
  if (current.snapshot.status === 'FAILED') {
    return resultView({
      target, runId, startedAt, requestCount, validated: current, expectedStatus,
      passed: false, issues: ['ORCHESTRATION_FAILED'], createReplayVerified: true,
      evidenceReplayVerified: false,
    })
  }
  if (dataPlaneCount(current) === 0 && allowEmptyAck !== ALLOW_EMPTY_ACK) {
    return resultView({
      target, runId, startedAt, requestCount, validated: current, expectedStatus,
      passed: false, issues: ['NO_AUTOMATED_DATA_ERASED'], createReplayVerified: true,
      evidenceReplayVerified: false,
    })
  }

  let evidenceReplayVerified = false
  if (current.snapshot.status === 'ACTION_REQUIRED') {
    if (!manualEvidence) {
      return resultView({
        target, runId, startedAt, requestCount, validated: current, expectedStatus,
        passed: false, issues: ['MANUAL_EVIDENCE_REQUIRED'], createReplayVerified: true,
        evidenceReplayVerified: false,
      })
    }
    for (const evidence of manualEvidence) {
      const evidencePath = `${requestPath}/domains/${encodeURIComponent(evidence.domainCode)}/evidence`
      current = validateSnapshot(await request('POST', evidencePath, {
        resultCode: evidence.resultCode,
        evidenceReference: evidence.evidenceReference,
      }), {
        rawRuntimeUserId: runtimeUserId,
        expectedTenantId: normalizedTenant,
        expectedClientRequestId: clientRequestId,
        expectedRequestId: first.requestId,
      })
      const persisted = current.domains.get(evidence.domainCode)
      if (persisted.status !== 'COMPLETED'
          || persisted.resultCode !== evidence.resultCode
          || persisted.evidenceReference !== evidence.evidenceReference) {
        throw new Error(`memory erasure evidence was not persisted for ${evidence.domainCode}`)
      }
    }
  }
  if (manualEvidence) {
    const evidence = manualEvidence[0]
    const replayPath = `${requestPath}/domains/${encodeURIComponent(evidence.domainCode)}/evidence`
    const evidenceReplay = validateSnapshot(await request('POST', replayPath, {
      resultCode: evidence.resultCode,
      evidenceReference: evidence.evidenceReference,
    }), {
      rawRuntimeUserId: runtimeUserId,
      expectedTenantId: normalizedTenant,
      expectedClientRequestId: clientRequestId,
      expectedRequestId: first.requestId,
    })
    const persisted = evidenceReplay.domains.get(evidence.domainCode)
    evidenceReplayVerified = persisted.status === 'COMPLETED'
      && persisted.resultCode === evidence.resultCode
      && persisted.evidenceReference === evidence.evidenceReference
    current = evidenceReplay
  }

  const everyDomainComplete = REQUIRED_ERASURE_DOMAINS
    .every(code => current.domains.get(code)?.status === 'COMPLETED')
  const passed = current.snapshot.status === expectedStatus
    && everyDomainComplete
    && (!manualEvidence || evidenceReplayVerified)
  const issues = []
  if (current.snapshot.status !== expectedStatus) issues.push('UNEXPECTED_FINAL_STATUS')
  if (!everyDomainComplete) issues.push('DOMAIN_NOT_COMPLETED')
  if (manualEvidence && !evidenceReplayVerified) issues.push('EVIDENCE_REPLAY_NOT_IDEMPOTENT')
  return resultView({
    target, runId, startedAt, requestCount, validated: current, expectedStatus, passed, issues,
    createReplayVerified: true, evidenceReplayVerified,
  })
}

function argument(name) {
  const prefix = `--${name}=`
  const value = process.argv.slice(2).find(item => item.startsWith(prefix))
  return value ? value.slice(prefix.length) : null
}

async function main() {
  const executeAck = process.env.REACHAI_MEMORY_ERASURE_EXECUTE
  const evidencePath = argument('manual-evidence')
    || process.env.REACHAI_MEMORY_ERASURE_MANUAL_EVIDENCE
  const manualEvidence = executeAck === EXECUTE_ACK && evidencePath
    ? JSON.parse(await readFile(path.resolve(evidencePath), 'utf8'))
    : null
  const result = await runMemoryErasureE2e({
    baseUrl: process.env.REACHAI_MEMORY_ERASURE_BASE_URL || 'http://127.0.0.1:18603',
    allowRemote: process.env.REACHAI_MEMORY_ERASURE_ALLOW_REMOTE === 'YES',
    executeAck,
    attestAck: process.env.REACHAI_MEMORY_ERASURE_ATTEST_EXECUTE,
    allowEmptyAck: process.env.REACHAI_MEMORY_ERASURE_ALLOW_EMPTY,
    token: executeAck === EXECUTE_ACK
      ? process.env.REACHAI_MEMORY_ERASURE_PLATFORM_SESSION_TOKEN
      : undefined,
    tenantId: process.env.REACHAI_MEMORY_ERASURE_TENANT_ID || 'default',
    runtimeUserId: process.env.REACHAI_MEMORY_ERASURE_RUNTIME_USER_ID,
    clientRequestId: process.env.REACHAI_MEMORY_ERASURE_CLIENT_REQUEST_ID,
    reasonCode: process.env.REACHAI_MEMORY_ERASURE_REASON_CODE || 'SYNTHETIC_E2E_ERASURE',
    referenceId: process.env.REACHAI_MEMORY_ERASURE_REFERENCE_ID,
    manualEvidence,
    expectedStatus: process.env.REACHAI_MEMORY_ERASURE_EXPECTED_STATUS || 'COMPLETED',
    pollIntervalMs: process.env.REACHAI_MEMORY_ERASURE_POLL_INTERVAL_MS,
    timeoutMs: process.env.REACHAI_MEMORY_ERASURE_TIMEOUT_MS,
    requestTimeoutMs: process.env.REACHAI_MEMORY_ERASURE_REQUEST_TIMEOUT_MS,
  })
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
  if (result.executed && !result.passed) process.exitCode = 1
}

if (process.argv[1]
    && fileURLToPath(import.meta.url) === fileURLToPath(new URL(`file:///${process.argv[1].replaceAll('\\', '/')}`))) {
  main().catch(error => {
    process.stderr.write(`${error.message}\n`)
    process.exitCode = 2
  })
}
