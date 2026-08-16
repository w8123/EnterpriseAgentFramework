#!/usr/bin/env node
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { spawn } from 'node:child_process'

const repoRoot = resolve(new URL('..', import.meta.url).pathname.replace(/^\/(?:([A-Za-z]:))/, '$1'))
const doctor = resolve(
  repoRoot,
  'reachai-control-service',
  'src',
  'main',
  'resources',
  'ai-assist',
  'skills',
  'reachai-onboarding',
  'scripts',
  'reachai-doctor.mjs',
)

function runDoctor(args, environment = {}, allowedExitCodes = [0]) {
  return new Promise((resolveRun, reject) => {
    const child = spawn(process.execPath, [doctor, ...args], {
      cwd: repoRoot,
      env: { ...process.env, ...environment },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    let stdout = ''
    let stderr = ''
    child.stdout.setEncoding('utf8')
    child.stderr.setEncoding('utf8')
    child.stdout.on('data', chunk => { stdout += chunk })
    child.stderr.on('data', chunk => { stderr += chunk })
    child.on('error', reject)
    child.on('close', code => {
      if (!allowedExitCodes.includes(code)) {
        reject(new Error(`doctor exited ${code}: ${stderr || stdout}`))
        return
      }
      try { resolveRun(JSON.parse(stdout)) } catch (error) {
        reject(new Error(`doctor returned invalid JSON: ${error.message}\n${stdout}\n${stderr}`))
      }
    })
  })
}

function check(result, key) {
  const value = result.checks.find(item => item.key === key)
  assert.ok(value, `missing doctor check: ${key}`)
  return value
}

function json(response, statusCode, payload) {
  const body = JSON.stringify(payload)
  response.writeHead(statusCode, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
  })
  response.end(body)
}

async function readJson(request) {
  const chunks = []
  for await (const chunk of request) chunks.push(chunk)
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
}

const fixtureRoot = await mkdtemp(resolve(tmpdir(), 'reachai-doctor-fixture-'))
let server
try {
  await mkdir(resolve(fixtureRoot, 'src', 'main', 'java'), { recursive: true })
  await mkdir(resolve(fixtureRoot, 'src', 'main', 'resources'), { recursive: true })
  await writeFile(resolve(fixtureRoot, 'package.json'), JSON.stringify({
    scripts: { build: 'ng build' },
    dependencies: {
      '@angular/core': '12.2.17',
      '@reachai/embed-chat': 'file:vendor/reachai/reachai-embed-chat.tgz',
    },
  }, null, 2))
  await mkdir(resolve(fixtureRoot, 'vendor', 'reachai'), { recursive: true })
  await mkdir(resolve(fixtureRoot, 'node_modules', '@reachai', 'embed-chat'), { recursive: true })
  const fixtureArtifact = resolve(fixtureRoot, 'vendor', 'reachai', 'reachai-embed-chat.tgz')
  await writeFile(fixtureArtifact, 'doctor-sdk-artifact')
  const fixtureArtifactSha = (await import('node:crypto'))
    .createHash('sha256')
    .update('doctor-sdk-artifact')
    .digest('hex')
  await writeFile(`${fixtureArtifact}.sha256`, `${fixtureArtifactSha}\n`)
  await writeFile(
    resolve(fixtureRoot, 'node_modules', '@reachai', 'embed-chat', '.reachai-artifact-sha256'),
    `${fixtureArtifactSha}\n`,
  )
  await writeFile(resolve(fixtureRoot, 'pom.xml'), `
<project>
  <dependencies>
    <dependency><artifactId>reachai-capability-sdk</artifactId></dependency>
    <dependency><artifactId>reachai-spring-boot2-starter</artifactId></dependency>
  </dependencies>
</project>
`)
  await writeFile(resolve(fixtureRoot, 'src', 'main', 'resources', 'application.yml'), `
reachai:
  registry:
    url: https://reachai.example.com
    app-key: \${REACHAI_REGISTRY_APP_KEY}
    app-secret: \${REACHAI_REGISTRY_APP_SECRET}
  project:
    code: doctor-fixture
    base-url: https://business.example.com
`)
  await writeFile(resolve(fixtureRoot, 'src', 'main', 'java', 'GatewaySecurity.java'), `
class GatewaySecurity {
  SecurityWebFilterChain reachAiEmbedProxySecurity() {
    return http.securityMatcher(pathMatchers("/api/reachai/embed/**")).build();
  }
  // Route /reachai/registry/** and preserve X-ReachAI-Signature.
}
`)

  const staticResult = await runDoctor([
    '--mode', 'static',
    '--business-root', fixtureRoot,
    '--frontend-dir', fixtureRoot,
  ])
  assert.equal(check(staticResult, 'CAPABILITY_SDK_DEPENDENCY').status, 'PASS')
  assert.equal(check(staticResult, 'SPRING_STARTER_DEPENDENCY').status, 'PASS')
  assert.equal(check(staticResult, 'REACHAI_CONFIGURATION_FIELDS').status, 'PASS')
  assert.equal(check(staticResult, 'EMBED_PROXY_ROUTE').status, 'PASS')
  assert.equal(check(staticResult, 'REGISTRY_CALLBACK_ROUTE').status, 'PASS')
  assert.equal(check(staticResult, 'EMBED_SECURITY_BOUNDARY').status, 'PASS')
  assert.equal(check(staticResult, 'ANGULAR_NODE_COMPATIBILITY').status,
    Number(process.versions.node.split('.')[0]) >= 17 ? 'WARN' : 'PASS')
  assert.equal(check(staticResult, 'EMBED_SDK_ARTIFACT_MATCH').status, 'PASS')

  await writeFile(`${fixtureArtifact}.sha256`, 'stale-vendored-manifest\n')
  const staleVendoredManifestResult = await runDoctor([
    '--mode', 'static',
    '--business-root', fixtureRoot,
    '--frontend-dir', fixtureRoot,
  ], {}, [1])
  assert.equal(check(staleVendoredManifestResult, 'EMBED_SDK_ARTIFACT_MATCH').status, 'FAIL')
  await writeFile(`${fixtureArtifact}.sha256`, `${fixtureArtifactSha}\n`)

  await writeFile(
    resolve(fixtureRoot, 'node_modules', '@reachai', 'embed-chat', '.reachai-artifact-sha256'),
    'stale-artifact\n',
  )
  const staleSdkResult = await runDoctor([
    '--mode', 'static',
    '--business-root', fixtureRoot,
    '--frontend-dir', fixtureRoot,
  ], {}, [1])
  assert.equal(check(staleSdkResult, 'EMBED_SDK_ARTIFACT_MATCH').status, 'FAIL')
  await writeFile(
    resolve(fixtureRoot, 'node_modules', '@reachai', 'embed-chat', '.reachai-artifact-sha256'),
    `${fixtureArtifactSha}\n`,
  )

  await mkdir(resolve(fixtureRoot, 'reachai-onboarding', 'examples', 'gateway'), { recursive: true })
  await writeFile(resolve(
    fixtureRoot,
    'reachai-onboarding',
    'examples',
    'gateway',
    'GatewaySecurity.java',
  ), 'SecurityWebFilterChain /api/reachai/embed/** /reachai/registry/**')
  await rm(resolve(fixtureRoot, 'src', 'main', 'java', 'GatewaySecurity.java'))
  const decoyResult = await runDoctor([
    '--mode', 'static',
    '--business-root', fixtureRoot,
    '--frontend-dir', fixtureRoot,
  ])
  assert.equal(check(decoyResult, 'EMBED_PROXY_ROUTE').status, 'WARN')
  assert.equal(check(decoyResult, 'REGISTRY_CALLBACK_ROUTE').status, 'WARN')

  server = createServer(async (request, response) => {
    try {
      if (request.url === '/manifest' && request.method === 'GET') {
        json(response, 200, {
          schema: 'reachai.onboarding-manifest.v1',
          sdkArtifacts: [{ artifactId: 'reachai-capability-sdk' }],
          endpoints: { sdkAccessCheckUrl: '/sdk-access-check' },
        })
        return
      }
      if (request.url === '/sdk-access-check' && request.method === 'POST') {
        json(response, 200, {
          overallStatus: 'PASS',
          readiness: [
            { key: 'CODE_READY', label: 'Code', status: 'PASS', message: 'ready' },
            { key: 'RUNTIME_READY', label: 'Runtime', status: 'PASS', message: 'online' },
            { key: 'SDK_CALLBACK_READY', label: 'Callback', status: 'PASS', message: 'snapshot received' },
          ],
        })
        return
      }
      if (request.url === '/broker' && request.method === 'POST') {
        assert.equal(request.headers.authorization, 'Bearer business-test')
        const body = await readJson(request)
        assert.equal(body.pageKey, 'orders.list')
        assert.ok(body.pageInstanceId.startsWith('reachai-doctor-'))
        json(response, 200, { code: 200, data: { token: 'embed-test', expiresIn: 300 } })
        return
      }
      if (request.url === '/broker-fail' && request.method === 'POST') {
        json(response, 401, {
          message: `rejected ${request.headers.authorization}`,
        })
        return
      }
      if (request.url === '/api/reachai/embed/chat/sessions' && request.method === 'POST') {
        assert.equal(request.headers.authorization, 'Bearer embed-test')
        const body = await readJson(request)
        assert.equal(body.sdkVersion, 'reachai-doctor/0.5.0')
        json(response, 200, { code: 200, data: { sessionId: 'session-doctor' } })
        return
      }
      if (request.url === '/api/reachai/embed/chat/sessions/session-doctor/messages'
          && request.method === 'POST') {
        assert.equal(request.headers.authorization, 'Bearer embed-test')
        const body = await readJson(request)
        assert.ok(body.message)
        json(response, 200, { code: 200, data: { answer: 'REACHAI_E2E_OK' } })
        return
      }
      json(response, 404, { message: 'not found' })
    } catch (error) {
      json(response, 500, { message: error.message })
    }
  })
  await new Promise(resolveListen => server.listen(0, '127.0.0.1', resolveListen))
  const address = server.address()
  const baseUrl = `http://127.0.0.1:${address.port}`

  const runtimeResult = await runDoctor([
    '--mode', 'runtime',
    '--manifest-url', `${baseUrl}/manifest`,
  ])
  assert.equal(check(runtimeResult, 'SDK_ACCESS_CHECK').status, 'PASS')
  assert.equal(check(runtimeResult, 'CODE_READY').status, 'PASS')
  assert.equal(check(runtimeResult, 'RUNTIME_READY').status, 'PASS')
  assert.equal(check(runtimeResult, 'SDK_CALLBACK_READY').status, 'PASS')

  const pendingResult = await runDoctor([
    '--mode', 'e2e',
    '--broker-url', `${baseUrl}/broker`,
    '--embed-api-base', `${baseUrl}/api/reachai/embed`,
    '--agent-id', 'orders-copilot',
    '--page-key', 'orders.list',
  ], {
    REACHAI_E2E_AUTHORIZATION: '',
    REACHAI_E2E_COOKIE: '',
  })
  assert.equal(check(pendingResult, 'EMBED_CONVERSATION_E2E').status, 'PENDING')

  const e2eResult = await runDoctor([
    '--mode', 'e2e',
    '--broker-url', `${baseUrl}/broker`,
    '--embed-api-base', `${baseUrl}/api/reachai/embed`,
    '--agent-id', 'orders-copilot',
    '--page-key', 'orders.list',
    '--route', '/orders',
  ], {
    REACHAI_E2E_AUTHORIZATION: 'Bearer business-test',
  })
  assert.equal(e2eResult.overallStatus, 'WARN')
  assert.equal(check(e2eResult, 'EMBED_CONVERSATION_E2E').status, 'PASS')
  assert.equal(check(e2eResult, 'WORKFLOW_CAPABILITY_E2E').status, 'PENDING')
  assert.equal(check(e2eResult, 'PAGE_ACTION_BROWSER_E2E').status, 'PENDING')
  assert.ok(!JSON.stringify(e2eResult).includes('business-test'))
  assert.ok(!JSON.stringify(e2eResult).includes('embed-test'))

  const redactionResult = await runDoctor([
    '--mode', 'e2e',
    '--broker-url', `${baseUrl}/broker-fail`,
    '--embed-api-base', `${baseUrl}/api/reachai/embed`,
    '--agent-id', 'orders-copilot',
    '--page-key', 'orders.list',
  ], {
    REACHAI_E2E_AUTHORIZATION: 'Bearer business-test',
  }, [1])
  assert.equal(check(redactionResult, 'EMBED_CONVERSATION_E2E').status, 'FAIL')
  assert.ok(!JSON.stringify(redactionResult).includes('business-test'))
  assert.ok(JSON.stringify(redactionResult).includes('[REDACTED]'))

  console.log('[verify-reachai-doctor] static=ok vendored-manifest=verified skill-decoy=ignored runtime=ok e2e-pending=ok e2e-authorized=ok redaction=ok')
} finally {
  if (server) await new Promise(resolveClose => server.close(resolveClose))
  await rm(fixtureRoot, { recursive: true, force: true })
}
