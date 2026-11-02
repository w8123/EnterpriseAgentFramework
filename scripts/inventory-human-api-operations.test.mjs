import assert from 'node:assert/strict'
import { test } from 'node:test'
import { collectRequestSites, collectStorageSites, collectVueEventSites } from './inventory-human-api-operations.mjs'

test('finds management requests and expands constants, helpers, aliases and path parameters', () => {
  const sites = collectRequestSites('ai-admin-front/src/api/example.ts', `
import knowledge, { controlRequest as control, modelRequest } from './request'
const ROOT = '/api/registry/projects'
const project = (projectCode: string) => \`\${ROOT}/\${encodeURIComponent(projectCode)}\`
function root(projectCode: string) { return \`\${project(projectCode)}/page-workbench\` }
export function updatePage(projectCode: string, id: number, data: unknown) {
  return control.patch(\`\${root(projectCode)}/pages/\${id}\`, data)
}
export function getKnowledge(code: string) { return knowledge.get(\`/knowledge/base/\${code}\`) }
export function listModels() { return modelRequest.get('/instances') }
`)

  assert.deepEqual(sites.map(({ method, path, pathResolved }) => ({ method, path, pathResolved })), [
    { method: 'PATCH', path: '/api/registry/projects/{projectCode}/page-workbench/pages/{id}', pathResolved: true },
    { method: 'GET', path: '/ai/knowledge/base/{code}', pathResolved: true },
    { method: 'GET', path: '/model/instances', pathResolved: true },
  ])
  assert.deepEqual(sites.map((site) => site.function), ['updatePage', 'getKnowledge', 'listModels'])
})

test('retains unknown paths and methods instead of presenting guesses as facts', () => {
  const sites = collectRequestSites('ai-admin-front/src/api/example.ts', `
import { controlRequest } from './request'
export function call(path: string, init: RequestInit) {
  fetch(chooseUrl(path), init)
  fetch('/api/one', { method: 'POST', ...init })
  fetch('/api/two', { ...init, method: 'POST' })
  controlRequest.get(someUrl)
}
`)
  assert.deepEqual(sites.map(({ method, pathResolved, methodResolved }) => ({ method, pathResolved, methodResolved })), [
    { method: 'UNKNOWN', pathResolved: false, methodResolved: false },
    { method: 'UNKNOWN', pathResolved: true, methodResolved: false },
    { method: 'POST', pathResolved: true, methodResolved: true },
    { method: 'GET', pathResolved: false, methodResolved: true },
  ])
  assert.match(sites[0].path, /^\{\?chooseUrl/)
  assert.equal(sites[3].path, '{?someUrl}')
})

test('scans Vue scripts with accurate source lines and ignores markup', () => {
  const sites = collectRequestSites('ai-admin-front/src/views/Example.vue', `
<template>
  <div>fetch('/not-real')</div>
</template>
<script setup lang="ts">
async function load(id: string) {
  return fetch(\`/api/things/\${id}\`)
}
</script>
`)
  assert.equal(sites.length, 1)
  assert.equal(sites[0].line, 7)
  assert.equal(sites[0].function, 'load')
  assert.equal(sites[0].path, '/api/things/{id}')
  assert.equal(sites[0].method, 'GET')
})

test('does not mistake unrelated client methods for a platform request', () => {
  const sites = collectRequestSites('ai-admin-front/src/api/example.ts', `
import { request } from 'some-other-library'
function demo() { request.get('/not-a-platform-api') }
`)
  assert.deepEqual(sites, [])
})

test('does not recurse forever when a path constant refers to itself', () => {
  const sites = collectRequestSites('ai-admin-front/src/api/example.ts', `
import { controlRequest } from './request'
const ROOT = ROOT
export function load() { return controlRequest.get(ROOT) }
`)
  assert.equal(sites[0].path, '{?ROOT}')
  assert.equal(sites[0].pathResolved, false)
})

test('does not resolve a mutable variable to its initial value', () => {
  const sites = collectRequestSites('ai-admin-front/src/api/example.ts', `
import { controlRequest } from './request'
let currentId = ''
export async function load() {
  const response = await controlRequest.get(\`/api/items/\${currentId}\`)
  return response
}
`)
  assert.equal(sites[0].path, '/api/items/{?currentId}')
  assert.equal(sites[0].pathResolved, false)
  assert.equal(sites[0].function, 'load')
})

test('finds browser-local state without reading values or pretending dynamic keys are known', () => {
  const sites = collectStorageSites('ai-admin-front/src/views/example.ts', `
const LAYOUT_KEY = 'dashboard.layout'
function save(value: string, key: string) {
  window.localStorage.setItem(LAYOUT_KEY, value)
  sessionStorage.removeItem(key)
  globalThis.localStorage.getItem(variableKey)
}
`)
  assert.deepEqual(sites.map(({ storage, method, key, keyResolved }) => ({ storage, method, key, keyResolved })), [
    { storage: 'localStorage', method: 'setItem', key: 'dashboard.layout', keyResolved: true },
    { storage: 'sessionStorage', method: 'removeItem', key: '{key}', keyResolved: true },
    { storage: 'localStorage', method: 'getItem', key: '{?variableKey}', keyResolved: false },
  ])
})

test('finds Vue event bindings through nested templates without treating comments as actions', () => {
  const sites = collectVueEventSites('ai-admin-front/src/views/Example.vue', `
<template>
  <!-- <button @click="notReal"> -->
  <div>
    <template #actions><button @click.stop="open">Open</button></template>
    <input @change="save" />
  </div>
</template>
<script setup lang="ts">const open = () => {}</script>
`)
  assert.deepEqual(sites.map(({ event, expression, line }) => ({ event, expression, line })), [
    { event: 'click', expression: 'open', line: 5 },
    { event: 'change', expression: 'save', line: 6 },
  ])
})
