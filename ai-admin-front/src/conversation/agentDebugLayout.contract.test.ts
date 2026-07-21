import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('AgentDebug conversation pane width contract', () => {
  it('keeps MIN_CONVERSATION_WIDTH in 320–360 so ~380px panes are reachable', () => {
    const src = readFileSync(
      resolve(__dirname, '../views/agent/AgentDebug.vue'),
      'utf8',
    )
    const match = src.match(/const\s+MIN_CONVERSATION_WIDTH\s*=\s*(\d+)/)
    expect(match).toBeTruthy()
    const value = Number(match?.[1])
    expect(value).toBeGreaterThanOrEqual(320)
    expect(value).toBeLessThanOrEqual(360)
    expect(src).toMatch(/function\s+constrainInsightWidth/)
    expect(src).toMatch(/aria-valuemin/)
    expect(src).toMatch(/ArrowLeft/)
    expect(src).toMatch(/ArrowRight/)
    const maxInsight = Number(src.match(/const\s+MAX_INSIGHT_WIDTH\s*=\s*(\d+)/)?.[1])
    // 常见桌面 debug-body 下要把对话区拖到 ~380，insight 上限需明显高于 640
    expect(maxInsight).toBeGreaterThanOrEqual(800)
  })

  it('no longer keeps dead legacy message selectors in AgentDebug styles', () => {
    const src = readFileSync(
      resolve(__dirname, '../views/agent/AgentDebug.vue'),
      'utf8',
    )
    expect(src).not.toMatch(/\.agent-message\b/)
    expect(src).not.toMatch(/\.agent-avatar\b/)
    expect(src).not.toMatch(/\.user-avatar\b/)
    expect(src).not.toMatch(/\.user-message\b/)
    expect(src).not.toMatch(/\.agent-card-shell\b/)
    expect(src).not.toMatch(/\.thinking-card-shell\b/)
    expect(src).not.toMatch(/\.response-card-shell\b/)
  })

  it('does not re-own conversation glass material or per-brand spectrum overrides', () => {
    const src = readFileSync(
      resolve(__dirname, '../views/agent/AgentDebug.vue'),
      'utf8',
    )
    expect(src).not.toMatch(/data-brand='metro-green'/)
    expect(src).not.toMatch(/data-brand='solar-gold'/)
    expect(src).not.toMatch(/--reachai-chat-glass-composer/)
    expect(src).not.toMatch(/backdrop-filter:\s*var\(--reachai-chat-glass-blur-composer\)/)
    expect(src).toMatch(/class="chat-input"/)
    expect(src).toMatch(/ConversationView/)
  })
})
