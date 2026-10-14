import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElDialog, ElInput } from 'element-plus'
import { afterEach, describe, expect, it } from 'vitest'
import AppDialog from '@/components/common/AppDialog.vue'
import SourceSemanticDialogs from './SourceSemanticDialogs.vue'

const wrappers: ReturnType<typeof mount>[] = []
afterEach(() => { wrappers.splice(0).forEach(view => view.unmount()); document.body.innerHTML = '' })
function setup(extra: Record<string, unknown> = {}) {
  const view = mount(SourceSemanticDialogs, { attachTo: document.body, props: {
    docEditVisible: true, docEditContent: '正在保存的说明', docEditSaving: true,
    ...extra,
  }, global: { plugins: [ElementPlus] } })
  wrappers.push(view); return view
}

describe('semantic editor save lock', () => {
  it('keeps the draft when write permission is revoked and still allows cancellation', async () => {
    const view = setup({ docEditSaving: false }); await flushPromises()
    const dialog = view.findAllComponents(AppDialog)[0], owner = dialog.getComponent(ElDialog)
    const input = dialog.getComponent(ElInput)
    await view.setProps({ canWrite: false }); await flushPromises()
    expect(input.props('disabled')).toBe(true)
    const buttons = Array.from(document.querySelectorAll('button'))
    const save = buttons.find(button => button.textContent?.trim() === '保存')!
    const cancel = buttons.find(button => button.textContent?.trim() === '取消')!
    expect(save.disabled).toBe(true)
    expect(cancel.disabled).toBe(false)
    expect(owner.props('showClose')).toBe(true)
    expect(document.body.textContent).toContain('当前账号仅可阅读业务说明')
    input.vm.$emit('update:modelValue', '不能修改只读草稿')
    save.click(); await flushPromises()
    expect(view.emitted('update:docEditContent')).toBeUndefined()
    expect(view.emitted('submitDocEdit')).toBeUndefined()
    await view.setProps({ canWrite: true }); await flushPromises()
    expect(input.props('disabled')).toBe(false)
    expect(save.disabled).toBe(false)
    await view.setProps({ canWrite: false }); await flushPromises()
    cancel.click(); await flushPromises()
    expect(view.emitted('update:docEditVisible')).toEqual([[false]])
  })
  it('blocks cancellation, dialog closure and content changes until saving settles', async () => {
    const view = setup(); await flushPromises()
    const dialog = view.findAllComponents(AppDialog)[0]
    const owner = dialog.getComponent(ElDialog)
    expect(owner.props('showClose')).toBe(false)
    expect(owner.props('closeOnClickModal')).toBe(false)
    expect(owner.props('closeOnPressEscape')).toBe(false)
    const cancel = Array.from(document.querySelectorAll('button')).find(button => button.textContent?.trim() === '取消')!
    expect(cancel.disabled).toBe(true)
    const input = dialog.getComponent(ElInput)
    expect(input.props('disabled')).toBe(true)
    owner.vm.$emit('update:modelValue', false)
    input.vm.$emit('update:modelValue', '被意外改动')
    cancel.click(); await flushPromises()
    expect(view.emitted('update:docEditVisible')).toBeUndefined()
    expect(view.emitted('update:docEditContent')).toBeUndefined()
    expect(owner.props('modelValue')).toBe(true)
    await view.setProps({ docEditSaving: false }); await flushPromises()
    expect(owner.props('showClose')).toBe(true)
    expect(owner.props('closeOnClickModal')).toBe(true)
    expect(owner.props('closeOnPressEscape')).toBe(true)
    expect(input.props('disabled')).toBe(false)
    cancel.click(); await flushPromises()
    expect(view.emitted('update:docEditVisible')).toEqual([[false]])
  })
})
