import { describe, expect, it } from 'vitest'
import {
  filterSidebarMenu,
  resolveActiveMenu,
  resolveOpenGroups,
  sidebarMenu,
} from './sidebarMenu'

describe('sidebarMenu information architecture', () => {
  it('groups navigation by the people who use it', () => {
    const groups = sidebarMenu
      .filter((entry) => entry.kind === 'group')
      .map((entry) => entry.label)

    expect(groups).toEqual([
      '开发',
      '运营',
      '平台管理',
    ])
  })

  it('does not expose duplicate navigation targets', () => {
    const targets = sidebarMenu.flatMap((entry) => {
      if (entry.kind === 'group') return []
      return entry.children?.map((child) => child.index) ?? [entry.index]
    })

    expect(new Set(targets).size).toBe(targets.length)
  })

  it('keeps retrieval navigation after retiring domain and context governance', () => {
    const targets = sidebarMenu.flatMap((entry) => (
      entry.kind === 'item' ? [entry.index, ...(entry.children ?? []).map(child => child.index)] : []
    ))

    expect(targets.some(path => path === '/domain' || path.startsWith('/domain/'))).toBe(false)
    expect(targets.some(path => path.startsWith('/context/'))).toBe(false)
    expect(targets).toEqual(expect.arrayContaining(['/settings/runtime-user-mappings', '/tool/retrieval']))
  })

  it('keeps only 知识库 for knowledge; file upload, retrieval test and biz index live elsewhere', () => {
    const targets = sidebarMenu.flatMap((entry) => (
      entry.kind === 'item' ? [entry.index, ...(entry.children ?? []).map(child => child.index)] : []
    ))

    expect(targets).toContain('/knowledge')
    expect(targets).not.toContain('/knowledge/import')
    expect(targets).not.toContain('/retrieval')
    expect(targets).not.toContain('/biz-index')
  })

  it('does not expose a generic Tool catalog as a product asset', () => {
    const items = sidebarMenu.flatMap((entry) => {
      if (entry.kind === 'group') return []
      return [{ index: entry.index, label: entry.label }, ...(entry.children ?? [])]
    })

    expect(items).not.toContainEqual({ index: '/tool', label: '工具目录' })
  })

  it('keeps MCP and A2A exposure under Agent', () => {
    const agent = sidebarMenu.find((entry) => (
      entry.kind === 'item' && entry.index === '/agent-group'
    ))

    expect(agent?.kind).toBe('item')
    if (agent?.kind !== 'item') return
    expect(agent.children).toEqual([
      { index: '/agent', label: 'Agent 列表', requiredPermissions: ['agent:read'] },
      { index: '/mcp-hub', label: 'MCP 开放', requiredPermissions: ['mcp-hub:read'] },
      { index: '/a2a-hub', label: 'A2A 开放', requiredPermissions: ['a2a-hub:read'] },
    ])
  })

  it('shows a developer only the development group', () => {
    const visible = filterSidebarMenu(sidebarMenu, ['platform:read', 'platform:write', 'capability:invoke'])
    const groups = visible.filter((entry) => entry.kind === 'group').map((entry) => entry.label)

    expect(groups).toEqual(['开发', '平台管理'])
    const platformItems = visible.slice(visible.findIndex((entry) => entry.kind === 'group' && entry.label === '平台管理') + 1)
    expect(platformItems.map((entry) => entry.kind === 'item' ? entry.index : '')).toEqual(['/diagnostics-group'])
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

  it('keeps end-user access with operations and account management with the administrator', () => {
    const visible = filterSidebarMenu(sidebarMenu, ['platform:read', 'tool-acl:read'])
    const endUsers = visible.find((entry) => (
      entry.kind === 'item' && entry.index === '/end-user-group'
    ))

    expect(endUsers?.kind).toBe('item')
    if (endUsers?.kind !== 'item') return
    expect(endUsers.children?.map((child) => child.index)).toEqual([
      '/settings/tool-acl',
    ])
    expect(visible.some((entry) => entry.kind === 'item' && entry.index === '/identity-group')).toBe(false)
    expect(visible.some((entry) => entry.kind === 'item' && entry.index === '/compliance-group')).toBe(false)
  })

  it('shows business user governance only with its dedicated permission', () => {
    const visible = filterSidebarMenu(sidebarMenu, ['identity:business-user:read'])
    const endUsers = visible.find((entry) => (
      entry.kind === 'item' && entry.index === '/end-user-group'
    ))

    expect(endUsers?.kind).toBe('item')
    if (endUsers?.kind !== 'item') return
    expect(endUsers.children?.map((child) => child.index)).toEqual(['/settings/business-users'])
    expect(visible.some((entry) => entry.kind === 'item' && entry.index === '/identity-group')).toBe(false)
  })

  it('shows one run center entry to anyone who can open one of its tabs', () => {
    expect(visibleTargets(['platform:read'])).not.toContain('/run-center')
    expect(visibleTargets(['automation:read'])).toContain('/run-center')
    expect(visibleTargets(['agent:evaluate'])).toContain('/run-center')
    const runCenter = sidebarMenu.find(entry => entry.kind === 'item' && entry.index === '/run-center')
    expect(runCenter?.kind === 'item' ? runCenter.children : 'missing').toBeUndefined()
  })

  it('separates Build assets from RunOps navigation permissions', () => {
    const builder = visibleTargets(['agent:read', 'workflow:read'])
    const operator = visibleTargets(['runops:read'])

    expect(builder).toContain('/agent')
    expect(builder).not.toContain('/run-center')
    expect(operator).not.toContain('/agent')
    expect(operator).toContain('/run-center')
  })
})

function visibleTargets(permissions: string[]): string[] {
  return filterSidebarMenu(sidebarMenu, permissions).flatMap((entry) => (
    entry.kind === 'item' ? [entry.index, ...(entry.children ?? []).map((child) => child.index)] : []
  ))
}

describe('resolveActiveMenu', () => {
  it.each([
    ['/knowledge/demo', '/knowledge'],
    ['/biz-index/orders', '/registry/projects'],
    ['/skill-market', '/skills'],
    ['/business-capabilities/java-methods', '/business-capabilities'],
    ['/business-capabilities/http-apis', '/business-capabilities'],
    ['/business-capabilities/http-apis/17', '/business-capabilities'],
    ['/business-methods', '/business-capabilities'],
    ['/apis/17', '/business-capabilities'],
    ['/model', '/model/instances'],
    ['/automations/demo', '/run-center'],
    ['/runops/trace-1', '/run-center'],
    ['/runops/evals/agent-1', '/run-center'],
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
    ['/api-market', []],
    ['/business-methods', []],
    ['/registry/projects/orders', []],
    ['/settings/tool-acl', ['/end-user-group']],
    ['/me/memories', []],
    ['/compliance/erasure', ['/compliance-group']],
    ['/mcp-hub/call-logs', ['/agent-group']],
    ['/model/playground', ['/model-group']],
    ['/a2a-hub/overview', ['/agent-group']],
    ['/tool/retrieval', ['/diagnostics-group']],
    ['/settings/runtime-user-mappings', ['/identity-group']],
    ['/domain', []],
    ['/domain/board', []],
    ['/domain/classifier-test', []],
  ])('opens the owning group for %s', (path, expected) => {
    expect(resolveOpenGroups(path)).toEqual(expected)
  })

  it('does not open a submenu for direct primary navigation', () => {
    expect(resolveOpenGroups('/workflows')).toEqual([])
    expect(resolveOpenGroups('/skill-market')).toEqual([])
  })
})
