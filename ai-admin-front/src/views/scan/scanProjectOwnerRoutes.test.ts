import { describe, expect, it } from 'vitest'
import { scanProjectOwnerRoutes } from './scanProjectOwnerRoutes'

describe('source view owner navigation', () => {
  it('carries only the verified project, never a scan name or global projection ref', () => {
    expect(scanProjectOwnerRoutes({ id: 7, projectCode: ' orders ' })).toEqual({
      businessMethods: { path: '/business-methods', query: { projectId: '7', projectCode: 'orders' } },
      apis: { path: '/apis', query: { projectId: '7', projectCode: 'orders' } },
    })
    expect(scanProjectOwnerRoutes({ id: 8, projectCode: 'other-orders' })?.apis.query.projectId).toBe('8')
  })
  it('does not guess a project or asset when source identity is missing', () => {
    expect(scanProjectOwnerRoutes(null)).toBeNull()
    expect(scanProjectOwnerRoutes({ id: 0, projectCode: 'orders' })).toBeNull()
    expect(scanProjectOwnerRoutes({ id: 7, projectCode: null })?.apis.query).toEqual({ projectId: '7' })
  })
})
