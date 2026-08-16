import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { compileStyle, parse } from '@vue/compiler-sfc'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createEmptySnapshot } from '../core/conversationTypes'
import ConversationMessageList from './ConversationMessageList.vue'
import ConversationView from './ConversationView.vue'

/**
 * Angular 12/Critters 无法解析 @container。共享会话组件改用
 * ResizeObserver 得到真实宿主宽度，并把窄容器状态传给消息布局。
 */
describe('Conversation avatar narrow-container contract', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('hides avatars at <=480px and restores them above the boundary', async () => {
    const resizeObserverState: { callback?: ResizeObserverCallback } = {}
    class TestResizeObserver {
      constructor(callback: ResizeObserverCallback) {
        resizeObserverState.callback = callback
      }

      observe() {}

      disconnect() {}
    }
    vi.stubGlobal('ResizeObserver', TestResizeObserver)

    const wrapper = mount(ConversationView, {
      props: {
        snapshot: createEmptySnapshot(),
        showAvatars: true,
      },
    })
    const messageList = wrapper.findComponent(ConversationMessageList)
    expect(messageList.props('showAvatars')).toBe(true)

    resizeObserverState.callback?.(
      [{ contentRect: { width: 480 } } as ResizeObserverEntry],
      {} as ResizeObserver,
    )
    await nextTick()
    expect(wrapper.find('.reachai-conversation').classes()).toContain(
      'reachai-conversation--narrow',
    )
    expect(messageList.props('showAvatars')).toBe(false)

    resizeObserverState.callback?.(
      [{ contentRect: { width: 481 } } as ResizeObserverEntry],
      {} as ResizeObserver,
    )
    await nextTick()
    expect(wrapper.find('.reachai-conversation').classes()).not.toContain(
      'reachai-conversation--narrow',
    )
    expect(messageList.props('showAvatars')).toBe(true)
    wrapper.unmount()
  })

  it('keeps the package free of unsupported container-query syntax', () => {
    const viewCss = readFileSync(
      resolve(__dirname, './ConversationView.vue'),
      'utf8',
    )
    const itemCss = readFileSync(
      resolve(__dirname, './ConversationMessageItem.vue'),
      'utf8',
    )

    expect(viewCss).toMatch(/AVATAR_HIDE_MAX_WIDTH\s*=\s*480/)
    expect(viewCss).toMatch(/new ResizeObserver/)
    expect(viewCss).not.toMatch(/^\s*@container\b/m)
    expect(itemCss).not.toMatch(/^\s*@container\b/m)
    expect(itemCss).toMatch(/reachai-conversation--narrow/)
    expect(itemCss).toMatch(
      /reachai-message__assistant-row--without-avatar[\s\S]*grid-template-columns:\s*minmax\(0,\s*1fr\)/,
    )
    expect(itemCss).toMatch(
      /\.reachai-message__assistant-row\s*\{[\s\S]*grid-template-columns:\s*minmax\(0,\s*1fr\)/,
    )
  })

  it('keeps ancestor selectors intact after Vue scoped CSS compilation', () => {
    const filename = resolve(__dirname, './ConversationMessageItem.vue')
    const source = readFileSync(filename, 'utf8')
    const parsed = parse(source, { filename })
    expect(parsed.errors).toEqual([])

    const scopedStyle = parsed.descriptor.styles.find((style) => style.scoped)
    expect(scopedStyle).toBeTruthy()
    const compiled = compileStyle({
      filename,
      id: 'data-v-reachai-contract',
      source: scopedStyle!.content,
      scoped: true,
    })
    expect(compiled.errors).toEqual([])

    for (const selector of [
      '.reachai-conversation--compact .reachai-message__user-avatar',
      '.reachai-conversation--narrow .reachai-message__prism-avatar',
      '.reachai-conversation--compact .reachai-message__assistant-row',
      '.reachai-conversation--narrow .reachai-message__user-row',
      '.reachai-conversation--compact .reachai-message__user-bubble',
    ]) {
      expect(compiled.code).toContain(selector)
    }
    expect(compiled.code).not.toMatch(
      /\.reachai-conversation--compact\s*,\s*\.reachai-conversation--narrow\s*\{[^}]*\b(?:display|grid-template-columns|gap|max-width)\s*:/,
    )
  })
})
