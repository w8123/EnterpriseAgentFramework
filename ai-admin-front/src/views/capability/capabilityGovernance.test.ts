import { describe, expect, it } from 'vitest'
import type { CapabilityDiffReviewItem } from '@/types/registry'
import {
  capabilityDescription,
  capabilityDescriptionHasEncodingIssue,
  capabilityEndpoint,
  capabilityReviewAttention,
  capabilitySideEffectLabel,
  capabilitySideEffectTone,
  capabilitySourceLabel,
  parseCapabilityFieldDiffs,
} from './capabilityGovernance'

function reviewItem(overrides: Partial<CapabilityDiffReviewItem> = {}): CapabilityDiffReviewItem {
  return {
    id: 1,
    snapshotId: 2,
    syncId: 'sync-1',
    projectCode: 'orders',
    qualifiedName: 'orders:queryOrder',
    name: 'queryOrder',
    storageName: 'orders_queryOrder',
    changeType: 'CHANGED',
    fieldDiffJson: '[]',
    reviewStatus: 'PENDING',
    ...overrides,
  }
}

describe('capability governance model', () => {
  it('identifies SDK-backed scanner projections without relabeling all scanner rows', () => {
    expect(capabilitySourceLabel('scanner', 'sdk:orders:queryOrder')).toBe('SDK 注册')
    expect(capabilitySourceLabel('scanner', 'scanner:orders:queryOrder')).toBe('源码扫描')
    expect(capabilitySourceLabel('sdk')).toBe('SDK 注册')
  })

  it('maps every persisted side-effect level to a user-facing risk', () => {
    expect(capabilitySideEffectLabel('READ_ONLY')).toBe('只读')
    expect(capabilitySideEffectTone('READ_ONLY')).toBe('success')
    expect(capabilitySideEffectLabel('IDEMPOTENT_WRITE')).toBe('幂等写入')
    expect(capabilitySideEffectTone('IDEMPOTENT_WRITE')).toBe('warning')
    expect(capabilitySideEffectLabel('IRREVERSIBLE')).toBe('不可逆操作')
    expect(capabilitySideEffectTone('IRREVERSIBLE')).toBe('danger')
  })

  it('joins the callable endpoint without duplicate slashes', () => {
    expect(capabilityEndpoint({
      name: 'queryOrder',
      title: '查询订单',
      description: '',
      parameters: [],
      source: 'scanner',
      enabled: true,
      baseUrl: 'https://orders.example.com/',
      contextPath: '/orders/',
      endpointPath: '/api/orders/{id}',
    })).toBe('https://orders.example.com/orders/api/orders/{id}')
  })

  it('surfaces damaged source copy instead of presenting question marks as a valid description', () => {
    const damaged = { description: '???????????????? Supervisor ????????' }
    expect(capabilityDescription(damaged)).toContain('编码异常')
    expect(capabilityDescriptionHasEncodingIssue(damaged)).toBe(true)
    expect(capabilityDescription({ aiDescription: '可读的 AI 说明', ...damaged })).toBe('可读的 AI 说明')
  })

  it('classifies contract changes and source removal without trusting placeholder impact data', () => {
    const contract = reviewItem({
      fieldDiffJson: JSON.stringify([{ field: 'parameters', oldValue: '[]', newValue: '[{"name":"id"}]' }]),
      impactJson: JSON.stringify({ agents: [], aclRuleIds: [] }),
    })
    expect(parseCapabilityFieldDiffs(contract)).toHaveLength(1)
    expect(capabilityReviewAttention(contract)).toMatchObject({ label: '调用契约变化', tone: 'warning' })
    expect(capabilityReviewAttention(reviewItem({ changeType: 'DELETED' }))).toMatchObject({ label: '目录移除', tone: 'danger' })
  })
})
