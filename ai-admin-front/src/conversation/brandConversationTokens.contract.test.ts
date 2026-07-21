import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const BRANDS = [
  'tech-purple',
  'metro-green',
  'aurora-cyan',
  'nebula-violet',
  'coral-rose',
  'solar-gold',
  'deep-ocean',
] as const

describe('Admin brand → Conversation token inheritance', () => {
  it('keeps seven data-brand primaries and conversation inherits --brand-primary', () => {
    const brand = readFileSync(
      resolve(__dirname, '../styles/tokens/_brand.scss'),
      'utf8',
    )
    const tokens = readFileSync(
      resolve(__dirname, './styles/conversation-tokens.css'),
      'utf8',
    )

    for (const name of BRANDS) {
      expect(brand).toContain(`[data-brand='${name}']`)
    }
    expect(brand).toMatch(/--brand-primary:\s*#6366f1/)
    expect(brand).toMatch(/--brand-primary:\s*#0b7a59/)
    expect(brand).toMatch(/--brand-primary:\s*#b45309/)

    expect(tokens).toMatch(
      /\.reachai-conversation\s*\{[\s\S]*?--reachai-chat-primary:\s*var\(--brand-primary/,
    )
    expect(tokens).toMatch(/--reachai-chat-primary-rgb:\s*var\(--brand-primary-rgb/)
  })
})
