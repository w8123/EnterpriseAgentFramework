import { describe, expect, it } from 'vitest'
import {
  frontendSnippet,
  nextAllowedAgentIds,
  parseOrigins,
  policiesByProject,
  toProjectEmbedPolicy,
} from './agentEmbed'

const agent = { id: '8e747bd7c1f1', keySlug: 'erp-assistant' }

describe('agent embed helpers', () => {
  it('reads the project policy and recognises the agent by id or key slug', () => {
    const policy = toProjectEmbedPolicy({
      id: 1, projectCode: 'yudao-erp', appKey: 'k', status: 'ACTIVE',
      allowedOriginsJson: '["https://yudao.w8123.com"]', allowedAgentIdsJson: '["erp-assistant"]', tokenTtlSeconds: 900,
    }, agent)
    expect(policy.origins).toEqual(['https://yudao.w8123.com'])
    expect(policy.agentAllowed).toBe(true)
    expect(policy.allAgents).toBe(false)
  })

  it('keeps one credential per project, preferring the active one', () => {
    const policies = policiesByProject([
      { id: 1, projectCode: 'a', appKey: 'k1', status: 'REVOKED' },
      { id: 2, projectCode: 'a', appKey: 'k2', status: 'ACTIVE' },
      { id: 3, projectCode: 'b', appKey: 'k3', status: 'ACTIVE', allowedAgentIdsJson: '["other"]' },
    ], agent)
    expect(policies.map((item) => item.id)).toEqual([2, 3])
  })

  it('normalises origins and reports invalid lines', () => {
    expect(parseOrigins('https://yudao.w8123.com/erp/stock\nhttps://*.Example.com, ftp://x\nnot a url')).toEqual({
      origins: ['https://yudao.w8123.com', 'https://*.example.com'],
      invalid: ['ftp://x', 'not', 'a', 'url'],
    })
  })

  it('refuses switches that would silently widen or hide access', () => {
    const all = toProjectEmbedPolicy({ id: 1, projectCode: 'a', appKey: 'k', status: 'ACTIVE' }, agent)
    expect(nextAllowedAgentIds(all, agent, true)).toEqual({ ok: true, agentIds: [] })
    expect(nextAllowedAgentIds(all, agent, false).ok).toBe(false)

    const onlyThis = toProjectEmbedPolicy({ id: 1, projectCode: 'a', appKey: 'k', status: 'ACTIVE', allowedAgentIdsJson: '["8e747bd7c1f1"]' }, agent)
    expect(nextAllowedAgentIds(onlyThis, agent, false).ok).toBe(false)

    const others = toProjectEmbedPolicy({ id: 1, projectCode: 'a', appKey: 'k', status: 'ACTIVE', allowedAgentIdsJson: '["other"]' }, agent)
    expect(nextAllowedAgentIds(others, agent, true)).toEqual({ ok: true, agentIds: ['other', '8e747bd7c1f1'] })

    const both = toProjectEmbedPolicy({ id: 1, projectCode: 'a', appKey: 'k', status: 'ACTIVE', allowedAgentIdsJson: '["other","erp-assistant"]' }, agent)
    expect(nextAllowedAgentIds(both, agent, false)).toEqual({ ok: true, agentIds: ['other'] })
  })

  it('builds a script snippet served from the ReachAI site', () => {
    const snippet = frontendSnippet({ reachAiBase: 'https://reachai.w8123.com/', agentId: 'erp-assistant' })
    expect(snippet).toContain('https://reachai.w8123.com/sdk/embed-chat/reachai-chat-embed.umd.js')
    expect(snippet).toContain("agentId: 'erp-assistant'")
    expect(snippet).not.toContain('appSecret')
  })
})
