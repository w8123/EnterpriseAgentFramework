import type { BusinessMethodInfo } from '@/types/businessMethod'

export function businessMethodFixture(overrides: Partial<BusinessMethodInfo> = {}): BusinessMethodInfo {
  const projectId = overrides.projectId ?? 7
  return {
    assetType: 'BUSINESS_METHOD', assetId: projectId * 100 + 1, acceptedRevisionId: projectId * 100 + 2,
    methodCode: 'orders.read', name: 'orders_read', title: '查询订单', qualifiedName: 'orders:read',
    projectId, projectCode: 'orders', status: 'ACCEPTED', source: 'sdk', sourceAvailability: 'READY',
    description: '按订单标识查询', enabled: true, sideEffect: 'READ', parameters: [], metadata: {},
    contractHash: 'a'.repeat(64), invocationHash: 'b'.repeat(64), bindingHash: 'c'.repeat(64), ...overrides,
  }
}
