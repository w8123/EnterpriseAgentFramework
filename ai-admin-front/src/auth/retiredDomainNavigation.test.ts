import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import { routes } from '@/router'

describe('retired domain navigation', () => {
  it.each(['/domain', '/domain/board', '/domain/classifier-test', '/tool/retrieval'])('sends retired %s to the dashboard like an unknown route', path => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      const router = createRouter({ history: createMemoryHistory(), routes })
      const unknown = router.resolve('/missing-page')
      const retired = router.resolve(path)
      expect(retired.matched).toEqual(unknown.matched)
      expect(retired.name).toBe('NotFound')
      expect(retired.matched[retired.matched.length - 1]?.redirect).toBe('/dashboard')
    } finally {
      warn.mockRestore()
    }
  })

  it('keeps the current business capability destinations registered and retires context governance', () => {
    const router = createRouter({ history: createMemoryHistory(), routes })
    expect(router.resolve('/business-capabilities/java-methods').name).toBe('BusinessMethodCatalog')
    expect(router.resolve('/business-capabilities/http-apis').name).toBe('HttpApiCatalog')
    expect(router.resolve('/context/governance').name).not.toBe('ContextGovernance')
    expect(router.resolve('/settings/runtime-user-mappings').name).toBe('RuntimeUserMapping')
    expect(router.resolve('/me/memories').name).toBe('MyMemories')
    expect(router.resolve('/compliance/erasure').name).toBe('DataErasure')
  })
})
