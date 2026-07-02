const DEFAULT_TIMEOUT_MS = 5000
const DEFAULT_INTERVAL_MS = 2000

const args = new Map()
for (let i = 2; i < process.argv.length; i += 1) {
  const arg = process.argv[i]
  if (arg === '--help' || arg === '-h') {
    printHelp()
    process.exit(0)
  }
  if (arg.startsWith('--')) {
    const next = process.argv[i + 1]
    if (next && !next.startsWith('--')) {
      args.set(arg.slice(2), next)
      i += 1
    } else {
      args.set(arg.slice(2), 'true')
    }
  }
}

const timeoutMs = Number(args.get('timeout-ms') || process.env.REACHAI_SMOKE_TIMEOUT_MS || DEFAULT_TIMEOUT_MS)
const waitMs = Number(args.get('wait-ms') || process.env.REACHAI_SMOKE_WAIT_MS || 0)
const intervalMs = Number(args.get('interval-ms') || process.env.REACHAI_SMOKE_INTERVAL_MS || DEFAULT_INTERVAL_MS)

const services = {
  control: {
    name: 'reachai-control-service',
    baseUrl: envUrl(['REACHAI_CONTROL_SERVICE_URL', 'CONTROL_SERVICE_URL', 'VITE_REACHAI_CONTROL_SERVICE_URL'], 'http://localhost:18603')
  },
  runtime: {
    name: 'reachai-runtime-service',
    baseUrl: envUrl(['RUNTIME_SERVICE_URL'], 'http://localhost:18604')
  },
  capability: {
    name: 'reachai-capability-service',
    baseUrl: envUrl(['CAPABILITY_SERVICE_URL'], 'http://localhost:18605')
  },
  knowledge: {
    name: 'reachai-knowledge-service',
    baseUrl: envUrl(['KNOWLEDGE_SERVICE_URL'], 'http://localhost:18602')
  },
  model: {
    name: 'reachai-model-service',
    baseUrl: envUrl(['MODEL_SERVICE_URL'], 'http://localhost:18601')
  }
}

const checks = [
  endpointCheck(services.control, '/actuator/health'),
  endpointCheck(services.runtime, '/internal/runtime/health'),
  endpointCheck(services.capability, '/internal/capability/health'),
  endpointCheck(services.knowledge, '/ai/actuator/health'),
  endpointCheck(services.model, '/actuator/health'),
  controlInternalServiceCheck('runtime'),
  controlInternalServiceCheck('capability')
]

const { results, attempts, waited } = await runChecksUntilReady()
const failures = results.filter(result => !result.ok)

if (failures.length > 0) {
  for (const failure of failures) {
    console.error(`[smoke failed] ${failure.label}`)
    console.error(`  ${failure.detail}`)
  }
  if (allPhysicalServicesUnavailable(failures)) {
    printAllServicesDownHint()
  }
  console.error(`physical service smoke check failed: ${failures.length} issue(s)`)
  process.exit(1)
}

console.log('physical service smoke check passed')
if (waited) {
  console.log(`  waited for services: attempts=${attempts}`)
}
for (const result of results) {
  console.log(`  ${result.label}`)
}

function printHelp() {
  console.log(`Usage: node scripts/check-physical-service-smoke.mjs [--timeout-ms 5000] [--wait-ms 120000] [--interval-ms 2000]

Checks the local five-service ReachAI topology after services are already running.
Use --wait-ms after starting services from IDEA to wait for slow Spring Boot startup.

Environment overrides:
  REACHAI_CONTROL_SERVICE_URL or CONTROL_SERVICE_URL or VITE_REACHAI_CONTROL_SERVICE_URL
  RUNTIME_SERVICE_URL
  CAPABILITY_SERVICE_URL
  KNOWLEDGE_SERVICE_URL
  MODEL_SERVICE_URL
  REACHAI_SMOKE_TIMEOUT_MS
  REACHAI_SMOKE_WAIT_MS
  REACHAI_SMOKE_INTERVAL_MS`)
}

async function runChecksUntilReady() {
  const startedAt = Date.now()
  const deadline = startedAt + Math.max(0, waitMs)
  let attempts = 0
  let lastResults = []
  while (true) {
    attempts += 1
    lastResults = await Promise.all(checks.map(check => check()))
    if (lastResults.every(result => result.ok)) {
      return {
        results: lastResults,
        attempts,
        waited: attempts > 1
      }
    }
    if (Date.now() >= deadline) {
      return {
        results: lastResults,
        attempts,
        waited: attempts > 1
      }
    }
    await sleep(Math.min(intervalMs, Math.max(0, deadline - Date.now())))
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function allPhysicalServicesUnavailable(failures) {
  const failedByLabel = new Map(failures.map(failure => [failure.label, failure]))
  return [
    `${services.control.name} /actuator/health`,
    `${services.runtime.name} /internal/runtime/health`,
    `${services.capability.name} /internal/capability/health`,
    `${services.knowledge.name} /ai/actuator/health`,
    `${services.model.name} /actuator/health`
  ].every(label => {
    const failure = failedByLabel.get(label)
    return failure && failure.detail.includes('request failed')
  })
}

function printAllServicesDownHint() {
  console.error('No ReachAI physical services responded on the configured URLs.')
  console.error('Start services in this order:')
  console.error('  reachai-model-service -> reachai-knowledge-service -> reachai-capability-service -> reachai-runtime-service -> reachai-control-service')
  console.error('Then rerun:')
  console.error('  node scripts/check-physical-service-smoke.mjs')
}

function envUrl(names, fallback) {
  for (const name of names) {
    const value = process.env[name]
    if (value && value.trim()) {
      return value.trim().replace(/\/+$/, '')
    }
  }
  return fallback
}

function endpointCheck(service, healthPath) {
  return async () => {
    const label = `${service.name} ${healthPath}`
    const response = await requestJson(service.baseUrl, healthPath)
    if (!response.ok) {
      return {
        ok: false,
        label,
        detail: response.detail
      }
    }
    return {
      ok: true,
      label
    }
  }
}

function controlInternalServiceCheck(serviceKey) {
  return async () => {
    const label = `control internal service ${serviceKey}`
    const response = await requestJson(services.control.baseUrl, '/api/internal-services/health', {
      acceptedStatuses: new Set([200])
    })
    if (!response.ok) {
      return {
        ok: false,
        label,
        detail: response.detail
      }
    }
    const status = response.body?.services?.[serviceKey]?.status
    if (status !== 'UP') {
      return {
        ok: false,
        label,
        detail: `/api/internal-services/health reported ${serviceKey} status ${JSON.stringify(status)}`
      }
    }
    return {
      ok: true,
      label
    }
  }
}

async function requestJson(baseUrl, path, options = {}) {
  const acceptedStatuses = options.acceptedStatuses || new Set([200])
  const url = new URL(path, `${baseUrl}/`)
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetch(url, { signal: controller.signal })
    const text = await response.text()
    const body = parseJson(text)
    if (!acceptedStatuses.has(response.status)) {
      return {
        ok: false,
        detail: `${url.pathname} returned HTTP ${response.status}${text ? ` body=${text.slice(0, 200)}` : ''}`
      }
    }
    if (body && typeof body.status === 'string' && body.status !== 'UP') {
      return {
        ok: false,
        detail: `${url.pathname} returned status ${body.status}`
      }
    }
    return {
      ok: true,
      body
    }
  } catch (error) {
    return {
      ok: false,
      detail: `${url.pathname} request failed: ${error.name === 'AbortError' ? `timeout after ${timeoutMs}ms` : error.message}`
    }
  } finally {
    clearTimeout(timer)
  }
}

function parseJson(text) {
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}
