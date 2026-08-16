import { describe, expect, it } from 'vitest'
import { parseToolTestArgument } from './toolTestArgument'

describe('parseToolTestArgument', () => {
  it('validates JSON containers against their declared shape', () => {
    expect(parseToolTestArgument('{"page":1}', 'Map<String, Object>', 'filters')).toEqual({ page: 1 })
    expect(parseToolTestArgument('[1,2]', 'java.util.List<Long>', 'ids')).toEqual([1, 2])
    expect(() => parseToolTestArgument('[]', 'object', 'filters')).toThrow('JSON 对象')
    expect(() => parseToolTestArgument('{}', 'array', 'ids')).toThrow('JSON 数组')
  })

  it('rejects ambiguous booleans and non-finite or fractional integers', () => {
    expect(parseToolTestArgument(' FALSE ', 'java.lang.Boolean', 'enabled')).toBe(false)
    expect(() => parseToolTestArgument('yes', 'boolean', 'enabled')).toThrow('true 或 false')
    expect(() => parseToolTestArgument('1.5', 'Integer', 'page')).toThrow('整数')
    expect(() => parseToolTestArgument('Infinity', 'Double', 'amount')).toThrow('有限数字')
  })

  it('preserves precision-sensitive long and decimal values as strings', () => {
    expect(parseToolTestArgument('42', 'Long', 'id')).toBe(42)
    expect(parseToolTestArgument('9223372036854775807', 'Long', 'id')).toBe('9223372036854775807')
    expect(parseToolTestArgument('1234567890.123456789', 'BigDecimal', 'amount')).toBe('1234567890.123456789')
  })
})
