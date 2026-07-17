import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConversationView from './ConversationView.vue'
import { createEmptySnapshot } from '../core/conversationTypes'

describe('ConversationView theme / surface', () => {
  it('applies atmosphere + density classes and inherits primary via CSS variable', () => {
    const wrapper = mount(ConversationView, {
      props: {
        snapshot: createEmptySnapshot(),
        density: 'compact',
        atmosphere: true,
        surface: 'embed',
      },
      attrs: {
        style: '--reachai-chat-primary: #7c3aed;',
      },
    })
    const root = wrapper.find('.reachai-conversation')
    expect(root.classes()).toContain('reachai-conversation--compact')
    expect(root.classes()).toContain('reachai-conversation--atmosphere')
    expect(root.attributes('data-surface')).toBe('embed')
    expect(root.attributes('style') || '').toContain('--reachai-chat-primary')
  })

  it('does not invent nested illegal CSS variable names on root class list', () => {
    const wrapper = mount(ConversationView, {
      props: {
        snapshot: createEmptySnapshot(),
        atmosphere: true,
      },
    })
    const className = wrapper.find('.reachai-conversation').attributes('class') || ''
    expect(className).not.toMatch(/--reachai-chat-primary--/)
    expect(className).toContain('reachai-conversation--atmosphere')
  })

  it('keeps theme.primaryColor override on brand anchor without inventing body fill vars', () => {
    const wrapper = mount(ConversationView, {
      props: {
        snapshot: createEmptySnapshot(),
        atmosphere: true,
      },
      attrs: {
        style: '--reachai-chat-primary: #db2777; --reachai-chat-primary-rgb: 219 39 119;',
      },
    })
    const style = wrapper.find('.reachai-conversation').attributes('style') || ''
    expect(style).toContain('--reachai-chat-primary: #db2777')
    expect(style).not.toMatch(/--reachai-chat-text:\s*#fff/i)
    expect(style).not.toMatch(/filter:\s*invert/)
  })
})
