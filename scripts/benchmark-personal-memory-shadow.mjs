#!/usr/bin/env node

import { createHmac, randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { performance } from 'node:perf_hooks'

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url))
const DEFAULT_FIXTURE = path.join(SCRIPT_DIR, 'fixtures', 'personal-memory-shadow-benchmark-v1.json')
const DEFAULT_MODEL = 'BAAI/bge-small-zh-v1.5'
const DEFAULT_TEI_BASE_URL = 'http://127.0.0.1:18781/v1'
const DEFAULT_MIN_SCORE = 0.5
const ADAPTER_SCHEMA = 'reachai-personal-memory-shadow-adapter-v1'

function boundedNumber(value, fallback, minimum, maximum) {
  const parsed = value == null ? fallback : Number(value)
  if (!Number.isFinite(parsed)) throw new Error(`expected a number, got ${value}`)
  return Math.max(minimum, Math.min(maximum, parsed))
}

export function createOwnerKey(tenantId, runtimeUserId, salt = 'synthetic-benchmark-owner-key-v1') {
  if (!tenantId || !runtimeUserId) throw new Error('tenantId and runtimeUserId are required')
  return `pm_${createHmac('sha256', salt).update(`${tenantId}\u0000${runtimeUserId}`, 'utf8').digest('hex')}`
}

function searchText(memory) {
  return [memory.type, memory.title, memory.summary, memory.content]
    .filter(value => typeof value === 'string' && value.trim())
    .join('\n')
}

export function sanitizeFixture(fixture, ownerSalt = 'synthetic-benchmark-owner-key-v1') {
  if (fixture?.schema !== 'reachai-personal-memory-shadow-benchmark-fixture-v1') {
    throw new Error('unsupported personal-memory benchmark fixture schema')
  }
  const rawIdentities = new Set()
  const owner = value => {
    rawIdentities.add(value.tenantId)
    rawIdentities.add(value.runtimeUserId)
    return createOwnerKey(value.tenantId, value.runtimeUserId, ownerSalt)
  }
  const memories = fixture.memories.map(memory => ({
    canonicalId: memory.canonicalId,
    ownerKey: owner(memory),
    status: memory.status,
    lifecycle: memory.lifecycle || null,
    text: searchText(memory),
  }))
  const queries = fixture.queries.map(query => ({
    queryId: query.queryId,
    ownerKey: owner(query),
    text: query.text,
    topK: fixture.topK || 3,
  }))
  const adapterPayload = {
    schema: ADAPTER_SCHEMA,
    benchmarkId: fixture.benchmarkId,
    memories,
    queries,
  }
  const serialized = JSON.stringify(adapterPayload)
  for (const identity of rawIdentities) {
    if (identity && serialized.includes(identity)) {
      throw new Error(`raw identity escaped benchmark projection: ${identity}`)
    }
  }
  return { adapterPayload, rawIdentities }
}

function normalize(value) {
  return String(value || '').trim().replaceAll(/\s+/g, ' ').toLowerCase()
}

export function lexicalScore(text, query) {
  const document = normalize(text)
  const normalizedQuery = normalize(query)
  if (!normalizedQuery) return 1
  let score = document.includes(normalizedQuery) ? 3 : 0
  for (const token of normalizedQuery.split(/[\s,，。！？、;；:：]+/u)) {
    if (token.length >= 2 && document.includes(token)) score += 1
  }
  return score
}

function percentile(values, percentage) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const index = Math.max(0, Math.ceil((percentage / 100) * sorted.length) - 1)
  return Number(sorted[index].toFixed(3))
}

function cosine(left, right) {
  if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length || left.length === 0) return -1
  let dot = 0
  let leftNorm = 0
  let rightNorm = 0
  for (let index = 0; index < left.length; index += 1) {
    dot += left[index] * right[index]
    leftNorm += left[index] * left[index]
    rightNorm += right[index] * right[index]
  }
  if (leftNorm === 0 || rightNorm === 0) return -1
  return dot / Math.sqrt(leftNorm * rightNorm)
}

async function openAiEmbeddings(texts, { baseUrl, model, dimensions, fetchImpl = globalThis.fetch }) {
  const endpoint = new URL('embeddings', baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`)
  const response = await fetchImpl(endpoint, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: 'Bearer benchmark-only',
    },
    body: JSON.stringify({ model, input: texts, dimensions, encoding_format: 'float' }),
  })
  if (!response.ok) {
    throw new Error(`embedding endpoint returned HTTP ${response.status}: ${(await response.text()).slice(0, 300)}`)
  }
  const payload = await response.json()
  const rows = [...(payload.data || [])].sort((left, right) => left.index - right.index)
  const vectors = rows.map(row => row.embedding)
  if (vectors.length !== texts.length || vectors.some(vector => vector.length !== dimensions)) {
    throw new Error(`embedding response shape mismatch: expected ${texts.length}x${dimensions}`)
  }
  return vectors
}

export function createLexicalProvider(adapterPayload) {
  const active = adapterPayload.memories.filter(memory => memory.status === 'ACTIVE')
  return {
    name: 'reachai-lexical',
    implementation: 'ReachAI bounded lexical baseline',
    lifecycle: {
      attemptedDeleteIds: adapterPayload.memories.filter(memory => memory.lifecycle === 'ADD_THEN_DELETE').map(memory => memory.canonicalId),
      confirmedAbsent: adapterPayload.memories.filter(memory => memory.lifecycle === 'ADD_THEN_DELETE').every(
        memory => !active.some(candidate => candidate.canonicalId === memory.canonicalId),
      ),
    },
    async search(query) {
      const startedAt = performance.now()
      const hits = active
        .filter(memory => memory.ownerKey === query.ownerKey)
        .map(memory => ({ memoryId: memory.canonicalId, score: lexicalScore(memory.text, query.text) }))
        .filter(hit => hit.score > 0)
        .sort((left, right) => right.score - left.score || left.memoryId.localeCompare(right.memoryId))
        .slice(0, query.topK)
      return { queryId: query.queryId, latencyMs: performance.now() - startedAt, hits }
    },
  }
}

export async function createTeiProvider(adapterPayload, {
  mode = 'hybrid',
  baseUrl = DEFAULT_TEI_BASE_URL,
  model = DEFAULT_MODEL,
  dimensions = 512,
  minScore = DEFAULT_MIN_SCORE,
  fetchImpl = globalThis.fetch,
} = {}) {
  const allMemories = adapterPayload.memories
  const allVectors = await openAiEmbeddings(allMemories.map(memory => memory.text), {
    baseUrl,
    model,
    dimensions,
    fetchImpl,
  })
  // The deletion canary is embedded first, then removed from the searchable projection.
  const active = allMemories
    .map((memory, index) => ({ ...memory, vector: allVectors[index] }))
    .filter(memory => memory.status === 'ACTIVE')
  const deleted = allMemories.filter(memory => memory.lifecycle === 'ADD_THEN_DELETE')
  return {
    name: mode === 'vector' ? 'reachai-tei-vector' : 'reachai-tei-hybrid',
    implementation: mode === 'vector'
      ? 'ReachAI owner-scoped cosine retrieval over OpenAI-compatible TEI'
      : 'ReachAI owner-scoped TEI cosine plus lexical reciprocal-rank fusion',
    metadata: { baseUrl, model, dimensions, mode, minScore, calibration: 'SYNTHETIC_DEVELOPER_CANARY_ONLY' },
    lifecycle: {
      attemptedDeleteIds: deleted.map(memory => memory.canonicalId),
      confirmedAbsent: deleted.every(memory => !active.some(candidate => candidate.canonicalId === memory.canonicalId)),
    },
    async search(query) {
      const startedAt = performance.now()
      const [queryVector] = await openAiEmbeddings([query.text], { baseUrl, model, dimensions, fetchImpl })
      const candidates = active.filter(memory => memory.ownerKey === query.ownerKey)
      const vectorRanked = candidates
        .map(memory => ({ memoryId: memory.canonicalId, score: cosine(memory.vector, queryVector) }))
        .filter(hit => hit.score >= minScore)
        .sort((left, right) => right.score - left.score || left.memoryId.localeCompare(right.memoryId))
      let hits = vectorRanked
      if (mode === 'hybrid') {
        const lexicalRanked = candidates
          .map(memory => ({ memoryId: memory.canonicalId, score: lexicalScore(memory.text, query.text) }))
          .filter(hit => hit.score > 0)
          .sort((left, right) => right.score - left.score || left.memoryId.localeCompare(right.memoryId))
        const fused = new Map()
        vectorRanked.forEach((hit, rank) => fused.set(hit.memoryId, {
          memoryId: hit.memoryId,
          score: 0.7 / (61 + rank),
        }))
        lexicalRanked.forEach((hit, rank) => {
          const current = fused.get(hit.memoryId) || { memoryId: hit.memoryId, score: 0 }
          current.score += 0.3 / (61 + rank)
          fused.set(hit.memoryId, current)
        })
        hits = [...fused.values()].sort(
          (left, right) => right.score - left.score || left.memoryId.localeCompare(right.memoryId),
        )
      }
      return {
        queryId: query.queryId,
        latencyMs: performance.now() - startedAt,
        hits: hits.slice(0, query.topK),
      }
    },
  }
}

async function runProcess(executable, args, { env, timeoutMs = 240_000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      env: { ...process.env, ...env },
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', chunk => { stdout += chunk.toString('utf8') })
    child.stderr.on('data', chunk => { stderr += chunk.toString('utf8') })
    const timer = setTimeout(() => {
      child.kill()
      reject(new Error(`adapter timed out after ${timeoutMs} ms`))
    }, timeoutMs)
    child.once('error', error => {
      clearTimeout(timer)
      reject(error)
    })
    child.once('exit', code => {
      clearTimeout(timer)
      if (code === 0) resolve({ stdout, stderr })
      else reject(new Error(`adapter exited ${code}: ${(stderr || stdout).slice(-2_000)}`))
    })
  })
}

function assertIdOnlyAdapterResult(result, rawIdentities) {
  if (result?.schema !== ADAPTER_SCHEMA || !Array.isArray(result.queries)) {
    throw new Error('external adapter returned an unsupported schema')
  }
  for (const query of result.queries) {
    if (!Array.isArray(query.hits)) throw new Error(`adapter query ${query.queryId} has no hits array`)
    for (const hit of query.hits) {
      const extra = Object.keys(hit).filter(key => !['memoryId', 'score'].includes(key))
      if (!hit.memoryId || extra.length) {
        throw new Error(`adapter hit contract must be ID-only; extra fields: ${extra.join(',')}`)
      }
    }
  }
  const serialized = JSON.stringify(result)
  for (const identity of rawIdentities) {
    if (identity && serialized.includes(identity)) throw new Error(`external adapter exposed raw identity: ${identity}`)
  }
}

export async function runExternalAdapter({
  provider,
  python,
  adapter,
  adapterPayload,
  rawIdentities,
  baseUrl = DEFAULT_TEI_BASE_URL,
  model = DEFAULT_MODEL,
  dimensions = 512,
  minScore = DEFAULT_MIN_SCORE,
}) {
  if (!python) throw new Error(`${provider} Python executable is required`)
  const tempRoot = await mkdtemp(path.join(os.tmpdir(), `reachai-${provider}-shadow-${randomUUID()}-`))
  const inputPath = path.join(tempRoot, 'input.json')
  const outputPath = path.join(tempRoot, 'output.json')
  const workspace = path.join(tempRoot, 'workspace')
  await mkdir(workspace, { recursive: true })
  await writeFile(inputPath, `${JSON.stringify(adapterPayload, null, 2)}\n`, 'utf8')
  try {
    await runProcess(python, [
      adapter,
      '--input', inputPath,
      '--output', outputPath,
      '--workspace', workspace,
      '--base-url', baseUrl,
      '--model', model,
      '--dimensions', String(dimensions),
      '--min-score', String(minScore),
    ], {
      env: {
        PYTHONUTF8: '1',
        PYTHONIOENCODING: 'utf-8',
        MEM0_TELEMETRY: 'False',
        NO_PROXY: '127.0.0.1,localhost',
      },
    })
    const result = JSON.parse(await readFile(outputPath, 'utf8'))
    assertIdOnlyAdapterResult(result, rawIdentities)
    return {
      name: result.provider || provider,
      implementation: result.implementation || provider,
      metadata: result.metadata || {},
      lifecycle: result.lifecycle || {},
      precomputedQueries: result.queries,
    }
  } finally {
    await rm(tempRoot, { recursive: true, force: true })
  }
}

function dcg(relevances) {
  return relevances.reduce((total, relevance, index) => total + relevance / Math.log2(index + 2), 0)
}

export function evaluateProvider({ fixture, ownerSalt, provider, queryResults }) {
  const canonical = new Map(fixture.memories.map(memory => [memory.canonicalId, {
    ownerKey: createOwnerKey(memory.tenantId, memory.runtimeUserId, ownerSalt),
    status: memory.status,
  }]))
  const byQuery = new Map(queryResults.map(result => [result.queryId, result]))
  const positiveQueries = fixture.queries.filter(query => (query.expectedIds || []).length > 0)
  let expectedTotal = 0
  let relevantAtK = 0
  let relevantReturned = 0
  let returnedAtK = 0
  let reciprocalRank = 0
  let ndcg = 0
  let invalid = 0
  let crossOwner = 0
  let deleted = 0
  let forbidden = 0
  let returned = 0
  let errors = 0
  let negativeQueriesWithHits = 0
  let negativeReturned = 0
  const latencies = []
  const queryEvidence = []

  for (const query of fixture.queries) {
    const result = byQuery.get(query.queryId)
    if (!result || result.error) {
      errors += 1
      queryEvidence.push({ queryId: query.queryId, error: result?.error || 'MISSING_RESULT', hits: [] })
      continue
    }
    const hits = (result.hits || []).slice(0, fixture.topK || 3)
    const ids = hits.map(hit => hit.memoryId)
    const expected = new Set(query.expectedIds || [])
    const forbiddenIds = new Set(query.forbiddenIds || [])
    const queryOwner = createOwnerKey(query.tenantId, query.runtimeUserId, ownerSalt)
    returned += ids.length
    if (expected.size === 0) {
      if (ids.length > 0) negativeQueriesWithHits += 1
      negativeReturned += ids.length
    }
    returnedAtK += ids.length
    relevantReturned += ids.filter(id => expected.has(id)).length
    forbidden += ids.filter(id => forbiddenIds.has(id)).length
    for (const id of ids) {
      const item = canonical.get(id)
      if (!item) invalid += 1
      else if (item.ownerKey !== queryOwner) crossOwner += 1
      else if (item.status !== 'ACTIVE') deleted += 1
    }
    if (expected.size > 0) {
      expectedTotal += expected.size
      const retrievedRelevant = ids.filter(id => expected.has(id)).length
      relevantAtK += retrievedRelevant
      const firstRank = ids.findIndex(id => expected.has(id))
      if (firstRank >= 0) reciprocalRank += 1 / (firstRank + 1)
      const relevances = ids.map(id => expected.has(id) ? 1 : 0)
      const ideal = Array.from({ length: Math.min(expected.size, fixture.topK || 3) }, () => 1)
      ndcg += ideal.length ? dcg(relevances) / dcg(ideal) : 0
    }
    if (Number.isFinite(result.latencyMs)) latencies.push(result.latencyMs)
    queryEvidence.push({
      queryId: query.queryId,
      hits: hits.map(hit => ({
        memoryId: hit.memoryId,
        score: Number.isFinite(Number(hit.score)) ? Number(Number(hit.score).toFixed(6)) : null,
      })),
    })
  }

  const successful = fixture.queries.length - errors
  const projectedValid = Math.max(0, returned - invalid - crossOwner - deleted)
  const metrics = {
    sampleCount: fixture.queries.length,
    positiveQueryCount: positiveQueries.length,
    errorRatio: fixture.queries.length ? errors / fixture.queries.length : 1,
    canonicalCoverageAtK: expectedTotal ? relevantAtK / expectedTotal : null,
    projectedPrecision: returned ? projectedValid / returned : successful ? 1 : 0,
    relevancePrecisionAtK: returnedAtK ? relevantReturned / returnedAtK : 0,
    meanReciprocalRank: positiveQueries.length ? reciprocalRank / positiveQueries.length : null,
    nDcgAtK: positiveQueries.length ? ndcg / positiveQueries.length : null,
    invalidIdRatio: returned ? invalid / returned : 0,
    crossOwnerLeakRatio: returned ? crossOwner / returned : 0,
    deletedItemLeakRatio: returned ? deleted / returned : 0,
    explicitForbiddenHitCount: forbidden,
    negativeQueryFalsePositiveRate: (fixture.queries.length - positiveQueries.length)
      ? negativeQueriesWithHits / (fixture.queries.length - positiveQueries.length)
      : null,
    negativeQueryMeanReturned: (fixture.queries.length - positiveQueries.length)
      ? negativeReturned / (fixture.queries.length - positiveQueries.length)
      : null,
    latencyMs: { p50: percentile(latencies, 50), p95: percentile(latencies, 95) },
  }
  const safetyPassed = metrics.errorRatio === 0
    && metrics.invalidIdRatio === 0
    && metrics.crossOwnerLeakRatio === 0
    && metrics.deletedItemLeakRatio === 0
    && metrics.explicitForbiddenHitCount === 0
    && provider.lifecycle?.confirmedAbsent !== false
  const developerQualityPassed = safetyPassed
    && metrics.canonicalCoverageAtK >= 0.8
    && metrics.meanReciprocalRank >= 0.7
    && (metrics.negativeQueryFalsePositiveRate == null || metrics.negativeQueryFalsePositiveRate <= 0.1)
  return {
    provider: provider.name,
    implementation: provider.implementation,
    metadata: provider.metadata || {},
    lifecycle: provider.lifecycle || {},
    safetyPassed,
    developerQualityPassed,
    productionPromotionEligible: false,
    productionPromotionBlockers: [
      'SYNTHETIC_CORPUS_ONLY',
      metrics.sampleCount < 100 ? 'INSUFFICIENT_SHADOW_SAMPLES' : null,
      'PRODUCTION_LOAD_AND_FAILURE_DRILLS_NOT_RUN',
    ].filter(Boolean),
    metrics,
    queryEvidence,
  }
}

async function executeProvider(provider, adapterPayload) {
  if (provider.precomputedQueries) return provider.precomputedQueries
  const results = []
  for (const query of adapterPayload.queries) {
    try {
      results.push(await provider.search(query))
    } catch (error) {
      results.push({ queryId: query.queryId, error: error.message, hits: [] })
    }
  }
  return results
}

export async function runBenchmark({
  fixture,
  providers,
  ownerSalt = 'synthetic-benchmark-owner-key-v1',
}) {
  const { adapterPayload, rawIdentities } = sanitizeFixture(fixture, ownerSalt)
  const results = []
  for (const factory of providers) {
    try {
      const provider = await factory({ adapterPayload, rawIdentities })
      const queryResults = await executeProvider(provider, adapterPayload)
      results.push(evaluateProvider({ fixture, ownerSalt, provider, queryResults }))
    } catch (error) {
      results.push({
        provider: factory.providerName || 'unknown',
        implementation: 'provider setup failed',
        safetyPassed: false,
        developerQualityPassed: false,
        productionPromotionEligible: false,
        productionPromotionBlockers: ['PROVIDER_SETUP_FAILED'],
        error: error.message,
        metrics: {
          sampleCount: fixture.queries.length,
          errorRatio: 1,
        },
        queryEvidence: [],
      })
    }
  }
  return {
    schema: 'reachai-personal-memory-shadow-benchmark-report-v1',
    generatedAt: new Date().toISOString(),
    benchmarkId: fixture.benchmarkId,
    corpus: {
      classification: 'SYNTHETIC_DEIDENTIFIED',
      memoryCount: fixture.memories.length,
      queryCount: fixture.queries.length,
      topK: fixture.topK || 3,
      rawIdentityForwardedToProviders: false,
      providerResponseContract: 'MEMORY_IDS_AND_SCORES_ONLY',
    },
    conclusionBoundary: 'Developer canary evidence only; never sufficient for production promotion.',
    results,
  }
}

function parseArgs(argv) {
  const options = {}
  for (const argument of argv) {
    if (!argument.startsWith('--') || !argument.includes('=')) throw new Error(`invalid argument: ${argument}`)
    const [key, ...rest] = argument.slice(2).split('=')
    options[key] = rest.join('=')
  }
  return options
}

function externalFactory(providerName, options) {
  const pythonKey = providerName === 'mem0' ? 'mem0-python' : 'reme-python'
  const adapter = path.join(SCRIPT_DIR, 'adapters', `personal-memory-${providerName}-adapter.py`)
  const factory = async ({ adapterPayload, rawIdentities }) => runExternalAdapter({
    provider: providerName,
    python: options[pythonKey] || process.env[`REACHAI_MEMORY_${providerName.toUpperCase()}_PYTHON`],
    adapter,
    adapterPayload,
    rawIdentities,
    baseUrl: options['tei-base-url'] || DEFAULT_TEI_BASE_URL,
    model: options.model || DEFAULT_MODEL,
    dimensions: boundedNumber(options.dimensions, 512, 1, 16_384),
    minScore: boundedNumber(options['min-score'], DEFAULT_MIN_SCORE, -1, 1),
  })
  factory.providerName = providerName
  return factory
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  const fixturePath = path.resolve(options.fixture || DEFAULT_FIXTURE)
  const fixture = JSON.parse(await readFile(fixturePath, 'utf8'))
  const requested = (options.providers || 'lexical').split(',').map(value => value.trim()).filter(Boolean)
  const factories = requested.map(providerName => {
    if (providerName === 'lexical') {
      const factory = async ({ adapterPayload }) => createLexicalProvider(adapterPayload)
      factory.providerName = 'reachai-lexical'
      return factory
    }
    if (providerName === 'tei-vector' || providerName === 'tei-hybrid') {
      const factory = async ({ adapterPayload }) => createTeiProvider(adapterPayload, {
        mode: providerName === 'tei-vector' ? 'vector' : 'hybrid',
        baseUrl: options['tei-base-url'] || DEFAULT_TEI_BASE_URL,
        model: options.model || DEFAULT_MODEL,
        dimensions: boundedNumber(options.dimensions, 512, 1, 16_384),
        minScore: boundedNumber(options['min-score'], DEFAULT_MIN_SCORE, -1, 1),
      })
      factory.providerName = `reachai-${providerName}`
      return factory
    }
    if (providerName === 'mem0' || providerName === 'reme') return externalFactory(providerName, options)
    throw new Error(`unsupported provider: ${providerName}`)
  })
  const report = await runBenchmark({ fixture, providers: factories })
  const serialized = `${JSON.stringify(report, null, 2)}\n`
  if (options.output) {
    const output = path.resolve(options.output)
    await mkdir(path.dirname(output), { recursive: true })
    await writeFile(output, serialized, 'utf8')
  }
  process.stdout.write(serialized)
  if (report.results.some(result => result.error || !result.safetyPassed)) process.exitCode = 1
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    process.stderr.write(`${error.stack || error.message}\n`)
    process.exitCode = 2
  })
}
