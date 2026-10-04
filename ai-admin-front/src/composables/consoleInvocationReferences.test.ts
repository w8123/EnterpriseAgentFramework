import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { normalizedReferences, readInvocationReferences, rememberInvocationReference, removeInvocationReferences } from './consoleInvocationReferences'

const id = '123e4567-e89b-42d3-a456-426614174000'
beforeEach(() => localStorage.clear())
afterEach(() => vi.unstubAllGlobals())
describe('shared Console query-only references', () => {
  it('allows only a bounded deduplicated ID/time record, never input/result payloads', () => {
    expect(normalizedReferences([{ invocationId: id, createdAt: 1, input: 'private' }, { invocationId: id, createdAt: 2 },
      { invocationId: 'invalid', createdAt: 3 }, { invocationId: id, createdAt: NaN }])).toEqual([{ invocationId: id, createdAt: 1 }])
    rememberInvocationReference('account|project|env|api', id)
    expect(Object.keys(readInvocationReferences('account|project|env|api')[0]).sort()).toEqual(['createdAt', 'invocationId'])
    expect(readInvocationReferences('another-account|project|env|api')).toEqual([])
    removeInvocationReferences('account|project|env|api'); expect(readInvocationReferences('account|project|env|api')).toEqual([])
  })
  it('handles unavailable storage without dispatch or restoring an invalid identity', () => {
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('unavailable') }, setItem: () => { throw new Error('unavailable') },
      removeItem: () => { throw new Error('unavailable') } })
    expect(readInvocationReferences('key')).toEqual([])
    expect(() => rememberInvocationReference('key', id)).not.toThrow()
    expect(() => removeInvocationReferences('key')).not.toThrow()
  })
})
