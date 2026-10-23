import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import type { ModelInstance } from '@/types/model'
import StudioAiEditBar from './StudioAiEditBar.vue'

const models = [
  { id: 'flash', name: 'DeepSeek V4 Flash', provider: 'deepseek', modelName: 'deepseek-v4-flash' },
  { id: 'pro', name: 'DeepSeek V4 Pro', provider: 'deepseek', modelName: 'deepseek-v4-pro' },
] as ModelInstance[]

const wrappers: ReturnType<typeof mount>[] = []

function render(extra: Record<string, unknown> = {}) {
  const wrapper = mount(StudioAiEditBar, {
    attachTo: document.body,
    props: {
      instruction: '', modelInstanceId: '', readOnly: false, loading: false, debugOpen: false,
      preview: null, previewFailed: false, operationGroups: [], modelOptions: models,
      modelOptionsLoading: false, modelOptionsLoadError: false, selectedModelLabel: '',
      ...extra,
    },
    global: { plugins: [ElementPlus], stubs: { ModelSelectEmptyState: true } },
  })
  wrappers.push(wrapper)
  return wrapper
}

async function openPicker(wrapper: ReturnType<typeof render>) {
  await wrapper.get('[aria-label="选择 AI 编排模型"]').trigger('click')
  await nextTick()
  await flushPromises()
  await vi.waitFor(() => {
    expect(wrapper.get('[aria-label="选择 AI 编排模型"]').attributes('aria-expanded')).toBe('true')
  })
}

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  document.body.innerHTML = ''
})

describe('StudioAiEditBar model picker', () => {
  it('reuses Model Center vendor images on the trigger and options, and follows explicit model switches', async () => {
    const gpt = { ...models[0], id: 'gpt', name: 'GPT 测试模型', provider: 'openai', modelName: 'gpt-test' }
    const wrapper = render({ modelInstanceId: 'flash', modelOptions: [...models, gpt], resolvedModelProvider: 'openai' })
    const trigger = wrapper.get('[aria-label="选择 AI 编排模型"]')
    expect(trigger.get('img').attributes('src')).toMatch(/deepseek\.png/)
    await openPicker(wrapper)
    const vendorImages = Array.from(document.querySelectorAll<HTMLImageElement>('.ai-edit-model-option .model-provider-icon__img'))
    expect(vendorImages.map((image) => image.getAttribute('alt'))).toEqual(['deepseek', 'deepseek', 'openai'])
    expect(vendorImages[2].getAttribute('src')).toMatch(/openai\.png/)
    await wrapper.setProps({ modelInstanceId: 'gpt' })
    expect(trigger.get('img').attributes('src')).toMatch(/openai\.png/)
  })

  it('shows the effective Workflow default vendor without pinning an explicit model', async () => {
    const wrapper = render({ resolvedModelProvider: 'deepseek' })
    const trigger = wrapper.get('[aria-label="选择 AI 编排模型"]')
    expect(trigger.get('img').attributes('src')).toMatch(/deepseek\.png/)
    expect(wrapper.get('.ai-edit-model-label').text()).toBe('Workflow 默认模型')
    await wrapper.setProps({ resolvedModelProvider: 'openai' })
    expect(trigger.get('img').attributes('src')).toMatch(/openai\.png/)
    expect(wrapper.emitted('update:modelInstanceId')).toBeUndefined()
  })

  it('opens a single list and selects an explicit model without changing the instruction', async () => {
    const wrapper = render({ instruction: '保留当前流程并增加结果说明' })
    await openPicker(wrapper)
    expect(wrapper.emitted('load-models')).toHaveLength(1)
    const options = Array.from(document.querySelectorAll<HTMLButtonElement>('.ai-edit-model-option'))
    expect(options).toHaveLength(3)
    expect(document.querySelector('.el-select')).toBeNull()
    expect(options[0].getAttribute('aria-pressed')).toBe('true')
    options[2].click()
    await nextTick()
    expect(wrapper.emitted('update:modelInstanceId')).toEqual([['pro']])
    expect(wrapper.emitted('update:instruction')).toBeUndefined()
    expect(wrapper.get('[aria-label="选择 AI 编排模型"]').attributes('aria-expanded')).toBe('false')
  })

  it('searches case-insensitively by display name, supplier and model identifier', async () => {
    const wrapper = render()
    await openPicker(wrapper)
    const search = document.querySelector<HTMLInputElement>('[aria-label="搜索编排模型"]')!
    for (const keyword of ['V4 PRO', 'DEEPSEEK', 'deepseek-v4-flash', 'no-match']) {
      search.value = keyword
      search.dispatchEvent(new Event('input', { bubbles: true }))
      await nextTick()
      const options = document.querySelectorAll('.ai-edit-model-option')
      expect(options).toHaveLength(keyword === 'DEEPSEEK' ? 2 : keyword === 'no-match' ? 0 : 1)
    }
    expect(document.querySelector('model-select-empty-state-stub')).not.toBeNull()
    expect(wrapper.emitted('update:modelInstanceId')).toBeUndefined()
  })

  it('shows the selected model and allows returning to the Workflow default', async () => {
    const wrapper = render({ modelInstanceId: 'pro', selectedModelLabel: 'DeepSeek V4 Pro / deepseek / deepseek-v4-pro' })
    expect(wrapper.get('.ai-edit-model-label').text()).toBe('DeepSeek V4 Pro')
    await openPicker(wrapper)
    expect(document.querySelector('.ai-edit-model-option.is-selected')?.textContent).toContain('DeepSeek V4 Pro')
    document.querySelector<HTMLButtonElement>('.ai-edit-model-default')!.click()
    await nextTick()
    expect(wrapper.emitted('update:modelInstanceId')).toEqual([['']])
  })

  it.each([
    { modelOptionsLoading: true },
    { modelOptionsLoadError: true },
  ])('keeps loading and failed catalogs out of the selectable list: %j', async (state) => {
    const wrapper = render(state)
    await openPicker(wrapper)
    expect(document.querySelectorAll('.ai-edit-model-option')).toHaveLength(1)
    expect(document.querySelector('model-select-empty-state-stub')).not.toBeNull()
  })

  it('keeps the selected instance visible before the catalog has loaded', () => {
    const wrapper = render({ modelInstanceId: 'pro', modelOptions: [], selectedModelLabel: 'DeepSeek V4 Pro / deepseek / deepseek-v4-pro' })
    expect(wrapper.get('.ai-edit-model-label').text()).toBe('DeepSeek V4 Pro')
  })

  it('prevents generating from a read-only Workflow', () => {
    const wrapper = render({ instruction: '增加结果说明', readOnly: true })
    expect(wrapper.get('[aria-label="生成编排方案"]').attributes('disabled')).toBeDefined()
  })
})
