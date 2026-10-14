import { describe, expect, it } from 'vitest'
import { sourceGroupMembersLocation, sourceOwnerRoutes } from './sourceOwnerRoutes'

describe('source view owner navigation', () => {
  it('carries only the verified project, never a scan name or global projection ref', () => {
    expect(sourceOwnerRoutes({ id: 7, projectCode: ' orders ' })).toEqual({
      businessMethods: { path: '/business-capabilities/java-methods', query: { projectId: '7', projectCode: 'orders' } },
      apis: { path: '/business-capabilities/http-apis', query: { projectId: '7', projectCode: 'orders' } },
    })
    expect(sourceOwnerRoutes({ id: 8, projectCode: 'other-orders' })?.apis.query.projectId).toBe('8')
  })
  it('does not guess a project or asset when source identity is missing', () => {
    expect(sourceOwnerRoutes(null)).toBeNull()
    expect(sourceOwnerRoutes({ id: 0, projectCode: 'orders' })).toBeNull()
    expect(sourceOwnerRoutes({ id: 7, projectCode: null })?.apis.query).toEqual({ projectId: '7' })
  })
  it('opens classification members in the current category and preserves sibling filters', () => {
    const group = { id: 5, projectId: 7 }
    const location = sourceGroupMembersLocation(7, 'http-apis', group, {
      methodKeyword: '订单', methodGroupId: '8', methodPage: '3', apiPage: '4', apiEnvironment: 'test', subjectType: 'GROUP', subjectId: '5',
    })
    expect(location).toEqual({ path: '/business-capabilities/http-apis', query: {
      projectId: '7', methodKeyword: '订单', apiPage: '1', methodPage: '3', apiEnvironment: 'test',
      methodGroupId: '8', apiGroupId: '5', subjectType: undefined, subjectId: undefined, subjectKey: undefined,
    } })
    expect(sourceGroupMembersLocation(8, 'http-apis', group, {})).toBeNull()
    expect(sourceGroupMembersLocation(7, 'java-methods', { ...group, id: 0 }, {})).toBeNull()
  })
})
