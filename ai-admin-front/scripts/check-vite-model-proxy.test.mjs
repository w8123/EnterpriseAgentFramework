import assert from 'node:assert/strict'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadConfigFromFile } from 'vite'

const frontRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const loaded = await loadConfigFromFile({ command: 'serve', mode: 'test' }, path.join(frontRoot, 'vite.config.ts'))
assert.ok(loaded, 'the production Vite config must load')
const proxies = loaded.config.server?.proxy || {}
const entries = Object.entries(proxies)
const resolveProxy = route => entries.find(([key]) => key.startsWith('^') ? new RegExp(key).test(route) : route.startsWith(key))?.[1]

const modelRoutes = [
  '/model/templates', '/model/templates?providerKey=openai',
  '/model/instances', '/model/instances/local-test/test',
  '/model/chat', '/model/chat/stream/events',
  '/model/catalog/status', '/model/catalog/settings', '/model/catalog/sync',
]
for (const route of modelRoutes) {
  assert.equal(resolveProxy(route)?.target, 'http://localhost:18601', `${route} must reach Model Gateway`)
}

for (const route of ['/model/playground', '/model/catalogs/status', '/model/instances-other']) {
  assert.equal(resolveProxy(route), undefined, `${route} must remain outside model API routing`)
}
const modelProxy = resolveProxy('/model/instances')
assert.equal(modelProxy.bypass({ headers: { accept: 'text/html,application/xhtml+xml' } }), '/index.html', 'model UI navigation must still use the SPA')
assert.equal(modelProxy.bypass({ headers: { accept: 'application/json' } }), undefined, 'JSON fetch must reach the backend')
assert.equal(resolveProxy('/api/agents')?.target, process.env.VITE_CONTROL_API_TARGET || 'http://localhost:18603', 'Control API owner must remain unchanged')
assert.equal(resolveProxy('/ai/knowledge/base/list')?.target, 'http://localhost:18602', 'Knowledge API owner must remain unchanged')
console.log(`Vite model proxy regression passed: ${modelRoutes.length} model endpoints, 3 boundaries, SPA/JSON split and 2 sibling owners`)
