import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ConversationComposer from './ConversationComposer.vue'

describe('ConversationComposer keyboard behavior', () => {
  it('owns validation and does not submit while an IME composition is active', async () => {
    const wrapper = mount(ConversationComposer)
    const form = wrapper.get('form')
    const input = wrapper.get('textarea')

    expect(form.attributes()).toHaveProperty('novalidate')
    expect(input.classes()).toContain('resize-none')

    await input.setValue('正在输入')
    await input.trigger('keydown', {
      key: 'Enter',
      ctrlKey: true,
      isComposing: true,
    })
    expect(wrapper.emitted('send')).toBeUndefined()

    await input.trigger('keydown', {
      key: 'Enter',
      ctrlKey: true,
      isComposing: false,
    })
    expect(wrapper.emitted('send')?.[0]).toEqual(['正在输入'])
  })
})
