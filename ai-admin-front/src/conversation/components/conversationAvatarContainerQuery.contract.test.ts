import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * DOM 单测无法真实计算 container query 生效结果。
 * 这里锁定共享组件中的窄容器契约源码，真实 display 断言留给浏览器验收。
 */
describe('Conversation avatar container-query contract', () => {
  it('uses named conversation container query to hide both avatars at <=480px', () => {
    const viewCss = readFileSync(
      resolve(__dirname, './ConversationView.vue'),
      'utf8',
    )
    const itemCss = readFileSync(
      resolve(__dirname, './ConversationMessageItem.vue'),
      'utf8',
    )

    expect(viewCss).toMatch(/container-type:\s*inline-size/)
    expect(viewCss).toMatch(/container-name:\s*reachai-conversation/)

    expect(itemCss).toMatch(
      /@container\s+reachai-conversation\s*\(\s*max-width:\s*480px\s*\)/,
    )
    expect(itemCss).toMatch(/\.reachai-message__user-avatar[\s\S]*display:\s*none/)
    expect(itemCss).toMatch(/\.reachai-message__prism-avatar[\s\S]*display:\s*none/)
    expect(itemCss).toMatch(
      /\.reachai-message__assistant-row\s*\{[\s\S]*grid-template-columns:\s*minmax\(0,\s*1fr\)/,
    )
    expect(itemCss).not.toMatch(
      /@container\s*\(\s*max-width:\s*480px\s*\)\s*\{[\s\S]*grid-template-columns:\s*var\(--reachai-chat-avatar-size/,
    )
  })
})
