import { beforeEach, expect, it, vi } from 'vitest'
import { getScanProjectOperationBlockers } from './scanProject'

const get = vi.hoisted(() => vi.fn())
vi.mock('./request', () => ({ controlRequest: { get } }))
beforeEach(() => vi.clearAllMocks())

it.each(['DELETE', 'RESCAN'] as const)('sends the specific %s operation to the owning project', async operation => {
  const body = { blocked: false, tools: [], agents: [], assets: [] }
  get.mockResolvedValue({ data: body })
  expect(await getScanProjectOperationBlockers(7, operation)).toEqual({ data: body })
  expect(get).toHaveBeenCalledWith('/api/scan-projects/7/operation-blockers', { params: { operation } })
})
