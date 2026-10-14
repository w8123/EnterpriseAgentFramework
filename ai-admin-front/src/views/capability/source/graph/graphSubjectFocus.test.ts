import { describe, expect, it } from 'vitest'
import type { ApiGraphNode } from '@/api/apiGraph'
import { resolveGraphSubject } from './graphSubjectFocus'
const node = (id: number, subjectId: number, projectId = 7): ApiGraphNode => ({ id, projectId, kind: 'API', label: '订单', refId: id,
  parentId: null, typeName: null, propsJson: JSON.stringify({ subjectType: 'BUSINESS_METHOD', subjectId }) })
describe('association analysis subject focus', () => {
  it('resolves only an exact formal subject in the verified project', () => {
    const nodes = [node(91, 33), node(33, 44), node(92, 33, 8)]
    expect(resolveGraphSubject(nodes, 7, { projectId: '7', subjectType: 'BUSINESS_METHOD', subjectId: '33' })).toBe(nodes[0])
    expect(resolveGraphSubject(nodes, 7, { subjectType: 'BUSINESS_METHOD', subjectId: '91' })).toBeNull()
    expect(resolveGraphSubject(nodes, 7, { projectId: '8', subjectType: 'BUSINESS_METHOD', subjectId: '33' })).toBeNull()
  })
  it('rejects ambiguous, malformed or missing deep links', () => {
    const query = { subjectType: 'BUSINESS_METHOD', subjectId: '33' }
    expect(resolveGraphSubject([node(1, 33), node(2, 33)], 7, query)).toBeNull()
    for (const subjectId of ['33x', '0', '-1', '3.3', ['33']]) expect(resolveGraphSubject([node(1, 33)], 7, { ...query, subjectId })).toBeNull()
    expect(resolveGraphSubject([node(1, 33)], 7, {})).toBeNull()
  })
})
