import { expect, it } from 'vitest'
import type { ScanProjectBlockers } from '@/types/scanProject'
import { formatScanProjectBlockersMessage, parseScanProjectBlockersFromError } from './scanProjectBlockers'

const owned: ScanProjectBlockers = { blocked: true, tools: [], agents: [], assets: [
  { assetType: 'BUSINESS_METHOD', assetId: 21, qualifiedName: 'orders:read', title: '查询订单' },
  { assetType: 'HTTP_API', assetId: 31, qualifiedName: 'orders:http:get', title: 'GET /orders' },
] }
const conflict = (data: unknown) => ({ response: { status: 409, data } })

it('shows the exact owner assets instead of claiming the project has an Agent reference', () => {
  const message = formatScanProjectBlockersMessage(owned)
  expect(message).toContain('无法删除')
  expect(message).toContain('业务方法：查询订单（orders:read）')
  expect(message).toContain('API：GET /orders（orders:http:get）')
  expect(message).not.toContain('Agent')
  expect(parseScanProjectBlockersFromError(conflict(owned))).toEqual(owned)
})

it('renders actual projection reference response fields', () => {
  expect(formatScanProjectBlockersMessage({ blocked: true, tools: ['orders_read'], assets: [],
    agents: [{ agentId: 'assistant', agentName: '订单助手' }] })).toContain('涉及 Agent：订单助手')
})

it.each([
  { blocked: true },
  { ...owned, tools: [7] },
  { ...owned, agents: [{ id: 'assistant', name: '旧字段' }] },
  { ...owned, assets: [{ ...owned.assets[0], assetType: 'UNCLASSIFIED' }] },
  { ...owned, assets: [{ ...owned.assets[0], assetId: 0 }] },
  { ...owned, assets: [null] },
])('rejects an incomplete or corrupted conflict body: %j', data => {
  expect(parseScanProjectBlockersFromError(conflict(data))).toBeNull()
})

it('does not reinterpret an authentication failure as an asset blocker', () => {
  expect(parseScanProjectBlockersFromError({ response: { status: 403, data: owned } })).toBeNull()
})
