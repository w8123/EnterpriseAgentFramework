import { describe, expect, it, beforeEach, afterEach } from 'vitest'
import {
  getCustomInteractionRenderer,
  listRegisteredCustomRendererKeys,
  registerCustomInteractionRenderer,
  resolveRendererKind,
  unregisterCustomInteractionRenderer,
} from '../renderers/interactionRegistry'

describe('interactionRegistry', () => {
  beforeEach(() => {
    for (const key of listRegisteredCustomRendererKeys()) {
      unregisterCustomInteractionRenderer(key)
    }
  })

  afterEach(() => {
    for (const key of listRegisteredCustomRendererKeys()) {
      unregisterCustomInteractionRenderer(key)
    }
  })

  it('maps builtin components to renderer kinds', () => {
    expect(resolveRendererKind('form')).toBe('form')
    expect(resolveRendererKind('multi_select')).toBe('select')
    expect(resolveRendererKind('table')).toBe('table')
    expect(resolveRendererKind('unknown-x')).toBe('custom')
  })

  it('registers and resolves custom renderer factories', () => {
    const factory = () => null
    registerCustomInteractionRenderer('host.card', factory)
    expect(getCustomInteractionRenderer('host.card')).toBe(factory)
    expect(listRegisteredCustomRendererKeys()).toContain('host.card')
    unregisterCustomInteractionRenderer('host.card')
    expect(getCustomInteractionRenderer('host.card')).toBeUndefined()
  })
})
