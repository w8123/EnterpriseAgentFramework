import { describe, expect, it } from 'vitest'
import { buildClassificationSections, classificationMemberships, validateClassificationIndex } from './catalogClassification'
import type { CatalogClassificationIndex } from '@/types/semanticDoc'

const index = (): CatalogClassificationIndex => ({ projectId: 7, subjectType: 'BUSINESS_METHOD',
  groups: [{ id: 2, projectId: 7, name: 'dictionary', displayName: '字典', sourceClasses: [], memberCount: 12 },
    { id: 3, projectId: 7, name: 'orders', displayName: '订单', sourceClasses: [], memberCount: 20 }],
  memberships: [{ subjectId: 10, groupIds: [2, 3] }, { subjectId: 11, groupIds: [] },
    ...Array.from({ length: 11 }, (_, i) => ({ subjectId: 1000 + i, groupIds: [2] })),
    ...Array.from({ length: 19 }, (_, i) => ({ subjectId: 2000 + i, groupIds: [3] })),
    ...Array.from({ length: 3 }, (_, i) => ({ subjectId: 3000 + i, groupIds: [] }))], ungroupedCount: 4 })
const rows = [{ id: 10, title: '查询' }, { id: 11, title: '未归类能力' }, { id: 12, title: '新同步能力' }]

describe('catalog classification identity and page sections', () => {
  it('keeps an empty selected classification available for description and rename', () => {
    const data = index()
    data.groups.push({ id: 9, projectId: 7, name: 'empty', displayName: '空分类', sourceClasses: [], memberCount: 0 })
    expect(buildClassificationSections([], data, '9', (row: { id: number }) => row.id))
      .toEqual([{ key: '9', title: '空分类', group: data.groups[2], memberCount: 0, rows: [] }])
  })
  it('renders a multi-category owner once and keeps every membership label', () => {
    const sections = buildClassificationSections(rows, index(), '', row => row.id)
    expect(sections.map(section => [section.key, section.rows.map(row => row.id)])).toEqual([
      ['2', [10]], ['unclassified', [11]], ['pending', [12]],
    ])
    expect(sections[0]?.memberCount).toBe(12)
    expect(classificationMemberships(index(), 10).map(group => group.displayName)).toEqual(['字典', '订单'])
  })
  it('uses a selected true membership even when it is not the primary category', () => {
    const sections = buildClassificationSections([rows[0]!], index(), '3', row => row.id)
    expect(sections[0]).toMatchObject({ key: '3', memberCount: 20 })
    expect(sections[0]?.rows).toEqual([rows[0]])
  })
  it('never calls failed, missing, or inconsistent memberships unclassified', () => {
    expect(buildClassificationSections(rows, null, '', row => row.id)[0]?.key).toBe('pending')
    const stale = index(); stale.memberships[0]!.groupIds = [999]
    expect(buildClassificationSections([rows[0]!], stale, '', row => row.id)[0]?.key).toBe('pending')
    expect(buildClassificationSections([rows[0]!], index(), 'unclassified', row => row.id)[0]?.key).toBe('pending')
  })
  it('rejects foreign project, type, duplicate identities and unknown group references', () => {
    expect(validateClassificationIndex(index(), 7, 'BUSINESS_METHOD')).toBe(true)
    expect(validateClassificationIndex(index(), 8, 'BUSINESS_METHOD')).toBe(false)
    expect(validateClassificationIndex(index(), 7, 'HTTP_API')).toBe(false)
    const duplicate = index(); duplicate.memberships.push({ subjectId: 10, groupIds: [] })
    expect(validateClassificationIndex(duplicate, 7, 'BUSINESS_METHOD')).toBe(false)
    const foreign = index(); foreign.groups[0]!.projectId = 8
    expect(validateClassificationIndex(foreign, 7, 'BUSINESS_METHOD')).toBe(false)
    const unknown = index(); unknown.memberships[0]!.groupIds = [999]
    expect(validateClassificationIndex(unknown, 7, 'BUSINESS_METHOD')).toBe(false)
    const partial = index(); partial.memberships.pop()
    expect(validateClassificationIndex(partial, 7, 'BUSINESS_METHOD')).toBe(false)
  })
})
