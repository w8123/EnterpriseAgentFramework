import { describe, expect, it } from 'vitest'
import {
  isBlockingUiRequest,
  isCardOnlyUiRequest,
  isTextOnlyUiRequest,
  normalizeUiRequest,
  uiPresentationMode,
} from './normalizeUiRequest'

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

  it('treats readonly output as non-blocking and honors explicit behavior override', () => {
    expect(isBlockingUiRequest({ component: 'LIST_CARD' })).toBe(false)
    expect(isBlockingUiRequest({ component: 'confirm' })).toBe(true)
    expect(isBlockingUiRequest({ component: 'confirm', behavior: { blocking: false } })).toBe(false)
    expect(isBlockingUiRequest({ component: 'list_card', behavior: { blocking: true } })).toBe(true)
  })

  it('normalizes presentation modes and keeps legacy responses backward compatible', () => {
    const cardOnly = normalizeUiRequest({
      component: 'LIST_CARD',
      presentation: { mode: 'card-only' },
    })
    expect(cardOnly?.presentation).toEqual({ mode: 'card_only' })
    expect(isCardOnlyUiRequest(cardOnly)).toBe(true)
    expect(isTextOnlyUiRequest({ component: 'detail', presentation: { mode: 'text_only' } })).toBe(true)
    expect(uiPresentationMode({ component: 'detail' })).toBe('text_and_card')
    expect(uiPresentationMode({ component: 'detail', presentation: { mode: 'unknown' } })).toBe('text_and_card')
  })
})
