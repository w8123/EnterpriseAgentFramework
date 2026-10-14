import { beforeEach, expect, it, vi } from 'vitest'
import { getScanProjectOperationBlockers, getScanProjectSources } from './scanProject'

const get = vi.hoisted(() => vi.fn())
vi.mock('./request', () => ({ controlRequest: { get } }))
beforeEach(() => vi.clearAllMocks())
it('sends source pagination, complete server search and exact owner observation filters without the UI defer flag', async () => {
  await getScanProjectSources(7, { current: 3, size: 20, keyword: '客户', moduleId: 2, observationIds: [31, 32], focusObservationId: 31, defer: false })
  expect(get).toHaveBeenCalledWith('/api/scan-projects/7/sources', { params: {
    current: 3, size: 20, keyword: '客户', moduleId: 2, observationIds: '31,32', focusObservationId: 31,
  }, errorFeedback: 'local' })
})

it.each(['DELETE', 'RESCAN'] as const)('sends the specific %s operation to the owning project', async operation => {
  const body = { blocked: false, tools: [], agents: [], assets: [] }
  get.mockResolvedValue({ data: body })
  expect(await getScanProjectOperationBlockers(7, operation)).toEqual({ data: body })
  expect(get).toHaveBeenCalledWith('/api/scan-projects/7/operation-blockers', { params: { operation } })
})
