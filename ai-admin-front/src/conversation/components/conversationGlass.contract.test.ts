import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

describe('Conversation glass token contract', () => {
  const tokens = readFileSync(
    resolve(__dirname, '../styles/conversation-tokens.css'),
    'utf8',
  )
  const card = readFileSync(
    resolve(__dirname, './ConversationMessageItem.vue'),
    'utf8',
  )
  const composer = readFileSync(
    resolve(__dirname, './ConversationComposer.vue'),
    'utf8',
  )
  const interaction = readFileSync(
    resolve(__dirname, './UnifiedInteractionRenderer.vue'),
    'utf8',
  )
  const block = readFileSync(
    resolve(__dirname, './ConversationBlockRenderer.vue'),
    'utf8',
  )

  it('defines shared glass semantic tokens once', () => {
    for (const name of [
      '--reachai-chat-glass-card',
      '--reachai-chat-glass-card-reading-layer',
      '--reachai-chat-glass-composer',
      '--reachai-chat-glass-control',
      '--reachai-chat-glass-border',
      '--reachai-chat-glass-highlight',
      '--reachai-chat-glass-blur',
      '--reachai-chat-glass-saturation',
      '--reachai-chat-glass-shadow',
      '--reachai-chat-glass-interaction',
      '--reachai-chat-card-glow-opacity',
      '--reachai-chat-text',
      '--reachai-chat-text-secondary',
      '--reachai-chat-text-muted',
    ]) {
      expect(tokens).toContain(name)
    }
    expect(tokens).toMatch(/@supports not/)
    expect(tokens).toMatch(/html\.dark|--reachai-chat-glass-card:\s*rgb\(15 23 42/)
  })

  it('keeps light-mode backdrop saturation below 1 for card and composer', () => {
    const cardBlur = tokens.match(/--reachai-chat-glass-blur:\s*([^;]+);/)?.[1] || ''
    const composerBlur = tokens.match(/--reachai-chat-glass-blur-composer:\s*([^;]+);/)?.[1] || ''
    expect(cardBlur).toMatch(/saturate\(0\.\d+\)/)
    expect(composerBlur).toMatch(/saturate\(0\.\d+\)/)
    expect(cardBlur).not.toMatch(/saturate\(1(\.\d+)?\)/)
    expect(composerBlur).not.toMatch(/saturate\(1(\.\d+)?\)/)
    expect(tokens).toMatch(/--reachai-chat-glass-saturation:\s*0\.\d+/)
  })

  it('defines a neutral reading layer and stable body text color', () => {
    expect(tokens).toMatch(
      /--reachai-chat-glass-card-reading-layer:\s*linear-gradient\([\s\S]*?rgb\(255 255 255/,
    )
    expect(tokens).toMatch(/--reachai-chat-text:\s*#1e293b/)
    expect(tokens).not.toMatch(/--reachai-chat-text:\s*#fff(?:fff)?\b/i)
    expect(tokens).not.toMatch(/--reachai-chat-text:\s*#000\b/)
  })

  it('wires card and composer to glass tokens without whole-card opacity', () => {
    expect(card).toMatch(/--reachai-chat-glass-card-reading-layer/)
    expect(card).toMatch(/--reachai-chat-glass-blur/)
    expect(card).toMatch(/-webkit-backdrop-filter:\s*var\(--reachai-chat-glass-blur\)/)
    expect(card).not.toMatch(/\.reachai-message__card[^{]*\{[^}]*opacity:\s*0\./)
    expect(card).not.toMatch(/\.reachai-message--cancelled[\s\S]{0,80}opacity:\s*0\./)
    expect(card).toMatch(/font-weight:\s*500/)
    expect(card).toMatch(/line-height:\s*1\.72/)
    expect(composer).toMatch(/--reachai-chat-glass-composer/)
    expect(composer).toMatch(/--reachai-chat-glass-blur-composer/)
    expect(composer).toMatch(/:focus-within/)
    expect(composer).toMatch(/::placeholder/)
    expect(interaction).toMatch(/--reachai-chat-glass-interaction/)
    expect(interaction).toMatch(/--reachai-chat-glass-control/)
    expect(interaction).toMatch(/backdrop-filter:\s*none/)
    expect(block).toMatch(/font-weight:\s*500/)
    expect(block).toMatch(/line-height:\s*1\.72/)
  })

  it('does not amplify nested interaction backdrop saturation', () => {
    expect(interaction).not.toMatch(/saturate\(1\./)
    expect(card).not.toMatch(/saturate\(1\./)
  })

  it('derives atmosphere washes from spectrum tokens instead of fixed indigo RGB', () => {
    expect(tokens).toMatch(/--reachai-chat-atmosphere-glow-primary:\s*color-mix/)
    expect(tokens).toMatch(/--reachai-chat-atmosphere-glow-secondary:\s*color-mix/)
    expect(tokens).toMatch(/--reachai-chat-atmosphere-theme-wash:\s*linear-gradient/)
    expect(tokens).not.toMatch(/--reachai-chat-atmosphere-glow-primary:\s*rgb\(100 220 255/)
  })

  it('inherits shell/preset primary when admin brand is absent (SDK nested path)', () => {
    expect(tokens).toMatch(
      /\.eaf-chat \.reachai-conversation[\s\S]*?--reachai-chat-primary:\s*inherit/,
    )
    expect(tokens).toMatch(
      /\.reachai-chat-root \.reachai-conversation[\s\S]*?--reachai-chat-primary-rgb:\s*inherit/,
    )
    expect(tokens).toMatch(/--reachai-chat-primary-soft:\s*color-mix\([\s\S]*?#ffffff\)/)
    expect(tokens).not.toMatch(/--reachai-chat-primary-soft:[^;]*#8b5cf6/)
  })

  it('shares composer frame focus ring for custom slot classes in ConversationView', () => {
    const view = readFileSync(
      resolve(__dirname, './ConversationView.vue'),
      'utf8',
    )
    expect(view).toMatch(/:deep\(\.chat-input:focus-within\)/)
    expect(view).toMatch(/:deep\(\.debug-chat-composer:focus-within\)/)
    expect(view).toMatch(/--reachai-chat-glass-focus-ring/)
    expect(view).toMatch(/prefers-reduced-motion/)
  })
})
