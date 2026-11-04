import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import KnowledgeFilesTab from './KnowledgeFilesTab.vue'
import { getKbFiles } from '@/api/knowledge'
import { commitDocumentImportJob, getDocumentImportJob } from '@/api/import'
import type { DocumentImportJob } from '@/types/import'

const access = vi.hoisted(() => ({ write: true }))
vi.mock('@/auth/usePlatformCan', () => ({ usePlatformCan: () => ({ can: () => access.write }) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api/knowledge', () => ({ getKbFiles: vi.fn(), deleteFile: vi.fn(), reparseFile: vi.fn() }))
vi.mock('@/api/import', () => ({
  getDocumentImportJob: vi.fn(), commitDocumentImportJob: vi.fn(), submitDocumentImportJob: vi.fn(),
  retryDocumentImportJob: vi.fn(), cancelDocumentImportJob: vi.fn(),
}))

const parsed: DocumentImportJob = {
  jobId: 'dij_review', fileId: 'file_review', knowledgeBaseCode: 'kb_test', fileName: '演示.md', fileType: 'md',
  providerType: 'JAVA_FAST', status: 'PARSED', stage: 'PARSED', attemptCount: 1, maxAttempts: 3, autoCommit: false,
}
const preview = {
  fileName: '演示.md', chunkStrategy: 'paragraph', chunkSize: 500, chunkOverlap: 50, totalChunks: 1,
  chunks: [{ index: 0, content: '演示库存低于 20 件时补货', length: 14, sectionPath: '采购补货' }],
}
const completed: DocumentImportJob = { ...parsed, status: 'COMPLETED', stage: 'COMPLETED' }

function render() {
  return mount(KnowledgeFilesTab, {
    props: { kbCode: 'kb_test', kb: null },
    global: { plugins: [ElementPlus], stubs: { LiquidGlassTable: { template: '<div><slot name="toolbar"/><slot/></div>' } } },
  })
}
function button(wrapper: ReturnType<typeof render>, label: string) {
  return wrapper.findAll('button').find(item => item.text().trim() === label)
}

beforeEach(() => {
  access.write = true
  window.sessionStorage.clear()
  window.sessionStorage.setItem('reachai:knowledge:file-job:kb_test', 'upload:dij_review')
  vi.mocked(getKbFiles).mockReset().mockResolvedValue({ data: [] } as never)
  vi.mocked(commitDocumentImportJob).mockReset()
  vi.mocked(getDocumentImportJob).mockReset().mockImplementation(async (_id, includePreview) => ({
    data: includePreview ? { ...parsed, preview } : parsed,
  }) as never)
})

describe('knowledge file import review', () => {
  it('restores a parsed job, shows its preview and commits only after a deliberate action', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('演示库存低于 20 件时补货')
    expect(commitDocumentImportJob).not.toHaveBeenCalled()
    vi.mocked(commitDocumentImportJob).mockResolvedValueOnce({ data: parsed } as never)
    vi.mocked(getDocumentImportJob).mockImplementation(async (_id, includePreview) => ({
      data: includePreview ? { ...parsed, preview } : completed,
    }) as never)
    await button(wrapper, '确认入库')!.trigger('click')
    await flushPromises()
    expect(commitDocumentImportJob).toHaveBeenCalledOnce()
    expect(commitDocumentImportJob).toHaveBeenCalledWith('dij_review')
    expect(wrapper.emitted('changed')).toHaveLength(1)
    expect(wrapper.text()).toContain('入库完成')
    wrapper.unmount()
  })

  it('prevents a write when the viewer has no knowledge write permission', async () => {
    access.write = false
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('演示库存低于 20 件时补货')
    expect(button(wrapper, '确认入库')).toBeUndefined()
    expect(commitDocumentImportJob).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('does not blindly resubmit an import when the commit result is unknown', async () => {
    const wrapper = render()
    await flushPromises()
    vi.mocked(commitDocumentImportJob).mockRejectedValueOnce(new Error('network lost'))
    await button(wrapper, '确认入库')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('入库结果未确认')
    expect(button(wrapper, '确认入库')!.attributes('disabled')).toBeDefined()
    await button(wrapper, '检查任务状态')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('入库结果仍未确认')
    expect(commitDocumentImportJob).toHaveBeenCalledOnce()
    wrapper.unmount()
    const restored = render()
    await flushPromises()
    expect(restored.text()).toContain('入库结果未确认')
    expect(button(restored, '确认入库')!.attributes('disabled')).toBeDefined()
    expect(commitDocumentImportJob).toHaveBeenCalledOnce()
    restored.unmount()
  })

  it('requires a successfully loaded preview before allowing a commit', async () => {
    vi.mocked(getDocumentImportJob).mockImplementation(async (_id, includePreview) => {
      if (includePreview) throw new Error('预览不可用')
      return { data: parsed } as never
    })
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('预览不可用')
    expect(button(wrapper, '确认入库')!.attributes('disabled')).toBeDefined()
    expect(button(wrapper, '重新读取预览')).toBeDefined()
    expect(commitDocumentImportJob).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('shows changed preview instead of committing content the user did not review', async () => {
    const wrapper = render()
    await flushPromises()
    vi.mocked(getDocumentImportJob).mockResolvedValueOnce({
      data: { ...parsed, preview: { ...preview, chunks: [{ ...preview.chunks[0], content: '已变更' }] } },
    } as never)
    await button(wrapper, '确认入库')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('解析结果已变化')
    expect(wrapper.text()).toContain('已变更')
    expect(commitDocumentImportJob).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('invalidates confirmation when the user leaves the file tab during the preview check', async () => {
    const wrapper = render()
    await flushPromises()
    let resolveCheck!: (value: unknown) => void
    vi.mocked(getDocumentImportJob).mockImplementationOnce(() => new Promise(resolve => { resolveCheck = resolve }) as never)
    await button(wrapper, '确认入库')!.trigger('click')
    await wrapper.setProps({ active: false })
    await wrapper.setProps({ active: true })
    resolveCheck({ data: { ...parsed, preview } })
    await flushPromises()
    expect(commitDocumentImportJob).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('invalidates confirmation when the component is unmounted during the preview check', async () => {
    const wrapper = render()
    await flushPromises()
    let resolveCheck!: (value: unknown) => void
    vi.mocked(getDocumentImportJob).mockImplementationOnce(() => new Promise(resolve => { resolveCheck = resolve }) as never)
    await button(wrapper, '确认入库')!.trigger('click')
    wrapper.unmount()
    resolveCheck({ data: { ...parsed, preview } })
    await flushPromises()
    expect(commitDocumentImportJob).not.toHaveBeenCalled()
  })

  it('blocks a new upload during recovery and makes a failed recovery visible', async () => {
    let rejectRecovery!: (reason: Error) => void
    vi.mocked(getDocumentImportJob).mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectRecovery = reject }) as never)
    const wrapper = render()
    await flushPromises()
    expect(button(wrapper, '上传文件')!.attributes('disabled')).toBeDefined()
    rejectRecovery(new Error('恢复任务失败'))
    await flushPromises()
    expect(wrapper.text()).toContain('恢复任务失败')
    expect(button(wrapper, '重新恢复任务')).toBeDefined()
    expect(button(wrapper, '上传文件')!.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('treats a definite permission rejection as a rejection rather than an unknown commit', async () => {
    const wrapper = render()
    await flushPromises()
    vi.mocked(commitDocumentImportJob).mockRejectedValueOnce({ response: { status: 403 } })
    await button(wrapper, '确认入库')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前账号没有入库权限')
    expect(button(wrapper, '检查任务状态')).toBeUndefined()
    expect(window.sessionStorage.getItem('reachai:knowledge:file-job:kb_test')).toBe('upload:dij_review')
    wrapper.unmount()
  })
})
