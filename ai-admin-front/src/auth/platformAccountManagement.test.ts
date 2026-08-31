import { describe, expect, it } from 'vitest'
import {
  groupPlatformPermissions,
  passwordPolicyIssues,
  platformAuditEventLabel,
  safeAuditDetailEntries,
} from './platformAccountManagement'

describe('platform account management presentation', () => {
  it('uses the same password policy as Control', () => {
    expect(passwordPolicyIssues('short')).toHaveLength(2)
    expect(passwordPolicyIssues('LongEnough123')).toEqual([])
    expect(passwordPolicyIssues('alllowercasebutlong')).toEqual([
      '至少包含大写、小写、数字、符号中的三类',
    ])
  })

  it('groups and sorts the permission catalog', () => {
    const groups = groupPlatformPermissions([
      { id: 2, permissionCode: 'workflow:write', permissionName: 'write', resourceType: 'WORKFLOW', reservedForSystemRole: false },
      { id: 1, permissionCode: 'agent:read', permissionName: 'read', resourceType: 'AGENT', reservedForSystemRole: false },
      { id: 3, permissionCode: 'workflow:read', permissionName: 'read', resourceType: 'WORKFLOW', reservedForSystemRole: false },
    ])

    expect(groups.map((group) => group.resourceType)).toEqual(['AGENT', 'WORKFLOW'])
    expect(groups[1].permissions.map((permission) => permission.permissionCode))
      .toEqual(['workflow:read', 'workflow:write'])
  })

  it('labels known audit events and defensively removes sensitive details', () => {
    expect(platformAuditEventLabel('PLATFORM_ACCOUNT_CREATED')).toBe('创建平台账号')
    expect(safeAuditDetailEntries({
      detailsJson: JSON.stringify({ status: 'ACTIVE', password: 'never-render', tokenCount: 2 }),
    })).toEqual([['status', 'ACTIVE']])
  })
})
