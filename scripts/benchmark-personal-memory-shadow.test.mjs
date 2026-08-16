import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createLexicalProvider,
  createOwnerKey,
  evaluateProvider,
  lexicalScore,
  sanitizeFixture,
} from './benchmark-personal-memory-shadow.mjs'

function fixture() {
  return {
    schema: 'reachai-personal-memory-shadow-benchmark-fixture-v1',
    benchmarkId: 'unit',
    topK: 2,
    memories: [
      {
        canonicalId: 'a-active',
        tenantId: 'tenant-a',
        runtimeUserId: 'alice',
        status: 'ACTIVE',
        type: 'PREFERENCE',
        title: '回复语言',
        summary: '默认中文',
        content: '默认使用简体中文回答。',
      },
      {
        canonicalId: 'a-deleted',
        tenantId: 'tenant-a',
        runtimeUserId: 'alice',
        status: 'DELETED',
        lifecycle: 'ADD_THEN_DELETE',
        type: 'NOTE',
        title: '已删除',
        summary: '',
        content: '不要花生。',
      },
      {
        canonicalId: 'b-active',
        tenantId: 'tenant-a',
        runtimeUserId: 'bob',
        status: 'ACTIVE',
        type: 'PREFERENCE',
        title: 'Reply language',
        summary: 'English',
        content: 'Answer in English.',
      },
    ],
    queries: [
      {
        queryId: 'q1',
        tenantId: 'tenant-a',
        runtimeUserId: 'alice',
        text: '默认中文',
        expectedIds: ['a-active'],
      },
      {
        queryId: 'q-delete',
        tenantId: 'tenant-a',
        runtimeUserId: 'alice',
        text: '花生',
        expectedIds: [],
        forbiddenIds: ['a-deleted'],
      },
    ],
  }
}

test('owner keys separate tenants and users without exposing either identifier', () => {
  const first = createOwnerKey('tenant-a', 'alice', 'salt')
  const otherUser = createOwnerKey('tenant-a', 'bob', 'salt')
  const otherTenant = createOwnerKey('tenant-b', 'alice', 'salt')

  assert.match(first, /^pm_[a-f0-9]{64}$/)
  assert.notEqual(first, otherUser)
  assert.notEqual(first, otherTenant)
  assert.equal(first.includes('tenant-a'), false)
  assert.equal(first.includes('alice'), false)
})

test('adapter projection contains only owner keys and omits raw identity fields', () => {
  const { adapterPayload } = sanitizeFixture(fixture(), 'salt')
  const serialized = JSON.stringify(adapterPayload)

  assert.equal(serialized.includes('tenantId'), false)
  assert.equal(serialized.includes('runtimeUserId'), false)
  assert.equal(serialized.includes('tenant-a'), false)
  assert.equal(serialized.includes('alice'), false)
  assert.equal(serialized.includes('bob'), false)
})

test('lexical provider filters owners and removes the deletion canary', async () => {
  const { adapterPayload } = sanitizeFixture(fixture(), 'salt')
  const provider = createLexicalProvider(adapterPayload)
  const positive = await provider.search(adapterPayload.queries[0])
  const deleted = await provider.search(adapterPayload.queries[1])

  assert.deepEqual(positive.hits.map(hit => hit.memoryId), ['a-active'])
  assert.deepEqual(deleted.hits, [])
  assert.equal(provider.lifecycle.confirmedAbsent, true)
  assert.ok(lexicalScore('默认使用简体中文回答', '简体中文') > 0)
})

test('evaluation passes a safe ID-only result and detects owner and deletion leaks', () => {
  const value = fixture()
  const safeProvider = {
    name: 'safe',
    implementation: 'test',
    lifecycle: { confirmedAbsent: true },
  }
  const safe = evaluateProvider({
    fixture: value,
    ownerSalt: 'salt',
    provider: safeProvider,
    queryResults: [
      { queryId: 'q1', latencyMs: 2, hits: [{ memoryId: 'a-active', score: 1 }] },
      { queryId: 'q-delete', latencyMs: 1, hits: [] },
    ],
  })
  assert.equal(safe.safetyPassed, true)
  assert.equal(safe.metrics.canonicalCoverageAtK, 1)
  assert.equal(safe.metrics.negativeQueryFalsePositiveRate, 0)
  assert.equal(safe.productionPromotionEligible, false)

  const unsafe = evaluateProvider({
    fixture: value,
    ownerSalt: 'salt',
    provider: { ...safeProvider, name: 'unsafe', lifecycle: { confirmedAbsent: false } },
    queryResults: [
      { queryId: 'q1', latencyMs: 2, hits: [{ memoryId: 'b-active', score: 1 }] },
      { queryId: 'q-delete', latencyMs: 1, hits: [{ memoryId: 'a-deleted', score: 1 }] },
    ],
  })
  assert.equal(unsafe.safetyPassed, false)
  assert.ok(unsafe.metrics.crossOwnerLeakRatio > 0)
  assert.ok(unsafe.metrics.deletedItemLeakRatio > 0)
  assert.equal(unsafe.metrics.explicitForbiddenHitCount, 1)
  assert.equal(unsafe.metrics.negativeQueryFalsePositiveRate, 1)
})
