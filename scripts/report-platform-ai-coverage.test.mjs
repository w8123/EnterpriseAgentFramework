import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { buildInventory } from './inventory-human-api-operations.mjs'
import { LINKS, UNOPENED, makeReport, normalizeRoute, registeredPlatformTools } from './report-platform-ai-coverage.mjs'

test('links known request sites while preserving unresolved and unrelated operations', () => {
  const inventory = {
    sites: [
      { surface: 'admin-frontend', method: 'GET', path: '/api/agents/{agentId}', pathResolved: true, methodResolved: true },
      { surface: 'admin-frontend', method: 'POST', path: '/api/agents/{agentId}', pathResolved: true, methodResolved: true },
      { surface: 'admin-frontend', method: 'GET', path: '/api/agents/{?dynamic}', pathResolved: false, methodResolved: true },
      { surface: 'employee-portal', method: 'GET', path: '/api/agents/{agentId}', pathResolved: true, methodResolved: true },
    ],
    storageSites: [{ surface: 'admin-frontend' }, { surface: 'employee-portal' }],
  }
  const report = makeReport(inventory, new Set(['reachai_agent_get']), [
    ['GET', '/api/agents/{}', 'reachai_agent_get', '按 Agent 标识读取'],
  ])
  assert.equal(normalizeRoute('/api/agents/{agentId}'), '/api/agents/{}')
  assert.equal(report.totalSites, 3)
  assert.equal(report.sitesWithRelatedTool, 1)
  assert.equal(report.sitesWithoutRelatedTool, 2)
  assert.equal(report.unresolvedSites, 1)
  assert.equal(report.browserStorageSites, 1)
  assert.equal(report.sites[2].relatedTool, null)
})

test('classifies browser storage by reviewed file and keeps new files visible', () => {
  const inventory = { sites: [], storageSites: [
    { surface: 'admin-frontend', file: 'a.ts' }, { surface: 'admin-frontend', file: 'a.ts' }, { surface: 'admin-frontend', file: 'b.ts' },
  ] }
  const report = makeReport(inventory, new Set(), [], { 'a.ts': ['viewer-preference', 'width'] })
  assert.deepEqual(report.browserStorageByClass, { unclassified: 1, 'viewer-preference': 2 })
  assert.deepEqual(report.unclassifiedStorageFiles, ['b.ts'])
})

test('rejects stale or ambiguous mappings', () => {
  const empty = { sites: [], storageSites: [] }
  assert.throws(() => makeReport(empty, new Set(), [['GET', '/api/x', 'missing', 'subset']]), /tool is missing/)
  assert.throws(() => makeReport(empty, new Set(['tool']), [
    ['GET', '/api/x', 'tool', 'a'], ['GET', '/api/x', 'tool', 'b'],
  ]), /duplicate/)
})

test('every current route link names a registered platform tool', () => {
  const source = fs.readFileSync(new URL('../reachai-control-service/src/main/java/com/enterprise/ai/control/mcp/application/platform/McpPlatformProtocolService.java', import.meta.url), 'utf8')
  assert.doesNotThrow(() => makeReport({ sites: [], storageSites: [] }, registeredPlatformTools(source), LINKS))
})

test('current inventory contains management sites and an identified related tool', () => {
  const root = fileURLToPath(new URL('..', import.meta.url))
  const source = fs.readFileSync(new URL('../reachai-control-service/src/main/java/com/enterprise/ai/control/mcp/application/platform/McpPlatformProtocolService.java', import.meta.url), 'utf8')
  const report = makeReport(buildInventory(root), registeredPlatformTools(source))
  assert.ok(report.totalSites > 0)
  assert.ok(report.sitesWithRelatedTool > 0)
  assert.ok(report.sitesWithoutRelatedTool > 0)
})

test('every unmapped route has a recorded reason and no reason outlives its route', () => {
  const root = fileURLToPath(new URL('..', import.meta.url))
  const source = fs.readFileSync(new URL('../reachai-control-service/src/main/java/com/enterprise/ai/control/mcp/application/platform/McpPlatformProtocolService.java', import.meta.url), 'utf8')
  const report = makeReport(buildInventory(root), registeredPlatformTools(source))
  assert.deepEqual(report.unclassifiedUnmappedRoutes, [])
  assert.deepEqual(report.staleUnopenedRoutes, [])
  for (const [kind, reason] of Object.values(UNOPENED)) assert.ok(kind && reason)
})

test('a linked route cannot also be recorded as unopened', () => {
  const empty = { sites: [], storageSites: [] }
  assert.throws(() => makeReport(empty, new Set(['tool']), [['GET', '/api/x', 'tool', 'a']], {}, { 'GET /api/x': ['not-yet', 'x'] }),
    /still listed as unopened/)
})
