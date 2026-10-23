import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AgentRecentActivity from './AgentRecentActivity.vue'

vi.mock('@/api/runops', () => ({
  getRecentRunOps: vi.fn().mockResolvedValue({ data: [{
    traceId: 't1', runType: 'AGENT', entryType: 'DEBUG', status: 'COMPLETED', agentConfigVersion: 12,
    startedAt: '2026-10-19T10:05:00', latencyMs: 2300,
    inputSummary: '{"inputType":"message","hasMessage":true,"messageLength":29}',
  }] }),
}))
vi.mock('@/api/agentEval', () => ({ listEvalOpsExperiments: vi.fn().mockResolvedValue({ data: [] }) }))

describe('AgentRecentActivity', () => {
  it('titles runs by entry and time instead of the raw input metadata', async () => {
    const wrapper = mount(AgentRecentActivity, {
      global: { plugins: [ElementPlus] },
      props: { agentId: 'a1', canReadRuns: true, canEvaluate: true },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('调试 · 10-19 10:05')
    expect(wrapper.text()).toContain('配置 v12 · 2.3s')
    expect(wrapper.text()).not.toContain('inputType')
    await wrapper.find('.recent-run-item').trigger('click')
    expect(wrapper.emitted('open-run')?.[0]).toEqual(['t1'])
  })
})
