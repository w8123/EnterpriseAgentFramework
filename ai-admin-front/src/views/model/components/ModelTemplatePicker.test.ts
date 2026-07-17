import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import type { ModelTemplate } from '@/types/model'
import ModelTemplatePicker from './ModelTemplatePicker.vue'

const getModelTemplates = vi.fn()

vi.mock('@/api/model', () => ({
  getModelTemplates: (...args: unknown[]) => getModelTemplates(...args),
}))

vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return {
    ...actual,
    ElMessage: {
      success: vi.fn(),
      warning: vi.fn(),
      error: vi.fn(),
    },
  }
})

function sampleTemplate(partial?: Partial<ModelTemplate>): ModelTemplate {
  return {
    id: 'tpl-1',
    name: 'DeepSeek Chat',
    provider: 'deepseek',
    modelType: 'LLM',
    modelName: 'deepseek-chat',
    protocol: 'OPENAI_COMPATIBLE',
    connectionDefaults: {
      baseUrl: 'https://api.deepseek.com',
      chatPath: '/chat/completions',
    },
    credentialSchema: [],
    defaultOptions: {},
    paramsSchema: [],
    iconKey: 'deepseek',
    enabled: true,
    remark: 'chat model',
    sortOrder: 1,
    ...partial,
  }
}

const catalog = [
  sampleTemplate(),
  sampleTemplate({
    id: 'tpl-2',
    name: 'DeepSeek Reasoner',
    modelName: 'deepseek-reasoner',
    sortOrder: 2,
  }),
  sampleTemplate({
    id: 'tpl-3',
    name: 'OpenAI GPT',
    provider: 'openai',
    modelName: 'gpt-5.2',
    iconKey: 'openai',
    sortOrder: 10,
  }),
  sampleTemplate({
    id: 'tpl-4',
    name: 'OpenAI Embedding',
    provider: 'openai',
    modelType: 'EMBEDDING',
    modelName: 'text-embedding-3-large',
    iconKey: 'openai',
    sortOrder: 11,
  }),
  sampleTemplate({
    id: 'tpl-disabled',
    name: 'Disabled',
    provider: 'deepseek',
    enabled: false,
    sortOrder: 3,
  }),
]

async function mountPicker(props: Record<string, unknown> = {}) {
  getModelTemplates.mockResolvedValue({ data: { data: catalog } })
  const wrapper = mount(ModelTemplatePicker, {
    props,
    global: {
      stubs: {
        'el-icon': true,
        'el-input': {
          props: ['modelValue', 'placeholder'],
          emits: ['update:modelValue'],
          template:
            '<input class="stub-input" :value="modelValue" :placeholder="placeholder" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        'el-alert': true,
        'el-empty': { template: '<div class="stub-empty"><slot /></div>' },
        'el-skeleton': true,
        'el-skeleton-item': true,
        'el-button': true,
        ModelProviderIcon: {
          props: ['provider', 'iconKey'],
          template: '<span class="stub-icon" :data-provider="provider" :data-icon="iconKey" />',
        },
      },
    },
  })
  await flushPromises()
  await nextTick()
  return wrapper
}

describe('ModelTemplatePicker two-step catalog', () => {
  beforeEach(() => {
    getModelTemplates.mockReset()
  })

  it('shows providers first and hides disabled templates from counts', async () => {
    const wrapper = await mountPicker()
    expect(wrapper.text()).toContain('选择模型厂商')
    expect(wrapper.findAll('.provider-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('2 个模型')
    expect(wrapper.text()).toContain('自定义 OpenAI 兼容模型')
    expect(wrapper.findAll('.model-card')).toHaveLength(0)
  })

  it('locks replace flow to same type providers and counts', async () => {
    const wrapper = await mountPicker({ modelType: 'LLM', lockModelType: true })
    const cards = wrapper.findAll('.provider-card')
    expect(cards).toHaveLength(2)
    expect(wrapper.text()).toContain('大语言模型')
    await cards[1].trigger('click')
    await nextTick()
    const models = wrapper.findAll('.model-card')
    expect(models).toHaveLength(1)
    expect(wrapper.text()).toContain('gpt-5.2')
    expect(wrapper.text()).not.toContain('text-embedding-3-large')
  })

  it('enters provider models then returns to provider list', async () => {
    const wrapper = await mountPicker()
    await wrapper.findAll('.provider-card')[0].trigger('click')
    await nextTick()
    expect(wrapper.find('.model-context__back').exists()).toBe(true)
    expect(wrapper.findAll('.model-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('DeepSeek Reasoner')
    await wrapper.find('.model-context__back').trigger('click')
    await nextTick()
    expect(wrapper.text()).toContain('选择模型厂商')
    expect(wrapper.findAll('.provider-card')).toHaveLength(2)
  })

  it('emits selectTemplate and selectCustom', async () => {
    const wrapper = await mountPicker()
    await wrapper.find('.custom-card').trigger('click')
    expect(wrapper.emitted('selectCustom')).toHaveLength(1)

    await wrapper.findAll('.provider-card')[0].trigger('click')
    await nextTick()
    await wrapper.findAll('.model-card')[0].trigger('click')
    const emitted = wrapper.emitted('selectTemplate')
    expect(emitted).toHaveLength(1)
    expect(emitted?.[0]?.[0]).toMatchObject({ id: 'tpl-1', modelName: 'deepseek-chat' })
  })

  it('falls back to provider step when reload removes current provider models', async () => {
    const wrapper = await mountPicker({ modelType: 'LLM', lockModelType: true })
    await wrapper.findAll('.provider-card')[0].trigger('click')
    await nextTick()
    expect(wrapper.find('.model-context__back').exists()).toBe(true)

    getModelTemplates.mockResolvedValue({
      data: {
        data: [
          sampleTemplate({
            id: 'tpl-3',
            name: 'OpenAI GPT',
            provider: 'openai',
            modelName: 'gpt-5.2',
            iconKey: 'openai',
            sortOrder: 10,
          }),
        ],
      },
    })
    await wrapper.find('.catalog-reload').trigger('click')
    await flushPromises()
    await nextTick()
    expect(wrapper.text()).toContain('选择模型厂商')
    expect(wrapper.findAll('.provider-card')).toHaveLength(1)
  })
})
