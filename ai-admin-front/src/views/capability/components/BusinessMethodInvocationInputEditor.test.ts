import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import BusinessMethodInvocationInputEditor from './BusinessMethodInvocationInputEditor.vue'

describe('BusinessMethodInvocationInputEditor', () => {
  it('keeps HTTP root arrays and scalar JSON typed while preserving malformed drafts', async () => {
    const wrapper = mount(BusinessMethodInvocationInputEditor, { props: { parameters: [], modelValue: undefined,
      httpJsonBody: true, jsonOnly: true }, global: { plugins: [ElementPlus] } })
    await wrapper.find('textarea').setValue('[{"directors":[],"enabled":false}]')
    expect(wrapper.emitted('http-body-change')?.slice(-1)[0]?.[0]).toEqual([{ directors: [], enabled: false }])
    await wrapper.find('textarea').setValue('[{"directors":')
    expect(wrapper.find('textarea').element.value).toBe('[{"directors":')
    expect(wrapper.emitted('draft-change')?.slice(-1)[0]?.[0]).toMatchObject({ valid: false })
    expect(wrapper.find('textarea').attributes('aria-invalid')).toBe('true')
    await wrapper.find('textarea').setValue('"plain text"')
    expect(wrapper.emitted('http-body-change')?.slice(-1)[0]?.[0]).toBe('plain text')
    await wrapper.find('textarea').setValue('false')
    expect(wrapper.emitted('http-body-change')?.slice(-1)[0]?.[0]).toBe(false)
    await wrapper.find('textarea').setValue('')
    expect(wrapper.emitted('http-body-change')?.slice(-1)[0]?.[0]).toBeUndefined()
    wrapper.unmount()
  })

  it('masks root-array sensitive fields from nested HTTP schemas until explicitly revealed', async () => {
    const wrapper = mount(BusinessMethodInvocationInputEditor, { props: { parameters: [],
      modelValue: [[{ accessCode: 'ROOT_ARRAY_PRIVATE_SENTINEL' }]], httpJsonBody: true, jsonOnly: true,
      httpBodySchema: { type: 'array', items: { type: 'array', items: { type: 'object', properties: {
        accessCode: { type: 'string', format: 'password' },
      } } } } }, global: { plugins: [ElementPlus] } })
    expect(wrapper.find('textarea').element.value).not.toContain('ROOT_ARRAY_PRIVATE_SENTINEL')
    expect(wrapper.find('textarea').element.disabled).toBe(true)
    await wrapper.findAll('button').find(button => button.text() === '显示敏感值并编辑')!.trigger('click')
    expect(wrapper.find('textarea').element.value).toContain('ROOT_ARRAY_PRIVATE_SENTINEL')
    await wrapper.find('textarea').setValue('[[{"accessCode":')
    await wrapper.findAll('button').find(button => button.text() === '重新隐藏敏感值')!.trigger('click')
    expect(wrapper.find('textarea').element.value).not.toContain('ROOT_ARRAY_PRIVATE_SENTINEL')
    await wrapper.findAll('button').find(button => button.text() === '显示敏感值并编辑')!.trigger('click')
    expect(wrapper.find('textarea').element.value).toBe('[[{"accessCode":')
    wrapper.unmount()
  })

  it('retains the business-method object-only contract outside the HTTP variant', async () => {
    const wrapper = mount(BusinessMethodInvocationInputEditor, { props: { parameters: [], modelValue: {} },
      global: { plugins: [ElementPlus] } })
    await wrapper.find('input[value="json"]').setValue()
    await wrapper.find('textarea').setValue('["unexpected-root-array"]')
    expect(wrapper.emitted('draft-change')?.slice(-1)[0]?.[0]).toMatchObject({ valid: false })
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })

  it('masks sensitive names inside declared HTTP maps in the form preview', () => {
    const wrapper = mount(BusinessMethodInvocationInputEditor, { props: {
      parameters: [{ name: 'labels', type: 'object', required: false, description: '' }],
      modelValue: { labels: { apiKey: 'MAP_PRIVATE_SENTINEL' } }, httpJsonBody: true,
      httpBodySchema: { type: 'object', properties: { labels: { type: 'object', additionalProperties: { type: 'string' } } } },
    }, global: { plugins: [ElementPlus] } })
    expect(wrapper.text()).not.toContain('MAP_PRIVATE_SENTINEL')
    expect(wrapper.findAll('textarea')).toHaveLength(0)
    expect(wrapper.find('button[aria-label="显示并编辑 labels 的敏感值"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('does not turn non-finite JSON numbers into null before HTTP validation', async () => {
    const wrapper = mount(BusinessMethodInvocationInputEditor, { props: {
      parameters: [], modelValue: undefined, httpJsonBody: true, jsonOnly: true,
      jsonValueValidator: value => typeof value === 'number' && !Number.isFinite(value) ? '请输入有限数字。' : '',
    }, global: { plugins: [ElementPlus] } })
    await wrapper.find('textarea').setValue('1e999')
    expect(wrapper.find('textarea').element.value).toBe('1e999')
    expect(wrapper.emitted('http-body-change')).toBeUndefined()
    expect(wrapper.emitted('draft-change')?.slice(-1)[0]?.[0]).toMatchObject({ valid: false })
    wrapper.unmount()
  })
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
