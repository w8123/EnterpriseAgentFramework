import { describe, expect, it } from 'vitest'
import { formatDateTime, formatTableDateTime } from './dateTime'

describe('formatDateTime', () => {
  it('formats ISO strings without the T separator', () => {
    const value = new Date(2026, 9, 16, 14, 43, 53)
    expect(formatDateTime(value)).toBe('2026-10-16 14:43:53')
    expect(formatDateTime('2026-10-16T14:43:53')).toBe('2026-10-16 14:43:53')
  })

  it('keeps empty and unparsable values readable', () => {
    expect(formatDateTime(null)).toBe('-')
    expect(formatDateTime('')).toBe('-')
    expect(formatDateTime('not a date')).toBe('not a date')
    expect(formatTableDateTime({}, {}, undefined)).toBe('-')
  })
})
