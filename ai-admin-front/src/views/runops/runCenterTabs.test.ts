import { describe, expect, it } from 'vitest'
import { activeRunCenterTab, firstRunCenterPath, visibleRunCenterTabs } from './runCenterTabs'

describe('run center tabs', () => {
  it('shows each tab only with its own permission', () => {
    expect(visibleRunCenterTabs(['runops:read']).map(tab => tab.key)).toEqual(['conversations', 'runs'])
    expect(visibleRunCenterTabs(['automation:read', 'agent:evaluate']).map(tab => tab.key)).toEqual(['automations', 'evals'])
    expect(visibleRunCenterTabs(['*']).map(tab => tab.key)).toEqual(['conversations', 'runs', 'automations', 'evals'])
  })

  it('lands on the first tab the user can open', () => {
    expect(firstRunCenterPath(['runops:read', 'agent:evaluate'])).toBe('/runops/conversations')
    expect(firstRunCenterPath(['agent:evaluate'])).toBe('/runops/evals')
    expect(firstRunCenterPath([])).toBe('/runops/conversations')
  })

  it('keeps run details under the run records tab', () => {
    expect(activeRunCenterTab('/runops/trace-1')).toBe('runs')
    expect(activeRunCenterTab('/runops/automations')).toBe('automations')
    expect(activeRunCenterTab('/runops/conversations/s-1')).toBe('conversations')
    expect(activeRunCenterTab('/runops/evals/agent-1')).toBe('evals')
  })
})
