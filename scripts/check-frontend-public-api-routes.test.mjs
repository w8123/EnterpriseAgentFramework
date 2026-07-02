import assert from 'node:assert'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const scriptPath = path.resolve('scripts/check-frontend-public-api-routes.mjs')

function writeFile(root, rel, text) {
  const target = path.join(root, rel)
  fs.mkdirSync(path.dirname(target), { recursive: true })
  fs.writeFileSync(target, text, 'utf8')
}

const forbiddenRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-frontend-routes-forbidden-'))
writeFile(forbiddenRoot, 'ai-admin-front/src/api/agent.ts', `
export function executeAgent(request) {
  return controlRequest.post('/api/agent/execute', request)
}
export function listDatasets() {
  return controlRequest.get('/api/agent/evals/datasets')
}
export function listPages() {
  return controlRequest.get('/api/platform/embed/pages/catalog')
}
`)
writeFile(forbiddenRoot, 'ai-admin-front/src/views/registry/LegacyRuntimeView.vue', `
<script setup lang="ts">
controlRequest.get('/api/agent/interactions/human-approvals')
</script>
`)

const forbiddenResult = spawnSync(process.execPath, [scriptPath], {
  cwd: forbiddenRoot,
  encoding: 'utf8'
})

assert.notStrictEqual(forbiddenResult.status, 0, forbiddenResult.stderr || forbiddenResult.stdout)
assert.match(forbiddenResult.stderr, /frontend public API route check failed/)
assert.match(forbiddenResult.stderr, /\/api\/agent\//)
assert.match(forbiddenResult.stderr, /\/api\/runtime\/agents\/execute/)
assert.match(forbiddenResult.stderr, /\/api\/runtime\/evals/)
assert.match(forbiddenResult.stderr, /\/api\/runtime\/interactions/)
assert.match(forbiddenResult.stderr, /\/api\/platform\/embed\/pages/)

const allowedRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'reachai-frontend-routes-allowed-'))
writeFile(allowedRoot, 'ai-admin-front/src/api/workflow.ts', `
export function listAgents() {
  return controlRequest.get('/api/agents')
}
export function gatewayChat(key) {
  return controlRequest.post(\`/api/v1/agents/\${key}/chat\`)
}
export function executeAgent(request) {
  return controlRequest.post('/api/runtime/agents/execute', request)
}
export function listCredentials() {
  return controlRequest.get('/api/workflows/credentials')
}
`)

const allowedResult = spawnSync(process.execPath, [scriptPath], {
  cwd: allowedRoot,
  encoding: 'utf8'
})

assert.strictEqual(allowedResult.status, 0, allowedResult.stderr || allowedResult.stdout)
