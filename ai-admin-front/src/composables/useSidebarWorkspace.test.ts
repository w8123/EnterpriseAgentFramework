import { beforeEach, describe, expect, it, vi } from 'vitest'

const session = vi.hoisted(() => ({ user: undefined as any }))

vi.mock('@/auth/platformSession', async () => {
  const { ref: vueRef } = await import('vue')
  session.user = vueRef(null)
  return { platformSessionUser: session.user }
})

import { useSidebarWorkspace } from './useSidebarWorkspace'

function labels(entries: ReturnType<typeof useSidebarWorkspace>['visibleMenu']['value']) {
  return entries.flatMap(entry => entry.kind === 'item' ? [entry.label] : [])
}

describe('useSidebarWorkspace', () => {
  beforeEach(() => {
    localStorage.clear()
    session.user.value = null
  })

  it('starts platform admins on the full menu and remembers the chosen identity per account', () => {
    session.user.value = { userId: 1, username: 'admin', roles: ['PLATFORM_ADMIN'], permissions: ['*'] }
    const workspace = useSidebarWorkspace()
    expect(workspace.currentWorkspace.value?.key).toBe('admin')
    expect(labels(workspace.visibleMenu.value)).toEqual(expect.arrayContaining(['项目管理', 'Agent', 'AI 使用情况']))

    workspace.setWorkspace('developer')
    expect(workspace.currentWorkspace.value?.key).toBe('developer')
    expect(labels(workspace.visibleMenu.value)).not.toContain('AI 使用情况')
    expect(localStorage.getItem('reachai.sidebar.workspace.user.1')).toBe('developer')

    session.user.value = { userId: 2, username: 'manager01', roles: ['MANAGER'], permissions: ['agent:read'] }
    expect(workspace.currentWorkspace.value?.key).toBe('manager')
    expect(workspace.availableWorkspaces.value.map(w => w.key)).toEqual(['manager', 'my'])

    session.user.value = { userId: 1, username: 'admin', roles: ['PLATFORM_ADMIN'], permissions: ['*'] }
    expect(workspace.currentWorkspace.value?.key).toBe('developer')
  })

  it('falls back to the default when a stored identity no longer exists', () => {
    localStorage.setItem('reachai.sidebar.workspace.user.6', 'operator')
    session.user.value = { userId: 6, username: 'ops01', roles: ['MANAGER'], permissions: ['agent:read'] }
    expect(useSidebarWorkspace().currentWorkspace.value?.key).toBe('manager')
  })

  it('ignores identities the account does not hold', () => {
    session.user.value = { userId: 3, username: 'manager01', roles: ['MANAGER'], permissions: ['mcp-hub:read'] }
    const workspace = useSidebarWorkspace()
    workspace.setWorkspace('admin')
    expect(workspace.currentWorkspace.value?.key).toBe('manager')
  })

  it('follows a page opened from a link into the workspace that owns it', () => {
    session.user.value = { userId: 4, username: 'admin', roles: ['PLATFORM_ADMIN'], permissions: ['*'] }
    const workspace = useSidebarWorkspace()
    workspace.setWorkspace('developer')
    workspace.followActiveMenu('/usage')
    expect(workspace.currentWorkspace.value?.key).toBe('manager')
    workspace.followActiveMenu('/agent')
    expect(workspace.currentWorkspace.value?.key).toBe('manager')
  })

  it('gives custom-role accounts the full permission-filtered menu', () => {
    session.user.value = { userId: 5, username: 'custom', roles: ['CUSTOM'], permissions: ['knowledge:read'] }
    const workspace = useSidebarWorkspace()
    expect(workspace.currentWorkspace.value.key).toBe('all')
    expect(labels(workspace.visibleMenu.value)).toEqual(['概览', 'AI 工具接入', '知识库'])
  })

  it('shows the same AI connection in every workspace without changing the current workspace', () => {
    session.user.value = { userId: 9, username: 'admin', roles: ['PLATFORM_ADMIN'], permissions: ['*'] }
    const workspace = useSidebarWorkspace()
    for (const key of ['developer', 'manager', 'admin', 'my'] as const) {
      workspace.setWorkspace(key)
      expect(labels(workspace.visibleMenu.value).filter(label => label === 'AI 工具接入')).toHaveLength(1)
      workspace.followActiveMenu('/me/ai-connections')
      expect(workspace.currentWorkspace.value.key).toBe(key)
    }
  })

  it('hides AI connection in role workspaces when there are no platform permissions', () => {
    session.user.value = { userId: 10, username: 'developer', roles: ['DEVELOPER'], permissions: [] }
    const workspace = useSidebarWorkspace()
    expect(workspace.currentWorkspace.value.key).toBe('developer')
    expect(labels(workspace.visibleMenu.value)).not.toContain('AI 工具接入')
  })

  it('puts accounts without any role in 我的 and hides the platform AI connection there', () => {
    session.user.value = { userId: 7, username: 'norole01', roles: [], permissions: [] }
    const workspace = useSidebarWorkspace()
    expect(workspace.currentWorkspace.value.key).toBe('my')
    expect(workspace.availableWorkspaces.value.map(w => w.key)).toEqual(['my'])
    expect(labels(workspace.visibleMenu.value)).toEqual(['我的首页', '我的记忆'])

    session.user.value = { userId: 8, username: 'manager01', roles: ['MANAGER'], permissions: ['agent:read'] }
    workspace.setWorkspace('my')
    expect(labels(workspace.visibleMenu.value)).toEqual(['我的首页', 'AI 工具接入', 'AI 操作审核', '我的记忆'])
  })
})
