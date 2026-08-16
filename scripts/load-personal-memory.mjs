#!/usr/bin/env node

import { randomUUID } from 'node:crypto'
import { fileURLToPath } from 'node:url'

export const EXECUTE_ACK = 'I_UNDERSTAND_THIS_WRITES_TEST_DATA'

function boundedInteger(value, fallback, min, max, name) {
  const parsed = value == null || String(value).trim() === '' ? fallback : Number(value)
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) {
    throw new Error(`${name} must be an integer between ${min} and ${max}`)
  }
  return parsed
}

function isLoopback(hostname) {
  const value = String(hostname || '').toLowerCase()
  return value === 'localhost' || value === '127.0.0.1' || value === '::1' || value === '[::1]'
}

export function validateTarget(rawBaseUrl, allowRemote = false) {
  const baseUrl = new URL(rawBaseUrl || 'http://127.0.0.1:18603')
  if (baseUrl.username || baseUrl.password || baseUrl.search || baseUrl.hash) {
    throw new Error('load target URL must not contain credentials, query parameters, or fragments')
  }
  const loopback = isLoopback(baseUrl.hostname)
  if (!loopback && !allowRemote) {
    throw new Error('remote load targets require REACHAI_MEMORY_LOAD_ALLOW_REMOTE=YES')
  }
  if (!loopback && baseUrl.protocol !== 'https:') {
    throw new Error('remote load targets must use HTTPS')
  }
  if (baseUrl.protocol !== 'http:' && baseUrl.protocol !== 'https:') {
    throw new Error('load target must use HTTP or HTTPS')
  }
  baseUrl.pathname = baseUrl.pathname.replace(/\/+$/, '') || '/'
  return { baseUrl, loopback }
}

export function percentile(values, ratio) {
  if (!Array.isArray(values) || values.length === 0) return null
  const sorted = [...values].sort((left, right) => left - right)
  const index = Math.max(0, Math.min(sorted.length - 1, Math.ceil(ratio * sorted.length) - 1))
  return Number(sorted[index].toFixed(3))
}

export async function mapConcurrent(items, concurrency, operation) {
  const results = new Array(items.length)
  const errors = []
  let cursor = 0
  async function worker() {
    for (;;) {
      const index = cursor++
      if (index >= items.length) return
      try {
        results[index] = await operation(items[index], index)
      } catch (error) {
        errors.push({ index, error })
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length || 1) }, worker))
  return { results, errors }
}

function safeActor(actor, label) {
  if (!actor || typeof actor.token !== 'string' || actor.token.trim() === '') {
    throw new Error(`actor ${label} bearer token is required`)
  }
  const tenantId = String(actor.tenantId || 'default').trim().toLowerCase()
  if (!/^[a-z0-9._:-]{1,96}$/.test(tenantId)) {
    throw new Error(`actor ${label} tenant id is invalid`)
  }
  return { label, token: actor.token.trim(), tenantId }
}

function timer() {
  const start = process.hrtime.bigint()
  return () => Number(process.hrtime.bigint() - start) / 1_000_000
}

function metricSummary(samples) {
  return {
    count: samples.length,
    p50Ms: percentile(samples, 0.5),
    p95Ms: percentile(samples, 0.95),
    maxMs: samples.length ? Number(Math.max(...samples).toFixed(3)) : null,
  }
}

function responseJson(text, status) {
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    throw new Error(`ReachAI returned non-JSON response with HTTP ${status}`)
  }
}

function memoryHits(payload) {
  return Array.isArray(payload?.hits) ? payload.hits : []
}

export async function runPersonalMemoryLoad({
  baseUrl = 'http://127.0.0.1:18603',
  allowRemote = false,
  executeAck,
  actors,
  samplesPerActor = 25,
  concurrency = 4,
  timeoutMs = 10_000,
  fetchImpl = globalThis.fetch,
  runId = randomUUID(),
} = {}) {
  const target = validateTarget(baseUrl, allowRemote)
  const sampleCount = boundedInteger(samplesPerActor, 25, 1, 200, 'samplesPerActor')
  const workerCount = boundedInteger(concurrency, 4, 1, 32, 'concurrency')
  const requestTimeoutMs = boundedInteger(timeoutMs, 10_000, 250, 120_000, 'timeoutMs')
  const plan = {
    schema: 'reachai-personal-memory-load-plan-v1',
    target: target.baseUrl.origin,
    loopback: target.loopback,
    samplesPerActor: sampleCount,
    actorCount: 2,
    concurrency: workerCount,
    expectedRequests: sampleCount * 12,
    writesTestData: true,
    cleanupAttempted: true,
  }
  if (executeAck !== EXECUTE_ACK) {
    return { ...plan, executed: false, passed: false, issues: ['EXECUTION_NOT_CONFIRMED'] }
  }
  if (typeof fetchImpl !== 'function') throw new Error('a fetch implementation is required')
  if (!Array.isArray(actors) || actors.length !== 2) {
    throw new Error('exactly two independently authenticated actors are required')
  }
  const safeActors = [safeActor(actors[0], 'A'), safeActor(actors[1], 'B')]
  if (safeActors[0].token === safeActors[1].token) {
    throw new Error('actor A and actor B must use different bearer tokens')
  }

  const latency = {
    create: [],
    ownQuery: [],
    crossOwnerQuery: [],
    delete: [],
    deletedQuery: [],
    cleanup: [],
  }
  const requestErrors = []
  const created = []
  let ownMisses = 0
  let crossOwnerLeaks = 0
  let deletedLeaks = 0
  let cleanupFailures = 0

  async function request(actor, method, path, body, metricName) {
    const stop = timer()
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), requestTimeoutMs)
    try {
      const response = await fetchImpl(new URL(path, target.baseUrl), {
        method,
        headers: {
          Authorization: `Bearer ${actor.token}`,
          'Content-Type': 'application/json',
          'X-ReachAI-Tenant-Id': actor.tenantId,
        },
        body: body == null ? undefined : JSON.stringify(body),
        signal: controller.signal,
      })
      const text = await response.text()
      if (!response.ok) throw new Error(`ReachAI returned HTTP ${response.status}`)
      return responseJson(text, response.status)
    } finally {
      clearTimeout(timeout)
      latency[metricName].push(stop())
    }
  }

  function rememberWork(actor) {
    return Array.from({ length: sampleCount }, (_, index) => {
      const canary = `reachai-memory-load-${runId}-${actor.label.toLowerCase()}-${index}`
      return { actor, index, canary }
    })
  }

  try {
    const createWork = safeActors.flatMap(rememberWork)
    const createResult = await mapConcurrent(createWork, workerCount, async work => {
      const response = await request(work.actor, 'POST', '/api/context/personal-memories', {
        type: 'NOTE',
        title: 'ReachAI memory load canary',
        content: work.canary,
        summary: 'Synthetic load-test record; safe to delete.',
        clientRequestId: `memory-load-${runId}-${work.actor.label}-${work.index}`,
      }, 'create')
      const id = Number(response?.memory?.id)
      if (!Number.isSafeInteger(id) || id <= 0) throw new Error('create response omitted a valid memory id')
      const record = { ...work, id }
      created.push(record)
      return record
    })
    requestErrors.push(...createResult.errors.map(item => `CREATE_${item.index}`))

    const ownResult = await mapConcurrent(created, workerCount, async record => {
      const response = await request(record.actor, 'POST', '/api/context/personal-memories/query', {
        query: record.canary,
        topK: 10,
        maxChars: 4096,
      }, 'ownQuery')
      if (!memoryHits(response).some(hit => Number(hit?.id) === record.id)) ownMisses++
    })
    requestErrors.push(...ownResult.errors.map(item => `OWN_QUERY_${item.index}`))

    const crossWork = created.map(record => ({
      queryingActor: record.actor.label === 'A' ? safeActors[1] : safeActors[0],
      target: record,
    }))
    const crossResult = await mapConcurrent(crossWork, workerCount, async work => {
      const response = await request(work.queryingActor, 'POST', '/api/context/personal-memories/query', {
        query: work.target.canary,
        topK: 10,
        maxChars: 4096,
      }, 'crossOwnerQuery')
      if (memoryHits(response).some(hit => Number(hit?.id) === work.target.id)) crossOwnerLeaks++
    })
    requestErrors.push(...crossResult.errors.map(item => `CROSS_QUERY_${item.index}`))

    const deleteResult = await mapConcurrent(created, workerCount, async record => {
      await request(record.actor, 'DELETE', `/api/context/personal-memories/${record.id}`, {
        reason: 'SYNTHETIC_LOAD_TEST_CLEANUP',
      }, 'delete')
    })
    requestErrors.push(...deleteResult.errors.map(item => `DELETE_${item.index}`))

    const deletedResult = await mapConcurrent(created, workerCount, async record => {
      const response = await request(record.actor, 'POST', '/api/context/personal-memories/query', {
        query: record.canary,
        topK: 10,
        maxChars: 4096,
      }, 'deletedQuery')
      if (memoryHits(response).some(hit => Number(hit?.id) === record.id)) deletedLeaks++
    })
    requestErrors.push(...deletedResult.errors.map(item => `DELETED_QUERY_${item.index}`))
  } finally {
    const cleanup = await mapConcurrent(created, workerCount, async record => {
      await request(record.actor, 'DELETE', `/api/context/personal-memories/${record.id}`, {
        reason: 'SYNTHETIC_LOAD_TEST_FINALIZER',
      }, 'cleanup')
    })
    cleanupFailures = cleanup.errors.length
  }

  const issues = []
  if (created.length !== sampleCount * 2) issues.push('CREATE_COUNT_MISMATCH')
  if (requestErrors.length > 0) issues.push('REQUEST_ERRORS')
  if (ownMisses > 0) issues.push('OWN_RECALL_MISSES')
  if (crossOwnerLeaks > 0) issues.push('CROSS_OWNER_LEAKS')
  if (deletedLeaks > 0) issues.push('DELETED_ITEM_LEAKS')
  if (cleanupFailures > 0) issues.push('CLEANUP_FAILURES')
  const totalRequestCount = Object.values(latency)
    .reduce((total, samples) => total + samples.length, 0)
  const queryP95Values = ['ownQuery', 'crossOwnerQuery', 'deletedQuery']
    .map(name => percentile(latency[name], 0.95))
    .filter(value => value != null)
  const queryP95Ms = queryP95Values.length ? Math.max(...queryP95Values) : null
  const errorRatio = totalRequestCount > 0
    ? Number(((requestErrors.length + cleanupFailures) / totalRequestCount).toFixed(6))
    : null
  return {
    schema: 'reachai-personal-memory-load-result-v1',
    runId,
    target: target.baseUrl.origin,
    loopback: target.loopback,
    executed: true,
    passed: issues.length === 0,
    actorCount: safeActors.length,
    samplesPerActor: sampleCount,
    concurrency: workerCount,
    requestCount: totalRequestCount,
    errorRatio,
    queryP95Ms,
    createdCount: created.length,
    requestErrorCount: requestErrors.length,
    ownRecallMisses: ownMisses,
    crossOwnerLeaks,
    deletedItemLeaks: deletedLeaks,
    cleanupFailures,
    latency: Object.fromEntries(Object.entries(latency).map(([key, samples]) => [key, metricSummary(samples)])),
    issues,
  }
}

function actorFromEnvironment(label) {
  return {
    token: process.env[`REACHAI_MEMORY_LOAD_TOKEN_${label}`],
    tenantId: process.env[`REACHAI_MEMORY_LOAD_TENANT_${label}`] || 'default',
  }
}

async function main() {
  const executeAck = process.env.REACHAI_MEMORY_LOAD_EXECUTE
  const options = {
    baseUrl: process.env.REACHAI_MEMORY_LOAD_BASE_URL || 'http://127.0.0.1:18603',
    allowRemote: process.env.REACHAI_MEMORY_LOAD_ALLOW_REMOTE === 'YES',
    executeAck,
    actors: executeAck === EXECUTE_ACK
      ? [actorFromEnvironment('A'), actorFromEnvironment('B')]
      : undefined,
    samplesPerActor: process.env.REACHAI_MEMORY_LOAD_SAMPLES_PER_ACTOR,
    concurrency: process.env.REACHAI_MEMORY_LOAD_CONCURRENCY,
    timeoutMs: process.env.REACHAI_MEMORY_LOAD_TIMEOUT_MS,
  }
  const result = await runPersonalMemoryLoad(options)
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
