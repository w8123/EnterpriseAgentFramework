import { describe, expect, it } from 'vitest'
import { apiMarketLocation, apiMarketTab } from './apiMarketRoutes'

describe('API market route contract', () => {
  it.each(['discover', 'integrations', 'changes', 'sources'] as const)('accepts the %s tab', tab => {
    expect(apiMarketTab(tab)).toBe(tab)
  })
  it.each([undefined, 'invalid', ['integrations'], null])('defaults an invalid tab to discover: %j', value => {
    expect(apiMarketTab(value)).toBe('discover')
  })
  it('serializes a confirmed project without conflicting platform scope or losing URL fields', () => {
    expect(apiMarketLocation('integrations', { scope: 'all', apiKeyword: 'weather', marketSource: 'official' },
      { kind: 'project', project: { id: 36, projectCode: 'bzjs21', name: 'Business' } }, '#connection'))
      .toEqual({ path: '/api-market', query: { projectId: '36', projectCode: 'bzjs21', marketTab: 'integrations',
        apiKeyword: 'weather', marketSource: 'official' }, hash: '#connection' })
  })
})
