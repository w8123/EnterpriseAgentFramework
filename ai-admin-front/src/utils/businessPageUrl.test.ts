import { describe, expect, it } from 'vitest'
import { isBusinessPageUrl, normalizeBusinessPageUrl } from './businessPageUrl'

describe('business page URL contract', () => {
  it('keeps an absolute HTTP business page URL', () => {
    expect(normalizeBusinessPageUrl(' http://localhost:9200/team-build/depart-management '))
      .toBe('http://localhost:9200/team-build/depart-management')
  })

  it('does not turn a relative route into a guessed page URL', () => {
    expect(normalizeBusinessPageUrl('/team-build/depart-management')).toBe('')
  })

  it('rejects non-browser-safe protocols', () => {
    expect(isBusinessPageUrl('javascript:alert(1)')).toBe(false)
  })
})
