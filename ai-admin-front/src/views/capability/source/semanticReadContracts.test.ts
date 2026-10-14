import { beforeEach, expect, it, vi } from 'vitest'
import { getClassificationAssignments, getSubjectSemanticDoc, listProjectSemanticDocs } from '@/api/semanticDoc'

const get = vi.hoisted(() => vi.fn())
vi.mock('@/api/request', () => ({ controlRequest: { get } }))
beforeEach(() => vi.clearAllMocks())
it('uses a separate project index and one typed subject body endpoint', async () => {
  await listProjectSemanticDocs(7)
  expect(get).toHaveBeenLastCalledWith('/api/business-capabilities/projects/7/semantic-docs')
  await getSubjectSemanticDoc(7, 'HTTP_API', 11)
  expect(get).toHaveBeenLastCalledWith('/api/business-capabilities/projects/7/subjects/HTTP_API/11/semantic', { errorFeedback: 'local' })
})
it('reads exact current assignment revisions in one project/type batch', async () => {
  await getClassificationAssignments(7, 'BUSINESS_METHOD', [11, 12])
  expect(get).toHaveBeenCalledOnce()
  expect(get).toHaveBeenCalledWith('/api/business-capabilities/projects/7/classification/BUSINESS_METHOD/assignments',
    { params: { subjectIds: '11,12' }, errorFeedback: 'local' })
})
