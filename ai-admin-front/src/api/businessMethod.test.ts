import { beforeEach, describe, expect, it, vi } from 'vitest'
import { businessMethodFixture } from '@/test/fixtures/businessMethod'
const request = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('./request', () => ({ controlRequest: request }))
import { getBusinessMethodSummary, listAllBusinessMethods } from './businessMethod'

beforeEach(() => vi.resetAllMocks())
describe('business-method owner pagination', () => {
  it('loads all project pages and keeps accepted source identities', async () => {
    request.get.mockResolvedValueOnce({ data: { records: [businessMethodFixture()], pages: 2, current: 1, total: 2 } })
      .mockResolvedValueOnce({ data: { records: [businessMethodFixture({ assetId: 702, name: 'orders_other' })], pages: 2, current: 2, total: 2 } })
    expect((await listAllBusinessMethods(7)).map(method => method.assetId)).toEqual([701, 702])
    expect(request.get).toHaveBeenLastCalledWith('/api/business-methods', { params: { projectId: 7, enabled: true, current: 2, size: 100 }, errorFeedback: 'local' })
  })
  it('requires an explicit project before sending a request', async () => {
    await expect(listAllBusinessMethods(0)).rejects.toThrow('关联')
    expect(request.get).not.toHaveBeenCalled()
  })
  it.each([
    { records: [{ name: 'projection', assetType: 'BUSINESS_METHOD', projectId: 7, parameters: [] }], pages: 1, current: 1, total: 1 },
    { records: [businessMethodFixture({ projectId: 8 })], pages: 1, current: 1, total: 1 },
    { records: [businessMethodFixture()], pages: 1, current: 1, total: 2 },
    { records: [], pages: 'unknown', current: 1, total: 0 },
  ])('rejects an unproven or incomplete catalog: %j', async (data) => {
    request.get.mockResolvedValueOnce({ data })
    await expect(listAllBusinessMethods(7)).rejects.toThrow()
  })
  it('does not return partial pages when a later request fails', async () => {
    request.get.mockResolvedValueOnce({ data: { records: [businessMethodFixture()], pages: 2, current: 1, total: 2 } })
      .mockRejectedValueOnce(new Error('owner unavailable'))
    await expect(listAllBusinessMethods(7)).rejects.toThrow('owner unavailable')
  })

  it('reads independent project counts with local error feedback', async () => {
    request.get.mockResolvedValue({ data: { total: 3, enabled: 2, disabled: 1 } })
    await getBusinessMethodSummary(7)
    expect(request.get).toHaveBeenCalledWith('/api/business-methods/summary', {
      params: { projectId: 7 }, errorFeedback: 'local',
    })
  })
})
