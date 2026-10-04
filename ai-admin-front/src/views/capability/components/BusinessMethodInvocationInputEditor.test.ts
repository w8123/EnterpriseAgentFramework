import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import BusinessMethodInvocationInputEditor from './BusinessMethodInvocationInputEditor.vue'

describe('BusinessMethodInvocationInputEditor', () => {
  it('keeps numbers, booleans, objects and arrays typed in full JSON mode without adding an arguments wrapper', async () => {
    const wrapper = mount(BusinessMethodInvocationInputEditor, {
      props: {
        parameters: [
          { name: 'count', type: 'integer', description: '', required: true },
          { name: 'enabled', type: 'boolean', description: '', required: true },
          { name: 'filter', type: 'object', description: '', required: false },
          { name: 'items', type: 'array', description: '', required: false },
        ],
        modelValue: {},
      },
      global: { plugins: [ElementPlus] },
    })
    const radios = wrapper.findAll('input[type="radio"]')
    await radios[1].setValue()
    await wrapper.find('textarea').setValue('{"count":0,"enabled":false,"filter":{"page":1},"items":[{"id":2}]}')
    await flushPromises()
    const values = wrapper.emitted('update:modelValue') || []
    const latestValue = values[values.length - 1]?.[0]
    expect(latestValue).toEqual({ count: 0, enabled: false, filter: { page: 1 }, items: [{ id: 2 }] })
    expect(latestValue).not.toHaveProperty('arguments')
  })

  it('masks direct, nested and array sensitive values in both default views until explicitly revealed', async () => {
    const wrapper = mount(BusinessMethodInvocationInputEditor, {
      props: {
        parameters: [
          { name: 'approved', type: 'boolean', description: '', required: true, metadata: { sensitive: true } },
          { name: 'request', type: 'object', description: '', required: true, children: [
            { name: 'token', type: 'string', description: '', required: true, metadata: { sensitive: true } },
          ] },
          { name: 'records', type: 'array', description: '', required: true, children: [
            { name: 'pin', type: 'string', description: '', required: true, metadata: { sensitive: true } },
          ] },
        ],
        modelValue: {
          approved: false,
          request: { token: 'NESTED_SECRET_SENTINEL' },
          records: [{ pin: 'ARRAY_SECRET_SENTINEL' }],
        },
      },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).not.toContain('NESTED_SECRET_SENTINEL')
    expect(wrapper.text()).not.toContain('ARRAY_SECRET_SENTINEL')
    expect(wrapper.findAll('textarea')).toHaveLength(0)

    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await flushPromises()
    expect(wrapper.find('textarea').element.value).toContain('••••••')
    expect(wrapper.find('textarea').element.value).not.toContain('NESTED_SECRET_SENTINEL')
    expect(wrapper.find('textarea').element.value).not.toContain('ARRAY_SECRET_SENTINEL')

    const reveal = wrapper.findAll('button').find((item) => item.text().trim() === '显示敏感值并编辑')
    if (!reveal) throw new Error('sensitive JSON reveal control is missing')
    await reveal.trigger('click')
    await flushPromises()
    expect(wrapper.find('textarea').element.value).toContain('NESTED_SECRET_SENTINEL')
    expect(wrapper.find('textarea').element.value).toContain('ARRAY_SECRET_SENTINEL')

    const hide = wrapper.findAll('button').find((item) => item.text().trim() === '重新隐藏敏感值')
    if (!hide) throw new Error('sensitive JSON hide control is missing')
    await hide.trigger('click')
    await flushPromises()
    expect(wrapper.find('textarea').element.value).toContain('••••••')
    expect(wrapper.find('textarea').element.value).not.toContain('NESTED_SECRET_SENTINEL')
    expect(wrapper.find('textarea').element.value).not.toContain('ARRAY_SECRET_SENTINEL')
  })
})
