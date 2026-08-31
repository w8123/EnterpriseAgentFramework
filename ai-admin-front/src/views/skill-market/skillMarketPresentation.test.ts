import { describe, expect, it } from 'vitest'
import {
  defaultMarketVersion,
  formatInstalls,
  normalizeGitHubPublisher,
  selectSuggestedCandidate,
  trustLabel,
} from './skillMarketPresentation'

describe('skill market presentation contracts', () => {
  it('keeps popularity separate from source trust', () => {
    expect(trustLabel('OFFICIAL')).toBe('官方来源')
    expect(trustLabel('COMMUNITY')).toBe('社区来源')
    expect(formatInstalls(656_993)).toBe('657K')
  })

  it('derives portable publisher and immutable fallback version', () => {
    expect(normalizeGitHubPublisher('Vercel-Labs')).toBe('vercel-labs')
    expect(defaultMarketVersion(null, 'abcdef1234567890')).toBe('git-abcdef123456')
  })

  it('only auto-selects an explicit suggestion or an unambiguous candidate', () => {
    const candidates = [
      { sourceRoot: 'repo/one', selectable: true, name: 'one', hasScripts: false, fileCount: 1, warnings: [] },
      { sourceRoot: 'repo/two', selectable: true, name: 'two', hasScripts: false, fileCount: 1, warnings: [] },
    ]
    expect(selectSuggestedCandidate(candidates, 'repo/two')?.name).toBe('two')
    expect(selectSuggestedCandidate(candidates, null)).toBeNull()
  })
})
