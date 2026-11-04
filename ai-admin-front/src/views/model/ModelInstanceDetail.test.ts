import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ModelInstanceDetail from './ModelInstanceDetail.vue'

const mocks = vi.hoisted(() => ({
  get: vi.fn(), archive: vi.fn(), confirm: vi.fn(), push: vi.fn(),
}))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: 'model-1' } }),
  useRouter: () => ({ push: mocks.push }),
  onBeforeRouteLeave: vi.fn(),
}))
vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: () => true }) }))
vi.mock('@/api/model', () => ({
  getModelInstance: mocks.get, archiveModelInstance: mocks.archive,
  testModelInstance: vi.fn(), testModelInstanceDraft: vi.fn(), updateModelInstance: vi.fn(),
}))
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus')
  return { ...actual, ElMessageBox: { confirm: mocks.confirm, alert: vi.fn() },
    ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }
})

const instance = {
  id: 'model-1', name: '测试模型', modelType: 'EMBEDDING', provider: 'custom',
  modelName: 'embedding', protocol: 'OPENAI_COMPATIBLE', status: 'ACTIVE',
  connection: { baseUrl: 'https://example.com/v1', embeddingPath: '/embeddings' },
  apiKeyConfigured: true,
  defaultOptions: { nested: { value: 1 } }, paramsSchema: [{ name: 'example' }],
}

function mountDetail() {
  return mount(ModelInstanceDetail, { global: { stubs: {
    WorkbenchPage: { template: '<main><slot /></main>' },
    PageHeader: { template: '<header><slot name="tags"/><slot name="meta"/><slot name="actions"/></header>' },
    WorkbenchPanel: { props: ['title'], template: '<section><h2>{{ title }}</h2><slot /></section>' },
    LiquidGlassButton: { template: '<button><slot /></button>' },
    LiquidGlassCard: { template: '<div><slot /></div>' },
    LiquidGlassNotice: true, LiquidGlassEmptyState: true, StatusTag: true, HeaderMetaList: true,
    ModelProviderIcon: true, ModelConnectionReuseDialog: true, ModelTemplatePicker: true,
    ModelTestResultPanel: true, AppDialog: true,
    ModelInstanceForm: { props: ['draft'], emits: ['update:draft'],
      template: '<input aria-label="接入名称" :value="draft.name" @input="$emit(\'update:draft\', { ...draft, name: $event.target.value })" />' },
    ElButton: { template: '<button><slot /></button>' },
    ElSkeleton: true, ElInput: true, ElFormItem: true, ElForm: true,
  } } })
}

function button(wrapper: ReturnType<typeof mountDetail>, label: string) {
  const found = wrapper.findAll('button').find((item) => item.text() === label)
  if (!found) throw new Error(`Missing button: ${label}`)
  return found
}

describe('模型详情', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.get.mockResolvedValue({ data: { code: 200, data: structuredClone(instance) } })
  })

  it('opens editing with reactive nested configuration and discards cancelled changes', async () => {
    const wrapper = mountDetail()
    await flushPromises()
    await button(wrapper, '编辑配置').trigger('click')
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('测试模型')
    await wrapper.get('input').setValue('已修改')
    await button(wrapper, '取消编辑').trigger('click')
    await button(wrapper, '编辑配置').trigger('click')
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('测试模型')
    wrapper.unmount()
  })

  it('confirms deletion as archival and returns to the model list', async () => {
    mocks.confirm.mockResolvedValue('confirm')
    mocks.archive.mockResolvedValue({})
    const wrapper = mountDetail()
    await flushPromises()
    await button(wrapper, '删除（归档）').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledWith(expect.stringContaining('测试模型'), '删除模型（归档）', expect.any(Object))
    expect(mocks.archive).toHaveBeenCalledWith('model-1')
    expect(mocks.push).toHaveBeenCalledWith({ name: 'ModelInstances' })
    wrapper.unmount()
  })

  it('does not archive when the confirmation is cancelled', async () => {
    mocks.confirm.mockRejectedValue('cancel')
    const wrapper = mountDetail()
    await flushPromises()
    await button(wrapper, '删除（归档）').trigger('click')
    await flushPromises()
    expect(mocks.archive).not.toHaveBeenCalled()
    expect(mocks.push).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
