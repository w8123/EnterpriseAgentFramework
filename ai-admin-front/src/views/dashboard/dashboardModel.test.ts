import { describe, expect, it } from 'vitest'
import type { RunSummary } from '@/types/runops'
import {
  buildTokenDistribution,
  buildTrendBuckets,
  summarizeIdentities,
  summarizeRunOutcomes,
  summarizeTokens,
} from './dashboardModel'

function run(partial: Partial<RunSummary>): RunSummary {
  return {
    traceId: partial.traceId ?? crypto.randomUUID(),
    runType: 'AGENT',
    entryType: 'API',
    status: 'COMPLETED',
    ...partial,
  }
}

describe('dashboardModel', () => {
  it('技术完成率只使用终态运行作为分母', () => {
    const summary = summarizeRunOutcomes([
      run({ status: 'COMPLETED' }),
      run({ status: 'FAILED' }),
      run({ status: 'RUNNING' }),
      run({ status: 'SUSPENDED' }),
    ])
    expect(summary.terminal).toBe(2)
    expect(summary.technicalCompletionRate).toBe(50)
  })

  it('用户与 Token 同时返回样本覆盖率', () => {
    const runs = [
      run({ userId: 'u1', tokenCost: 10 }),
      run({ userId: 'u1', tokenCost: 0 }),
      run({ userId: undefined, tokenCost: undefined }),
    ]
    expect(summarizeIdentities(runs)).toMatchObject({
      distinctUsers: 1,
      identifiedRuns: 2,
      coverage: 66.7,
    })
    expect(summarizeTokens(runs)).toMatchObject({
      total: 10,
      recordedRuns: 1,
      coverage: 33.3,
    })
  })

  it('趋势只使用真实时间戳落桶', () => {
    const now = new Date('2026-08-26T12:00:00+08:00')
    const buckets = buildTrendBuckets(
      [
        run({ startedAt: '2026-08-26T10:10:00+08:00', tokenCost: 12, userId: 'u1' }),
        run({ startedAt: '2026-08-26T10:40:00+08:00', tokenCost: 8, userId: 'u1' }),
      ],
      1,
      now,
    )
    expect(buckets).toHaveLength(24)
    expect(buckets[0].label).toBe('13:00')
    expect(buckets[buckets.length - 1]?.label).toBe('12:00')
    expect(buckets.reduce((sum, bucket) => sum + bucket.runs, 0)).toBe(2)
    expect(buckets.reduce((sum, bucket) => sum + bucket.tokens, 0)).toBe(20)
  })

  it('Token 分布保留无法归属到项目的已记录 Token', () => {
    const runs = [
      run({ projectCode: 'ORDER', tokenCost: 20 }),
      run({ projectCode: undefined, tokenCost: 5 }),
    ]
    const distribution = buildTokenDistribution(
      [
        {
          key: 'ORDER',
          projectCode: 'ORDER',
          name: '订单系统',
          agentCount: 0,
          runs: 1,
          users: 0,
          identityCoverage: 0,
          tokens: 20,
          tokenCoverage: 100,
          technicalCompletionRate: 100,
          sampleRuns: [runs[0]],
        },
      ],
      runs,
    )
    expect(distribution.map((item) => [item.name, item.tokens])).toEqual([
      ['订单系统', 20],
      ['未归属项目', 5],
    ])
    expect(distribution.reduce((sum, item) => sum + item.tokens, 0)).toBe(25)
  })
})
