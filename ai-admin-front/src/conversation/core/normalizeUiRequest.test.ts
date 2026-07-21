import { describe, expect, it } from 'vitest'
import { normalizeUiRequest } from './normalizeUiRequest'

describe('normalizeUiRequest', () => {
  it('normalizes uppercase component aliases', () => {
    const ui = normalizeUiRequest({
      component: 'FORM',
      interactionId: 'x1',
      fields: [{ key: 'name', label: 'Name', type: 'string', required: true }],
    })
    expect(ui?.schemaVersion).toBe('1.0')
    expect(ui?.component).toBe('form')
    expect(ui?.fields?.[0].key).toBe('name')
  })

  it('maps CHOICE and CARD aliases', () => {
    expect(normalizeUiRequest({ component: 'CHOICE', interactionId: 'a' })?.component).toBe('choice')
    expect(normalizeUiRequest({ component: 'CARD', interactionId: 'b' })?.component).toBe('card')
    expect(normalizeUiRequest({ component: 'SUMMARY_CARD', interactionId: 'c' })?.component).toBe('summary_card')
  })

  it('generates stable local id for readonly cards without interactionId', () => {
    const a = normalizeUiRequest({ component: 'table', title: 'T', data: [{ a: 1 }] })
    const b = normalizeUiRequest({ component: 'table', title: 'T', data: [{ a: 1 }] })
    expect(a?.interactionId).toMatch(/^local:readonly:table:/)
    expect(a?.interactionId).toBe(b?.interactionId)
  })

  it('promotes renderSchema into the canonical schema field', () => {
    const topLevel = normalizeUiRequest({
      component: 'LIST_CARD',
      renderSchema: { titleField: 'name' },
    })
    const legacyExtension = normalizeUiRequest({
      component: 'LIST_CARD',
      extension: { renderSchema: { titleField: 'displayName' } },
    })

    expect(topLevel?.component).toBe('list_card')
    expect(topLevel?.schema).toEqual({ titleField: 'name' })
    expect(legacyExtension?.schema).toEqual({ titleField: 'displayName' })
  })

  it('returns null for invalid raw', () => {
    expect(normalizeUiRequest(null)).toBeNull()
    expect(normalizeUiRequest('x')).toBeNull()
  })
})
