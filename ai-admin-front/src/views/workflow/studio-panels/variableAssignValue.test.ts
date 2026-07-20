import { describe, expect, it } from 'vitest'
import { buildAssignmentsFromRows, resolveVariableAssignValue } from './variableAssignValue'

describe('variableAssignValue', () => {
  it('rejects invalid JSON instead of degrading to string', () => {
    const result = resolveVariableAssignValue({
      target: 'var.payload',
      valueKind: 'json',
      textValue: '',
      numberValue: undefined,
      boolValue: false,
      jsonText: '{bad',
    })
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toContain('JSON')
    }
  })

  it('blocks assignment sync when any row has invalid JSON', () => {
    const built = buildAssignmentsFromRows([
      {
        target: 'var.ok',
        valueKind: 'number',
        textValue: '',
        numberValue: 1,
        boolValue: false,
        jsonText: '',
      },
      {
        target: 'var.bad',
        valueKind: 'json',
        textValue: '',
        numberValue: undefined,
        boolValue: false,
        jsonText: '[1,]',
      },
    ])
    expect(built.ok).toBe(false)
    if (!built.ok) {
      expect(built.index).toBe(1)
    }
  })

  it('parses valid JSON object/array', () => {
    const objectResult = resolveVariableAssignValue({
      target: 'var.obj',
      valueKind: 'json',
      textValue: '',
      numberValue: undefined,
      boolValue: false,
      jsonText: '{"a":1}',
    })
    expect(objectResult).toEqual({ ok: true, value: { a: 1 } })
  })
})
