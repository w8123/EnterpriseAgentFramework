import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import KnowledgeSettingsTab from './KnowledgeSettingsTab.vue'
import type { KnowledgeBase } from '@/types/knowledge'
import { updateKnowledge, updateKbConfig } from '@/api/knowledge'

// 这些用例覆盖有写权限的角色；只读角色另有用例。
vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: () => true, canAny: () => true }) }))
vi.mock('@/api/knowledge', () => ({ updateKnowledge: vi.fn(), updateKbConfig: vi.fn() }))
vi.mock('@/api/model', () => ({ getModelInstances: vi.fn().mockResolvedValue({ data: [] }) }))

const kb = {
  code: 'kb_test', name: '原名称', description: '原说明',
  embeddingModelInstanceId: 'embedding', llmModelInstanceId: 'llm', rerankModelInstanceId: 'rerank',
  chunkSize: 500, chunkOverlap: 50, splitType: 'FIXED', accessMode: 'ALL_CALLERS',
  searchMode: 'hybrid', topK: 5, similarityThreshold: 0.5, rerankEnabled: false, vectorWeight: 0.7, keywordWeight: 0.3,
} as KnowledgeBase

function render() {
  return mount(KnowledgeSettingsTab, {
    props: { kbCode: kb.code, kb: { ...kb }, modelName: (id?: string | null) => id || '未配置' },
    global: { plugins: [ElementPlus], stubs: { ModelSelectEmptyState: true } },
  })
}

beforeEach(() => {
  vi.mocked(updateKnowledge).mockReset().mockResolvedValue({} as never)
  vi.mocked(updateKbConfig).mockReset().mockResolvedValue({} as never)
})

describe('KnowledgeSettingsTab', () => {
  it('saves edited basic information with the immutable code and existing model selections', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.find('input').setValue(' 新名称 ')
    await wrapper.find('textarea').setValue('新的中文说明')
    expect(wrapper.findAll('input')[1].attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('button').filter(button => button.text().includes('保存'))).toHaveLength(1)
    await wrapper.findAll('button').find(button => button.text() === '保存设置')!.trigger('click')
    await flushPromises()
    expect(updateKnowledge).toHaveBeenCalledWith({
      code: 'kb_test', name: '新名称', description: '新的中文说明',
      embeddingModelInstanceId: 'embedding', llmModelInstanceId: 'llm', rerankModelInstanceId: 'rerank',
    })
    expect(updateKbConfig).toHaveBeenCalledWith('kb_test', expect.objectContaining({ chunkSize: 500, topK: 5 }))
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })

  it('rejects an empty name before sending an update', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.find('input').setValue('   ')
    await wrapper.findAll('button').find(button => button.text() === '保存设置')!.trigger('click')
    await flushPromises()
    expect(updateKnowledge).not.toHaveBeenCalled()
    expect(updateKbConfig).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(wrapper.text()).toContain('请输入知识库名称'))
    expect(wrapper.emitted('saved')).toBeUndefined()
    wrapper.unmount()
  })

  it('preserves unsaved basic edits when retrieval settings refresh', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.find('input').setValue('尚未保存的名称')
    await wrapper.setProps({ kb: { ...kb, topK: 8 } })
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('尚未保存的名称')
    await wrapper.findAll('button').find(button => button.text() === '保存设置')!.trigger('click')
    await flushPromises()
    expect(updateKbConfig).toHaveBeenCalledWith('kb_test', expect.objectContaining({ topK: 8 }))
    expect(updateKnowledge).toHaveBeenCalledWith(expect.objectContaining({ name: '尚未保存的名称' }))
    wrapper.unmount()
  })

  it('saves model changes and retrieval edits together', async () => {
    const wrapper = render()
    await flushPromises()
    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    selects[0].vm.$emit('update:modelValue', 'new_embedding')
    selects[1].vm.$emit('update:modelValue', 'new_llm')
    wrapper.findAllComponents({ name: 'ElInputNumber' })[0].vm.$emit('update:modelValue', 700)
    await wrapper.findAll('button').find(button => button.text() === '保存设置')!.trigger('click')
    await flushPromises()
    expect(updateKnowledge).toHaveBeenCalledWith(expect.objectContaining({
      embeddingModelInstanceId: 'new_embedding', llmModelInstanceId: 'new_llm',
    }))
    expect(updateKbConfig).toHaveBeenCalledWith('kb_test', expect.objectContaining({ chunkSize: 700 }))
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })

  it('retains edits for retry and does not report success when the second update fails', async () => {
    vi.mocked(updateKbConfig).mockRejectedValueOnce(new Error('config failed'))
    const wrapper = render()
    await flushPromises()
    await wrapper.find('input').setValue('待保存的名称')
    wrapper.findAllComponents({ name: 'ElInputNumber' })[0].vm.$emit('update:modelValue', 700)
    const saveButton = wrapper.findAll('button').find(button => button.text() === '保存设置')!
    await saveButton.trigger('click')
    await flushPromises()
    expect(wrapper.emitted('saved')).toBeUndefined()
    expect((wrapper.find('input').element as HTMLInputElement).value).toBe('待保存的名称')
    await saveButton.trigger('click')
    await flushPromises()
    expect(updateKbConfig).toHaveBeenLastCalledWith('kb_test', expect.objectContaining({ chunkSize: 700 }))
    expect(wrapper.emitted('saved')).toHaveLength(1)
    wrapper.unmount()
  })

  it('stops before updating retrieval settings if basic information fails to save', async () => {
    vi.mocked(updateKnowledge).mockRejectedValueOnce(new Error('basic failed'))
    const wrapper = render()
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '保存设置')!.trigger('click')
    await flushPromises()
    expect(updateKbConfig).not.toHaveBeenCalled()
    expect(wrapper.emitted('saved')).toBeUndefined()
    wrapper.unmount()
  })
})
