import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ModelCatalogSettingsDialog from './ModelCatalogSettingsDialog.vue'

const {
  getModelCatalogStatus,
  getModelInstances,
  updateModelCatalogSettings,
  triggerModelCatalogSync,
  messageSuccess,
} = vi.hoisted(() => ({
  getModelCatalogStatus: vi.fn(),
  getModelInstances: vi.fn(),
  updateModelCatalogSettings: vi.fn(),
  triggerModelCatalogSync: vi.fn(),
  messageSuccess: vi.fn(),
}))

vi.mock('@/api/model', () => ({
  getModelCatalogStatus: (...args: unknown[]) => getModelCatalogStatus(...args),
  getModelInstances: (...args: unknown[]) => getModelInstances(...args),
  updateModelCatalogSettings: (...args: unknown[]) => updateModelCatalogSettings(...args),
  triggerModelCatalogSync: (...args: unknown[]) => triggerModelCatalogSync(...args),
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: messageSuccess,
    info: vi.fn(),
    error: vi.fn(),
  },
}))

function catalogStatus(autoSyncEnabled = false, analyzerModelInstanceId: string | null = 'qwen-analyzer') {
  return {
    analyzerModelInstanceId,
    enabled: autoSyncEnabled,
    autoSyncEnabled,
    zoneId: 'Asia/Shanghai',
    businessDate: '2026-08-25',
    analyzerConfigured: true,
    stale: false,
    catalogVerifiedAt: null,
    lastAnySuccessfulAt: null,
    sourceCount: 8,
    completedToday: 3,
    message: autoSyncEnabled ? '自动同步已开启' : '自动同步已关闭，可手动同步',
    sources: [],
  }
}

async function mountDialog() {
  const wrapper = mount(ModelCatalogSettingsDialog, {
    props: { modelValue: false },
    global: {
      directives: { loading: () => undefined },
      stubs: {
        AppDialog: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template: '<div><slot /><slot name="footer" /></div>',
        },
        LiquidGlassNotice: {
          props: ['title', 'description'],
          template: '<div class="stub-alert">{{ title }} {{ description }}</div>',
        },
        'el-switch': {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<button class="stub-switch" :disabled="disabled" @click="$emit(\'update:modelValue\', !modelValue)">{{ modelValue }}</button>',
        },
        'el-select': {
          props: ['modelValue', 'disabled'],
          emits: ['update:modelValue'],
          template:
            '<select class="stub-analyzer" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
        },
        'el-option': {
          props: ['label', 'value'],
          template: '<option :value="value">{{ label }}</option>',
        },
        'el-button': {
          props: ['disabled', 'loading'],
          emits: ['click'],
          template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
        },
      },
    },
  })
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
  return wrapper
}

describe('ModelCatalogSettingsDialog', () => {
  beforeEach(() => {
    getModelCatalogStatus.mockReset()
    updateModelCatalogSettings.mockReset()
    triggerModelCatalogSync.mockReset()
    messageSuccess.mockReset()
    getModelInstances.mockReset()
    getModelCatalogStatus.mockResolvedValue({ data: { data: catalogStatus(false) } })
    getModelInstances.mockResolvedValue({
      data: {
        data: [
          { id: 'qwen-analyzer', name: '通义千问', modelName: 'qwen-plus', status: 'ACTIVE' },
          { id: 'qwen-max', name: '通义旗舰', modelName: 'qwen3-max', status: 'ACTIVE' },
          { id: 'old', name: '已停用', modelName: 'qwen-turbo', status: 'DISABLED' },
        ],
      },
    })
  })

  it('lists active LLM instances and saves only the changed analyzer choice', async () => {
    updateModelCatalogSettings.mockResolvedValue({ data: { data: catalogStatus(false, 'qwen-max') } })
    const wrapper = await mountDialog()

    expect(getModelInstances).toHaveBeenCalledWith({ modelType: 'LLM' })
    const options = wrapper.findAll('.stub-analyzer option').map((option) => option.attributes('value'))
    expect(options).toEqual(['qwen-analyzer', 'qwen-max'])

    await wrapper.get('.stub-analyzer').setValue('qwen-max')
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存设置'))
    await saveButton!.trigger('click')
    await flushPromises()

    expect(updateModelCatalogSettings).toHaveBeenCalledWith({ analyzerModelInstanceId: 'qwen-max' })
    expect(messageSuccess).toHaveBeenCalledWith('已保存目录分析模型')
  })

  it('loads the persisted default-off setting and saves an enabled setting', async () => {
    updateModelCatalogSettings.mockResolvedValue({ data: { data: catalogStatus(true) } })
    const wrapper = await mountDialog()

    expect(wrapper.text()).toContain('默认关闭')
    expect(wrapper.text()).toContain('今日 3/8 个来源已完成')
    expect(wrapper.get('.stub-switch').text()).toBe('false')

    await wrapper.get('.stub-switch').trigger('click')
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存设置'))
    expect(saveButton).toBeDefined()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(updateModelCatalogSettings).toHaveBeenCalledWith({ autoSyncEnabled: true })
    expect(wrapper.emitted('updated')?.[0]?.[0]).toMatchObject({ autoSyncEnabled: true })
    expect(messageSuccess).toHaveBeenCalledWith('已开启模型目录自动同步')
  })

  it('submits a manual sync and refreshes same-day progress', async () => {
    triggerModelCatalogSync.mockResolvedValue({
      data: {
        data: {
          businessDate: '2026-08-25',
          accepted: true,
          sourceCount: 8,
          completedToday: 3,
          queuedCount: 5,
          message: '已提交 5 个来源',
        },
      },
    })
    const wrapper = await mountDialog()

    const syncButton = wrapper.findAll('button').find((button) => button.text().includes('立即同步'))
    expect(syncButton).toBeDefined()
    await syncButton!.trigger('click')
    await flushPromises()

    expect(triggerModelCatalogSync).toHaveBeenCalledTimes(1)
    expect(getModelCatalogStatus).toHaveBeenCalledTimes(2)
    expect(messageSuccess).toHaveBeenCalledWith('已提交 5 个来源')
  })
})
