import assert from 'node:assert'
import { spawn } from 'node:child_process'
import http from 'node:http'
import path from 'node:path'

const scriptPath = path.resolve('scripts/check-physical-service-smoke.mjs')

function startServer(routes) {
  const server = http.createServer((req, res) => {
    const route = routes[req.url]
    if (!route) {
      res.writeHead(404, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ status: 'NOT_FOUND', path: req.url }))
      return
    }
    const [statusCode, body] = typeof route === 'function' ? route(req) : route
    res.writeHead(statusCode, { 'content-type': 'application/json' })
    res.end(JSON.stringify(body))
  })
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address()
      resolve({
        url: `http://127.0.0.1:${port}`,
        close: () => new Promise(closeResolve => server.close(closeResolve))
      })
    })
  })
}

async function withServers(definitions, run) {
  const servers = {}
  try {
    for (const [name, routes] of Object.entries(definitions)) {
      servers[name] = await startServer(routes)
    }
    return await run(servers)
  } finally {
    await Promise.all(Object.values(servers).map(server => server.close()))
  }
}

function runSmoke(servers, extraArgs = []) {
  const env = {
    ...process.env,
    REACHAI_CONTROL_SERVICE_URL: servers.control.url,
    RUNTIME_SERVICE_URL: servers.runtime.url,
    CAPABILITY_SERVICE_URL: servers.capability.url,
    KNOWLEDGE_SERVICE_URL: servers.knowledge.url,
    MODEL_SERVICE_URL: servers.model.url,
    REACHAI_SMOKE_TIMEOUT_MS: '3000'
  }
  return runSmokeWithEnv(env, extraArgs)
}

function runSmokeWithEnv(env, extraArgs = []) {
  return new Promise(resolve => {
    const child = spawn(process.execPath, [scriptPath, ...extraArgs], {
      cwd: path.resolve('.'),
      env
    })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', chunk => {
      stdout += chunk.toString()
    })
    child.stderr.on('data', chunk => {
      stderr += chunk.toString()
    })
    child.on('close', status => {
      resolve({ status, stdout, stderr })
    })
  })
}

await withServers({
  control: {
    '/actuator/health': [200, { status: 'UP' }],
    '/api/internal-services/health': [200, {
      status: 'UP',
      services: {
        runtime: { status: 'UP' },
        capability: { status: 'UP' }
      }
    }]
  },
  runtime: {
    '/internal/runtime/health': [200, { status: 'UP', service: 'reachai-runtime-service' }]
  },
  capability: {
    '/internal/capability/health': [200, { status: 'UP', service: 'reachai-capability-service' }]
  },
  knowledge: {
    '/ai/actuator/health': [200, { status: 'UP' }]
  },
  model: {
    '/actuator/health': [200, { status: 'UP' }]
  }
}, async servers => {
  const result = await runSmoke(servers)

  assert.strictEqual(result.status, 0, result.stderr || result.stdout)
  assert.match(result.stdout, /physical service smoke check passed/)
  assert.match(result.stdout, /reachai-control-service/)
  assert.match(result.stdout, /reachai-runtime-service/)
  assert.match(result.stdout, /reachai-capability-service/)
  assert.match(result.stdout, /reachai-knowledge-service/)
  assert.match(result.stdout, /reachai-model-service/)
})

await withServers({
  control: {
    '/actuator/health': [200, { status: 'UP' }],
    '/api/internal-services/health': [200, {
      status: 'UP',
      services: {
        runtime: { status: 'UP' },
        capability: { status: 'DOWN' }
      }
    }]
  },
  runtime: {
    '/internal/runtime/health': [200, { status: 'UP', service: 'reachai-runtime-service' }]
  },
  capability: {
    '/internal/capability/health': [503, { status: 'DOWN', service: 'reachai-capability-service' }]
  },
  knowledge: {
    '/ai/actuator/health': [200, { status: 'UP' }]
  },
  model: {
    '/actuator/health': [200, { status: 'UP' }]
  }
}, async servers => {
  const result = await runSmoke(servers)

  assert.notStrictEqual(result.status, 0, result.stderr || result.stdout)
  assert.match(result.stderr, /reachai-capability-service/)
  assert.match(result.stderr, /\/internal\/capability\/health/)
  assert.match(result.stderr, /control internal service capability/)
})

{
  const result = await runSmokeWithEnv({
    ...process.env,
    REACHAI_CONTROL_SERVICE_URL: 'http://127.0.0.1:9',
    RUNTIME_SERVICE_URL: 'http://127.0.0.1:9',
    CAPABILITY_SERVICE_URL: 'http://127.0.0.1:9',
    KNOWLEDGE_SERVICE_URL: 'http://127.0.0.1:9',
    MODEL_SERVICE_URL: 'http://127.0.0.1:9',
    REACHAI_SMOKE_TIMEOUT_MS: '500'
  })

  assert.notStrictEqual(result.status, 0, result.stderr || result.stdout)
  assert.match(result.stderr, /No ReachAI physical services responded/)
  assert.match(result.stderr, /Start services in this order/)
  assert.match(result.stderr, /reachai-model-service -> reachai-knowledge-service -> reachai-capability-service -> reachai-runtime-service -> reachai-control-service/)
  assert.match(result.stderr, /node scripts\/check-physical-service-smoke\.mjs/)
}

{
  let controlHealthAttempts = 0
  await withServers({
    control: {
      '/actuator/health': () => {
        controlHealthAttempts += 1
        return controlHealthAttempts < 2
          ? [503, { status: 'DOWN' }]
          : [200, { status: 'UP' }]
      },
      '/api/internal-services/health': [200, {
        status: 'UP',
        services: {
          runtime: { status: 'UP' },
          capability: { status: 'UP' }
        }
      }]
    },
    runtime: {
      '/internal/runtime/health': [200, { status: 'UP', service: 'reachai-runtime-service' }]
    },
    capability: {
      '/internal/capability/health': [200, { status: 'UP', service: 'reachai-capability-service' }]
    },
    knowledge: {
      '/ai/actuator/health': [200, { status: 'UP' }]
    },
    model: {
      '/actuator/health': [200, { status: 'UP' }]
    }
  }, async servers => {
    const result = await runSmoke(servers, ['--wait-ms', '1500', '--interval-ms', '100'])

    assert.strictEqual(result.status, 0, result.stderr || result.stdout)
    assert.match(result.stdout, /physical service smoke check passed/)
    assert.match(result.stdout, /waited for services/)
    assert.ok(controlHealthAttempts >= 2, 'smoke should retry until delayed service is up')
  })
}
