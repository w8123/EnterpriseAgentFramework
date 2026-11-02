import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import KnowledgeSettingsTab from './KnowledgeSettingsTab.vue'
import type { KnowledgeBase } from '@/types/knowledge'
import { getKnowledgeSettingsContext, saveKnowledgeSettings } from '@/api/knowledge'

vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: () => true, canAny: () => true }) }))
vi.mock('@/api/knowledge', () => ({ getKnowledgeSettingsContext: vi.fn(), saveKnowledgeSettings: vi.fn() }))
vi.mock('@/api/model', () => ({ getModelInstances: vi.fn().mockResolvedValue({ data: [] }) }))

const kb = {
  code: 'kb_test', name: '原名称', description: '原说明', embeddingModelInstanceId: 'embedding', llmModelInstanceId: 'llm', rerankModelInstanceId: 'rerank',
  chunkSize: 500, chunkOverlap: 50, splitType: 'FIXED', accessMode: 'ALL_CALLERS', searchMode: 'hybrid', topK: 5,
  similarityThreshold: 0.5, rerankEnabled: false, vectorWeight: 0.7, keywordWeight: 0.3,
} as KnowledgeBase
const revision = 'a'.repeat(64)
const context = { contractVersion: 1, kbCode: kb.code, knowledgeBaseId: 7, revision, resource: { scope: 'PROJECT', workspaceId: 'team', projectCode: 'orders' }, before: kb }
function render() {
  return mount(KnowledgeSettingsTab, {
    props: { kbCode: kb.code, kb: { ...kb }, modelName: (id?: string | null) => id || '未配置' },
    global: { plugins: [ElementPlus], stubs: { ModelSelectEmptyState: true } },
  })
}
function saveButton(wrapper: ReturnType<typeof render>) { return wrapper.findAll('button').find(button => button.text() === '保存设置')! }
beforeEach(() => {
  vi.restoreAllMocks()
  vi.mocked(getKnowledgeSettingsContext).mockReset().mockResolvedValue({ data: context } as never)
  vi.mocked(saveKnowledgeSettings).mockReset().mockImplementation(async (_code, _revision, changes) => ({
    data: { ...context, revision: 'b'.repeat(64), before: { ...kb, ...changes } },
  }) as never)
})

describe('KnowledgeSettingsTab', () => {
  it('saves all settings once with the exact loaded revision and retains the immutable code', async () => {
    const wrapper = render(); await flushPromises()
    await wrapper.find('input').setValue(' 新名称 ')
    await wrapper.find('textarea').setValue('新的中文说明')
    expect(wrapper.findAll('input')[1].attributes('disabled')).toBeDefined()
    await saveButton(wrapper).trigger('click'); await flushPromises()
    expect(saveKnowledgeSettings).toHaveBeenCalledTimes(1)
    expect(saveKnowledgeSettings).toHaveBeenCalledWith('kb_test', revision, expect.objectContaining({ name: '新名称', description: '新的中文说明', chunkSize: 500, topK: 5 }))
    expect(vi.mocked(saveKnowledgeSettings).mock.calls[0][2]).not.toHaveProperty('code')
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })
  it('rejects an empty name before sending any update', async () => {
    const wrapper = render(); await flushPromises()
    await wrapper.find('input').setValue('   ')
    await saveButton(wrapper).trigger('click'); await flushPromises()
    expect(saveKnowledgeSettings).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(wrapper.text()).toContain('请输入知识库名称'))
    wrapper.unmount()
  })
  it('keeps unsaved inputs and their baseline when parent metadata refreshes', async () => {
    const wrapper = render(); await flushPromises()
    await wrapper.find('input').setValue('尚未保存的名称')
    await wrapper.setProps({ kb: { ...kb, name: '其他人更新', topK: 8 } })
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('尚未保存的名称')
    await saveButton(wrapper).trigger('click'); await flushPromises()
    expect(saveKnowledgeSettings).toHaveBeenCalledWith('kb_test', revision, expect.objectContaining({ name: '尚未保存的名称', topK: 5 }))
    wrapper.unmount()
  })
  it('uses the owner context rather than stale props and saves model and retrieval changes atomically', async () => {
    vi.mocked(getKnowledgeSettingsContext).mockResolvedValueOnce({ data: { ...context, before: { ...kb, name: '当前服务端名称' } } } as never)
    const wrapper = render(); await flushPromises()
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('当前服务端名称')
    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    selects[0].vm.$emit('update:modelValue', 'new_embedding'); selects[1].vm.$emit('update:modelValue', 'new_llm')
    wrapper.findAllComponents({ name: 'ElInputNumber' })[0].vm.$emit('update:modelValue', 700)
    await saveButton(wrapper).trigger('click'); await flushPromises()
    expect(saveKnowledgeSettings).toHaveBeenCalledWith('kb_test', revision, expect.objectContaining({ embeddingModelInstanceId: 'new_embedding', llmModelInstanceId: 'new_llm', chunkSize: 700 }))
    wrapper.unmount()
  })
  it('retains inputs after a conflict and requires deliberate reload before another save', async () => {
    vi.mocked(saveKnowledgeSettings).mockRejectedValueOnce({ response: { status: 409 } })
    const wrapper = render(); await flushPromises()
    await wrapper.find('input').setValue('我的未保存名称')
    await saveButton(wrapper).trigger('click'); await flushPromises()
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect(wrapper.text()).toContain('当前输入已保留')
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('我的未保存名称')
    expect(wrapper.find('input').attributes('disabled')).toBeUndefined()
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel')
    await wrapper.findAll('button').find(button => button.text() === '重新读取设置')!.trigger('click'); await flushPromises()
    expect(getKnowledgeSettingsContext).toHaveBeenCalledTimes(1)
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('我的未保存名称')
    wrapper.unmount()
  })
  it('does not enable saving if the revision context could not be loaded', async () => {
    vi.mocked(getKnowledgeSettingsContext).mockRejectedValueOnce(new Error('unavailable'))
    const wrapper = render(); await flushPromises()
    expect(wrapper.text()).toContain('无法读取当前设置')
    expect(saveButton(wrapper).attributes('disabled')).toBeDefined()
    expect(saveKnowledgeSettings).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('does not claim success for an unconfirmed save and preserves the input', async () => {
    vi.mocked(saveKnowledgeSettings).mockRejectedValueOnce(new Error('connection lost'))
    const wrapper = render(); await flushPromises()
    await wrapper.find('input').setValue('结果未知')
    await saveButton(wrapper).trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('保存结果未确认')
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('结果未知')
    expect(wrapper.emitted('saved')).toBeUndefined()
    wrapper.unmount()
  })
})
