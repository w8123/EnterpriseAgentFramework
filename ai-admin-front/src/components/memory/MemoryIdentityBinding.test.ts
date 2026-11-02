import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MemoryIdentityBinding from './MemoryIdentityBinding.vue'

const stubs = {
  LiquidGlassButton: { template: '<button><slot /></button>' },
  LiquidGlassInput: { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">' },
}

describe('MemoryIdentityBinding', () => {
  it('generates a short-lived code and confirms a code from the other system', async () => {
    const api = { issue: vi.fn().mockResolvedValue({ code: 'one-time-code', expiresAt: new Date(Date.now() + 60000).toISOString() }), confirm: vi.fn().mockResolvedValue(1) }
    const wrapper = mount(MemoryIdentityBinding, { props: { api }, global: { stubs } })
    await wrapper.findAll('button')[0].trigger('click'); await flushPromises()
    expect(wrapper.find('textarea').element.value).toBe('one-time-code')
    await wrapper.find('input').setValue('  another-code  ')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(api.confirm).toHaveBeenCalledWith('another-code')
    expect(wrapper.emitted('linked')).toHaveLength(1)
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('input').element.value).toBe('')
    wrapper.unmount()
  })

  it('does not show an expired code and preserves failed confirmation for correction', async () => {
    const api = { issue: vi.fn().mockResolvedValue({ code: 'expired', expiresAt: new Date(Date.now() - 1000).toISOString() }), confirm: vi.fn().mockRejectedValue(new Error('关联码已失效')) }
    const wrapper = mount(MemoryIdentityBinding, { props: { api }, global: { stubs } })
    await wrapper.findAll('button')[0].trigger('click'); await flushPromises()
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('[role="alert"]').text()).toContain('关联码已过期')
    await wrapper.find('input').setValue('invalid')
    await wrapper.find('form').trigger('submit'); await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('关联码已失效')
    expect(wrapper.find('input').element.value).toBe('invalid')
    wrapper.unmount()
  })
})
