import { describe, expect, it } from 'vitest'
import {
  filterSidebarMenu,
  resolveActiveMenu,
  resolveOpenGroups,
  sidebarMenu,
} from './sidebarMenu'

describe('sidebarMenu information architecture', () => {
  it('keeps task-oriented groups in the intended order', () => {
    const groups = sidebarMenu
      .filter((entry) => entry.kind === 'group')
      .map((entry) => entry.label)

    expect(groups).toEqual([
      '智能化改造',
      'AI 资产',
      '运行与治理',
      '平台资源',
      '实验室',
    ])
  })

  it('does not expose duplicate navigation targets', () => {
    const targets = sidebarMenu.flatMap((entry) => {
      if (entry.kind === 'group') return []
      return entry.children?.map((child) => child.index) ?? [entry.index]
    })

    expect(new Set(targets).size).toBe(targets.length)
  })

  it('does not expose a generic Tool catalog as a product asset', () => {
    const items = sidebarMenu.flatMap((entry) => {
      if (entry.kind === 'group') return []
      return [{ index: entry.index, label: entry.label }, ...(entry.children ?? [])]
    })

    expect(items).not.toContainEqual({ index: '/tool', label: '工具目录' })
    expect(items.find((item) => item.index === '/experimental-group')?.label).toBe('实验性能力')
  })

  it('groups external integration assets under AI assets', () => {
    const integration = sidebarMenu.find((entry) => (
      entry.kind === 'item' && entry.index === '/integration-group'
    ))

    expect(integration?.kind).toBe('item')
    if (integration?.kind !== 'item') return
    expect(integration.children).toEqual([
      { index: '/api-market', label: 'API 市场' },
      { index: '/mcp-hub', label: 'MCP Hub' },
      { index: '/a2a-hub', label: 'A2A 互联中心' },
    ])
  })

  it('exposes one business capability workbench, with source changes owned by projects', () => {
    const assets = sidebarMenu.filter(entry => entry.kind === 'item' && ['/business-capabilities', '/business-methods', '/apis'].includes(entry.index))
    expect(assets.map(entry => entry.kind === 'item' ? entry.index : '')).toEqual(['/business-capabilities'])
    for (const entry of assets) {
      if (entry.kind !== 'item') continue
      expect(entry.children).toBeUndefined()
      expect(entry.label).toBe('业务能力')
      expect(entry.requiredPermissions).toEqual(['platform:read'])
    }
    const paths = sidebarMenu.flatMap(entry => entry.kind === 'item' ? [entry.index, ...(entry.children || []).map(child => child.index)] : [])
    expect(paths.some(path => path.startsWith('/capability'))).toBe(false)
  })

  it('removes privileged identity entries without leaving the group empty', () => {
    const visible = filterSidebarMenu(sidebarMenu, ['platform:read'])
    const identity = visible.find((entry) => (
      entry.kind === 'item' && entry.index === '/identity-group'
    ))

    expect(identity?.kind).toBe('item')
    if (identity?.kind !== 'item') return
    expect(identity.children?.map((child) => child.index)).toEqual([
      '/settings/personal-memory',
      '/settings/tool-acl',
    ])
  })

  it('shows business user governance only with its dedicated permission', () => {
    const visible = filterSidebarMenu(sidebarMenu, ['identity:business-user:read'])
    const identity = visible.find((entry) => (
      entry.kind === 'item' && entry.index === '/identity-group'
    ))

    expect(identity?.kind).toBe('item')
    if (identity?.kind !== 'item') return
    expect(identity.children?.map((child) => child.index)).toContain('/settings/business-users')
    expect(identity.children?.map((child) => child.index)).not.toContain('/settings/platform-users')
  })

  it('shows Automation only to principals with Automation read access', () => {
    const withoutRead = filterSidebarMenu(sidebarMenu, ['platform:read'])
    const withRead = filterSidebarMenu(sidebarMenu, ['automation:read'])

    expect(withoutRead.some((entry) => (
      entry.kind === 'item' && entry.index === '/automations'
    ))).toBe(false)
    expect(withRead.some((entry) => (
      entry.kind === 'item' && entry.index === '/automations'
    ))).toBe(true)
  })

  it('separates Build assets from RunOps navigation permissions', () => {
    const builder = filterSidebarMenu(sidebarMenu, ['agent:read', 'workflow:read'])
    const operator = filterSidebarMenu(sidebarMenu, ['runops:read'])

    expect(builder.some((entry) => entry.kind === 'item' && entry.index === '/agent')).toBe(true)
    expect(builder.some((entry) => entry.kind === 'item' && entry.index === '/runops')).toBe(false)
    expect(operator.some((entry) => entry.kind === 'item' && entry.index === '/agent')).toBe(false)
    expect(operator.some((entry) => entry.kind === 'item' && entry.index === '/runops')).toBe(true)
  })
})

describe('resolveActiveMenu', () => {
  it.each([
    ['/knowledge/import', '/knowledge/import'],
    ['/knowledge/demo', '/knowledge'],
    ['/skill-market', '/skills'],
    ['/business-capabilities/java-methods', '/business-capabilities'],
    ['/business-capabilities/http-apis', '/business-capabilities'],
    ['/business-capabilities/http-apis/17', '/business-capabilities'],
    ['/business-methods', '/business-capabilities'],
    ['/apis/17', '/business-capabilities'],
    ['/registry/projects/orders/sync-diagnostics', '/registry/projects'],
    ['/model', '/model/instances'],
    ['/automations/demo', '/automations'],
    ['/tool/retrieval', '/tool/retrieval'],
  ])('maps %s to %s', (path, expected) => {
    expect(resolveActiveMenu(path)).toBe(expected)
  })

  it('prioritizes the route metadata override', () => {
    expect(resolveActiveMenu('/unmapped/detail', '/agent')).toBe('/agent')
  })
})

describe('resolveOpenGroups', () => {
  it.each([
    ['/api-market', ['/integration-group']],
    ['/business-methods', []],
    ['/registry/projects/orders', []],
    ['/settings/tool-acl', ['/identity-group']],
    ['/settings/personal-memory', ['/identity-group']],
    ['/mcp-hub/call-logs', ['/integration-group']],
    ['/model/playground', ['/model-group']],
    ['/a2a-hub/overview', ['/integration-group']],
    ['/tool/retrieval', ['/diagnostics-group']],
    ['/domain/classifier-test', ['/diagnostics-group']],
  ])('opens the owning group for %s', (path, expected) => {
    expect(resolveOpenGroups(path)).toEqual(expected)
  })

  it('does not open a submenu for direct primary navigation', () => {
    expect(resolveOpenGroups('/runops')).toEqual([])
    expect(resolveOpenGroups('/skill-market')).toEqual([])
  })
})
