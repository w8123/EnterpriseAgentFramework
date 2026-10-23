import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ModelOnboardingDialog from './ModelOnboardingDialog.vue'

const api = vi.hoisted(() => ({
  getProviderAccounts: vi.fn(),
  createProviderAccount: vi.fn(),
  updateProviderAccount: vi.fn(),
  discoverProviderModelsDraft: vi.fn(),
  getProviderAccountModels: vi.fn(),
  createProviderAccountInstances: vi.fn(),
  messageSuccess: vi.fn(),
  messageWarning: vi.fn(),
}))

vi.mock('@/api/model', () => ({
  getProviderAccounts: (...args: unknown[]) => api.getProviderAccounts(...args),
  createProviderAccount: (...args: unknown[]) => api.createProviderAccount(...args),
  updateProviderAccount: (...args: unknown[]) => api.updateProviderAccount(...args),
  discoverProviderModelsDraft: (...args: unknown[]) => api.discoverProviderModelsDraft(...args),
  getProviderAccountModels: (...args: unknown[]) => api.getProviderAccountModels(...args),
  createProviderAccountInstances: (...args: unknown[]) => api.createProviderAccountInstances(...args),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: api.messageSuccess, warning: api.messageWarning, error: vi.fn() },
}))

const group = {
  provider: 'tongyi',
  displayName: '通义千问',
  iconKey: null,
  modelCount: 3,
  sortOrder: 1,
  templates: [{ modelType: 'LLM', connectionDefaults: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' } }],
}

const PickerStub = defineComponent({
  emits: ['selectProvider', 'selectCustom'],
  template: '<button class="pick-provider" @click="$emit(\'selectProvider\', group)">pick</button>',
  setup: () => ({ group }),
})

const models = {
  success: true,
  message: 'ok',
  total: 3,
  models: [
    { id: 'qwen3.8-max', created: 1792000000, inferredType: 'LLM' },
    { id: 'qwen-plus', created: 1700000000, inferredType: 'LLM' },
    { id: 'text-embedding-v4', created: null, inferredType: 'EMBEDDING' },
  ],
  recommendedModelNames: ['qwen-plus'],
  connected: [],
}

const account = {
  id: 'acct-1',
  name: '通义千问账号',
  provider: 'tongyi',
  connection: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', apiKey: 'sk-ab******wxyz' },
  apiKeyConfigured: true,
  status: 'ACTIVE',
  instanceCount: 0,
}

function mountDialog() {
  return mount(ModelOnboardingDialog, {
    props: { modelValue: false },
    global: {
      directives: { loading: () => undefined },
      stubs: {
        ModelTemplatePicker: PickerStub,
        ModelProviderIcon: true,
        AppDialog: { template: '<div><slot name="header" /><slot /><slot name="footer" /></div>' },
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { props: ['label'], template: '<label><slot /></label>' },
        'el-input': {
          props: ['modelValue', 'placeholder'],
          emits: ['update:modelValue'],
          template: '<input :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
        },
        'el-button': {
          props: ['disabled', 'loading'],
          emits: ['click'],
          template: '<button class="btn" :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>',
        },
        'el-checkbox': {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template: '<input type="checkbox" class="row-check" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
        },
        'el-select': { props: ['modelValue'], template: '<select><slot /></select>' },
        'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
        LiquidGlassNotice: { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
        'el-empty': { props: ['description'], template: '<div>{{ description }}</div>' },
      },
    },
  })
}

function button(wrapper: ReturnType<typeof mountDialog>, text: string) {
  const found = wrapper.findAll('button').find((item) => item.text().includes(text))
  expect(found, text).toBeDefined()
  return found!
}

describe('ModelOnboardingDialog provider-first flow', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset())
  })

  it('asks for a key when the provider has no account, verifies it, then lists live models', async () => {
    api.getProviderAccounts.mockResolvedValue({ data: { data: [] } })
    api.discoverProviderModelsDraft.mockResolvedValue({ data: { data: models } })
    api.createProviderAccount.mockResolvedValue({ data: { data: account } })
    api.createProviderAccountInstances.mockResolvedValue({
      data: { data: { created: [{ id: 'm-1', name: 'qwen3.8-max' }], skipped: [] } },
    })
    const wrapper = mountDialog()
    await wrapper.setProps({ modelValue: true })

    await wrapper.get('.pick-provider').trigger('click')
    await flushPromises()
    expect(api.getProviderAccounts).toHaveBeenCalledWith('tongyi')
    expect((wrapper.find('input[placeholder="https://api.example.com/v1"]').element as HTMLInputElement).value)
      .toBe('https://dashscope.aliyuncs.com/compatible-mode/v1')

    await wrapper.get('input[placeholder="在厂商控制台创建的 API Key"]').setValue('sk-real-key-123456')
    await button(wrapper, '验证并获取模型').trigger('click')
    await flushPromises()

    expect(api.discoverProviderModelsDraft).toHaveBeenCalledWith({
      provider: 'tongyi',
      connection: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', apiKey: 'sk-real-key-123456' },
    })
    expect(api.createProviderAccount).toHaveBeenCalled()
    expect(wrapper.text()).toContain('qwen3.8-max')
    expect(wrapper.text()).toContain('目录推荐')

    await wrapper.findAll('.row-check')[0].setValue(true)
    await button(wrapper, '接入所选').trigger('click')
    await flushPromises()

    expect(api.createProviderAccountInstances).toHaveBeenCalledWith('acct-1', {
      models: [{ modelName: 'qwen3.8-max', modelType: 'LLM' }],
    })
    expect(api.messageSuccess).toHaveBeenCalledWith('已接入 1 个模型')
    expect(wrapper.emitted('created')?.[0]?.[0]).toMatchObject({ id: 'm-1' })
  })

  it('does not save the account when the provider rejects the key', async () => {
    api.getProviderAccounts.mockResolvedValue({ data: { data: [] } })
    api.discoverProviderModelsDraft.mockResolvedValue({
      data: { data: { ...models, success: false, message: 'API Key 无效或没有权限（HTTP 401）', models: [] } },
    })
    const wrapper = mountDialog()
    await wrapper.setProps({ modelValue: true })
    await wrapper.get('.pick-provider').trigger('click')
    await flushPromises()

    await wrapper.get('input[placeholder="在厂商控制台创建的 API Key"]').setValue('sk-wrong')
    await button(wrapper, '验证并获取模型').trigger('click')
    await flushPromises()

    expect(api.createProviderAccount).not.toHaveBeenCalled()
    expect(wrapper.get('.alert').text()).toContain('API Key 无效')
  })

  it('reuses an existing account and marks already connected models', async () => {
    api.getProviderAccounts.mockResolvedValue({ data: { data: [{ ...account, instanceCount: 1 }] } })
    api.getProviderAccountModels.mockResolvedValue({
      data: {
        data: {
          ...models,
          connected: [{ modelName: 'qwen-plus', modelType: 'LLM', instanceId: 'm-0', instanceName: 'qwen-plus' }],
        },
      },
    })
    const wrapper = mountDialog()
    await wrapper.setProps({ modelValue: true })
    await wrapper.get('.pick-provider').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('通义千问账号')
    await button(wrapper, '获取模型列表').trigger('click')
    await flushPromises()

    expect(api.getProviderAccountModels).toHaveBeenCalledWith('acct-1')
    expect(api.discoverProviderModelsDraft).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('已接入')
    const checks = wrapper.findAll('.row-check')
    expect(checks.filter((item) => (item.element as HTMLInputElement).disabled)).toHaveLength(1)
  })
})
