import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ModelConnectionReuseDialog from './ModelConnectionReuseDialog.vue'

const { testModel, createModel } = vi.hoisted(() => ({ testModel: vi.fn(), createModel: vi.fn() }))
vi.mock('@/api/model', () => ({
  testModelWithSavedConnection: testModel,
  createModelWithSavedConnection: createModel,
}))
vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() } }))

const source = { id: 'source-1', name: 'Embedding', modelType: 'EMBEDDING', provider: 'tongyi' }

function mountDialog() {
  return mount(ModelConnectionReuseDialog, {
    props: { open: true, source: source as any },
    global: { stubs: {
      AppDialog: { template: '<div><slot /><slot name="footer" /></div>' },
      LiquidGlassNotice: { template: '<div><slot /></div>' },
      LiquidGlassButton: { props: ['disabled', 'loading'], template: '<button :disabled="disabled"><slot /></button>' },
      'el-form': { template: '<form><slot /></form>' },
      'el-form-item': { template: '<label><slot /></label>' },
      'el-input': { props: ['modelValue', 'disabled'], emits: ['update:modelValue'],
        template: '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
      'el-select': { template: '<div><slot /></div>' },
      'el-option': { template: '<span />' },
      ModelTestResultPanel: { template: '<div />' },
    } },
  })
}

describe('reuse a model connection', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('tests and creates without sending connection settings, and invalidates a changed model', async () => {
    testModel.mockResolvedValue({ data: { code: 200, data: { success: true, latencyMs: 20, message: 'ok' } } })
    createModel.mockResolvedValue({ data: { code: 200, data: { id: 'new-model' } } })
    const wrapper = mountDialog()
    const inputs = wrapper.findAll('input')
    await inputs[0]!.setValue('Chat')
    await inputs[1]!.setValue('qwen-plus')
    const buttons = wrapper.findAll('button')
    expect(buttons[2]!.attributes('disabled')).toBeDefined()
    await buttons[1]!.trigger('click')
    await flushPromises()
    expect(testModel).toHaveBeenCalledWith('source-1', { modelType: 'LLM', modelName: 'qwen-plus' })
    await inputs[1]!.setValue('another-model')
    expect(buttons[2]!.attributes('disabled')).toBeDefined()
    await inputs[1]!.setValue('qwen-plus')
    await buttons[1]!.trigger('click')
    await flushPromises()
    await buttons[2]!.trigger('click')
    await flushPromises()
    expect(createModel).toHaveBeenCalledWith('source-1', { name: 'Chat', modelType: 'LLM', modelName: 'qwen-plus' })
    expect(wrapper.emitted('created')?.[0]).toEqual(['new-model'])
  })
})
