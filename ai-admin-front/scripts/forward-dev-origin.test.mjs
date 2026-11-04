import assert from 'node:assert/strict'
import { test } from 'node:test'
import { forwardDevOrigin } from './forward-dev-origin.mjs'

test('uses the incoming dev origin instead of inherited forwarded headers', () => {
  const headers = new Map([['X-Forwarded-Host', 'untrusted.example'], ['X-Forwarded-Proto', 'https']])
  const proxyRequest = { setHeader: (name, value) => headers.set(name, value), removeHeader: name => headers.delete(name) }
  forwardDevOrigin(proxyRequest, { headers: { host: '127.0.0.1:5200' }, socket: {} })
  assert.equal(headers.get('X-Forwarded-Host'), '127.0.0.1:5200')
  assert.equal(headers.get('X-Forwarded-Proto'), 'http')
  forwardDevOrigin(proxyRequest, { headers: {}, socket: { encrypted: true } })
  assert.equal(headers.has('X-Forwarded-Host'), false)
  assert.equal(headers.get('X-Forwarded-Proto'), 'https')
})
