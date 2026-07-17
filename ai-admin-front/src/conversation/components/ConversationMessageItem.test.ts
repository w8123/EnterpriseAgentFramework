import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConversationMessageItem from './ConversationMessageItem.vue'
import type { ConversationMessage } from '../core/conversationTypes'
import { nowIso } from '../core/conversationTypes'

function userMessage(overrides: Partial<ConversationMessage> = {}): ConversationMessage {
  return {
    id: 'u1',
    role: 'user',
    status: 'completed',
    createdAt: nowIso(),
    blocks: [{ id: 'b1', type: 'text', text: '你好，帮我查一下班组档案' }],
    ...overrides,
  }
}

function assistantMessage(overrides: Partial<ConversationMessage> = {}): ConversationMessage {
  return {
    id: 'a1',
    role: 'assistant',
    status: 'completed',
    createdAt: nowIso(),
    blocks: [{ id: 'b2', type: 'text', text: '已为你整理结果。' }],
    ...overrides,
  }
}

describe('ConversationMessageItem Prism shell', () => {
  it('renders user visual classes, right-aligned row, avatar, and no 「你」 label', () => {
    const wrapper = mount(ConversationMessageItem, {
      props: { message: userMessage() },
    })
    expect(wrapper.find('.reachai-message--user').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__user-row').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__user-bubble').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__user-avatar').exists()).toBe(true)
    // 不渲染独立「你」角色标签（正文可含「你」字）
    expect(wrapper.find('.message-role').exists()).toBe(false)
    expect(wrapper.text()).not.toMatch(/(^|\s)你(\s|$)/)
    expect(wrapper.text()).toContain('你好，帮我查一下班组档案')
  })

  it('renders assistant prism card shell, avatar, and completed status without empty footer', () => {
    const wrapper = mount(ConversationMessageItem, {
      props: { message: assistantMessage() },
    })
    expect(wrapper.find('.reachai-message--assistant').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__card-shell').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__card-shell--response').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__prism-avatar').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__card--completed').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__meta').exists()).toBe(false)
  })

  it('hides both avatars when showAvatars=false and keeps default avatars on', () => {
    const withAvatars = mount(ConversationMessageItem, {
      props: { message: userMessage() },
    })
    const assistant = mount(ConversationMessageItem, {
      props: { message: assistantMessage() },
    })
    expect(withAvatars.find('.reachai-message__user-avatar').exists()).toBe(true)
    expect(assistant.find('.reachai-message__prism-avatar').exists()).toBe(true)

    const noUser = mount(ConversationMessageItem, {
      props: { message: userMessage(), showAvatars: false },
    })
    const noAssistant = mount(ConversationMessageItem, {
      props: { message: assistantMessage(), showAvatars: false },
    })
    expect(noUser.find('.reachai-message__user-avatar').exists()).toBe(false)
    expect(noAssistant.find('.reachai-message__prism-avatar').exists()).toBe(false)
    expect(noUser.text()).not.toMatch(/(^|\s)你(\s|$)/)
  })

  it('keeps thinking visual while pending/streaming even after delta text arrives', async () => {
    const wrapper = mount(ConversationMessageItem, {
      props: {
        message: assistantMessage({
          status: 'streaming',
          blocks: [{ id: 'b2', type: 'text', text: '部分内容', status: 'streaming' }],
        }),
        statusHint: '正在处理',
      },
    })
    expect(wrapper.find('.reachai-message--thinking').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__card-shell--thinking').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__thinking-title').text()).toContain('思考中')
    expect(wrapper.find('.reachai-message__thinking-dots').exists()).toBe(true)
    expect(wrapper.text()).toContain('部分内容')
    expect(wrapper.text()).toContain('正在处理')

    await wrapper.setProps({
      message: assistantMessage({ status: 'completed' }),
      statusHint: '',
    })
    expect(wrapper.find('.reachai-message--thinking').exists()).toBe(false)
    expect(wrapper.find('.reachai-message__card-shell--response').exists()).toBe(true)
    expect(wrapper.find('.reachai-message__thinking-title').exists()).toBe(false)
  })

  it('stops thinking visuals for failed and cancelled states', () => {
    for (const status of ['failed', 'cancelled'] as const) {
      const wrapper = mount(ConversationMessageItem, {
        props: { message: assistantMessage({ status }) },
      })
      expect(wrapper.find('.reachai-message__card-shell--thinking').exists()).toBe(false)
      expect(wrapper.find(`.reachai-message__card-shell--${status}`).exists()).toBe(true)
      expect(wrapper.find('.reachai-message__status-capsule').exists()).toBe(true)
    }
  })

  it('nests interaction inside assistant card and preserves submit/cancel contract', async () => {
    const wrapper = mount(ConversationMessageItem, {
      props: {
        message: assistantMessage({
          blocks: [{
            id: 'ix1',
            type: 'interaction',
            state: 'waiting',
            request: {
              schemaVersion: '1.0',
              interactionId: 'ix-1',
              component: 'confirm',
              title: '确认执行',
              message: '是否继续？',
            },
          }],
        }),
      },
    })
    expect(wrapper.find('.reachai-message__card .reachai-interaction').exists()).toBe(true)
    const confirmBtn = wrapper.find('.reachai-interaction__actions button.is-primary')
    expect(confirmBtn.exists()).toBe(true)
    await confirmBtn.trigger('click')
    expect(wrapper.emitted('interaction-submit')?.[0]).toEqual(['ix-1', 'confirm', { confirm: true }])
  })

  it('does not render weak assistant title in embed surface', () => {
    const wrapper = mount(ConversationMessageItem, {
      props: {
        message: assistantMessage(),
        assistantLabel: '班组建设服务 Page Copilot',
        surface: 'embed',
      },
    })
    expect(wrapper.find('.reachai-message__weak-label').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Page Copilot')
  })

  it('renders meta footer only when slot provides content', () => {
    const empty = mount(ConversationMessageItem, {
      props: { message: assistantMessage() },
      slots: { meta: '' },
    })
    // slot exists but empty — :has(> *) hides via CSS; DOM footer may exist
    const withMeta = mount(ConversationMessageItem, {
      props: { message: assistantMessage() },
      slots: {
        meta: '<span class="elapsed-chip">1.2s</span>',
      },
    })
    expect(withMeta.find('.reachai-message__meta .elapsed-chip').exists()).toBe(true)
    expect(empty.find('.elapsed-chip').exists()).toBe(false)
  })

  it('M: renders expandable thinking presentation only while thinking', async () => {
    const wrapper = mount(ConversationMessageItem, {
      props: {
        message: assistantMessage({
          status: 'pending',
          blocks: [],
        }),
        statusHint: '正在连接运行时…',
        thinkingPresentation: {
          label: '本轮执行 2 步',
          count: 2,
          steps: [
            { id: 's1', title: '识别 Agent 范围', state: 'complete' },
            { id: 's2', title: '规划执行路径', detail: '选择 Workflow', state: 'active' },
          ],
        },
      },
    })
    expect(wrapper.find('.reachai-message__thinking-title').text()).toContain('思考中')
    expect(wrapper.text()).toContain('正在连接运行时')
    expect(wrapper.text()).not.toContain('正在理解意图')
    expect(wrapper.find('.reachai-message__thinking-toggle').text()).toContain('本轮执行 2 步')
    expect(wrapper.find('.reachai-message__thinking-steps').exists()).toBe(false)

    await wrapper.find('.reachai-message__thinking-toggle').trigger('click')
    expect(wrapper.find('.reachai-message__thinking-steps').exists()).toBe(true)
    expect(wrapper.text()).toContain('识别 Agent 范围')
    expect(wrapper.text()).toContain('规划执行路径')
    expect(wrapper.text()).toContain('选择 Workflow')

    await wrapper.setProps({
      message: assistantMessage({ status: 'completed' }),
      statusHint: '',
      thinkingPresentation: null,
    })
    expect(wrapper.find('.reachai-message__thinking-toggle').exists()).toBe(false)
    expect(wrapper.find('.reachai-message__thinking-title').exists()).toBe(false)
  })

  it('M: embed surface shows generic processing without supervisor/node steps', () => {
    const wrapper = mount(ConversationMessageItem, {
      props: {
        message: assistantMessage({ status: 'pending', blocks: [] }),
        statusHint: '正在处理',
        thinkingPresentation: null,
        surface: 'embed',
      },
    })
    expect(wrapper.find('.reachai-message__thinking-title').text()).toContain('思考中')
    expect(wrapper.text()).toContain('正在处理')
    expect(wrapper.find('.reachai-message__thinking-toggle').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Supervisor')
    expect(wrapper.text()).not.toContain('节点执行')
    expect(wrapper.text()).not.toContain('本轮推理')
  })
})
