import { describe, expect, it } from 'vitest'
import { roleShortcutGroups } from './roleShortcuts'

const titles = (permissions: string[]) => roleShortcutGroups(permissions).map(group => group.title)

describe('roleShortcutGroups', () => {
  it('shows a developer only the development shortcuts', () => {
    expect(titles(['platform:read', 'platform:write', 'agent:read', 'workflow:read', 'runops:read'])).toEqual(['开发'])
  })

  it('shows an operator the operations shortcuts it can use', () => {
    const groups = roleShortcutGroups(['platform:read', 'workflow:write', 'agent:write', 'runops:read', 'tool-acl:read'])
    expect(groups.map(group => group.title)).toEqual(['运营'])
    expect(groups[0].shortcuts.map(shortcut => shortcut.to)).toEqual(['/workflows', '/agent', '/settings/tool-acl', '/runops'])
  })

  it('shows the administrator every group except the read-only one', () => {
    expect(titles(['*'])).toEqual(['开发', '运营', '平台管理'])
  })

  it('gives a read-only auditor the run records', () => {
    expect(titles(['platform:read', 'runops:read'])).toEqual(['审计'])
  })

  it('shows nothing without permissions', () => {
    expect(roleShortcutGroups([])).toEqual([])
  })
})
